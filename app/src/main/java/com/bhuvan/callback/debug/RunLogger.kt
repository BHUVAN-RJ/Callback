package com.bhuvan.callback.debug

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * When the APK is debuggable, mirrors selected logs to a UTF-8 file under app external storage.
 * Use together with [scripts/capture-logcat.sh] on the host for full ARCore/native logcat.
 */
object RunLogger {
    private const val LOG_SUBDIR = "callback_run_logs"

    private val writerRef = AtomicReference<BufferedWriter?>(null)
    private val sessionStartedRef = AtomicReference<Long>(0L)

    private var previousUncaught: Thread.UncaughtExceptionHandler? = null

    /** Starts a new log file for this process; no-op on release builds. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (!isDebuggable(app)) {
            return
        }
        val prev = writerRef.get()
        if (prev != null) {
            return
        }
        synchronized(this) {
            if (writerRef.get() != null) return
            val dir =
                app.getExternalFilesDir(LOG_SUBDIR)
                    ?: File(app.filesDir, LOG_SUBDIR).apply { mkdirs() }
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val stamp =
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "run_$stamp.log")
            sessionStartedRef.set(System.currentTimeMillis())
            val stream = java.io.FileOutputStream(file, true)
            val writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8))
            writer.appendLine("=== Callback RunLogger session pid=${android.os.Process.myPid()} ===")
            writer.appendLine("File: ${file.absolutePath}")
            writer.appendLine()
            writer.flush()
            writerRef.set(writer)
            Log.i(TAG, "RunLogger file=${file.absolutePath}")
            chainCrashHandler()
        }
    }

    /** Flushes and closes the log file. */
    fun stop() {
        val w = writerRef.getAndSet(null) ?: return
        synchronized(this) {
            try {
                w.appendLine("=== RunLogger stop elapsedMs=${elapsedMs()} ===")
                w.flush()
            } finally {
                try {
                    w.close()
                } catch (_: Exception) {
                    // ignore
                }
            }
        }
        restoreCrashHandler()
    }

    /** Writes [android.util.Log.INFO] and appends to file when logging is active. */
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append("I", tag, message, null)
    }

    /** Writes [android.util.Log.WARN] and appends to file when logging is active. */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        append("W", tag, message, throwable)
    }

    /** Writes [android.util.Log.ERROR] and appends to file when logging is active. */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        append("E", tag, message, throwable)
    }

    private fun append(level: String, tag: String, message: String, throwable: Throwable?) {
        val w = writerRef.get() ?: return
        val line =
            buildString {
                append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
                append(' ')
                append(level)
                append('/')
                append(tag)
                append(": ")
                append(message)
                if (throwable != null) {
                    append('\n')
                    append(Log.getStackTraceString(throwable))
                }
                append('\n')
            }
        synchronized(this) {
            try {
                w.append(line)
                w.flush()
            } catch (ex: Exception) {
                Log.w(TAG, "RunLogger append failed", ex)
            }
        }
    }

    private fun elapsedMs(): Long {
        val start = sessionStartedRef.get()
        if (start == 0L) return -1L
        return System.currentTimeMillis() - start
    }

    private fun isDebuggable(context: Context): Boolean {
        val flags = context.applicationInfo.flags
        return (flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun chainCrashHandler() {
        if (previousUncaught != null) return
        previousUncaught = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                e("UncaughtException", "thread=${thread.name}", throwable)
                writerRef.get()?.flush()
            } catch (_: Exception) {
                // ignore
            }
            previousUncaught?.uncaughtException(thread, throwable)
        }
    }

    private fun restoreCrashHandler() {
        val prev = previousUncaught ?: return
        Thread.setDefaultUncaughtExceptionHandler(prev)
        previousUncaught = null
    }

    private const val TAG = "RunLogger"
}
