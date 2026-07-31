package de.neon.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Eine Änderung, die nicht danebengreifen kann.
 *
 * **Warum das die zentrale Entscheidung der Live-Bearbeitung ist.** Der naheliegende Weg — das
 * Modell schreibt die Datei neu — scheitert an diesem Gerät: Bei 15 Token je Sekunde dauert
 * eine Datei mit 400 Zeilen über vier Minuten, und wird das Token-Budget vorher aufgebraucht,
 * ist die Datei abgeschnitten. Genau dieser Fall ist hier schon einmal aufgetreten, als ein
 * Denkblock das ganze Budget verbrauchte und keine Antwort übrig blieb.
 *
 * Ein verankerter Austausch kostet nur die geänderten Zeilen **und** sagt selbst, ob er
 * gepasst hat. Das ist der Unterschied zwischen einer prüfbaren und einer geglaubten Änderung.
 */
class AnchoredEditTest {

    private val quelle = """
        |fun begruesse(name: String) {
        |    println("Hallo, ${'$'}name")
        |}
        |
        |fun verabschiede(name: String) {
        |    println("Tschüss, ${'$'}name")
        |}
    """.trimMargin()

    @Test
    fun `ein eindeutiger Anker wird ersetzt und die Zeile gemeldet`() {
        val ergebnis = AnchoredEdit.ersetze(quelle, "Tschüss", "Bis bald")

        val geaendert = assertIs<AnchoredEdit.Result.Geaendert>(ergebnis)
        assertTrue(geaendert.text.contains("Bis bald"))
        assertTrue(!geaendert.text.contains("Tschüss"))
        // Die Zeilennummer ist nicht Zierde: Ohne sie kann die Oberfläche nicht hinspringen.
        assertEquals(6, geaendert.zeile)
    }

    @Test
    fun `der Rest der Datei bleibt Zeichen fuer Zeichen gleich`() {
        val ergebnis = AnchoredEdit.ersetze(quelle, "Tschüss", "Bis bald")
        val geaendert = assertIs<AnchoredEdit.Result.Geaendert>(ergebnis)

        // Der eigentliche Gewinn gegenüber einer neu geschriebenen Datei: Was nicht gemeint
        // war, kann sich nicht mit verändern.
        assertEquals(quelle.replace("Tschüss", "Bis bald"), geaendert.text)
    }

    @Test
    fun `ein mehrfacher Anker aendert nichts und nennt die Stellen`() {
        // Der gefährlichste Fall. Zu raten hieße: eine Änderung an der falschen von zwei
        // gleichen Stellen — die fällt erst beim Übersetzen auf, wenn überhaupt.
        val ergebnis = AnchoredEdit.ersetze(quelle, "println(", "print(")

        val mehrdeutig = assertIs<AnchoredEdit.Result.Mehrdeutig>(ergebnis)
        assertEquals(listOf(2, 6), mehrdeutig.treffer)
    }

    @Test
    fun `ein Anker, der nicht da ist, zeigt auf die naechstliegende Zeile`() {
        // Der häufige Fall: Das Modell zitiert den Inhalt aus dem Gedächtnis statt aus der
        // Datei. Ein bloßes „nicht gefunden" schickt es in denselben Fehler zurück.
        val ergebnis = AnchoredEdit.ersetze(quelle, "fun verabschiede(name: Text) {", "…")

        val fehlt = assertIs<AnchoredEdit.Result.NichtGefunden>(ergebnis)
        assertEquals(5, fehlt.aehnlichsteZeile)
    }

    @Test
    fun `ohne jede Aehnlichkeit wird nichts behauptet`() {
        val ergebnis = AnchoredEdit.ersetze(quelle, "zzzz", "…")

        // Ein Hinweis, der auf eine zufällige Zeile zeigt, ist schlechter als keiner.
        assertEquals(null, assertIs<AnchoredEdit.Result.NichtGefunden>(ergebnis).aehnlichsteZeile)
    }

    @Test
    fun `ein leerer Anker ist unzulaessig`() {
        // Er würde an einer beliebigen Stelle einfügen, und „beliebig" ist bei einer
        // Quelldatei keine Angabe.
        assertFailsWith<IllegalArgumentException> { AnchoredEdit.ersetze(quelle, "", "x") }
    }

    @Test
    fun `ueberlappende Treffer werden nicht doppelt gezaehlt`() {
        // "aa" steckt in "aaa" zweimal, ersetzen lässt sich aber nur einmal. Eine
        // Trefferzahl, die höher ist als die Zahl der ersetzbaren Stellen, wäre irreführend.
        val ergebnis = AnchoredEdit.ersetze("aaa", "aa", "b")

        assertIs<AnchoredEdit.Result.Geaendert>(ergebnis)
        assertEquals("ba", (ergebnis as AnchoredEdit.Result.Geaendert).text)
    }

    @Test
    fun `ein mehrzeiliger Anker funktioniert`() {
        // Der Normalfall bei echten Änderungen: Eine ganze Funktion wird ausgetauscht.
        val ergebnis = AnchoredEdit.ersetze(
            quelle,
            "fun verabschiede(name: String) {\n    println(\"Tschüss, \$name\")\n}",
            "fun verabschiede(name: String) = Unit",
        )

        val geaendert = assertIs<AnchoredEdit.Result.Geaendert>(ergebnis)
        assertTrue(geaendert.text.contains("fun verabschiede(name: String) = Unit"), geaendert.text)
        assertEquals(5, geaendert.zeile)
    }

    @Test
    fun `ein Anker ohne Einrueckung passt trotzdem`() {
        // Gesucht wird als Teilzeichenkette, nicht zeilenweise. Das ist die nachsichtige
        // Richtung und die richtige: Ein Modell, das den Ausdruck ohne die vier Leerzeichen
        // davor zitiert, meint zweifelsfrei diese Stelle.
        val ergebnis = AnchoredEdit.ersetze(quelle, "println(\"Hallo, \$name\")", "print(name)")

        val geaendert = assertIs<AnchoredEdit.Result.Geaendert>(ergebnis)
        // Und die Einrückung der Datei bleibt, weil sie nie Teil des Austauschs war.
        assertTrue(geaendert.text.contains("    print(name)"), geaendert.text)
    }

    @Test
    fun `Leerraum wird Zeichen fuer Zeichen verglichen`() {
        // Kein Entgegenkommen beim Leerraum: Die Datei ist mit Leerzeichen eingerückt, der
        // Anker mit einem Tabulator. Das anzugleichen würde die Einrückung der Datei
        // verändern — eine stille Änderung, und genau die soll dieses Verfahren verhindern.
        //
        // Kürzere Einrückung fällt dagegen nicht auf und darf es nicht: "  println" steckt in
        // "    println". Diese Nachsicht ist der Preis dafür, dass als Teilzeichenkette
        // gesucht wird, und sie schadet nicht — die Stelle bleibt dieselbe.
        val ergebnis = AnchoredEdit.ersetze(quelle, "\tprintln(\"Hallo, \$name\")", "…")

        assertIs<AnchoredEdit.Result.NichtGefunden>(ergebnis)
    }
}
