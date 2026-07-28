package de.neon.platform

import android.util.Log
import java.io.File

/**
 * Die Protokollierung von Neon.
 *
 * Schreibt gleichzeitig in den Logcat (für den Fall, dass jemand mit `adb` mitliest) und in
 * eine Datei, die in der App selbst lesbar und teilbar ist. Letzteres ist der eigentliche
 * Zweck: Ohne Entwicklungsumgebung wäre ein Fehler auf dem Telefon sonst nicht
 * nachvollziehbar.
 *
 * Ein einzelnes Objekt statt einer durchgereichten Abhängigkeit, weil an den interessanten
 * Stellen — im Audio-Thread, im Prozessbeobachter — nichts durchgereicht werden kann, ohne
 * überall Konstruktoren aufzublähen. Ist keine Datei eingerichtet, landet trotzdem alles im
 * Logcat.
 */
object NeonLog {

    @Volatile
    private var fileLogger: FileLogger? = null

    /** Wird einmal beim Anwendungsstart aufgerufen. */
    fun install(directory: File) {
        fileLogger = runCatching { FileLogger(directory) }.getOrNull()
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        fileLogger?.log(FileLogger.Level.DEBUG, tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        fileLogger?.log(FileLogger.Level.INFO, tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        fileLogger?.log(FileLogger.Level.WARN, tag, message)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.e(tag, message, error) else Log.e(tag, message)
        fileLogger?.log(FileLogger.Level.ERROR, tag, message, error)
    }

    fun recent(lines: Int = FileLogger.DEFAULT_RECENT_LINES): List<String> =
        fileLogger?.recent(lines) ?: emptyList()

    fun fullText(): String = fileLogger?.fullText().orEmpty()

    fun clear() {
        fileLogger?.clear()
    }

    fun sizeBytes(): Long = fileLogger?.sizeBytes() ?: 0L
}
