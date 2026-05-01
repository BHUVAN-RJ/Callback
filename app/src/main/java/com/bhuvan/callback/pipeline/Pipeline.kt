package com.bhuvan.callback.pipeline

import com.google.ar.core.Anchor
import com.google.ar.core.Frame

/** Orchestrates detector → VLM → embedding → memory; phase 1 is a no-op stub. */
class Pipeline {
    /** Future hook: run detector and write path on stable boxes. */
    @Suppress("UNUSED_PARAMETER")
    fun onArFrameDecorated(frame: Frame) {
        // Phase 2+ wiring.
    }

    /** Placeholder for gated remember path. */
    fun rememberFromStableDetectionStub(): Anchor? = null

    /** Placeholder for voice recall path. */
    @Suppress("UNUSED_PARAMETER")
    fun answerWhereIsStub(queryText: String): String = ""
}
