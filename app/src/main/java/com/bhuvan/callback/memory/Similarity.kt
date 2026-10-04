package com.bhuvan.callback.memory

/**
 * Dot product for pre-normalized embeddings (cosine similarity equals the dot product).
 */
fun dot(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "Vectors must have the same length" }
    var sum = 0f
    for (i in a.indices) {
        sum += a[i] * b[i]
    }
    return sum
}
