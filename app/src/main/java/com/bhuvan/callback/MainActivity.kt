package com.bhuvan.callback

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bhuvan.callback.ar.AnchorManager
import com.bhuvan.callback.ar.ArGlRenderer
import com.bhuvan.callback.ar.ArSessionWrapper
import com.bhuvan.callback.ar.WorldToScreen
import com.bhuvan.callback.pipeline.Pipeline
import com.bhuvan.callback.ui.OverlayView
import com.bhuvan.callback.ui.TouchRoutingFrameLayout
import com.google.android.material.button.MaterialButton
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException

/** Hosts ARCore: GL camera preview, tap-to-place anchors, tracking label, and bottom chrome for phase 2+ voice. */
class MainActivity :
    AppCompatActivity(),
    ArGlRenderer.Host {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val arSessionWrapper = ArSessionWrapper(this)
    private val anchorManager = AnchorManager()
    private val pipeline = Pipeline()
    private val arRenderer = ArGlRenderer()

    @Volatile
    private var glViewportWidth = 1

    @Volatile
    private var glViewportHeight = 1

    private var installRequested = false

    private lateinit var touchRoot: TouchRoutingFrameLayout
    private lateinit var glView: GLSurfaceView
    private lateinit var overlay: OverlayView
    private lateinit var trackingLabel: TextView

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startArCoreAndSession()
            } else {
                Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        touchRoot = findViewById(R.id.touch_root)
        glView = findViewById(R.id.gl_surface_view)
        overlay = findViewById(R.id.overlay_view)
        trackingLabel = findViewById(R.id.tracking_label)
        val bottomChrome = findViewById<android.view.View>(R.id.bottom_chrome)
        touchRoot.glSurfaceView = glView
        touchRoot.chromeView = bottomChrome

        glView.setEGLContextClientVersion(2)
        glView.preserveEGLContextOnPause = true
        arRenderer.host = this
        glView.setRenderer(arRenderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        glView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                arRenderer.queueTap(event.x, event.y)
            }
            true
        }

        findViewById<MaterialButton>(R.id.button_ask).setOnClickListener {
            // Wired in phase 2 (STT).
        }

        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> startArCoreAndSession()
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startArCoreAndSession() {
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }
        } catch (e: UnavailableUserDeclinedInstallationException) {
            finish()
            return
        } catch (e: UnavailableDeviceNotCompatibleException) {
            Toast.makeText(this, R.string.arcore_device_unsupported, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        try {
            arSessionWrapper.createSession()
        } catch (e: UnavailableArcoreNotInstalledException) {
            Toast.makeText(this, R.string.arcore_install_required, Toast.LENGTH_LONG).show()
            finish()
            return
        } catch (e: UnavailableUserDeclinedInstallationException) {
            finish()
            return
        } catch (e: UnavailableApkTooOldException) {
            Toast.makeText(this, R.string.arcore_install_required, Toast.LENGTH_LONG).show()
            finish()
            return
        } catch (e: UnavailableSdkTooOldException) {
            Toast.makeText(this, R.string.arcore_device_unsupported, Toast.LENGTH_LONG).show()
            finish()
            return
        } catch (e: UnavailableDeviceNotCompatibleException) {
            Toast.makeText(this, R.string.arcore_device_unsupported, Toast.LENGTH_LONG).show()
            finish()
            return
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            arSessionWrapper.session?.resume()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Session resume failed", e)
        }
        glView.onResume()
    }

    override fun onPause() {
        glView.onPause()
        try {
            arSessionWrapper.pause()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Session pause failed", e)
        }
        super.onPause()
    }

    override fun onDestroy() {
        arSessionWrapper.close()
        arRenderer.host = null
        super.onDestroy()
    }

    override fun getSession(): Session? = arSessionWrapper.session

    override fun onGlDisplayGeometryChanged(widthPx: Int, heightPx: Int) {
        glViewportWidth = widthPx
        glViewportHeight = heightPx
        arSessionWrapper.setDisplayGeometry(displayRotation(), widthPx, heightPx)
    }

    override fun onGlFrame(frame: Frame) {
        pipeline.onArFrameDecorated(frame)
        val trackingState = frame.camera.trackingState
        val labelRes = trackingLabelRes(trackingState)
        val markers =
            anchorManager.snapshot().mapNotNull { anchor ->
                WorldToScreen.projectAnchor(frame, anchor, glViewportWidth, glViewportHeight)
            }
        mainHandler.post {
            overlay.markerPositions = markers
            trackingLabel.text = getString(labelRes)
        }
    }

    override fun onTapAnchor(anchor: Anchor) {
        anchorManager.add(anchor)
    }

    private fun trackingLabelRes(state: TrackingState): Int =
        when (state) {
            TrackingState.TRACKING -> R.string.tracking_state_tracking
            TrackingState.PAUSED -> R.string.tracking_state_limited
            TrackingState.STOPPED -> R.string.tracking_state_not_tracking
        }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            windowManager.defaultDisplay.rotation
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
