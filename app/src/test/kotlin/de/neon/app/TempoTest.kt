package de.neon.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Die Geschwindigkeitsangabe unter jeder Antwort.
 *
 * **Wozu sie da ist.** Dass Neon mit 0,71 Token je Sekunde antwortete, stand nur in der
 * Ausgabe von `llama-server` — man musste das Protokoll teilen und die richtigen Zeilen
 * darin finden, um es zu sehen. Auf dem Bildschirm sah eine zähe Antwort genauso aus wie
 * eine flotte, nur später.
 *
 * Die Zahlen in diesen Tests stammen aus dem echten Protokoll vom 29. Juli.
 */
class TempoTest {

    @Test
    fun `unter zehn zaehlt die Nachkommastelle`() {
        // Der gemessene Fall: 10 Token in 14,1 Sekunden. Ohne Nachkommastelle stünde hier
        // "0 T/s" oder "1 T/s" — und der Unterschied zwischen 0,7 und 4,2 ist genau der
        // zwischen unbenutzbar und zäh.
        assertEquals("0,7 T/s", tempo(10, 14_112)?.replace('.', ','))
        assertEquals("4,2 T/s", tempo(159, 38_086)?.replace('.', ','))
    }

    @Test
    fun `darueber genuegt eine ganze Zahl`() {
        // Was zu erwarten wäre, wenn der Befehlssatz stimmt. Die Nachkommastelle wäre
        // dort nur Rauschen.
        assertEquals("20 T/s", tempo(200, 10_000))
        assertEquals("15 T/s", tempo(45, 3_000))
    }

    @Test
    fun `ohne brauchbare Zahlen bleibt die Angabe weg`() {
        // Die Regelstufe antwortet ohne Modell und ohne Token. Dann ist "0 T/s" keine
        // Auskunft, sondern eine falsche.
        assertNull(tempo(0, 5_000))
        assertNull(tempo(10, 0))
        assertNull(tempo(-1, 100))
    }

    @Test
    fun `die Angabe ist kurz genug fuer die Zeile unter der Blase`() {
        // Darunter steht schon Modellname, Latenz und Begründung. Wird es zu lang, bricht
        // die Zeile um und die Blase wird unruhig.
        val text = tempo(159, 38_086)!!
        assertTrue(text.length <= 8, "zu lang: '$text'")
    }
}
