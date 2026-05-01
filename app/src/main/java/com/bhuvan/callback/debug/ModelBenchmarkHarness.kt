package com.bhuvan.callback.debug

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.util.Log
import com.bhuvan.callback.ml.Detector
import com.bhuvan.callback.ml.EmbeddingService
import com.bhuvan.callback.ml.VLMService
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

    /** Detector `.tflite` candidates (rename downloads to match, or extend this list). */
    val DETECTOR_CANDIDATES: List<ModelCandidate> =
        listOf(
            ModelCandidate("detector_spec_default", "detector.tflite", "SPEC/README canonical"),
            ModelCandidate("yolov8_quantized", "detector_yolov8.tflite", "Qualcomm AI Hub — YOLOv8 detection INT8"),
            ModelCandidate("mobilenetv3_ssd", "detector_mobilenetv3_ssd.tflite", "Qualcomm MobileNetV3-SSD"),
            ModelCandidate("efficientdet_lite0", "detector_efficientdet_lite0.tflite", "Qualcomm EfficientDet-Lite0"),
        )

    /** LiteRT-LM VLM bundles — extensions vary by HF packaging; align filenames after download. */
    val VLM_CANDIDATES: List<ModelCandidate> =
        listOf(
            ModelCandidate("gemma_3n_e2b_it_spec", "gemma-3n-e2b.task", "SPEC / ROADMAP Gemma-3n-E2B-it LiteRT-LM"),
            ModelCandidate(
                "gemma_4_e2b_it_target",
                "gemma-4-e2b-it.task",
                "Your pick — rename HF LiteRT-LM artifact to match after confirming extension",
            ),
            ModelCandidate(
                "gemma_4_e2b_it_alt",
                "gemma-4-E2B-it.task",
                "Alternate casing if you keep upstream naming",
            ),
        )

    val EMBEDDING_CANDIDATES: List<ModelCandidate> =
        listOf(
            ModelCandidate("embedding_gemma_default", "embedding-gemma.task", "README canonical"),
            ModelCandidate(
                "embedding_gemma_hf",
                "embeddinggemma-300m.task",
                "If HF export uses repo-style name",
            ),
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
            .put("sdk_int", Build.VERSION.SDK_INT)

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
        fun exists(name: String): Boolean = top.contains(name)

        val arr = JSONArray()
        val allCandidates = DETECTOR_CANDIDATES + VLM_CANDIDATES + EMBEDDING_CANDIDATES
        for (c in allCandidates.distinctBy { it.assetFile }) {
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("file", c.assetFile)
                    .put("present", exists(c.assetFile))
                    .put("bucket", c.bucketJsonKey()),
            )
        }
        return JSONObject().put("files", arr).put("asset_root_count", top.size)
    }

    private fun stubBaselinesJson(context: Context): JSONObject {
        val detector = Detector()
        val vlm = VLMService()
        val embed = EmbeddingService()

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

    private fun measureDetect(detector: Detector, bitmap: Bitmap): Long {
        val t0 = System.nanoTime()
        detector.detect(bitmap)
        return ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(0L)
    }

    private fun measureDescribe(vlm: VLMService, crop: Bitmap): Long {
        val t0 = System.nanoTime()
        vlm.describe(crop)
        return ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(0L)
    }

    private fun measureEmbed(embed: EmbeddingService): Long {
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
}
