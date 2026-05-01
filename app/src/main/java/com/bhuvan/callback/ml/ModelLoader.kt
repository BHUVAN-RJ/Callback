package com.bhuvan.callback.ml

/**
 * Loads bundled LiteRT / LiteRT-LM models at startup (phase 4+).
 * Phase 2 leaves inference as stubs; this type exists for SPEC file layout only.
 */
object ModelLoader {
    /** No-op until real `.tflite` / `.task` assets are wired. */
    fun assertPlaceholderForPhase2() {
        // Intentionally empty — splash + real loading arrive with phase 4.
    }
}
