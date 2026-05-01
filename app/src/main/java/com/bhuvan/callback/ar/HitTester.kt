package com.bhuvan.callback.ar

import com.google.ar.core.Frame
import com.google.ar.core.HitResult

/** Thin wrapper around [Frame.hitTest] for tap-to-anchor placement. */
object HitTester {
    /** Returns the first hit suitable for anchoring, or null. */
    fun firstAnchorableHit(frame: Frame, x: Float, y: Float): HitResult? {
        val hits = frame.hitTest(x, y)
        return hits.firstOrNull()
    }
}
