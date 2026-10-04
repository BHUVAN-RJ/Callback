package com.bhuvan.callback.ar

import android.content.Context
import com.google.ar.core.Session

/** Owns a single [Session] lifecycle (create, resume, pause, close). */
class ArSessionWrapper(private val context: Context) {
    var session: Session? = null
        private set

    /** Creates the session if not already created; may throw ARCore unavailable exceptions. */
    fun createSession() {
        if (session != null) return
        session = Session(context)
    }

    fun setDisplayGeometry(displayRotation: Int, widthPx: Int, heightPx: Int) {
        session?.setDisplayGeometry(displayRotation, widthPx, heightPx)
    }

    fun resume() {
        session?.resume()
    }

    fun pause() {
        session?.pause()
    }

    fun close() {
        session?.close()
        session = null
    }
}
