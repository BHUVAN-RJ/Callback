package com.bhuvan.callback.ml

import android.graphics.Bitmap
import android.graphics.RectF

// Edit this set to change which COCO classes are tracked.
// Keep small (3–10) to reduce vector-store noise.
// Stub uses "bottle" as the fake class until a real label file is wired in phase 4.
val TRACKED_CLASSES: Set<String> =
    setOf(
        "cup",
        "bottle",
        "cell phone",
    )

/** One detection from the object detector (stub uses image-center box). */
data class Detection(
    val classLabel: String,
    val score: Float,
    val bbox: RectF,
)

/** Phase-2 stub detector; phase 4 replaces with LiteRT + QNN. */
class Detector {
    /** Returns a single plausible detection centered in [bitmap] pixel space. */
    fun detect(bitmap: Bitmap): List<Detection> {
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val side = minOf(w, h) * 0.22f
        val cx = w / 2f
        val cy = h / 2f
        val bbox = RectF(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
        val label = "bottle".takeIf { it in TRACKED_CLASSES } ?: TRACKED_CLASSES.first()
        return listOf(
            Detection(
                classLabel = label,
                score = 0.95f,
                bbox = bbox,
            ),
        )
    }
}
