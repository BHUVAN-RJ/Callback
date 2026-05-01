package com.bhuvan.callback.ml

import ai.djl.sentencepiece.SpTokenizer
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.LiteRtException
import com.google.ai.edge.litert.TensorBuffer
import kotlin.math.sqrt

/**
 * EmbeddingGemma via LiteRT [CompiledModel] on NPU; tokenization uses SentencePiece (HF model card).
 */
class EmbeddingService(
    private val model: CompiledModel,
    private val sentencePiece: SpTokenizer,
) {
    private val inputBuffers: List<TensorBuffer> = model.createInputBuffers()
    private val outputBuffers: List<TensorBuffer> = model.createOutputBuffers()

    /** Returns a unit-norm 768-D embedding for [text]. */
    fun embed(text: String): FloatArray {
        val prompt = PREFIX + text
        val ids = sentencePiece.processor.encode(prompt)
        val padded = padOrTruncate(ids, SEQ_LEN, PAD_ID)
        try {
            inputBuffers[0].writeInt(padded)
            model.run(inputBuffers, outputBuffers)
            val raw = outputBuffers[0].readFloat().copyOf()
            return unitNormalize(raw)
        } catch (_: LiteRtException) {
            return FloatArray(EMBED_DIM)
        }
    }

    private fun padOrTruncate(ids: IntArray, len: Int, pad: Int): IntArray {
        val out = IntArray(len) { pad }
        val n = minOf(len, ids.size)
        System.arraycopy(ids, 0, out, 0, n)
        return out
    }

    private fun unitNormalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val n = sqrt(sum).coerceAtLeast(1e-6f)
        for (i in v.indices) v[i] /= n
        return v
    }

    companion object {
        private const val SEQ_LEN = 1024
        private const val PAD_ID = 0
        private const val EMBED_DIM = 768
        /** Instructional prefix per EmbeddingGemma retrieval guidance (HF / Google samples). */
        const val PREFIX: String = "task: search result | query: "
    }
}
