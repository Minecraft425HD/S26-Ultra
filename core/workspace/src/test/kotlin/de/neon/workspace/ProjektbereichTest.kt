package de.neon.workspace

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mehrere Projekte nebeneinander — und was passiert, wenn eines weg soll.
 *
 * **Woher die Tests kommen.** Der Nutzer konnte genau ein Projekt haben; ein zweites
 * `app-anlegen` überschrieb das Manifest des ersten, und Löschen gab es überhaupt nicht. Jeder
 * Fall hier ist einer, an dem er hängen geblieben ist.
 *
 * **Der Umzug ist der heikelste davon.** Er läuft genau einmal, auf dem Gerät des Nutzers, mit
 * dessen bisheriger Arbeit darin. Geht er schief, ist Arbeit weg — und zwar unbemerkt, weil
 * ein leeres Projektverzeichnis wie ein frisch installiertes aussieht.
 */
class ProjektbereichTest {

    private fun tempDir(name: String): File =
        File.createTempFile(name, "").apply { delete(); mkdirs(); deleteOnExit() }

    /** Ein Bereich mit einem Gedächtnis, wie es der Container aus den Einstellungen liefert. */
    private class Aufbau(wurzel: File) {
        var gemerkt: String? = null
        val papierkorb = File(wurzel, "papierkorb")
        val bereich = Projektbereich(
            wurzel = File(wurzel, "projekt"),
            papierkorb = papierkorb,
            gemerkterName = { gemerkt },
            merkeName = { gemerkt = it },
            uhr = { 1_000L },
        )
    }

    // ---- Namen ------------------------------------------------------------------------

    @Test
    fun `aus Zaehler wird zaehler und nicht z-hler`() {
        assertEquals("zaehler", Projektbereich.ordnername("Zähler"))
        assertEquals("gruen-weiss", Projektbereich.ordnername("Grün Weiß"))
    }

    /**
     * **Der Name kommt aus einem Sprachmodell.** Ein Projekt namens `../../models` wäre kein
     * Projekt, sondern ein Ausbruch aus dem Behälter — und zwar einer, der die Modelldateien
     * trifft, die daneben liegen.
     */
    @Test
    fun `ein Name kann nicht aus dem Behaelter hinausfuehren`() {
        assertEquals("models", Projektbereich.ordnername("../../models"))
        assertEquals("etc-passwd", Projektbereich.ordnername("/etc/passwd"))
        assertNull(Projektbereich.ordnername("../.."))
        assertNull(Projektbereich.ordnername("   "))
        assertFalse(Projektbereich.ordnername("a/b/c")!!.contains('/'))
    }

    @Test
    fun `ein Ausbruchsversuch legt den Ordner tatsaechlich im Behaelter an`() {
        val auf = Aufbau(tempDir("neon-bereich"))
        val projekt = auf.bereich.anlegen("../../models")

        assertNotNull(projekt)
        assertEquals(auf.bereich.wurzel, projekt.verzeichnis.canonicalFile.parentFile)
    }

    // ---- Auswahl ----------------------------------------------------------------------

    @Test
    fun `zwei Projekte stehen nebeneinander, jedes mit eigenem Arbeitsbereich`() {
        val auf = Aufbau(tempDir("neon-bereich"))
        val a = auf.bereich.anlegen("zaehler")!!
        val b = auf.bereich.anlegen("notizen")!!

        auf.bereich.arbeitsbereich(a).schreib("AndroidManifest.xml", """<manifest package="de.neon.zaehler" />""")
        auf.bereich.arbeitsbereich(b).schreib("notiz.txt", "hallo")

        assertContentEquals(listOf("notizen", "zaehler"), auf.bereich.projekte().map { it.name })
        // Das Manifest des einen taucht im anderen nicht auf — genau das ging vorher schief.
        assertContentEquals(listOf("notiz.txt"), auf.bereich.arbeitsbereich(b).dateien())
        assertTrue(auf.bereich.projekt("zaehler")!!.istAndroidProjekt)
        assertFalse(auf.bereich.projekt("notizen")!!.istAndroidProjekt)
        assertEquals("de.neon.zaehler", auf.bereich.projekt("zaehler")!!.paketname())
    }

    @Test
    fun `bei genau einem Projekt muss niemand eines auswaehlen`() {
        val auf = Aufbau(tempDir("neon-bereich"))
        auf.bereich.anlegen("zaehler")
        auf.gemerkt = null

        assertEquals("zaehler", auf.bereich.aktiv()?.name)
    }

    @Test
    fun `ein gemerkter Name, den es nicht mehr gibt, blockiert nicht`() {
        val auf = Aufbau(tempDir("neon-bereich"))
        auf.bereich.anlegen("zaehler")
        auf.gemerkt = "weggeraeumt"

        // Ohne diesen Rückfall stünde man nach einem Löschen von außen vor einem Bereich,
        // der ein Projekt hat und trotzdem keines findet.
        assertEquals("zaehler", auf.bereich.aktiv()?.name)
    }

    @Test
    fun `wer kein Projekt hat, bekommt eines statt einer Fehlermeldung`() {
        val auf = Aufbau(tempDir("neon-bereich"))
        val ws = auf.bereich.aktiverArbeitsbereich()

        ws.schreib("skript.py", "print(1)")
        assertEquals(Projektbereich.STANDARDNAME, auf.bereich.projekte().single().name)
        assertEquals(auf.bereich.wurzel, ws.wurzel.parentFile)
    }

    @Test
    fun `wechseln merkt sich das Projekt ueber den Neustart hinaus`() {
        val wurzel = tempDir("neon-bereich")
        val auf = Aufbau(wurzel)
        auf.bereich.anlegen("zaehler")
        auf.bereich.anlegen("notizen")
        auf.bereich.waehle("zaehler")

        // Ein zweiter Bereich über demselben Verzeichnis: das, was ein Neustart tut.
        val nachNeustart = Projektbereich(File(wurzel, "projekt"), gemerkterName = { auf.gemerkt })
        assertEquals("zaehler", nachNeustart.aktiv()?.name)
    }

    @Test
    fun `ein unbekannter Name aendert nichts`() {
        val auf = Aufbau(tempDir("neon-bereich"))
        auf.bereich.anlegen("zaehler")

        assertNull(auf.bereich.waehle("gibtesnicht"))
        assertEquals("zaehler", auf.bereich.aktiv()?.name)
    }

    // ---- Papierkorb -------------------------------------------------------------------

    @Test
    fun `ein geloeschtes Projekt liegt im Papierkorb, nicht im Nichts`() {
        val auf = Aufbau(tempDir("neon-bereich"))
        val zaehler = auf.bereich.anlegen("zaehler")!!
        auf.bereich.arbeitsbereich(zaehler).schreib("src/Main.kt", "fun main() {}")
        auf.bereich.anlegen("notizen")

        val ablage = auf.bereich.inDenPapierkorb("zaehler")

        assertNotNull(ablage)
        assertContentEquals(listOf("notizen"), auf.bereich.projekte().map { it.name })
        // Und die Arbeit ist noch da. Das ist der ganze Zweck.
        assertEquals("fun main() {}", File(ablage, "src/Main.kt").readText())
    }

    /**
     * Der Papierkorb liegt **neben** dem Behälter.
     *
     * Läge er darin, wäre er selbst ein Projekt — und ein gelöschtes Projekt stünde nach dem
     * Löschen wieder in der Liste.
     */
    @Test
    fun `der Papierkorb ist kein Projekt`() {
        val auf = Aufbau(tempDir("neon-bereich"))
        auf.bereich.anlegen("zaehler")
        auf.bereich.inDenPapierkorb("zaehler")

        assertTrue(auf.bereich.projekte().isEmpty())
        assertTrue(auf.papierkorb.isDirectory)
    }

    @Test
    fun `nach dem Loeschen des aktiven Projekts ist ein anderes aktiv`() {
        val auf = Aufbau(tempDir("neon-bereich"))
        auf.bereich.anlegen("notizen")
        auf.bereich.anlegen("zaehler")
        auf.bereich.waehle("zaehler")

        auf.bereich.inDenPapierkorb("zaehler")

        assertNull(auf.gemerkt, "ein Name, der auf nichts zeigt, gehört gelöscht")
        assertEquals("notizen", auf.bereich.aktiv()?.name)
    }

    @Test
    fun `was es nicht gibt, kann nicht geloescht werden`() {
        val auf = Aufbau(tempDir("neon-bereich"))
        auf.bereich.anlegen("zaehler")

        assertNull(auf.bereich.inDenPapierkorb("gibtesnicht"))
        assertEquals(1, auf.bereich.projekte().size)
    }

    // ---- Umzug ------------------------------------------------------------------------

    /**
     * Der Fall auf dem Gerät des Nutzers: Manifest und Quelltext liegen lose im Behälter.
     *
     * Ohne den Umzug sähe er nach der Aktualisierung ein leeres Projektverzeichnis und müsste
     * glauben, seine Arbeit sei weg.
     */
    @Test
    fun `die alte flache Ablage zieht in einen eigenen Ordner um`() {
        val wurzel = tempDir("neon-bereich")
        val behaelter = File(wurzel, "projekt").apply { mkdirs() }
        File(behaelter, "AndroidManifest.xml")
            .writeText("""<manifest package="de.neon.zaehler" />""")
        File(behaelter, "src").mkdirs()
        File(behaelter, "src/Main.kt").writeText("fun main() {}")

        val auf = Aufbau(wurzel)
        val name = auf.bereich.holeAltesProjektHerein()

        assertEquals("zaehler", name, "der Name kommt aus dem Paketnamen")
        assertEquals("zaehler", auf.gemerkt)
        val projekt = auf.bereich.projekt("zaehler")!!
        assertContentEquals(
            listOf("AndroidManifest.xml", "src/Main.kt"),
            auf.bereich.arbeitsbereich(projekt).dateien(),
        )
    }

    @Test
    fun `ohne Manifest bekommt die alte Ablage einen sprechenden Namen`() {
        val wurzel = tempDir("neon-bereich")
        File(wurzel, "projekt").mkdirs()
        File(wurzel, "projekt/skript.py").writeText("print(1)")

        val auf = Aufbau(wurzel)

        assertEquals(Projektbereich.UMZUGSNAME, auf.bereich.holeAltesProjektHerein())
    }

    @Test
    fun `der Umzug laeuft nur einmal`() {
        val wurzel = tempDir("neon-bereich")
        File(wurzel, "projekt").mkdirs()
        File(wurzel, "projekt/skript.py").writeText("print(1)")

        val auf = Aufbau(wurzel)
        auf.bereich.holeAltesProjektHerein()

        // Beim zweiten Start liegt nichts mehr lose herum — und es entsteht kein zweites,
        // leeres Projekt neben dem umgezogenen.
        assertNull(auf.bereich.holeAltesProjektHerein())
        assertEquals(1, auf.bereich.projekte().size)
    }

    @Test
    fun `ein leerer Behaelter zieht nichts um`() {
        val auf = Aufbau(tempDir("neon-bereich"))
        assertNull(auf.bereich.holeAltesProjektHerein())
        assertTrue(auf.bereich.projekte().isEmpty())
    }

    @Test
    fun `vorhandene Projektordner bleiben beim Umzug unangetastet`() {
        val wurzel = tempDir("neon-bereich")
        val auf = Aufbau(wurzel)
        auf.bereich.anlegen("notizen")
        File(auf.bereich.wurzel, "lose.txt").writeText("lag da")

        auf.bereich.holeAltesProjektHerein()

        assertContentEquals(
            listOf(Projektbereich.UMZUGSNAME, "notizen"),
            auf.bereich.projekte().map { it.name }.sorted(),
        )
    }
}
