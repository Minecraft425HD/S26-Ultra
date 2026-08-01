package de.neon.workspace

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mehrere erlaubte Orte — und die Grenze bleibt trotzdem eine Grenze.
 *
 * **Was sich geändert hat und warum es heikel ist.** Bisher war absolut gleich verboten:
 * Jeder Pfad, der mit `/` begann, flog raus. Das war eine bequeme Regel, solange es genau
 * einen erlaubten Ort gab. Seit der Nutzer weitere freigeben kann, ist ein absoluter Pfad die
 * einzige Art, eine Datei in seinen Downloads zu benennen — die pauschale Ablehnung musste
 * also fallen.
 *
 * Damit fällt eine ganze Klasse von Ablehnungen weg, und die eigentliche Prüfung muss sie
 * auffangen: Liegt der **aufgelöste** Pfad unter einem erlaubten Ort? Diese Tests prüfen
 * beide Richtungen, denn ein Zugriffsschutz, der alles ablehnt, ist genauso falsch wie einer,
 * der alles durchlässt — nur fällt der erste sofort auf und der zweite nie.
 */
class FreigegebeneOrteTest {

    private fun verzeichnis(name: String): File =
        File.createTempFile("neon-$name", "").apply {
            delete(); mkdirs(); deleteOnExit()
        }.canonicalFile

    @Test
    fun `ohne Freigabe bleibt es beim Projekt`() {
        val ws = Workspace(verzeichnis("projekt"))
        val fremd = verzeichnis("downloads")
        File(fremd, "brief.txt").writeText("hallo")

        assertEquals(listOf(ws.wurzel), ws.erlaubteWurzeln())
        assertNull(
            ws.datei(File(fremd, "brief.txt").absolutePath),
            "ohne Freigabe darf kein fremder Pfad durchkommen",
        )
    }

    @Test
    fun `ein freigegebener Ort ist erreichbar, mit absolutem Pfad`() {
        val downloads = verzeichnis("downloads")
        val ws = Workspace(verzeichnis("projekt"), weitereWurzeln = { listOf(downloads) })
        File(downloads, "brief.txt").writeText("hallo")

        val ziel = ws.datei(File(downloads, "brief.txt").absolutePath)
        assertNotNull(ziel, "der freigegebene Ort war nicht erreichbar")
        assertEquals("hallo", ws.lies(ziel.absolutePath))
    }

    @Test
    fun `neben einem freigegebenen Ort ist weiterhin Schluss`() {
        val downloads = verzeichnis("downloads")
        val ws = Workspace(verzeichnis("projekt"), weitereWurzeln = { listOf(downloads) })

        // Der Nachbar heißt fast gleich. Ein Vergleich auf Zeichenketten-Präfix würde ihn
        // durchlassen — `…/downloads-geheim` beginnt mit `…/downloads`. Deshalb wird über
        // die Elternkette verglichen und nicht über den Namen.
        val nachbar = File(downloads.parentFile, downloads.name + "-geheim")
            .apply { mkdirs(); deleteOnExit() }
        File(nachbar, "passwoerter.txt").writeText("geheim")

        assertNull(
            ws.datei(File(nachbar, "passwoerter.txt").absolutePath),
            "ein Nachbarverzeichnis mit ähnlichem Namen wurde durchgelassen",
        )
    }

    @Test
    fun `auch mit Freigabe fuehrt kein Umweg irgendwohin`() {
        val downloads = verzeichnis("downloads")
        val ws = Workspace(verzeichnis("projekt"), weitereWurzeln = { listOf(downloads) })

        listOf(
            "../draussen.txt",
            "src/../../draussen.txt",
            "/etc/passwd",
            "/data/data/de.neon.app/databases/neon.db",
            File(downloads, "../../etc/passwd").absolutePath,
        ).forEach { pfad ->
            assertNull(ws.datei(pfad), "„$pfad\" wurde durchgelassen")
            assertNull(ws.schreib(pfad, "x"), "„$pfad\" wurde geschrieben")
        }
    }

    @Test
    fun `eine entzogene Freigabe wirkt sofort`() {
        // Die Freigabe wird in den Systemeinstellungen erteilt und dort auch entzogen. Eine
        // beim Bauen eingefrorene Liste hieße: Wer sie zurücknimmt, wird trotzdem weiter
        // gelesen — bis zum nächsten Neustart der App.
        val downloads = verzeichnis("downloads")
        File(downloads, "brief.txt").writeText("hallo")
        var freigegeben = true
        val ws = Workspace(
            verzeichnis("projekt"),
            weitereWurzeln = { if (freigegeben) listOf(downloads) else emptyList() },
        )

        assertNotNull(ws.datei(File(downloads, "brief.txt").absolutePath))
        freigegeben = false
        assertNull(ws.datei(File(downloads, "brief.txt").absolutePath))
    }

    @Test
    fun `ein Ort, den es nicht gibt, erlaubt nichts`() {
        val fehlt = File(verzeichnis("weg"), "niemals")
        val ws = Workspace(verzeichnis("projekt"), weitereWurzeln = { listOf(fehlt) })

        assertEquals(listOf(ws.wurzel), ws.erlaubteWurzeln())
    }

    @Test
    fun `ein Ordner laesst sich ansehen, ohne alles aufzuzaehlen`() {
        val downloads = verzeichnis("downloads")
        File(downloads, "b.txt").writeText("zwei")
        File(downloads, "a.txt").writeText("eins")
        File(downloads, "unterordner").mkdirs()
        val ws = Workspace(verzeichnis("projekt"), weitereWurzeln = { listOf(downloads) })

        val eintraege = ws.ordner(downloads.absolutePath)
        assertNotNull(eintraege)
        // Verzeichnisse zuerst und mit Schrägstrich: Das Modell soll sehen, wo es
        // weitersuchen kann, ohne es zu raten.
        assertEquals("unterordner/", eintraege.first())
        assertTrue(eintraege.any { it.startsWith("a.txt") }, eintraege.toString())
        assertTrue(eintraege.any { it.startsWith("b.txt") }, eintraege.toString())
    }

    @Test
    fun `ein Ordner ausserhalb der erlaubten Orte bleibt verschlossen`() {
        val ws = Workspace(verzeichnis("projekt"))
        assertNull(ws.ordner(verzeichnis("fremd").absolutePath))
    }

    @Test
    fun `die Fehlermeldung nennt die erlaubten Orte`() {
        // Ohne diese Angabe rät das Modell weiter, und jeder Fehlversuch kostet auf diesem
        // Gerät eine halbe Minute.
        val downloads = verzeichnis("downloads")
        val tools = WorkspaceTools(
            Workspace(verzeichnis("projekt"), weitereWurzeln = { listOf(downloads) })
        )

        val ergebnis = tools.lies("/etc/passwd")
        assertFalse(ergebnis.gelungen)
        assertTrue(
            downloads.absolutePath in ergebnis.gesprochen,
            "die Meldung nennt die erlaubten Orte nicht: ${ergebnis.gesprochen}",
        )
    }

    @Test
    fun `ohne Freigabe sagt die Fehlermeldung genau das`() {
        val tools = WorkspaceTools(Workspace(verzeichnis("projekt")))

        val ergebnis = tools.lies("/storage/emulated/0/Download/brief.txt")
        assertFalse(ergebnis.gelungen)
        assertTrue(
            "Freigabe" in ergebnis.gesprochen,
            "die Meldung erklärt nicht, dass die Freigabe fehlt: ${ergebnis.gesprochen}",
        )
    }
}
