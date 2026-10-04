package com.bhuvan.callback.ar

import com.google.ar.core.Anchor
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory anchors created from user hit-tests (phase 1). */
class AnchorManager {
    private val anchors = CopyOnWriteArrayList<Anchor>()

    fun add(anchor: Anchor) {
        anchors.add(anchor)
    }

    fun snapshot(): List<Anchor> = anchors.toList()
}
