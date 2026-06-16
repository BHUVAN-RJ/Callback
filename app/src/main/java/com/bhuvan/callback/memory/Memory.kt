package com.bhuvan.callback.memory

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.ar.core.Anchor

/**
 * One remembered object: spatial anchor, text description, embedding, and UI thumbnail.
 */
data class Memory(
    val id: String,
    val anchor: Anchor,
    val description: String,
    val embedding: FloatArray,
    val classLabel: String,
    val thumbnail: Bitmap,
    val createdAtMs: Long,
    var lastSeenBbox: RectF,
    var lastSeenAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Memory

        if (id != other.id) return false
        if (anchor != other.anchor) return false
        if (description != other.description) return false
        if (!embedding.contentEquals(other.embedding)) return false
        if (classLabel != other.classLabel) return false
        if (thumbnail != other.thumbnail) return false
        if (createdAtMs != other.createdAtMs) return false
        if (lastSeenBbox != other.lastSeenBbox) return false
        if (lastSeenAtMs != other.lastSeenAtMs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + anchor.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + classLabel.hashCode()
        result = 31 * result + thumbnail.hashCode()
        result = 31 * result + createdAtMs.hashCode()
        result = 31 * result + lastSeenBbox.hashCode()
        result = 31 * result + lastSeenAtMs.hashCode()
        return result
    }
}
