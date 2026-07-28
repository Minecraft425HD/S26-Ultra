package de.neon.platform

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hält fest, woran die App gestorben ist — damit es sich beim nächsten Start ablesen lässt.
 *
 * **Warum das gebraucht wird.** Auf einem Telefon ohne Entwicklungsumgebung ist ein
 * Absturz eine Sackgasse: Android zeigt „App wurde beendet" und sonst nichts, der Logcat
 * ist ohne `adb` nicht erreichbar, und ein Protokoll im Diagnose-Screen hilft nicht, wenn
 * die App gar nicht erst so weit kommt. Genau dieser Fall ist eingetreten und hat mehrere
 * Runden gekostet.
 *
 * Deshalb: Der Handler wird als **allererstes** installiert, noch bevor irgendetwas anderes
 * passiert, und schreibt die vollständige Aufrufliste in eine Datei. Beim nächsten Start
 * zeigt die App sie an, statt so zu tun, als sei nichts gewesen.
 */
class CrashReporter(private val directory: File) {

    private val crashFile: File get() = File(directory, FILE_NAME)

    private val timestamps = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)

    init {
        if (!directory.exists()) directory.mkdirs()
    }

    /**
     * Übernimmt die Behandlung unbehandelter Ausnahmen.
     *
     * Der vorherige Handler wird anschließend weiter aufgerufen — sonst würde Android die
     * App nicht mehr regulär beenden und stattdessen hängen bleiben.
     */
    fun install(versionName: String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(thread.name, error, versionName) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Hält einen Fehler fest, der abgefangen wurde, aber trotzdem verhindert, dass Neon
     * arbeitet — etwa wenn der Objektgraph sich nicht aufbauen lässt.
     */
    fun recordHandled(context: String, error: Throwable, versionName: String) {
        runCatching { write(context, error, versionName, fatal = false) }
    }

    private fun write(
        where: String,
        error: Throwable,
        versionName: String,
        fatal: Boolean = true,
    ) {
        val text = buildString {
            appendLine(if (fatal) "ABSTURZ" else "FEHLER BEIM START")
            appendLine("Zeitpunkt: ${timestamps.format(Date())}")
            appendLine("Version:   $versionName")
            appendLine("Stelle:    $where")
            appendLine("Gerät:     ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android:   ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine()
            append(error.stackTraceToString())
        }
        crashFile.writeText(text.take(MAX_BYTES))
    }

    /** Der letzte festgehaltene Absturz, oder `null`. */
    fun lastCrash(): String? = runCatching {
        if (crashFile.isFile && crashFile.length() > 0) crashFile.readText() else null
    }.getOrNull()

    fun clear() {
        runCatching { crashFile.delete() }
    }

    companion object {
        private const val FILE_NAME = "crash.txt"

        /** Aufruflisten sind lang, aber nicht unbegrenzt. */
        private const val MAX_BYTES = 64 * 1024
    }
}
