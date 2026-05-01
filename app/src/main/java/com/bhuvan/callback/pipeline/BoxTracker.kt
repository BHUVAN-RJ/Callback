package com.bhuvan.callback.pipeline

import android.graphics.RectF
import android.os.SystemClock
import com.bhuvan.callback.ml.Detection
import kotlin.math.max
import kotlin.math.min

/**
 * Tracks IoU-stable boxes and signals when a detection has stayed consistent for [stableHoldMs].
 */
class BoxTracker(
    private val stableHoldMs: Long = 2000L,
    private val iouThreshold: Float = 0.5f,
    private val onStable: (Detection) -> Unit,
) {
    private var referenceBox: RectF? = null
    private var stableStartElapsed: Long = 0L
    private var cooldownUntilElapsed: Long = 0L

    /** Feeds the next frame of detections; may invoke [onStable] on the GL thread. */
    fun update(detections: List<Detection>, nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val det = detections.firstOrNull() ?: run {
            reset()
            return
        }
        if (nowElapsedMs < cooldownUntilElapsed) {
            return
        }
        val box = det.bbox
        val ref = referenceBox
        if (ref == null) {
            referenceBox = RectF(box)
            stableStartElapsed = nowElapsedMs
            return
        }
        if (iou(ref, box) < iouThreshold) {
            referenceBox = RectF(box)
            stableStartElapsed = nowElapsedMs
            return
        }
        if (nowElapsedMs - stableStartElapsed >= stableHoldMs) {
            onStable(det)
            cooldownUntilElapsed = nowElapsedMs + 800L
            referenceBox = RectF(box)
            stableStartElapsed = nowElapsedMs
        }
    }

    /** Clears state after a successful remember or when tracking should restart. */
    fun reset() {
        referenceBox = null
        stableStartElapsed = 0L
        cooldownUntilElapsed = 0L
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interW = (interRight - interLeft).coerceAtLeast(0f)
        val interH = (interBottom - interTop).coerceAtLeast(0f)
        val interArea = interW * interH
        if (interArea <= 0f) return 0f
        val areaA = a.width() * a.height()
        val areaB = b.width() * b.height()
        val union = areaA + areaB - interArea
        if (union <= 0f) return 0f
        return interArea / union
    }
}
