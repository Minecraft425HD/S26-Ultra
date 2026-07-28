package de.neon.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GermanNumbersTest {

    @Test
    fun `liest Ziffernfolgen`() {
        assertEquals(0, GermanNumbers.parse("0"))
        assertEquals(42, GermanNumbers.parse("42"))
        assertEquals(100, GermanNumbers.parse("100"))
    }

    @Test
    fun `liest Einer Zehner und die Teens`() {
        assertEquals(1, GermanNumbers.parse("eine"))
        assertEquals(3, GermanNumbers.parse("drei"))
        assertEquals(9, GermanNumbers.parse("neun"))
        assertEquals(12, GermanNumbers.parse("zwölf"))
        assertEquals(17, GermanNumbers.parse("siebzehn"))
        assertEquals(30, GermanNumbers.parse("dreißig"))
        assertEquals(90, GermanNumbers.parse("neunzig"))
    }

    @Test
    fun `liest zusammengesetzte Zahlen`() {
        assertEquals(21, GermanNumbers.parse("einundzwanzig"))
        assertEquals(45, GermanNumbers.parse("fünfundvierzig"))
        assertEquals(99, GermanNumbers.parse("neunundneunzig"))
    }

    @Test
    fun `kommt ohne Umlaute aus`() {
        // Manche Spracherkenner liefern Umschrift statt Umlauten.
        assertEquals(5, GermanNumbers.parse("fuenf"))
        assertEquals(45, GermanNumbers.parse("fuenfundvierzig"))
        assertEquals(30, GermanNumbers.parse("dreissig"))
    }

    @Test
    fun `gibt null fuer Nicht-Zahlen`() {
        assertNull(GermanNumbers.parse("licht"))
        assertNull(GermanNumbers.parse(""))
        assertNull(GermanNumbers.parse("undzwanzig"))
    }

    @Test
    fun `findet die erste Zahl samt Position`() {
        val tokens = "timer auf fünf minuten".split(" ")
        val found = GermanNumbers.findFirst(tokens)
        assertEquals(2, found?.index)
        assertEquals(5, found?.value)
        assertEquals("minuten", tokens[found!!.index + 1])
    }

    @Test
    fun `findFirst zaehlt auch bloße Artikel mit`() {
        // Das ist Absicht: Die Unterscheidung trifft findQuantity, nicht findFirst.
        val found = GermanNumbers.findFirst("stell einen timer".split(" "))
        assertEquals(1, found?.index)
        assertEquals(1, found?.value)
    }

    @Test
    fun `findet keine Zahl wenn keine da ist`() {
        assertNull(GermanNumbers.findFirst("mach das licht aus".split(" ")))
    }

    @Test
    fun `bevorzugt die Zahl vor der passenden Einheit`() {
        val tokens = "stell mir einen timer auf fünfundzwanzig minuten".split(" ")
        val quantity = GermanNumbers.findQuantity(tokens) { it.startsWith("minute") }
        assertEquals(25, quantity?.value)
    }

    @Test
    fun `ueberspringt bloße Artikel wenn keine Einheit folgt`() {
        val tokens = "stell einen wecker auf sieben".split(" ")
        val quantity = GermanNumbers.findQuantity(tokens) { it == "uhr" }
        assertEquals(7, quantity?.value)
    }

    @Test
    fun `nimmt ein als Zahl wenn eine Einheit folgt`() {
        val tokens = "timer auf eine minute".split(" ")
        val quantity = GermanNumbers.findQuantity(tokens) { it.startsWith("minute") }
        assertEquals(1, quantity?.value)
    }
}
