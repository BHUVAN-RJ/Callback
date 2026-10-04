package com.bhuvan.callback.ml

import com.google.ai.edge.litert.CompiledModel

/**
 * Holds loaded ML handles. VLM and embedding disabled until NPU dispatch + DJL JNI are resolved.
 */
class MlBundle(
    val detectorModel: CompiledModel,
    val cocoLabels: List<String>,
) : AutoCloseable {
    override fun close() {
        runCatching { detectorModel.close() }
    }
}
