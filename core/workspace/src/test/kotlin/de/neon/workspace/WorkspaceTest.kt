package de.neon.workspace

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Die Grenze des Arbeitsbereichs — der eigentliche Zweck dieser Klasse.
 *
 * **Warum das der erste Test ist und nicht der letzte.** Die Pfade kommen aus einem
 * Sprachmodell. Ein Modell, das `../../databases/neon.db` schreibt, tut das nicht aus Bosheit,
 * sondern weil es einen Pfad halluziniert hat — und der Schaden ist derselbe. In dieser App
 * liegen daneben das Gedächtnis, der Anhang-Index, die Modelldateien und das Protokoll.
 *
 * Geprüft wird der **aufgelöste** Pfad. Ein Vergleich auf `".."` wäre keine Sicherung:
 * `a/../../b`, ein Punkt in der Mitte oder eine symbolische Verknüpfung gehen daran vorbei.
 */
class WorkspaceTest {

    private fun frisch(): Workspace {
        val verzeichnis = File.createTempFile("neon-ws", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        return Workspace(verzeichnis)
    }

    @Test
    fun `im Projekt darf geschrieben und gelesen werden`() {
        val ws = frisch()

        assertEquals("src/Main.kt", ws.schreib("src/Main.kt", "fun main() {}"))
        assertEquals("fun main() {}", ws.lies("src/Main.kt"))
        // Die Verzeichnisse darüber entstehen mit. Ein Modell, das erst `mkdir` aufrufen muss,
        // vergisst es.
        assertTrue(File(ws.wurzel, "src").isDirectory)
    }

    @Test
    fun `aus dem Projekt heraus fuehrt kein Pfad`() {
        val ws = frisch()

        // Jeder dieser Pfade ist schon einmal von einem Modell erzeugt worden.
        listOf(
            "../draussen.txt",
            "src/../../draussen.txt",
            "./src/./../../draussen.txt",
            "/etc/passwd",
            "/data/data/de.neon.app/databases/neon.db",
        ).forEach { pfad ->
            assertNull(ws.datei(pfad), "„$pfad\" wurde durchgelassen")
            assertNull(ws.schreib(pfad, "x"), "„$pfad\" wurde geschrieben")
        }
    }

    @Test
    fun `ein Punkt in der Mitte ist harmlos, solange er drin bleibt`() {
        val ws = frisch()

        // Nicht jeder Pfad mit `..` führt hinaus. Ein Wächter, der zu viel verbietet, wird
        // umgangen — und dann verbietet er nichts mehr.
        assertEquals("src/Main.kt", ws.schreib("src/tmp/../Main.kt", "x"))
    }

    @Test
    fun `eine Aenderung wird nur bei einem Treffer geschrieben`() {
        val ws = frisch()
        ws.schreib("a.txt", "eins\nzwei\neins\n")

        val mehrdeutig = ws.aendere("a.txt", "eins", "drei")

        assertIs<AnchoredEdit.Result.Mehrdeutig>(mehrdeutig)
        // Der Punkt des Tests: Die Datei ist unberührt. Eine halb geänderte Quelldatei ist
        // schlimmer als eine unveränderte.
        assertEquals("eins\nzwei\neins\n", ws.lies("a.txt"))
    }

    @Test
    fun `eine eindeutige Aenderung landet auf der Platte`() {
        val ws = frisch()
        ws.schreib("a.txt", "eins\nzwei\n")

        assertIs<AnchoredEdit.Result.Geaendert>(ws.aendere("a.txt", "zwei", "drei"))
        assertEquals("eins\ndrei\n", ws.lies("a.txt"))
    }

    @Test
    fun `eine Aenderung an einer fehlenden Datei ergibt null`() {
        assertNull(frisch().aendere("gibtsnicht.txt", "a", "b"))
    }

    @Test
    fun `die Dateiliste laesst Bauverzeichnisse aus`() {
        val ws = frisch()
        ws.schreib("src/Main.kt", "x")
        ws.schreib("build/tmp/Main.class", "x")
        ws.schreib(".git/objects/ab/cdef", "x")
        ws.schreib("py/__pycache__/m.pyc", "x")
        ws.schreib("README.md", "x")

        // Ein Modell, dem man 4000 Dateien aus build/ vorlegt, findet die eigentlichen zwölf
        // nicht mehr — und jeder Eintrag kostet Kontext.
        assertEquals(listOf("README.md", "src/Main.kt"), ws.dateien())
    }

    @Test
    fun `das Projektverzeichnis entsteht, wenn es fehlt`() {
        val eltern = File.createTempFile("neon-ws", "").apply { delete(); mkdirs(); deleteOnExit() }
        val ws = Workspace(File(eltern, "neu/tiefer"))

        assertTrue(ws.wurzel.isDirectory)
        assertNotNull(ws.schreib("a.txt", "x"))
    }

    @Test
    fun `Pfade werden mit Schraegstrich gemeldet, auch unter Windows`() {
        val ws = frisch()
        ws.schreib("a/b/c.txt", "x")

        // Die Pfade gehen an ein Sprachmodell und kommen von ihm zurück. Ein Wechsel des
        // Trennzeichens zwischen Hin- und Rückweg wäre eine Fehlerquelle ohne Gegenwert.
        assertEquals(listOf("a/b/c.txt"), ws.dateien())
    }
}
