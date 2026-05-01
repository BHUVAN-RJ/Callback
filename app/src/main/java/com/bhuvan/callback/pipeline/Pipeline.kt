package com.bhuvan.callback.pipeline

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.bhuvan.callback.ar.HitTester
import com.bhuvan.callback.memory.MemoryStore
import com.bhuvan.callback.ml.Detection
import com.bhuvan.callback.ml.Detector
import com.bhuvan.callback.ml.EmbeddingService
import com.bhuvan.callback.ml.ModelLoader
import com.bhuvan.callback.ml.VLMService
import com.bhuvan.callback.ui.ArrowDrawState
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Orchestrates detector → VLM → embedding → memory and voice recall (stubs through phase 2). */
class Pipeline(
    private val detector: Detector = Detector(),
    private val vlm: VLMService = VLMService(),
    private val embeddingService: EmbeddingService = EmbeddingService(),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    init {
        ModelLoader.assertPlaceholderForPhase2()
    }

    private val frameTick = AtomicInteger(0)
    private var stubBitmap: Bitmap? = null
    private var stubW = 0
    private var stubH = 0

    private val recallTargetId = AtomicReference<String?>(null)

    private val boxTracker =
        BoxTracker { detection ->
            handleStableDetection(detection)
        }

    @Volatile
    private var latestFrame: Frame? = null

    var onMemoriesChanged: (() -> Unit)? = null
    var onRecallArrow: ((ArrowDrawState?) -> Unit)? = null

    /** Runs throttled stub detection and recall arrow projection on the GL thread. */
    fun onArFrameDecorated(frame: Frame, viewWidth: Int, viewHeight: Int) {
        latestFrame = frame
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            return
        }
        val tick = frameTick.incrementAndGet()
        if (tick % 3 != 0) {
            return
        }
        ensureStubBitmap(viewWidth, viewHeight)
        val bitmap = stubBitmap ?: return
        val detections = detector.detect(bitmap)
        boxTracker.update(detections)
        publishRecallArrow(frame, viewWidth, viewHeight)
    }

    /** Handles a finalized voice query on the main thread (phase 2 uses most-recent memory). */
    fun handleVoiceQuery(
        rawText: String,
        emptyStoreSpoken: String,
        foundSpoken: String,
    ): VoiceRecallResult {
        val cleaned = rawText.trim().lowercase()
        if (cleaned.isEmpty()) {
            recallTargetId.set(null)
            publishArrowMain(null)
            return VoiceRecallResult.EmptyQuery
        }
        val memories = MemoryStore.snapshot()
        if (memories.isEmpty()) {
            recallTargetId.set(null)
            publishArrowMain(null)
            return VoiceRecallResult.Miss(emptyStoreSpoken)
        }
        // Phase 2: deterministic stubs are not semantically aligned with open-ended speech;
        // ROADMAP expects the arrow on the most recently remembered anchor. Phase 4 switches
        // to SPEC ranking via embedding similarity on [cleaned].
        val target = memories.last()
        recallTargetId.set(target.id)
        return VoiceRecallResult.Hit(target.id, cleaned, foundSpoken)
    }

    private fun handleStableDetection(detection: Detection) {
        val frame = latestFrame ?: return
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            return
        }
        val bitmap = stubBitmap ?: return
        val now = System.currentTimeMillis()
        if (MemoryStore.mergeIfDuplicate(detection.classLabel, detection.bbox, now)) {
            return
        }
        val cx = (detection.bbox.left + detection.bbox.right) / 2f
        val cy = (detection.bbox.top + detection.bbox.bottom) / 2f
        val hit = HitTester.firstAnchorableHit(frame, cx, cy) ?: return
        val anchor =
            try {
                hit.createAnchor()
            } catch (_: Exception) {
                return
            }
        val crop = cropThumbnail(bitmap, detection.bbox)
        val phrase = vlm.describe(crop).trim().lowercase()
        val vector = embeddingService.embed(phrase)
        val added =
            MemoryStore.remember(
                anchor = anchor,
                description = phrase,
                embedding = vector,
                classLabel = detection.classLabel,
                thumbnail = crop,
                bbox = detection.bbox,
                nowMs = now,
            )
        if (added != null) {
            mainHandler.post { onMemoriesChanged?.invoke() }
        }
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

    private fun ensureStubBitmap(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (stubBitmap != null && stubW == w && stubH == h) {
            return
        }
        stubBitmap?.recycle()
        stubBitmap =
            try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                null
            }
        stubW = w
        stubH = h
    }

    private fun cropThumbnail(source: Bitmap, bbox: android.graphics.RectF): Bitmap {
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
