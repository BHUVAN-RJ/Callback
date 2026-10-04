package com.bhuvan.callback.voice

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import java.util.UUID

/** Thin wrapper around [TextToSpeech] for short recall responses. */
class TtsController(
    private val activity: AppCompatActivity,
) {
    private var tts: TextToSpeech? = null
    private var ready: Boolean = false

    /** Initializes the engine asynchronously. */
    fun ensureLoaded(onReady: (() -> Unit)? = null) {
        if (tts != null) {
            if (ready) onReady?.invoke()
            return
        }
        tts =
            TextToSpeech(activity) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.language = Locale.getDefault()
                    onReady?.invoke()
                }
            }
    }

    /** Speaks [text] when the engine is ready (no-op if init failed). */
    fun speak(text: String) {
        val engine = tts ?: return
        if (!ready) return
        val utteranceId = UUID.randomUUID().toString()
        @Suppress("DEPRECATION")
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /** Optional listener for utterance lifecycle (UI hooks). */
    fun setUtteranceListener(listener: UtteranceProgressListener?) {
        tts?.setOnUtteranceProgressListener(listener)
    }

    /** Shuts down the engine. */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
