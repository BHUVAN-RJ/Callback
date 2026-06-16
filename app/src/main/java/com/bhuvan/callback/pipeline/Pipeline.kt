package com.bhuvan.callback.pipeline

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import com.bhuvan.callback.ar.HitTester
import com.bhuvan.callback.memory.MemoryStore
import com.bhuvan.callback.ml.Detection
import com.bhuvan.callback.ml.Detector
import com.bhuvan.callback.ml.MlBundle

import com.bhuvan.callback.ml.Yuv420888ToBitmap
import com.bhuvan.callback.ui.ArrowDrawState
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val RECALL_SCORE_THRESHOLD = 0.4f

/**
 * Orchestrates detector → VLM → embedding → memory and voice recall (phase 4 real models).
 */
class Pipeline(
    bundle: MlBundle,
    private val detector: Detector = Detector(bundle.detectorModel, bundle.cocoLabels),
    private val inferDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    private val inferScope = CoroutineScope(SupervisorJob() + inferDispatcher)

    /** Enqueues [block] on the ARCore GL thread (e.g. [BoxTracker] updates). Set from [MainActivity]. */
    var runOnGlThread: ((Runnable) -> Unit)? = null

    private val frameTick = AtomicInteger(0)
    private val recallTargetId = AtomicReference<String?>(null)

    private val inferenceBusy = AtomicBoolean(false)

    private val viewW = AtomicInteger(1)
    private val viewH = AtomicInteger(1)

    private val frameLock = Any()
    private var pendingCameraRgb: Bitmap? = null

    private val boxTracker =
        BoxTracker { detection ->
            handleStableDetection(detection)
        }

    @Volatile
    private var latestFrame: Frame? = null

    var onMemoriesChanged: (() -> Unit)? = null
    var onRecallArrow: ((ArrowDrawState?) -> Unit)? = null

    /** Runs throttled detection on a worker and applies results on the GL thread. */
    fun onArFrameDecorated(frame: Frame, viewWidth: Int, viewHeight: Int) {
        latestFrame = frame
        viewW.set(viewWidth)
        viewH.set(viewHeight)
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            return
        }
        val tick = frameTick.incrementAndGet()
        if (tick % 3 != 0) {
            publishRecallArrow(frame, viewWidth, viewHeight)
            return
        }
        if (!inferenceBusy.compareAndSet(false, true)) {
            publishRecallArrow(frame, viewWidth, viewHeight)
            return
        }
        val image =
            try {
                frame.acquireCameraImage()
            } catch (_: Exception) {
                inferenceBusy.set(false)
                publishRecallArrow(frame, viewWidth, viewHeight)
                return
            }
        val rgb =
            try {
                Yuv420888ToBitmap.convert(image)
            } catch (_: Throwable) {
                image.close()
                inferenceBusy.set(false)
                publishRecallArrow(frame, viewWidth, viewHeight)
                return
            }
        image.close()
        synchronized(frameLock) {
            pendingCameraRgb?.recycle()
            pendingCameraRgb = rgb
        }
        inferScope.launch {
            if (!isActive) {
                inferenceBusy.set(false)
                return@launch
            }
            val cameraCopy =
                synchronized(frameLock) {
                    val src = pendingCameraRgb ?: return@synchronized null
                    src.copy(Bitmap.Config.ARGB_8888, false)
                } ?: run {
                    inferenceBusy.set(false)
                    return@launch
                }
            try {
                val dets = detector.detect(cameraCopy)
                val gl = runOnGlThread
                if (gl != null) {
                    gl.invoke(
                        Runnable {
                            try {
                                boxTracker.update(dets)
                            } finally {
                                inferenceBusy.set(false)
                            }
                            val fr = latestFrame
                            if (fr != null) {
                                publishRecallArrow(fr, viewW.get(), viewH.get())
                            }
                        },
                    )
                } else {
                    try {
                        boxTracker.update(dets)
                    } finally {
                        inferenceBusy.set(false)
                    }
                }
            } catch (_: Throwable) {
                inferenceBusy.set(false)
            } finally {
                cameraCopy.recycle()
            }
        }
        publishRecallArrow(frame, viewWidth, viewHeight)
    }

    /** Releases inference jobs; call from Activity [onDestroy]. */
    fun dispose() {
        inferScope.cancel()
        synchronized(frameLock) {
            pendingCameraRgb?.recycle()
            pendingCameraRgb = null
        }
    }

    /**
     * Embedding-ranked recall; runs embedding on [inferDispatcher] (not the main thread).
     */
    suspend fun handleVoiceQuery(
        rawText: String,
        emptyStoreSpoken: String,
        foundSpoken: String,
        noMatchSpoken: String,
    ): VoiceRecallResult =
        withContext(inferDispatcher) {
            val cleaned = rawText.trim().lowercase()
            if (cleaned.isEmpty()) {
                recallTargetId.set(null)
                publishArrowMain(null)
                return@withContext VoiceRecallResult.EmptyQuery
            }
            val memories = MemoryStore.snapshot()
            if (memories.isEmpty()) {
                recallTargetId.set(null)
                publishArrowMain(null)
                return@withContext VoiceRecallResult.Miss(emptyStoreSpoken)
            }
            val match = MemoryStore.snapshot()
                .firstOrNull { it.classLabel.contains(cleaned) || cleaned.contains(it.classLabel) }
                ?.let { it to 1f }
            if (match == null) {
                recallTargetId.set(null)
                publishArrowMain(null)
                return@withContext VoiceRecallResult.Miss(noMatchSpoken)
            }
            recallTargetId.set(match.first.id)
            VoiceRecallResult.Hit(match.first.id, cleaned, foundSpoken)
        }

    private fun handleStableDetection(detection: Detection) {
        val frame = latestFrame ?: return
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            return
        }
        val now = System.currentTimeMillis()
        if (MemoryStore.mergeIfDuplicate(detection.classLabel, detection.bbox, now)) {
            return
        }
        val cameraBmp =
            synchronized(frameLock) {
                pendingCameraRgb?.copy(Bitmap.Config.ARGB_8888, false)
            } ?: return
        val bbox = RectF(detection.bbox)
        val classLabel = detection.classLabel
        val imgW = cameraBmp.width
        val imgH = cameraBmp.height
        inferScope.launch {
            val crop = cropThumbnail(cameraBmp, bbox)
            val phrase = classLabel
            val vector = FloatArray(1)
            val thumb = crop.copy(Bitmap.Config.ARGB_8888, false)
            crop.recycle()
            cameraBmp.recycle()
            val gl = runOnGlThread
            if (gl != null) {
                gl.invoke(
                    Runnable {
                        val fr = latestFrame
                        if (fr == null || fr.camera.trackingState != TrackingState.TRACKING) {
                            thumb.recycle()
                            return@Runnable
                        }
                        val hitPos = imageCenterToView(fr, bbox, imgW, imgH) ?: run {
                            thumb.recycle()
                            return@Runnable
                        }
                        val hit = HitTester.firstAnchorableHit(fr, hitPos.first, hitPos.second) ?: run {
                            thumb.recycle()
                            return@Runnable
                        }
                        val anchor =
                            try {
                                hit.createAnchor()
                            } catch (_: Exception) {
                                thumb.recycle()
                                return@Runnable
                            }
                        val added =
                            MemoryStore.remember(
                                anchor = anchor,
                                description = phrase,
                                embedding = vector,
                                classLabel = classLabel,
                                thumbnail = thumb,
                                bbox = bbox,
                                nowMs = System.currentTimeMillis(),
                            )
                        if (added != null) {
                            mainHandler.post { onMemoriesChanged?.invoke() }
                        } else {
                            thumb.recycle()
                        }
                    },
                )
            } else {
                thumb.recycle()
            }
        }
    }

    private fun imageCenterToView(
        frame: Frame,
        bbox: RectF,
        imgW: Int,
        imgH: Int,
    ): Pair<Float, Float>? {
        val cx = (bbox.left + bbox.right) / 2f
        val cy = (bbox.top + bbox.bottom) / 2f
        if (cx < 0f || cy < 0f || cx > imgW || cy > imgH) return null
        val src = floatArrayOf(cx, cy)
        val dst = FloatArray(2)
        frame.transformCoordinates2d(
            Coordinates2d.IMAGE_PIXELS,
            src,
            Coordinates2d.VIEW,
            dst,
        )
        return dst[0] to dst[1]
    }

    private fun publishRecallArrow(frame: Frame, viewWidth: Int, viewHeight: Int) {
        val id = recallTargetId.get() ?: run {
            publishArrowMain(null)
            return
        }
        val memory = MemoryStore.snapshot().firstOrNull { it.id == id } ?: run {
            publishArrowMain(null)
            return
        }
        val anchor = memory.anchor
        val projected =
            com.bhuvan.callback.ar.WorldToScreen.projectAnchor(
                frame,
                anchor,
                viewWidth,
                viewHeight,
            )
        val visible = projected != null
        val targetX = projected?.first ?: (viewWidth / 2f)
        val targetY = projected?.second ?: (viewHeight * 0.12f)
        publishArrowMain(ArrowDrawState(targetX, targetY, visible))
    }

    private fun publishArrowMain(state: ArrowDrawState?) {
        mainHandler.post { onRecallArrow?.invoke(state) }
    }

    private fun cropThumbnail(source: Bitmap, bbox: RectF): Bitmap {
        val left = bbox.left.toInt().coerceIn(0, source.width - 1)
        val top = bbox.top.toInt().coerceIn(0, source.height - 1)
        val right = bbox.right.toInt().coerceIn(left + 1, source.width)
        val bottom = bbox.bottom.toInt().coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }
}

/** Outcome of a voice recall attempt. */
sealed class VoiceRecallResult {
    data class Hit(
        val memoryId: String,
        val subtitle: String,
        val spoken: String,
    ) : VoiceRecallResult()

    data class Miss(
        val spoken: String,
    ) : VoiceRecallResult()

    data object EmptyQuery : VoiceRecallResult()
}
