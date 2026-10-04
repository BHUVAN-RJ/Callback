package com.bhuvan.callback.ar

import android.opengl.Matrix
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState

/** Projects anchor poses into view pixel coordinates for 2D overlays. */
object WorldToScreen {
    private val worldPos = FloatArray(4)
    private val eye = FloatArray(4)
    private val clip = FloatArray(4)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    /**
     * Returns pixel coordinates relative to [viewWidth] x [viewHeight], or null if not visible.
     */
    fun projectAnchor(
        frame: Frame,
        anchor: Anchor,
        viewWidth: Int,
        viewHeight: Int,
    ): Pair<Float, Float>? {
        if (anchor.trackingState != TrackingState.TRACKING) return null
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return null

        val pose: Pose = anchor.pose
        pose.getTranslation(worldPos, 0)
        worldPos[3] = 1f

        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)

        Matrix.multiplyMV(eye, 0, viewMatrix, 0, worldPos, 0)
        Matrix.multiplyMV(clip, 0, projectionMatrix, 0, eye, 0)

        val w = clip[3]
        if (w == 0f) return null
        val ndcX = clip[0] / w
        val ndcY = clip[1] / w
        val ndcZ = clip[2] / w
        if (ndcZ < -1f || ndcZ > 1f) return null

        val x = (ndcX * 0.5f + 0.5f) * viewWidth
        val y = (1f - (ndcY * 0.5f + 0.5f)) * viewHeight
        return Pair(x, y)
    }
}
