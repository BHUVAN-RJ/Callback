package com.bhuvan.callback.voice

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.appcompat.app.AppCompatActivity

/**
 * Wraps [SpeechRecognizer] with offline-first extras for the Ask flow.
 */
class SttController(
    private val activity: AppCompatActivity,
    private val onFinalText: (String) -> Unit,
    private val onErrorCode: (Int) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null

    /** Ensures a recognizer instance exists (main thread). */
    fun ensureCreated() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity)
            recognizer?.setRecognitionListener(listener)
        }
    }

    /** Starts one-shot listening using the device default language model. */
    fun startListening() {
        ensureCreated()
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }
        recognizer?.startListening(intent)
    }

    /** Releases the underlying recognizer. */
    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private val listener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                onErrorCode(error)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull().orEmpty()
                onFinalText(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
}
