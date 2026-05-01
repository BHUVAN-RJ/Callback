package com.bhuvan.callback.ml

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import kotlin.math.max

/**
 * Converts a single [Image] in [ImageFormat.YUV_420_888] to an [ARGB_8888] [Bitmap] (CPU path).
 */
object Yuv420888ToBitmap {
    /** Converts [image] to RGB; caller must close [image] after this returns. */
    fun convert(image: Image): Bitmap {
        require(image.format == ImageFormat.YUV_420_888) { "expected YUV_420_888" }
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        yBuffer.position(0)
        uBuffer.position(0)
        vBuffer.position(0)

        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixStride = vPlane.pixelStride

        val argb = IntArray(width * height)
        var outIndex = 0
        for (j in 0 until height) {
            val yRowStart = j * yRowStride
            val uvRow = j shr 1
            val uRowStart = uvRow * uRowStride
            val vRowStart = uvRow * vRowStride
            for (i in 0 until width) {
                val yIndex = yRowStart + i * yPixStride
                val uvCol = i shr 1
                val uIndex = uRowStart + uvCol * uPixStride
                val vIndex = vRowStart + uvCol * vPixStride
                val y = (yBuffer.get(yIndex).toInt() and 0xff)
                val u = (uBuffer.get(uIndex).toInt() and 0xff) - 128
                val v = (vBuffer.get(vIndex).toInt() and 0xff) - 128
                val r = y + (1.370705f * v).toInt()
                val g = y - (0.337633f * u + 0.698001f * v).toInt()
                val b = y + (1.732446f * u).toInt()
                argb[outIndex++] =
                    -0x1000000 or
                        (clamp255(r) shl 16) or
                        (clamp255(g) shl 8) or
                        clamp255(b)
            }
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun clamp255(v: Int): Int = max(0, v.coerceAtMost(255))
}
