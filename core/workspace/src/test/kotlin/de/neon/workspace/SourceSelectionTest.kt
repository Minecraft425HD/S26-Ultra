package de.neon.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * „Was macht das hier?" — mit genug Zusammenhang, dass die Antwort stimmt.
 *
 * **Warum nicht einfach die markierten Zeichen in den Prompt.** Wer drei Zeilen mitten aus
 * einer Funktion markiert und fragt, bekommt sonst eine Antwort über drei Zeilen ohne
 * Zusammenhang: Das Modell weiß nicht, in welcher Funktion sie stehen, woher die Variablen
 * kommen, in welcher Datei es ist. Die Antwort klingt dann richtig und ist es nicht — die
 * unangenehmste Sorte Fehler.
 */
class SourceSelectionTest {

    private val quelle = (1..20).joinToString("\n") { "zeile $it" }

    @Test
    fun `nur das Markierte ist der Anker`() {
        val auswahl = SourceSelection("a.kt", quelle, vonZeile = 3, bisZeile = 4)

        // Genau diese Zeichenkette geht später als Anker in eine Änderung. Sie muss dem
        // Dateiinhalt exakt entsprechen, sonst greift der Austausch daneben.
        assertEquals("zeile 3\nzeile 4", auswahl.markiert)
    }

    @Test
    fun `der Prompt nennt Datei, Zeilen und Umgebung`() {
        val block = SourceSelection("app/Main.kt", quelle, 10, 11).alsPromptBlock()

        assertTrue(block.contains("app/Main.kt"), block)
        assertTrue(block.contains("Zeilen 10 bis 11"), block)
        // Markiert mit >, Umgebung mit Leerzeichen — die knappste Form, die beides mitteilt.
        assertTrue(block.contains("> 10  zeile 10"), block)
        assertTrue(block.contains("  9  zeile 9"), block)
        // Fünf Zeilen davor und danach.
        assertTrue(block.contains("zeile 5"), block)
        assertTrue(block.contains("zeile 16"), block)
        assertTrue(!block.contains("zeile 4"), block)
        assertTrue(!block.contains("zeile 17"), block)
    }

    @Test
    fun `eine einzelne Zeile wird im Singular genannt`() {
        val block = SourceSelection("a.kt", quelle, 7, 7).alsPromptBlock()

        assertTrue(block.contains("die Zeilen 7 ("), block)
        assertTrue(!block.contains("bis 7"), block)
    }

    @Test
    fun `am Dateianfang wird nicht ueber den Rand gelesen`() {
        val block = SourceSelection("a.kt", quelle, 1, 2).alsPromptBlock()

        assertTrue(block.contains("> 1  zeile 1"), block)
        // Keine leeren Zeilen davor und kein negativer Index.
        assertEquals(7, block.lines().size - 1)
    }

    @Test
    fun `am Dateiende genauso`() {
        val block = SourceSelection("a.kt", quelle, 19, 20).alsPromptBlock()

        assertTrue(block.contains("> 20  zeile 20"), block)
        assertTrue(block.contains("  14  zeile 14"), block)
    }

    @Test
    fun `unsinnige Zeilennummern werden zurechtgebogen statt zu stuerzen`() {
        // Die Nummern kommen aus der Oberfläche und aus Modellantworten. Ein Absturz beim
        // Fragen wäre die schlechteste mögliche Antwort auf eine Markierung.
        assertEquals(1..1, SourceSelection("a.kt", quelle, 0, 0).bereich)
        assertEquals(20..20, SourceSelection("a.kt", quelle, 99, 200).bereich)
        // Verdrehte Grenzen: bis vor von.
        assertEquals(10..10, SourceSelection("a.kt", quelle, 10, 3).bereich)
    }

    @Test
    fun `eine leere Datei stuerzt nicht ab`() {
        val auswahl = SourceSelection("leer.kt", "", 1, 1)

        assertEquals(1..1, auswahl.bereich)
        assertEquals("", auswahl.markiert)
    }

    @Test
    fun `die Zeilennummern sind rechtsbuendig, damit der Code ausgerichtet bleibt`() {
        // Bei einem Sprung von 9 auf 10 verschiebt sich sonst der ganze Codeblock um ein
        // Zeichen, und ein Modell liest die Einrückung falsch.
        val block = SourceSelection("a.kt", quelle, 9, 10).alsPromptBlock(umgebung = 5)

        assertTrue(block.contains("   4  zeile 4"), block)
        assertTrue(block.contains("> 10  zeile 10"), block)
    }

    /**
     * Aus Zeichenpositionen werden Zeilennummern.
     *
     * Compose kennt nur Abstände vom Dateianfang; der Prompt braucht Zeilen, denn nur die kann
     * eine Antwort nennen und ein Mensch wiederfinden.
     */
    @Test
    fun `eine Markierung ueber zwei Zeilen ergibt zwei Zeilen`() {
        val text = "eins\nzwei\ndrei\nvier"
        //          0123 4 5678 9 ...

        // Von Anfang "zwei" bis Ende "drei".
        val auswahl = SourceSelection.ausZeichenbereich("a.kt", text, start = 5, ende = 14)

        assertEquals(2..3, auswahl.bereich)
        assertEquals("zwei\ndrei", auswahl.markiert)
    }

    @Test
    fun `eine Markierung bis zum naechsten Zeilenanfang nimmt die Zeile nicht mit`() {
        val text = "eins\nzwei\ndrei"

        // Wer mit dem Finger über "eins" streicht, markiert meistens bis zum Anfang von
        // "zwei" — und bekäme sonst eine Zeile mehr, als er sieht.
        val auswahl = SourceSelection.ausZeichenbereich("a.kt", text, start = 0, ende = 5)

        assertEquals(1..1, auswahl.bereich)
        assertEquals("eins", auswahl.markiert)
    }

    @Test
    fun `ohne Markierung gilt die Zeile, in der der Cursor steht`() {
        val text = "eins\nzwei\ndrei"

        // Der häufigste Fall beim Antippen: Es ist nichts markiert, nur der Cursor steht
        // irgendwo. „Was macht das hier" meint dann diese Zeile.
        val auswahl = SourceSelection.ausZeichenbereich("a.kt", text, start = 7, ende = 7)

        assertEquals(2..2, auswahl.bereich)
    }

    @Test
    fun `Positionen ausserhalb des Textes stuerzen nicht ab`() {
        val text = "eins\nzwei"

        // Sie kommen aus einer Oberfläche, in der gerade getippt wurde: Ein Zustand, in dem
        // Text und Markierung um einen Tastendruck auseinanderliegen, ist normal.
        assertEquals(1..2, SourceSelection.ausZeichenbereich("a.kt", text, -5, 999).bereich)
        assertEquals(1..1, SourceSelection.ausZeichenbereich("a.kt", "", 3, 7).bereich)
    }

    @Test
    fun `eine rueckwaerts gezogene Markierung meint denselben Bereich`() {
        // Rückwärts markieren ist üblich, und manche Oberflächen reichen dann start > ende
        // durch. Nur zu begrenzen ließe die Markierung auf einen Punkt am falschen Ende
        // zusammenfallen — die Frage bezöge sich auf eine Zeile, die niemand markiert hat.
        val text = "eins\nzwei\ndrei"

        val rueckwaerts = SourceSelection.ausZeichenbereich("a.kt", text, start = 9, ende = 2)
        val vorwaerts = SourceSelection.ausZeichenbereich("a.kt", text, start = 2, ende = 9)

        assertEquals(vorwaerts.bereich, rueckwaerts.bereich)
        assertEquals(1..2, rueckwaerts.bereich)
    }
}
