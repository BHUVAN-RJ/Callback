package com.bhuvan.callback.memory

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.ar.core.Anchor
import java.util.UUID
import kotlin.math.max

/**
 * In-process list of [Memory] entries with write-time deduplication per SPEC.
 */
object MemoryStore {
    private const val DEDUP_IOU = 0.5f

    private val memories = mutableListOf<Memory>()

    /** Thread-safe snapshot for UI and search. */
    @Synchronized
    fun snapshot(): List<Memory> = memories.toList()

    /**
     * If a same-class overlapping memory exists, updates its last-seen fields and returns true.
     * Call before allocating a new [Anchor] to avoid orphan anchors on deduped writes.
     */
    @Synchronized
    fun mergeIfDuplicate(
        classLabel: String,
        bbox: RectF,
        nowMs: Long,
    ): Boolean {
        for (existing in memories) {
            if (existing.classLabel != classLabel) continue
            if (iou(bbox, existing.lastSeenBbox) > DEDUP_IOU) {
                existing.lastSeenBbox = RectF(bbox)
                existing.lastSeenAtMs = nowMs
                return true
            }
        }
        return false
    }

    /**
     * Attempts to append a new memory or dedupe against an existing same-class overlap.
     *
     * @return the new [Memory], or null if merged into an existing entry.
     */
    @Synchronized
    fun remember(
        anchor: Anchor,
        description: String,
        embedding: FloatArray,
        classLabel: String,
        thumbnail: Bitmap,
        bbox: RectF,
        nowMs: Long,
    ): Memory? {
        if (mergeIfDuplicate(classLabel, bbox, nowMs)) {
            return null
        }
        val memory =
            Memory(
                id = UUID.randomUUID().toString(),
                anchor = anchor,
                description = description,
                embedding = embedding,
                classLabel = classLabel,
                thumbnail = thumbnail,
                createdAtMs = nowMs,
                lastSeenBbox = RectF(bbox),
                lastSeenAtMs = nowMs,
            )
        memories.add(memory)
        return memory
    }

    /** Returns the best-scoring memory by dot product, or null if the store is empty. */
    @Synchronized
    fun bestMatch(queryEmbedding: FloatArray): Pair<Memory, Float>? {
        if (memories.isEmpty()) return null
        var best: Memory? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (m in memories) {
            val s = dot(m.embedding, queryEmbedding)
            if (s > bestScore) {
                bestScore = s
                best = m
            }
        }
        return best?.let { it to bestScore }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        val interW = (interRight - interLeft).coerceAtLeast(0f)
        val interH = (interBottom - interTop).coerceAtLeast(0f)
        val interArea = interW * interH
        if (interArea <= 0f) return 0f
        val areaA = a.width() * a.height()
        val areaB = b.width() * b.height()
        val union = areaA + areaB - interArea
        if (union <= 0f) return 0f
        return interArea / union
    }
}
