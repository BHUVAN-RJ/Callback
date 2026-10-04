package com.bhuvan.callback

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bhuvan.callback.ar.AnchorManager
import com.bhuvan.callback.ar.ArGlRenderer
import com.bhuvan.callback.ar.ArSessionWrapper
import com.bhuvan.callback.ar.WorldToScreen
import com.bhuvan.callback.debug.ModelBenchmarkHarness
import com.bhuvan.callback.debug.RunLogger
import com.bhuvan.callback.memory.MemoryStore
import com.bhuvan.callback.ml.MlBundle
import com.bhuvan.callback.ml.ModelLoader
import com.bhuvan.callback.pipeline.Pipeline
import com.bhuvan.callback.pipeline.VoiceRecallResult
import com.bhuvan.callback.ui.OverlayView
import com.bhuvan.callback.ui.ThumbnailStrip
import com.bhuvan.callback.ui.TouchRoutingFrameLayout
import com.bhuvan.callback.voice.SttController
import com.bhuvan.callback.voice.TtsController
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hosts ARCore, LiteRT / LiteRT-LM inference, and the voice recall loop. */
class MainActivity :
    AppCompatActivity(),
    ArGlRenderer.Host {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val arSessionWrapper = ArSessionWrapper(this)
    private val anchorManager = AnchorManager()
    private val arRenderer = ArGlRenderer()

    private var mlBundle: MlBundle? = null
    private var pipeline: Pipeline? = null

    private lateinit var sttController: SttController
    private lateinit var ttsController: TtsController

    @Volatile
    private var glViewportWidth = 1

    @Volatile
    private var glViewportHeight = 1

    private var installRequested = false

    private lateinit var touchRoot: TouchRoutingFrameLayout
    private lateinit var glView: GLSurfaceView
    private lateinit var overlay: OverlayView
    private lateinit var trackingLabel: TextView
    private lateinit var thumbnailStrip: ThumbnailStrip
    private lateinit var voiceFeedback: TextView
    private lateinit var modelLoadingOverlay: View

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startArCoreAndSession()
            } else {
                Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                beginAskFlow()
            } else {
                Toast.makeText(this, R.string.mic_permission_required, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RunLogger.start(applicationContext)
        setContentView(R.layout.activity_main)

        touchRoot = findViewById(R.id.touch_root)
        glView = findViewById(R.id.gl_surface_view)
        overlay = findViewById(R.id.overlay_view)
        trackingLabel = findViewById(R.id.tracking_label)
        thumbnailStrip = findViewById(R.id.thumbnail_strip)
        voiceFeedback = findViewById(R.id.voice_feedback)
        modelLoadingOverlay = findViewById(R.id.model_loading_overlay)
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

        ttsController = TtsController(this)
        ttsController.ensureLoaded()

        sttController =
            SttController(
                activity = this,
                onFinalText = { text -> handleSpeechResult(text) },
                onErrorCode = { code -> handleSpeechError(code) },
            )

        findViewById<MaterialButton>(R.id.button_ask).apply {
            isEnabled = false
            setOnClickListener { onAskButtonClicked() }
        }

        refreshThumbnailStrip()

        maybeRunDebugModelBenchmark()

        loadMlAsync()

        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> startArCoreAndSession()
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun loadMlAsync() {
        modelLoadingOverlay.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bundle = ModelLoader.load(this@MainActivity)
                withContext(Dispatchers.Main) {
                    mlBundle = bundle
                    val p = Pipeline(bundle)
                    p.runOnGlThread = { runnable -> glView.queueEvent(runnable) }
                    p.onMemoriesChanged = { mainHandler.post { refreshThumbnailStrip() } }
                    p.onRecallArrow = { state -> mainHandler.post { overlay.recallArrow = state } }
                    pipeline = p
                    findViewById<MaterialButton>(R.id.button_ask).isEnabled = true
                    modelLoadingOverlay.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Model load failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, R.string.model_load_failed, Toast.LENGTH_LONG).show()
                    modelLoadingOverlay.visibility = View.GONE
                    finish()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeRunDebugModelBenchmark()
    }

    /** Debug APK only: `adb shell am start ... --ez RUN_MODEL_BENCHMARK true` — see [ModelBenchmarkHarness]. */
    private fun maybeRunDebugModelBenchmark() {
        if (!intent.getBooleanExtra(ModelBenchmarkHarness.EXTRA_RUN_MODEL_BENCHMARK, false)) {
            return
        }
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) {
            Log.w(TAG_BENCH, "RUN_MODEL_BENCHMARK set but APK is not debuggable — install debug: ./gradlew installDebug")
            Toast.makeText(this, R.string.benchmark_requires_debug_apk, Toast.LENGTH_LONG).show()
            return
        }
        Log.i(TAG_BENCH, "Starting model benchmark (see full JSON below this line)")
        Toast.makeText(this, R.string.benchmark_started_toast, Toast.LENGTH_LONG).show()
        ModelBenchmarkHarness.runAsync(applicationContext, mainHandler) {
            Toast.makeText(applicationContext, R.string.benchmark_finished_toast, Toast.LENGTH_LONG).show()
        }
    }

    private fun onAskButtonClicked() {
        voiceFeedback.text = getString(R.string.voice_feedback_listening)
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> beginAskFlow()
            else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun beginAskFlow() {
        sttController.ensureCreated()
        sttController.startListening()
    }

    private fun handleSpeechResult(text: String) {
        if (text.isBlank()) {
            voiceFeedback.text = getString(R.string.stt_error_no_match)
            ttsController.speak(getString(R.string.stt_error_no_match))
            return
        }
        voiceFeedback.text = getString(R.string.voice_feedback_heard, text)
        val p = pipeline
        if (p == null) {
            voiceFeedback.text = getString(R.string.model_loading_message)
            return
        }
        lifecycleScope.launch {
            val result =
                p.handleVoiceQuery(
                    rawText = text,
                    emptyStoreSpoken = getString(R.string.recall_empty_spoken),
                    foundSpoken = getString(R.string.recall_found_spoken),
                    noMatchSpoken = getString(R.string.recall_no_match_spoken),
                )
            when (result) {
                is VoiceRecallResult.Hit -> {
                    ttsController.speak(result.spoken)
                }
                is VoiceRecallResult.Miss -> {
                    overlay.recallArrow = null
                    ttsController.speak(result.spoken)
                }
                VoiceRecallResult.EmptyQuery -> {
                    overlay.recallArrow = null
                }
            }
        }
    }

    private fun handleSpeechError(code: Int) {
        val messageRes =
            when (code) {
                android.speech.SpeechRecognizer.ERROR_NO_MATCH -> R.string.stt_error_no_match
                android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    R.string.stt_error_permissions
                android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.stt_error_busy
                else -> R.string.stt_error_generic
            }
        voiceFeedback.text = getString(messageRes)
        ttsController.speak(getString(messageRes))
    }

    private fun refreshThumbnailStrip() {
        val thumbs = MemoryStore.snapshot().map { it.thumbnail }
        thumbnailStrip.setThumbnails(thumbs)
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
            Log.w(TAG, "Session resume failed", e)
        }
        glView.onResume()
    }

    override fun onPause() {
        glView.onPause()
        try {
            arSessionWrapper.pause()
        } catch (e: Exception) {
            Log.w(TAG, "Session pause failed", e)
        }
        super.onPause()
    }

    override fun onDestroy() {
        sttController.destroy()
        ttsController.shutdown()
        arSessionWrapper.close()
        arRenderer.host = null
        pipeline?.onMemoriesChanged = null
        pipeline?.onRecallArrow = null
        pipeline?.dispose()
        pipeline = null
        mlBundle?.close()
        mlBundle = null
        RunLogger.stop()
        super.onDestroy()
    }

    override fun getSession(): Session? = arSessionWrapper.session

    override fun onGlDisplayGeometryChanged(widthPx: Int, heightPx: Int) {
        glViewportWidth = widthPx
        glViewportHeight = heightPx
        arSessionWrapper.setDisplayGeometry(displayRotation(), widthPx, heightPx)
    }

    override fun onGlFrame(frame: Frame) {
        pipeline?.onArFrameDecorated(frame, glViewportWidth, glViewportHeight)
        val trackingState = frame.camera.trackingState
        val labelRes = trackingLabelRes(trackingState)
        val markers =
            anchorManager.snapshot().mapNotNull { anchor ->
                WorldToScreen.projectAnchor(frame, anchor, glViewportWidth, glViewportHeight)
            }
        mainHandler.post {
            overlay.markerPositions = markers
            trackingLabel.text = getString(labelRes)
            if (trackingState != TrackingState.TRACKING) {
                overlay.recallArrow = null
            }
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
        private const val TAG_BENCH = "CallbackBench"
    }
}
