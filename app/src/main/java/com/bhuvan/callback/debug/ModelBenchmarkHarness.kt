package com.bhuvan.callback.debug

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.util.Log
import com.bhuvan.callback.ml.Detection
import com.bhuvan.callback.ml.TRACKED_CLASSES
import android.graphics.RectF
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * Debug-only model comparison harness: inventories bundled assets, records stub-stage timings as a
 * baseline, and emits structured `CallbackBench` lines for log capture (Cursor/host-side ranking).
 *
 * **LiteRT / LiteRT-LM inference timings** are not run here until Gradle pins `litert`, `litert-qnn`,
 * and `litertlm-android` and loaders are copied from official samples (`TODO(human-verify)`).
 *
 * Compare **three buckets**: detectors (YOLO-class CV bbox models), VLMs (multimodal caption),
 * embedding models — not “YOLO as VLM.”
 */
object ModelBenchmarkHarness {
    const val EXTRA_RUN_MODEL_BENCHMARK: String = "RUN_MODEL_BENCHMARK"
    private const val TAG = "CallbackBench"

    /** Locked detector — YOLOv8n SM8750, compiled by Qualcomm AI Hub job j5wm6ok4g. */
    val DETECTOR_CANDIDATES: List<ModelCandidate> =
        listOf(
            ModelCandidate("yolov8n_sm8750", "detector_yolov8.tflite", "Qualcomm AI Hub YOLOv8n float SM8750 — 1.9ms 258/258 ops NPU"),
        )

    /** Locked VLM — Gemma-4-E2B-IT SM8750 LiteRT-LM build. */
    val VLM_CANDIDATES: List<ModelCandidate> =
        listOf(
            ModelCandidate("gemma4_e2b_it_sm8750", "gemma-4-e2b-it.litertlm", "litert-community/gemma-4-E2B-it-litert-lm SM8750 2.8GB"),
        )

    /** Locked embedding — EmbeddingGemma-300M SM8750 seq1024. */
    val EMBEDDING_CANDIDATES: List<ModelCandidate> =
        listOf(
            ModelCandidate("embeddinggemma_300m_sm8750", "embedding-gemma.tflite", "litert-community/EmbeddingGemma-300M SM8750 seq1024 186MB"),
        )

    private const val STUB_WARMUP = 3
    private const val STUB_RUNS = 25
    private const val STUB_DETECT_W = 640
    private const val STUB_DETECT_H = 480
    private const val STUB_VLM_CROP = 224

    /**
     * Runs the full report on a background thread and posts [onComplete] on [mainHandler].
     */
    fun runAsync(
        context: Context,
        mainHandler: Handler,
        onComplete: () -> Unit,
    ) {
        Thread(
            {
                try {
                    runSync(context.applicationContext)
                } catch (t: Throwable) {
                    logLine("FATAL ${t.javaClass.simpleName}: ${t.message}")
                    RunLogger.e(TAG, "benchmark crashed", t)
                } finally {
                    mainHandler.post(onComplete)
                }
            },
            "CallbackBench",
        ).start()
    }

    /** Synchronous bench; call off the main thread. */
    fun runSync(context: Context) {
        if (!isDebuggable(context)) {
            Log.w(TAG, "runSync aborted: not a debuggable build")
            return
        }
        val app = context.applicationContext
        val root = JSONObject()
        root.put("schema", "callback_bench_v1")
        root.put("device", deviceJson())
        root.put("assets_on_disk", assetsInventoryJson(app))
        root.put("candidates_config", candidatesConfigJson())
        root.put("stub_pipeline_baseline_ms", stubBaselinesJson(app))
        root.put(
            "litert_linked",
            JSONObject()
                .put("status", "NOT_IN_CLASSPATH")
                .put(
                    "note",
                    "Add SPEC §9 deps via Claude Code + copy loaders from samples; then extend " +
                        "this harness with timed Interpreter / LiteRT-LM calls.",
                ),
        )

        logLine("=== CallbackBench BEGIN (json) ===")
        logLine(root.toString(2))
        logLine("=== CallbackBench END ===")
        summarizeForRanker(root)
    }

    private fun summarizeForRanker(root: JSONObject) {
        logLine("--- Human/LLM summary: pick one winner per bucket after NPU/GPU runs ---")
        logLine("BUCKET|detector|asset_present_only|see assets_on_disk + future litert latency")
        logLine("BUCKET|vlm|asset_present_only|compare gemma_3n vs gemma_4 when both bundled")
        logLine("BUCKET|embedding|asset_present_only|embedding_gemma variants")
        logLine("STUB_BASELINE_MS|detector_mean|${root.optJSONObject("stub_pipeline_baseline_ms")?.optLong("detector_detect_mean_ms", -1)}")
        logLine("STUB_BASELINE_MS|vlm_describe_mean|${root.optJSONObject("stub_pipeline_baseline_ms")?.optLong("vlm_describe_mean_ms", -1)}")
        logLine("STUB_BASELINE_MS|embed_mean|${root.optJSONObject("stub_pipeline_baseline_ms")?.optLong("embed_text_mean_ms", -1)}")
        logLine("NOTE|YOLO-class models are STAGE1_DETECTOR not VLM; VLMs caption crops (Gemma multimodal).")
    }

    private fun deviceJson(): JSONObject =
        JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("hardware", Build.HARDWARE)
            .put("board", Build.BOARD)
            .put("soc_model", Build.SOC_MODEL)
            .put("soc_manufacturer", Build.SOC_MANUFACTURER)
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("release", Build.VERSION.RELEASE)

    private fun candidatesConfigJson(): JSONObject =
        JSONObject()
            .put(
                "detectors",
                JSONArray(DETECTOR_CANDIDATES.map { it.toJson() }),
            )
            .put("vlms", JSONArray(VLM_CANDIDATES.map { it.toJson() }))
            .put("embeddings", JSONArray(EMBEDDING_CANDIDATES.map { it.toJson() }))

    private fun assetsInventoryJson(context: Context): JSONObject {
        val am = context.assets
        val top = am.list("")?.toSet().orEmpty()

        fun fileDetails(name: String): JSONObject {
            val obj = JSONObject().put("file", name)
            if (!top.contains(name)) return obj.put("present", false)
            return try {
                val fd = am.openFd(name)
                val sizeMb = String.format("%.1f", fd.length / 1_048_576.0)
                fd.close()
                // TFLite identifier is 4 bytes at offset 4: "TFL3"
                // LiteRT-LM (.litertlm) is a ZIP: starts PK (0x50 0x4B)
                val header = am.open(name).use { it.readNBytes(8) }
                val fmt = when {
                    header.size >= 8 &&
                        header[4] == 0x54.toByte() && header[5] == 0x46.toByte() &&
                        header[6] == 0x4C.toByte() && header[7] == 0x33.toByte() -> "tflite-ok"
                    header.size >= 2 &&
                        header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() -> "zip/litertlm-ok"
                    else -> "unknown-header:${header.take(8).joinToString("") { "%02x".format(it) }}"
                }
                obj.put("present", true).put("size_mb", sizeMb).put("format_check", fmt)
            } catch (e: Exception) {
                obj.put("present", true).put("error", e.message)
            }
        }

        val arr = JSONArray()
        val allCandidates = DETECTOR_CANDIDATES + VLM_CANDIDATES + EMBEDDING_CANDIDATES
        for (c in allCandidates.distinctBy { it.assetFile }) {
            arr.put(fileDetails(c.assetFile).put("id", c.id).put("bucket", c.bucketJsonKey()))
        }
        return JSONObject().put("files", arr).put("asset_root_count", top.size)
    }

    private fun stubBaselinesJson(context: Context): JSONObject {
        val detector = BenchmarkStubDetector()
        val vlm = BenchmarkStubVlm()
        val embed = BenchmarkStubEmbed()

        val detectBmp =
            Bitmap.createBitmap(STUB_DETECT_W, STUB_DETECT_H, Bitmap.Config.ARGB_8888)
        val crop =
            Bitmap.createBitmap(STUB_VLM_CROP, STUB_VLM_CROP, Bitmap.Config.ARGB_8888)
        try {
            repeat(STUB_WARMUP) {
                detector.detect(detectBmp)
                vlm.describe(crop)
                embed.embed("benchmark query phrase for latency smoke")
            }
            val detSamples = LongArray(STUB_RUNS) { measureDetect(detector, detectBmp) }
            val vlmSamples = LongArray(STUB_RUNS) { measureDescribe(vlm, crop) }
            val embSamples = LongArray(STUB_RUNS) { measureEmbed(embed) }

            return JSONObject()
                .put("warmup_iterations", STUB_WARMUP)
                .put("timed_iterations", STUB_RUNS)
                .put("detector_bitmap_px", "${STUB_DETECT_W}x${STUB_DETECT_H}")
                .put("detector_detect_mean_ms", detSamples.mean())
                .put("detector_detect_p50_ms", detSamples.percentile50())
                .put("detector_detect_min_ms", detSamples.minOrNull() ?: -1)
                .put("detector_detect_max_ms", detSamples.maxOrNull() ?: -1)
                .put("vlm_crop_px", "${STUB_VLM_CROP}x${STUB_VLM_CROP}")
                .put("vlm_describe_mean_ms", vlmSamples.mean())
                .put("vlm_describe_p50_ms", vlmSamples.percentile50())
                .put("embed_text_mean_ms", embSamples.mean())
                .put("embed_text_p50_ms", embSamples.percentile50())
                .put(
                    "interpretation",
                    "Stub Kotlin-only cost floor (no LiteRT). Real Gemma/YOLO dominates startup+NPU.",
                )
        } finally {
            detectBmp.recycle()
            crop.recycle()
        }
    }

    private fun measureDetect(detector: BenchmarkStubDetector, bitmap: Bitmap): Long {
        val t0 = System.nanoTime()
        detector.detect(bitmap)
        return ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(0L)
    }

    private fun measureDescribe(vlm: BenchmarkStubVlm, crop: Bitmap): Long {
        val t0 = System.nanoTime()
        vlm.describe(crop)
        return ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(0L)
    }

    private fun measureEmbed(embed: BenchmarkStubEmbed): Long {
        val t0 = System.nanoTime()
        embed.embed("blue bottle on desk corner")
        return ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(0L)
    }

    private fun LongArray.mean(): Long =
        if (isEmpty()) {
            -1L
        } else {
            (sumOf { it }.toDouble() / size).roundToLong()
        }

    private fun LongArray.percentile50(): Long {
        if (isEmpty()) return -1L
        val s = sortedArray()
        return s[s.size / 2]
    }

    private fun logLine(line: String) {
        /* RunLogger forwards to Logcat; avoid duplicate lines vs Log.i + RunLogger.i */
        RunLogger.i(TAG, line)
    }

    private fun isDebuggable(context: Context): Boolean {
        val flags = context.applicationInfo.flags
        return (flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    data class ModelCandidate(
        val id: String,
        val assetFile: String,
        val notes: String,
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("id", id)
                .put("asset_file", assetFile)
                .put("notes", notes)

        fun bucketJsonKey(): String =
            when {
                DETECTOR_CANDIDATES.any { it.id == id } -> "detector"
                VLM_CANDIDATES.any { it.id == id } -> "vlm"
                EMBEDDING_CANDIDATES.any { it.id == id } -> "embedding"
                else -> "unknown"
            }
    }

    /** Phase-2-style stub for harness timing only (no LiteRT). */
    private class BenchmarkStubDetector {
        fun detect(bitmap: Bitmap): List<Detection> {
            val w = bitmap.width.coerceAtLeast(1)
            val h = bitmap.height.coerceAtLeast(1)
            val side = minOf(w, h) * 0.22f
            val cx = w / 2f
            val cy = h / 2f
            val bbox = RectF(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
            val label = "bottle".takeIf { it in TRACKED_CLASSES } ?: TRACKED_CLASSES.first()
            return listOf(Detection(classLabel = label, score = 0.95f, bbox = bbox))
        }
    }

    private class BenchmarkStubVlm {
        @Suppress("UNUSED_PARAMETER")
        fun describe(crop: Bitmap): String = "stub object description"
    }

    private class BenchmarkStubEmbed {
        fun embed(text: String): FloatArray {
            val dim = 768
            val v = FloatArray(dim)
            var seed = text.hashCode().toLong()
            if (seed == 0L) seed = 1L
            for (i in 0 until dim) {
                seed = seed * 6364136223846793005L + 1L
                val u = ((seed ushr 33) and 0xffff).toInt() / 65535f
                v[i] = u * 2f - 1f
            }
            var sumSq = 0f
            for (i in v.indices) sumSq += v[i] * v[i]
            val norm = sqrt(sumSq).coerceAtLeast(1e-6f)
            for (i in v.indices) v[i] /= norm
            return v
        }
    }
}
