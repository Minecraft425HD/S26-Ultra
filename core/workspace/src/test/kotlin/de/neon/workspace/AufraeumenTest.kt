package de.neon.workspace

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Löschen und Verschieben — die beiden Vorgänge, die es überhaupt nicht gab.
 *
 * **Warum das mehr als Bequemlichkeit ist.** Wer eine Datei an die richtige Stelle legen
 * wollte, musste sie neu schreiben und die alte stehen lassen. Im Projekt blieb dann eine
 * Leiche, die das Modell beim nächsten `dateien-auflisten` wieder mitliest — und mit der es
 * beim nächsten `datei-aendern` weiterarbeitet.
 *
 * **Und warum gelöscht hier nie vernichtet heißt.** Die Pfade kommen aus einem Sprachmodell.
 * Ein Modell, das den falschen Namen erwischt, tut das nicht aus Bosheit; ohne Papierkorb wäre
 * der Schaden trotzdem endgültig.
 */
class AufraeumenTest {

    private fun tempDir(name: String): File =
        File.createTempFile(name, "").apply { delete(); mkdirs(); deleteOnExit() }

    private class Aufbau {
        val oben = File.createTempFile("neon-aufraeumen", "")
            .apply { delete(); mkdirs(); deleteOnExit() }
        val papierkorb = File(oben, "papierkorb")
        val ws = Workspace(File(oben, "projekt"), papierkorb = papierkorb)
    }

    // ---- Verschieben ------------------------------------------------------------------

    @Test
    fun `verschieben legt die Datei um und laesst keine Leiche zurueck`() {
        val auf = Aufbau()
        auf.ws.schreib("Main.kt", "fun main() {}")

        assertEquals("src/Main.kt", auf.ws.verschiebe("Main.kt", "src/Main.kt"))
        assertEquals(listOf("src/Main.kt"), auf.ws.dateien())
        assertEquals("fun main() {}", auf.ws.lies("src/Main.kt"))
    }

    @Test
    fun `umbenennen ist derselbe Vorgang`() {
        val auf = Aufbau()
        auf.ws.schreib("alt.txt", "inhalt")

        assertEquals("neu.txt", auf.ws.verschiebe("alt.txt", "neu.txt"))
        assertEquals(listOf("neu.txt"), auf.ws.dateien())
    }

    /**
     * **Kein stillschweigendes Überschreiben.** Ein Modell, das zwei Dateien auf denselben
     * Namen schiebt, verlöre sonst eine davon — und niemand erführe davon.
     */
    @Test
    fun `ein belegtes Ziel wird nicht ueberschrieben`() {
        val auf = Aufbau()
        auf.ws.schreib("a.txt", "A")
        auf.ws.schreib("b.txt", "B")

        assertNull(auf.ws.verschiebe("a.txt", "b.txt"))
        assertEquals("A", auf.ws.lies("a.txt"))
        assertEquals("B", auf.ws.lies("b.txt"))
    }

    @Test
    fun `was es nicht gibt, laesst sich nicht verschieben`() {
        val auf = Aufbau()
        assertNull(auf.ws.verschiebe("gibtesnicht.txt", "irgendwo.txt"))
    }

    @Test
    fun `verschieben fuehrt nicht aus dem Projekt hinaus`() {
        val auf = Aufbau()
        auf.ws.schreib("geheim.txt", "inhalt")

        assertNull(auf.ws.verschiebe("geheim.txt", "../../draussen.txt"))
        assertEquals("inhalt", auf.ws.lies("geheim.txt"), "die Quelle bleibt, wo sie war")
    }

    @Test
    fun `ein ganzer Ordner laesst sich verschieben`() {
        val auf = Aufbau()
        auf.ws.schreib("alt/Main.kt", "fun main() {}")
        auf.ws.schreib("alt/Hilfe.kt", "// hilfe")

        assertNotNull(auf.ws.verschiebe("alt", "src"))
        assertEquals(listOf("src/Hilfe.kt", "src/Main.kt"), auf.ws.dateien())
    }

    // ---- Löschen ----------------------------------------------------------------------

    @Test
    fun `geloescht heisst im Papierkorb`() {
        val auf = Aufbau()
        auf.ws.schreib("weg.txt", "noch da")

        val ablage = auf.ws.loesche("weg.txt", zeitstempel = 42L)

        assertNotNull(ablage)
        assertTrue(auf.ws.dateien().isEmpty())
        assertEquals("noch da", File(ablage).readText())
        assertEquals("42-weg.txt", File(ablage).name)
    }

    /**
     * Der Papierkorb liegt **außerhalb** des Projekts.
     *
     * Läge er darin, tauchte das Gelöschte beim nächsten Auflisten wieder auf, und das Modell
     * arbeitete damit weiter — als hätte man gar nichts gelöscht.
     */
    @Test
    fun `Geloeschtes taucht nicht wieder in der Dateiliste auf`() {
        val auf = Aufbau()
        auf.ws.schreib("weg.txt", "x")
        auf.ws.schreib("bleibt.txt", "y")

        auf.ws.loesche("weg.txt")

        assertEquals(listOf("bleibt.txt"), auf.ws.dateien())
    }

    @Test
    fun `zweimal dieselbe Datei loeschen verliert die erste Fassung nicht`() {
        val auf = Aufbau()
        auf.ws.schreib("notiz.txt", "erste")
        auf.ws.loesche("notiz.txt", zeitstempel = 1L)
        auf.ws.schreib("notiz.txt", "zweite")
        auf.ws.loesche("notiz.txt", zeitstempel = 2L)

        val ablagen = auf.papierkorb.listFiles().orEmpty().map { it.readText() }.sorted()
        assertEquals(listOf("erste", "zweite"), ablagen)
    }

    @Test
    fun `ein Ordner wandert samt Inhalt in den Papierkorb`() {
        val auf = Aufbau()
        auf.ws.schreib("build/klassen/A.class", "bytes")

        val ablage = auf.ws.loesche("build", zeitstempel = 7L)

        assertNotNull(ablage)
        assertEquals("bytes", File(ablage, "klassen/A.class").readText())
        assertFalse(File(auf.ws.wurzel, "build").exists())
    }

    @Test
    fun `was es nicht gibt, laesst sich nicht loeschen`() {
        val auf = Aufbau()
        assertNull(auf.ws.loesche("gibtesnicht.txt"))
    }

    @Test
    fun `loeschen fuehrt nicht aus dem Projekt hinaus`() {
        val auf = Aufbau()
        val draussen = File(auf.oben, "wichtig.txt").apply { writeText("nicht anfassen") }

        assertNull(auf.ws.loesche("../wichtig.txt"))
        assertTrue(draussen.isFile)
    }

    /**
     * **Ein freigegebener Ort hat keinen Papierkorb im Projekt.**
     *
     * Er hat ihn trotzdem: Der Papierkorb liegt im Datenverzeichnis der App, und dorthin darf
     * auch etwas aus den Downloads wandern. Das ist die einzige Art, ein Löschen im
     * Gerätespeicher rücknehmbar zu machen.
     */
    @Test
    fun `auch aus einem freigegebenen Ort wandert es in den Papierkorb`() {
        val oben = tempDir("neon-frei")
        val downloads = File(oben, "downloads").apply { mkdirs() }
        val papierkorb = File(oben, "papierkorb")
        val ws = Workspace(
            File(oben, "projekt"),
            weitereWurzeln = { listOf(downloads) },
            papierkorb = papierkorb,
        )
        val datei = File(downloads, "alt.pdf").apply { writeText("inhalt") }

        val ablage = ws.loesche(datei.absolutePath, zeitstempel = 5L)

        assertNotNull(ablage)
        assertFalse(datei.exists())
        assertEquals("inhalt", File(ablage).readText())
    }

    // ---- Ohne Papierkorb ---------------------------------------------------------------

    @Test
    fun `ohne Papierkorb wird wirklich geloescht`() {
        val ws = Workspace(tempDir("neon-ohne-korb"))
        ws.schreib("weg.txt", "x")

        assertEquals("gelöscht", ws.loesche("weg.txt"))
        assertTrue(ws.dateien().isEmpty())
    }
}
