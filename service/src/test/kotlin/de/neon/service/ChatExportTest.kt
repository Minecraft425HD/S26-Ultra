package de.neon.service

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Der Chat-Export.
 *
 * **Wozu er da ist, bestimmt, was drinstehen muss.** Der häufige Fall ist nicht das Archiv,
 * sondern der Fehlerbericht: Eine Antwort war merkwürdig, und jemand will sie zeigen. Dafür
 * zählen drei Dinge — der Wortlaut unverändert, wer wann gesprochen hat, und mit welchem
 * Modell bei welcher Geschwindigkeit. Ohne das Letzte ist „Neon hat Unsinn geschrieben" nicht
 * von „das 1.7B-Modell hat bei zwölf Token je Sekunde Unsinn geschrieben" zu unterscheiden.
 */
class ChatExportTest {

    private val jetzt = 1_700_000_000_000L

    private fun frage(text: String, zeit: Long = jetzt) =
        ChatEntry(fromUser = true, text = text, timestampMillis = zeit)

    private fun antwort(
        text: String,
        modell: String? = "qwen3-4b-instruct",
        token: Int = 0,
        dauer: Long = 0,
        zeit: Long = jetzt,
    ) = ChatEntry(
        fromUser = false,
        text = text,
        timestampMillis = zeit,
        modelId = modell,
        tokenCount = token,
        latencyMs = dauer,
    )

    @Test
    fun `beide Seiten stehen im Export, in der richtigen Reihenfolge`() {
        val text = ChatExport.alsMarkdown(
            listOf(frage("Wie spät ist es?"), antwort("Kurz nach drei.")),
            jetzt = jetzt,
        )

        assertTrue(text.indexOf("Wie spät ist es?") < text.indexOf("Kurz nach drei."), text)
        assertContains(text, "### Du · ")
        assertContains(text, "### Neon · ")
    }

    /**
     * **Der Wortlaut bleibt, wie er war.**
     *
     * Der Export ist am häufigsten dann interessant, wenn Quelltext im Gespräch steht. Wer
     * ihn einrückte oder umbräche, machte aus einem Codeblock etwas anderes als das, was auf
     * dem Bildschirm stand — und genau darum geht es beim Zeigen.
     */
    @Test
    fun `Quelltext geht unveraendert durch`() {
        val code = "```kotlin\nfun main() {\n    println(\"hallo\")\n}\n```"

        val text = ChatExport.alsMarkdown(listOf(antwort(code)), jetzt = jetzt)

        assertContains(text, code)
    }

    @Test
    fun `unter der Antwort stehen Modell, Token und Geschwindigkeit`() {
        val text = ChatExport.alsMarkdown(
            listOf(antwort("Kurz nach drei.", modell = "qwen3-1.7b", token = 100, dauer = 8000)),
            jetzt = jetzt,
        )

        assertContains(text, "qwen3-1.7b")
        assertContains(text, "100 Token")
        assertContains(text, "8000 ms")
        // 100 Token in 8 Sekunden. Die Zahl, an der man erkennt, ob das Gerät gedrosselt hat.
        assertContains(text, "12,5 Token/s")
    }

    /** Unter dem, was der Nutzer geschrieben hat, gibt es nichts zu messen. */
    @Test
    fun `unter der eigenen Frage steht keine Fussnote`() {
        val text = ChatExport.alsMarkdown(listOf(frage("Hallo")), jetzt = jetzt)

        assertFalse("Token" in text, text)
        assertFalse("ms" in text.substringAfter("Hallo"), text)
    }

    /**
     * Hinweise sind keine Gesprächsbeiträge.
     *
     * „Das Modell wird geladen" gehört in den Export — es erklärt eine Lücke von einer
     * Minute —, aber nicht als Antwort von Neon mit Modellangabe darunter.
     */
    @Test
    fun `ein Hinweis wird als Hinweis ausgewiesen`() {
        val text = ChatExport.alsMarkdown(
            listOf(
                ChatEntry(
                    fromUser = false,
                    text = "Ich lade gerade das Modell.",
                    timestampMillis = jetzt,
                    notice = true,
                    modelId = "qwen3-4b-instruct",
                )
            ),
            jetzt = jetzt,
        )

        assertContains(text, "### Hinweis · ")
        assertFalse("qwen3-4b-instruct" in text, "ein Hinweis hat kein antwortendes Modell")
    }

    /**
     * **Der Baustand gehört an den Anfang.**
     *
     * Bei einem gemeldeten Fehler ist das die erste Frage: Welche Fassung war das? Ohne die
     * Angabe kostet jede Meldung eine Rückfrage.
     */
    @Test
    fun `die Kopfzeile steht vor dem ersten Beitrag`() {
        val text = ChatExport.alsMarkdown(
            listOf(frage("Hallo")),
            kopfzeile = "Fassung 0.1.0-m1 (abc1234).",
            jetzt = jetzt,
        )

        assertTrue(text.indexOf("abc1234") < text.indexOf("Hallo"), text)
    }

    @Test
    fun `ein leeres Gespraech ergibt kein leeres Blatt`() {
        val text = ChatExport.alsMarkdown(emptyList(), jetzt = jetzt)

        assertContains(text, "Neon")
        assertContains(text, "leer")
    }

    @Test
    fun `der Dateiname traegt Datum und Uhrzeit`() {
        val name = ChatExport.dateiname(jetzt)

        assertTrue(name.startsWith("neon-chat-"), name)
        assertTrue(name.endsWith(".md"), name)
        // Zwei Exporte in derselben Minute überschreiben sich; in verschiedenen nicht.
        assertEquals(name, ChatExport.dateiname(jetzt + 1_000))
        assertTrue(ChatExport.dateiname(jetzt + 120_000) != name)
    }
}
