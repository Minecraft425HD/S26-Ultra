package de.neon.service

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Macht aus dem Gesprächsverlauf einen Text, den man weitergeben kann.
 *
 * **Warum Markdown und nicht JSON.** Ein Export, den niemand lesen kann, ist ein Backup; ein
 * Export, den man in eine Nachricht kopieren kann, ist ein Werkzeug. Der häufige Fall ist,
 * dass eine Antwort merkwürdig war und jemand sie zeigen will — dafür zählt Lesbarkeit, und
 * Quelltextblöcke bleiben in Markdown das, was sie sind.
 *
 * **Die Zeile unter der Antwort geht mit.** Welches Modell geantwortet hat, wie lange es
 * gebraucht hat und wie viele Token es waren, steht im Chat unter jeder Blase. Genau diese
 * Angaben unterscheiden „Neon hat Unsinn geschrieben" von „das 1.7B-Modell hat bei 12 Token
 * je Sekunde Unsinn geschrieben" — und ohne sie ist der Export als Fehlerbericht wertlos.
 *
 * Ohne Android, damit sich das Ergebnis Zeile für Zeile prüfen lässt.
 */
object ChatExport {

    /**
     * Der ganze Verlauf als Markdown.
     *
     * @param kopfzeile eine Zeile über dem Gespräch — etwa der Baustand. Bei einem gemeldeten
     *   Fehler ist das die erste Frage: Welche Fassung war das?
     */
    fun alsMarkdown(
        eintraege: List<ChatEntry>,
        kopfzeile: String? = null,
        jetzt: Long = System.currentTimeMillis(),
    ): String = buildString {
        appendLine("# Neon — Gesprächsverlauf")
        appendLine()
        appendLine("Exportiert am ${zeitpunkt(jetzt)}.")
        kopfzeile?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
        appendLine()

        if (eintraege.isEmpty()) {
            appendLine("_Das Gespräch ist leer._")
            return@buildString
        }

        eintraege.forEach { eintrag ->
            appendLine("---")
            appendLine()
            appendLine("### ${kopf(eintrag)}")
            appendLine()
            // Der Text unverändert. Wer ihn einrückte oder umbräche, machte aus einem
            // Quelltextblock etwas anderes als das, was auf dem Bildschirm stand.
            appendLine(eintrag.text.trimEnd())
            appendLine()
            fussnote(eintrag)?.let {
                appendLine("_${it}_")
                appendLine()
            }
        }
    }

    /** Wer spricht, und wann. */
    private fun kopf(eintrag: ChatEntry): String {
        val wer = when {
            eintrag.notice -> "Hinweis"
            eintrag.fromUser -> "Du"
            else -> "Neon"
        }
        return "$wer · ${zeitpunkt(eintrag.timestampMillis)}"
    }

    /**
     * Modell, Dauer, Geschwindigkeit — dieselben Angaben wie unter der Blase.
     *
     * `null` bei allem, was der Nutzer geschrieben hat, und bei Hinweisen: Dort gibt es
     * nichts zu messen, und eine leere Fußnote unter jeder zweiten Zeile macht den Export
     * unleserlich.
     */
    private fun fussnote(eintrag: ChatEntry): String? {
        if (eintrag.fromUser || eintrag.notice) return null

        val teile = buildList {
            eintrag.modelId?.let { add(it) }
            eintrag.routeReason?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (eintrag.tokenCount > 0) add("${eintrag.tokenCount} Token")
            if (eintrag.latencyMs > 0) {
                add("${eintrag.latencyMs} ms")
                // Die Geschwindigkeit ausgerechnet und nicht dem Leser überlassen: Sie ist
                // die Zahl, an der man erkennt, ob das Gerät gedrosselt hat.
                if (eintrag.tokenCount > 0) {
                    val proSekunde = eintrag.tokenCount * 1000.0 / eintrag.latencyMs
                    add(String.format(Locale.GERMANY, "%.1f Token/s", proSekunde))
                }
            }
        }
        return teile.joinToString(" · ").ifBlank { null }
    }

    private fun zeitpunkt(millis: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date(millis))

    /** Der Dateiname, unter dem der Export geteilt wird. */
    fun dateiname(jetzt: Long = System.currentTimeMillis()): String =
        "neon-chat-" +
            SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.GERMANY).format(Date(jetzt)) +
            ".md"
}
