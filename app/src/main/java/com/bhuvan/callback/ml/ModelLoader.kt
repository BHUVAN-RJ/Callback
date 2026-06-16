package com.bhuvan.callback.ml

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.LiteRtException
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Loads the YOLO detector on CPU. VLM and embedding disabled until NPU dispatch + DJL JNI are resolved.
 */
object ModelLoader {
    private const val TAG = "ModelLoader"
    const val ASSET_DETECTOR = "detector_yolov8.tflite"
    const val ASSET_LABELS = "yolov8_labels.txt"

    fun load(context: Context): MlBundle {
        val app = context.applicationContext

        val cpuOptions = CompiledModel.Options(Accelerator.CPU)
        val detector =
            try {
                CompiledModel.create(app.assets, ASSET_DETECTOR, cpuOptions, null)
            } catch (e: LiteRtException) {
                throw IllegalStateException("Detector CPU init failed: ${e.message}", e)
            }

        val labels = readLabels(app)
        Log.i(TAG, "ML load OK — detector on CPU (${labels.size} labels), VLM+embedding disabled")
        return MlBundle(detector, labels)
    }

    private fun readLabels(context: Context): List<String> {
        context.assets.open(ASSET_LABELS).use { ins ->
            BufferedReader(InputStreamReader(ins)).useLines { lines ->
                return lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
        }
    }
}
