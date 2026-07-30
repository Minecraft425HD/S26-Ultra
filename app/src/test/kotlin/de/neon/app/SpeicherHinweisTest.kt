package de.neon.app

import de.neon.platform.MemoryReading
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Was am Regler steht, wenn der Speicher nicht reicht.
 *
 * **Der Anlass.** Am Regler stand „16384 Token — 1,1 GB Arbeitsspeicher", und das klang wie
 * eine Auskunft. Es war eine: Genau diese 1,1 GB haben die App auf einem Gerät mit 1,6 GB
 * freiem Speicher sechsmal erschlagen. Was fehlte, war der Vergleich — die Zahl neben der
 * Zahl.
 *
 * Die Werte hier stammen aus dem Protokoll vom 30. Juli.
 */
class SpeicherHinweisTest {

    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    /** Das gemessene Gerät: 5,3 GB insgesamt, 1,6 GB frei. */
    private val gemessen = MemoryReading(totalBytes = 5_690L * mb, availableBytes = 1_600L * mb)

    @Test
    fun `warnt, wenn der Wunsch nicht passt`() {
        val text = speicherHinweis(gemessen, gewuenscht = 16_384)

        assertTrue(text.contains("4096"), "die mögliche Größe fehlt: $text")
        // Und der Grund. Ohne ihn liest sich eine Kürzung wie eine Willkür.
        assertTrue(text.contains("beendet Android die App"), text)
    }

    @Test
    fun `bestaetigt, wenn es passt`() {
        val text = speicherHinweis(gemessen, gewuenscht = 4_096)

        assertTrue(text.contains("passt"), text)
        assertTrue(!text.contains("Neon nimmt"), "unnötige Warnung: $text")
    }

    @Test
    fun `nennt in jedem Fall den freien Speicher`() {
        listOf(4_096, 8_192, 16_384, 32_768).forEach { wunsch ->
            val text = speicherHinweis(gemessen, wunsch)
            assertTrue(text.contains("1,6"), "freier Speicher fehlt bei $wunsch: $text")
            assertTrue(text.contains("5,"), "Gesamtspeicher fehlt bei $wunsch: $text")
        }
    }

    @Test
    fun `sagt es, wenn nichts zu messen war`() {
        val text = speicherHinweis(MemoryReading(0, 0), gewuenscht = 16_384)

        // Keine erfundene Beruhigung und keine erfundene Warnung.
        assertTrue(text.contains("nicht lesbar"), text)
        assertTrue(text.contains("eingestellten Wert"), text)
    }

    @Test
    fun `bei viel Speicher passt auch das groesste Fenster`() {
        // 12 GB frei: Ein Fünftel davon sind 2,4 GB, und 32768 Token brauchen 2,25 GB.
        val reichlich = MemoryReading(totalBytes = 16 * gb, availableBytes = 12 * gb)

        assertTrue(speicherHinweis(reichlich, 32_768).contains("passt"))
    }

    @Test
    fun `auch reichlich Speicher hat eine Grenze`() {
        // 10 GB frei reichen für 32768 Token nicht: Ein Fünftel sind 2,0 GB, gebraucht
        // werden 2,25. Diese Erwartung stand vorher auf "passt" — sie hing an der
        // Drittel-Regel, die auf dem Gerät zum Abschuss führte. Ein Test, der eine
        // verworfene Regel festhält, hält die Regel am Leben.
        val zehn = MemoryReading(totalBytes = 16 * gb, availableBytes = 10 * gb)
        val text = speicherHinweis(zehn, 32_768)

        assertTrue(text.contains("16384"), "die mögliche Größe fehlt: $text")
    }
}
