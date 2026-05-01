package com.bhuvan.callback.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.bhuvan.callback.debug.RunLogger
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Runs [Session.update] on the GL thread (background relative to main) and draws the camera
 * passthrough each frame.
 */
class ArGlRenderer : GLSurfaceView.Renderer {
    /** Supplies the live session and receives per-frame callbacks on the GL thread. */
    interface Host {
        fun getSession(): Session?

        fun onGlFrame(frame: Frame)

        fun onGlDisplayGeometryChanged(widthPx: Int, heightPx: Int)

        /** Invoked on the GL thread when a new anchor is created from a tap. */
        fun onTapAnchor(anchor: com.google.ar.core.Anchor)
    }

    var host: Host? = null

    private val backgroundRenderer = ArBackgroundRenderer()
    private val pendingTap = AtomicReference<Pair<Float, Float>?>(null)

    private var viewportWidth = 1
    private var viewportHeight = 1

    fun queueTap(x: Float, y: Float) {
        pendingTap.set(Pair(x, y))
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        host?.onGlDisplayGeometryChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val session = host?.getSession() ?: return
        try {
            session.setCameraTextureName(backgroundRenderer.cameraTextureId)
            val frame = session.update()
            GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            // Camera passthrough must be drawn whenever the frame is valid — not only while
            // tracking. Gating on TRACKING causes flicker/noise when tracking flaps; 3D hits still
            // require TRACKING inside handlers if needed (see ARCore augmented_image_java sample).
            backgroundRenderer.draw(frame)

            consumeTap(frame, session)
            host?.onGlFrame(frame)
        } catch (e: CameraNotAvailableException) {
            RunLogger.e(TAG, "Camera not available during draw", e)
        } catch (e: Throwable) {
            RunLogger.e(TAG, "Frame update failed", e)
        }
    }

    private fun consumeTap(frame: Frame, session: Session) {
        val tap = pendingTap.getAndSet(null) ?: return
        val hit = HitTester.firstAnchorableHit(frame, tap.first, tap.second) ?: return
        try {
            val anchor = hit.createAnchor()
            host?.onTapAnchor(anchor)
        } catch (e: Exception) {
            Log.w(TAG, "createAnchor failed", e)
        }
    }

    companion object {
        private const val TAG = "ArGlRenderer"
    }
}
