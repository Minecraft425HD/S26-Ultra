package de.neon.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Den freien Arbeitsspeicher richtig lesen.
 *
 * **Wozu.** Wird ein Prozess von Android wegen Speichermangels beendet, hinterlässt das
 * keine Spur. Ob es Enge war, lässt sich nur beantworten, wenn die Zahl **vorher**
 * aufgeschrieben wurde. Und wie viel Arbeitsspeicher das Gerät überhaupt hat, war in diesem
 * Projekt bisher eine Annahme aus der Gerätebezeichnung — nie nachgesehen.
 *
 * Die Vorlage unten ist eine echte Ausgabe.
 */
class DeviceMemoryTest {

    private val vomTelefon = """
        MemTotal:       11923456 kB
        MemFree:          312044 kB
        MemAvailable:    3987120 kB
        Buffers:           28912 kB
        Cached:          4210880 kB
        SwapTotal:       4194304 kB
        SwapFree:        2097152 kB
    """.trimIndent()

    @Test
    fun `liest Gesamt und Verfuegbar in Byte`() {
        val messung = DeviceMemory.parse(vomTelefon)

        assertEquals(11_923_456L * 1024, messung.totalBytes)
        assertEquals(3_987_120L * 1024, messung.availableBytes)
        assertTrue(messung.known)
    }

    @Test
    fun `nimmt MemAvailable und nicht MemFree`() {
        val messung = DeviceMemory.parse(vomTelefon)

        // MemFree ist auf Linux fast immer klein, weil der Seitencache allen ungenutzten
        // Speicher belegt. Wer MemFree liest, hält jedes gesunde System für erschöpft — und
        // würde hier 305 MB statt 3,8 GB melden.
        assertFalse(
            messung.availableBytes == 312_044L * 1024,
            "MemFree gelesen statt MemAvailable",
        )
    }

    @Test
    fun `eine unlesbare Datei ergibt eine ehrliche Fehlanzeige`() {
        val messung = DeviceMemory.parse("")

        assertFalse(messung.known)
        // Nicht null melden, als wäre der Speicher voll. Eine fehlende Auskunft ist etwas
        // anderes als eine schlechte — das war die Lehre aus der verschluckten Ausnahme.
        assertTrue(messung.describe().contains("nicht lesbar"), messung.describe())
    }

    @Test
    fun `die Beschreibung nennt beide Zahlen in Gigabyte`() {
        val text = DeviceMemory.parse(vomTelefon).describe()

        assertTrue(text.contains("3,8"), text)
        assertTrue(text.contains("11,4"), text)
    }

    @Test
    fun `auf dieser Maschine kommt eine brauchbare Zeile heraus`() {
        // Kein Anspruch an den Inhalt — der Testlauf kann in einem Container ohne Zugriff
        // stattfinden. Geprüft wird, dass nichts wirft und etwas dasteht.
        val text = DeviceMemory.read().describe()
        assertTrue(text.isNotBlank())
        println(text)
    }
}
