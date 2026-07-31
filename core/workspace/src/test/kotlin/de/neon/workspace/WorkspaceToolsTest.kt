package de.neon.workspace

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Die Sätze, die ein Werkzeug zurückgibt, sind Teil der Schnittstelle zum Modell.
 *
 * **Warum das getestet wird und nicht nur der Rückgabewert.** Das Modell **liest** diese
 * Sätze und leitet daraus den nächsten Versuch ab. Ein „hat nicht geklappt" schickt es in
 * denselben Fehler zurück, und bei 15 Token je Sekunde kostet jeder Fehlversuch eine halbe
 * Minute. Die Fehlermeldung muss also sagen, **was** zu tun ist — das ist kein Feinschliff,
 * sondern die Funktion.
 */
class WorkspaceToolsTest {

    private fun frisch(): Pair<Workspace, WorkspaceTools> {
        val verzeichnis = File.createTempFile("neon-wt", "").apply {
            delete(); mkdirs(); deleteOnExit()
        }
        val ws = Workspace(verzeichnis)
        return ws to WorkspaceTools(ws)
    }

    @Test
    fun `schreiben meldet Pfad und Zeilenzahl`() {
        val (ws, tools) = frisch()

        val ergebnis = tools.schreib("src/Main.kt", "fun main() {\n    println(1)\n}")

        assertTrue(ergebnis.gelungen)
        assertTrue(ergebnis.gesprochen.contains("src/Main.kt"), ergebnis.gesprochen)
        assertTrue(ergebnis.gesprochen.contains("3 Zeilen"), ergebnis.gesprochen)
        assertEquals("fun main() {\n    println(1)\n}", ws.lies("src/Main.kt"))
    }

    @Test
    fun `ein Pfad nach draussen wird abgewiesen und erklaert`() {
        val (_, tools) = frisch()

        val ergebnis = tools.schreib("../../databases/neon.db", "x")

        assertFalse(ergebnis.gelungen)
        assertTrue(ergebnis.gesprochen.contains("außerhalb"), ergebnis.gesprochen)
        // Damit das Modell den nächsten Pfad richtig bildet.
        assertTrue(ergebnis.gesprochen.contains("relativ"), ergebnis.gesprochen)
    }

    @Test
    fun `die Abweisung nennt nicht den absoluten Pfad des Projekts`() {
        val (ws, tools) = frisch()

        val ergebnis = tools.schreib("/etc/passwd", "x")

        // Was ein Modell sieht, schreibt es irgendwann hin. Den Datenpfad der App braucht es
        // nicht, um einen gültigen relativen Pfad zu bilden.
        assertFalse(ergebnis.gesprochen.contains(ws.wurzel.path), ergebnis.gesprochen)
    }

    @Test
    fun `ein mehrdeutiger Anker sagt, wie es besser geht`() {
        val (_, tools) = frisch()
        tools.schreib("a.kt", "val x = 1\nval y = 1\nval z = 1")

        val ergebnis = tools.aendere("a.kt", "= 1", "= 2")

        assertFalse(ergebnis.gelungen)
        assertTrue(ergebnis.gesprochen.contains("3 Mal"), ergebnis.gesprochen)
        assertTrue(ergebnis.gesprochen.contains("1, 2, 3"), ergebnis.gesprochen)
        // Der Handlungshinweis ist der Punkt: mehr Zeilen dazunehmen.
        assertTrue(ergebnis.gesprochen.contains("mehr Zeilen"), ergebnis.gesprochen)
    }

    @Test
    fun `ein fehlender Anker verweist auf die naechstliegende Zeile`() {
        val (_, tools) = frisch()
        tools.schreib("a.kt", "fun begruesse() {}\nfun verabschiede() {}")

        val ergebnis = tools.aendere("a.kt", "fun verabschieden() {}", "…")

        assertFalse(ergebnis.gelungen)
        assertTrue(ergebnis.gesprochen.contains("Zeile 2"), ergebnis.gesprochen)
        // Und die Anweisung, die den Fehler nicht wiederholt.
        assertTrue(ergebnis.gesprochen.contains("wörtlich"), ergebnis.gesprochen)
    }

    @Test
    fun `eine gelungene Aenderung nennt die Zeile`() {
        val (ws, tools) = frisch()
        tools.schreib("a.kt", "val x = 1\nval y = 2")

        val ergebnis = tools.aendere("a.kt", "val y = 2", "val y = 3")

        assertTrue(ergebnis.gelungen, ergebnis.gesprochen)
        assertTrue(ergebnis.gesprochen.contains("Zeile 2"), ergebnis.gesprochen)
        assertEquals("val x = 1\nval y = 3", ws.lies("a.kt"))
    }

    @Test
    fun `ein leerer Anker wird abgefangen statt zu stuerzen`() {
        val (_, tools) = frisch()
        tools.schreib("a.kt", "x")

        // AnchoredEdit wirft hier absichtlich. Ein Werkzeug darf das nicht weitergeben: Eine
        // Ausnahme aus einem Modellaufruf bringt den ganzen Durchgang zum Stehen.
        val ergebnis = tools.aendere("a.kt", "", "y")

        assertFalse(ergebnis.gelungen)
        assertTrue(ergebnis.gesprochen.contains("ersetzt werden soll"), ergebnis.gesprochen)
    }

    @Test
    fun `lesen gibt den Inhalt zurueck, nicht eine Beschreibung davon`() {
        val (_, tools) = frisch()
        tools.schreib("a.kt", "val x = 1")

        // Der Inhalt ist das Werkzeugergebnis. Eine Zusammenfassung wäre hier das Falsche:
        // Das Modell braucht den Wortlaut, um daraus einen Anker zu bilden.
        assertEquals("val x = 1", tools.lies("a.kt").gesprochen)
    }

    @Test
    fun `eine fehlende Datei ist kein Absturz`() {
        val (_, tools) = frisch()

        val ergebnis = tools.lies("gibtsnicht.kt")

        assertFalse(ergebnis.gelungen)
        assertTrue(ergebnis.gesprochen.contains("gibt es nicht"), ergebnis.gesprochen)
    }

    @Test
    fun `die Dateiliste ist begrenzt und sagt, dass sie es ist`() {
        val (_, tools) = frisch()
        repeat(12) { tools.schreib("datei$it.txt", "x") }

        val ergebnis = tools.dateien(grenze = 5)

        assertEquals(5, ergebnis.gesprochen.lines().count { it.endsWith(".txt") })
        // Eine abgeschnittene Liste, die nicht sagt, dass sie abgeschnitten ist, lässt das
        // Modell glauben, es kenne das ganze Projekt.
        assertTrue(ergebnis.gesprochen.contains("7 weitere"), ergebnis.gesprochen)
    }

    @Test
    fun `ein leeres Projekt sagt das auch so`() {
        val (_, tools) = frisch()

        assertTrue(tools.dateien().gesprochen.contains("leer"))
    }
}
