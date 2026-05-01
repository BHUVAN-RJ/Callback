package com.bhuvan.callback.ml

import android.graphics.Bitmap

/** Phase-2 stub VLM; phase 4 replaces with LiteRT-LM Gemma-3n-E2B. */
class VLMService {
    /** Returns a fixed phrase (real model will describe [crop]). */
    @Suppress("UNUSED_PARAMETER")
    fun describe(crop: Bitmap): String = "stub object description"
}
