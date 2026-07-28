package de.neon.platform

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Schreibt Protokollzeilen in eine Datei mit begrenzter Größe.
 *
 * **Warum das nötig ist.** Androids `Log` schreibt in den Logcat, und den liest man mit
 * `adb` — also mit einer Entwicklungsumgebung, die auf dem Telefon niemand hat. Scheitert
 * llama-server beim Start, hört der Nutzer sonst nur „Da ging etwas schief" und erfährt
 * nie, ob die Binärdatei fehlte, das Modell beschädigt war oder der Speicher nicht reichte.
 *
 * Bewusst eine eigene kleine Klasse statt einer Protokollbibliothek: Gebraucht wird ein
 * Ringpuffer auf der Platte, und der ist in fünfzig Zeilen geschrieben und vollständig
 * prüfbar.
 *
 * Threadsicher, weil aus dem Audio-Thread, dem Serverbeobachter und der Oberfläche
 * geschrieben wird.
 */
class FileLogger(
    private val directory: File,
    /** Ab dieser Größe wird umgebrochen. */
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()

    private val current: File get() = File(directory, FILE_NAME)
    private val previous: File get() = File(directory, "$FILE_NAME.1")

    private val timestamps = SimpleDateFormat("MM-dd HH:mm:ss", Locale.GERMANY)

    init {
        if (!directory.exists()) directory.mkdirs()
    }

    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun log(level: Level, tag: String, message: String, error: Throwable? = null) {
        val line = buildString {
            append(timestamps.format(Date(clock())))
            append(' ').append(level.name.first())
            append(' ').append(tag).append(": ").append(message)
            if (error != null) {
                append(" — ").append(error::class.simpleName).append(": ").append(error.message)
            }
        }

        synchronized(lock) {
            runCatching {
                rotateIfNeeded()
                current.appendText(line + "\n")
            }
        }
    }

    /**
     * Bricht um, statt die Datei zu leeren.
     *
     * Ein Fehler passiert oft kurz vor dem Umbruch. Würde einfach geleert, wäre genau die
     * interessante Stelle weg — mit einer Vorgängerdatei bleibt sie erhalten.
     */
    private fun rotateIfNeeded() {
        if (current.length() < maxBytes) return
        previous.delete()
        current.renameTo(previous)
    }

    /** Die jüngsten Zeilen, neueste zuletzt. */
    fun recent(lines: Int = DEFAULT_RECENT_LINES): List<String> = synchronized(lock) {
        runCatching {
            val all = buildList {
                if (previous.isFile) addAll(previous.readLines())
                if (current.isFile) addAll(current.readLines())
            }
            all.takeLast(lines)
        }.getOrDefault(emptyList())
    }

    /** Alles als ein Text — für „Protokoll teilen". */
    fun fullText(): String = recent(MAX_SHARE_LINES).joinToString("\n")

    fun clear() = synchronized(lock) {
        current.delete()
        previous.delete()
        Unit
    }

    fun sizeBytes(): Long = current.length() + previous.length()

    companion object {
        private const val FILE_NAME = "neon.log"

        /** Zwei Dateien à 512 KB — genug für mehrere Tage Betrieb, unauffällig im Speicher. */
        const val DEFAULT_MAX_BYTES = 512L * 1024

        const val DEFAULT_RECENT_LINES = 200

        /** Beim Teilen genügt, was in eine Nachricht passt. */
        const val MAX_SHARE_LINES = 2_000
    }
}
