package com.bhuvan.callback.ml

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.LiteRtException
import com.google.ai.edge.litert.TensorBuffer
import kotlin.math.max
import kotlin.math.min

// Edit this set to change which COCO classes are tracked.
// Keep small (3–10) to reduce vector-store noise.
val TRACKED_CLASSES: Set<String> =
    setOf(
        "cup",
        "bottle",
        "cell phone",
    )

/** One detection from the object detector. */
data class Detection(
    val classLabel: String,
    val score: Float,
    val bbox: RectF,
)

/**
 * YOLOv8n detector via LiteRT [CompiledModel] on NPU; input is letterboxed to [INPUT_SIZE].
 */
class Detector(
    private val model: CompiledModel,
    private val labels: List<String>,
) {
    private val inputBuffers: List<TensorBuffer> = model.createInputBuffers()
    private val outputBuffers: List<TensorBuffer> = model.createOutputBuffers()

    /** Runs inference; returns detections in the same pixel space as [bitmap] (view or camera). */
    fun detect(bitmap: Bitmap): List<Detection> {
        if (bitmap.width <= 0 || bitmap.height <= 0) return emptyList()
        val letter = letterboxToRgbFloat(bitmap)
        try {
            inputBuffers[0].writeFloat(letter.rgbFloat)
            model.run(inputBuffers, outputBuffers)
            return postprocess(outputBuffers, letter, bitmap.width, bitmap.height)
        } catch (_: LiteRtException) {
            return emptyList()
        }
    }

    private fun postprocess(
        outs: List<TensorBuffer>,
        letter: LetterboxResult,
        srcW: Int,
        srcH: Int,
    ): List<Detection> {
        if (outs.size < 3) return emptyList()
        val boxes = outs[0].readFloat()
        val scores = outs[1].readFloat()
        val clsBytes = outs[2].readInt8()
        val num = scores.size.coerceAtMost(clsBytes.size).coerceAtMost(boxes.size / 4)
        val candidates = ArrayList<Detection>(64)
        for (i in 0 until num) {
            val s = scores[i]
            if (s < CONF_THRESHOLD) continue
            val c = clsBytes[i].toInt() and 0xff
            if (c !in labels.indices) continue
            val label = labels[c]
            if (label !in TRACKED_CLASSES) continue
            val o = i * 4
            var cx = boxes[o]
            var cy = boxes[o + 1]
            var w = boxes[o + 2]
            var h = boxes[o + 3]
            if (maxOf(cx, cy, w, h) <= 1.5f) {
                cx *= INPUT_SIZE.toFloat()
                cy *= INPUT_SIZE.toFloat()
                w *= INPUT_SIZE.toFloat()
                h *= INPUT_SIZE.toFloat()
            }
            val rect640 = cxcywhToRect640(cx, cy, w, h)
            val srcRect = map640RectToSource(rect640, letter, srcW, srcH)
            candidates.add(Detection(label, s, srcRect))
        }
        return nms(candidates, NMS_IOU)
    }

    private data class LetterboxResult(
        val rgbFloat: FloatArray,
        val scale: Float,
        val padX: Float,
        val padY: Float,
    )

    private fun letterboxToRgbFloat(src: Bitmap): LetterboxResult {
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        val scale = min(INPUT_SIZE / sw, INPUT_SIZE / sh)
        val nw = (sw * scale).toInt().coerceAtLeast(1)
        val nh = (sh * scale).toInt().coerceAtLeast(1)
        val padX = ((INPUT_SIZE - nw) / 2f)
        val padY = ((INPUT_SIZE - nh) / 2f)
        val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
        val padded = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(padded)
        c.drawColor(android.graphics.Color.rgb(114, 114, 114))
        c.drawBitmap(scaled, padX, padY, null)
        if (scaled != src) scaled.recycle()
        val floats = FloatArray(INPUT_SIZE * INPUT_SIZE * 3)
        var p = 0
        val px = IntArray(INPUT_SIZE * INPUT_SIZE)
        padded.getPixels(px, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (argb in px) {
            val r = ((argb shr 16) and 0xff) / 255f
            val g = ((argb shr 8) and 0xff) / 255f
            val b = (argb and 0xff) / 255f
            floats[p++] = r
            floats[p++] = g
            floats[p++] = b
        }
        padded.recycle()
        return LetterboxResult(floats, scale, padX, padY)
    }

    private fun cxcywhToRect640(cx: Float, cy: Float, w: Float, h: Float): RectF {
        val halfW = w / 2f
        val halfH = h / 2f
        return RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    }

    private fun map640RectToSource(
        r640: RectF,
        letter: LetterboxResult,
        srcW: Int,
        srcH: Int,
    ): RectF {
        fun back(x: Float, y: Float): Pair<Float, Float> {
            val x0 = (x - letter.padX) / letter.scale
            val y0 = (y - letter.padY) / letter.scale
            return x0 to y0
        }
        val p1 = back(r640.left, r640.top)
        val p2 = back(r640.right, r640.bottom)
        val left = min(p1.first, p2.first).coerceIn(0f, srcW - 1f)
        val top = min(p1.second, p2.second).coerceIn(0f, srcH - 1f)
        val right = max(p1.first, p2.first).coerceIn(1f, srcW.toFloat())
        val bottom = max(p1.second, p2.second).coerceIn(1f, srcH.toFloat())
        return RectF(left, top, right, bottom)
    }

    private fun nms(dets: List<Detection>, iouTh: Float): List<Detection> {
        val sorted = dets.sortedByDescending { it.score }
        val kept = ArrayList<Detection>(8)
        for (d in sorted) {
            var ok = true
            for (k in kept) {
                if (iou(d.bbox, k.bbox) > iouTh) {
                    ok = false
                    break
                }
            }
            if (ok) kept.add(d)
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val iw = (interRight - interLeft).coerceAtLeast(0f)
        val ih = (interBottom - interTop).coerceAtLeast(0f)
        val inter = iw * ih
        if (inter <= 0f) return 0f
        val ua = a.width() * a.height() + b.width() * b.height() - inter
        return if (ua <= 0f) 0f else inter / ua
    }

    companion object {
        const val INPUT_SIZE = 640
        const val CONF_THRESHOLD = 0.5f
        private const val NMS_IOU = 0.45f
    }
}
