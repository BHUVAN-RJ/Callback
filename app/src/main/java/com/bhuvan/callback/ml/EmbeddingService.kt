package com.bhuvan.callback.ml

import kotlin.math.sqrt

/** Phase-2 deterministic embedding stub; phase 4 replaces with EmbeddingGemma. */
class EmbeddingService {
    /** Returns a unit-norm 768-D vector derived from [text] (reproducible hash). */
    fun embed(text: String): FloatArray {
        val dim = 768
        val v = FloatArray(dim)
        var seed = text.hashCode().toLong()
        if (seed == 0L) {
            seed = 1L
        }
        for (i in 0 until dim) {
            seed = seed * 6364136223846793005L + 1L
            val u = ((seed ushr 33) and 0xffff).toInt() / 65535f
            v[i] = u * 2f - 1f
        }
        var sumSq = 0f
        for (i in v.indices) {
            sumSq += v[i] * v[i]
        }
        val norm = sqrt(sumSq).coerceAtLeast(1e-6f)
        for (i in v.indices) {
            v[i] /= norm
        }
        return v
    }
}
