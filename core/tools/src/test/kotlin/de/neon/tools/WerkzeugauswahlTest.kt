package de.neon.tools

import de.neon.workspace.AndroidBuild
import de.neon.workspace.BuildTools
import de.neon.workspace.CommandResult
import de.neon.workspace.CommandRunner
import de.neon.workspace.Projektbereich
import de.neon.workspace.Workspace
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Nur die Werkzeuge, die gerade etwas ausrichten können.
 *
 * **Warum das eine Effizienzfrage ist.** Jedes angebotene Werkzeug steht zweimal im Prompt —
 * als Zeile in der Beschreibung und als Regeln in der Grammatik. Auf dem Gerät kostete das
 * Verarbeiten von 1057 Prompt-Token auf dem 4-B-Modell **16,2 Sekunden**, bevor das erste
 * Wort kam. Die Werkzeugliste war davon rund die Hälfte.
 *
 * **Und warum es nicht geraten wird.** Die naheliegende Abkürzung wäre, im Wortlaut der Frage
 * nach „Python" oder „App" zu suchen. Das geht schief, sobald jemand „mach das fertig" sagt:
 * Dann fehlt das Werkzeug, das er meint, und Neon tut etwas anderes. Stattdessen entscheidet
 * der Zustand — was liegt da, was ist freigegeben, gibt es ein Manifest. Ein Werkzeug fällt
 * nur weg, wenn es in diesem Moment ausschließlich scheitern könnte.
 */
class WerkzeugauswahlTest {

    private fun leeresProjekt(): Workspace = Workspace(
        File.createTempFile("neon-auswahl", "").apply { delete(); mkdirs(); deleteOnExit() }
    )

    private fun namen(workspace: Workspace, mitBauKette: Boolean = false): Set<String> =
        WorkspaceToolset.alle(
            workspace = workspace,
            build = if (mitBauKette) bauKette() else null,
        )
            .map { it.spec.name }
            .toSet()

    /**
     * Eine Bau-Kette, die nie gestartet wird.
     *
     * Hier geht es allein um die Frage, **welche** Werkzeuge angeboten werden. Ob sie
     * funktionieren, prüft `AndroidBuildTest` in `core/workspace` gegen echte Befehlszeilen.
     */
    private fun bauKette(): AndroidBuild {
        val nirgendwo = File("/dev/null")
        return AndroidBuild(
            tools = BuildTools(
                aapt2 = nirgendwo, d8 = nirgendwo, kotlinc = nirgendwo, apksigner = nirgendwo,
                androidJar = nirgendwo, kotlinStdlib = nirgendwo, annotations = nirgendwo,
                keystore = nirgendwo,
            ),
            runner = object : CommandRunner {
                override fun run(
                    command: List<String>,
                    workingDir: File,
                    env: Map<String, String>,
                    timeoutMillis: Long,
                ): CommandResult = error("wird in diesem Test nie aufgerufen")
            },
            java = { _, _, _, _, _ -> error("wird in diesem Test nie aufgerufen") },
        )
    }

    /**
     * **Die Rückfrage steht vorn.**
     *
     * Sie stand am Ende, mit der Begründung, erst kämen die Handlungen und dann der Ausweg.
     * Das war falsch herum: Ein Modell wählt, was zuerst dasteht, und am Ende der Liste wurde
     * die Rückfrage nie gewählt. Auf „mach mir eine QR-App" legte Neon ungefragt ein
     * Android-Projekt an, obwohl ein Python-Skript genauso gemeint sein konnte.
     *
     * Das Fragen kommt vor dem Tun, also auch im Prompt.
     */
    @Test
    fun `die Rueckfrage ist das erste Werkzeug`() {
        val namen = WorkspaceToolset
            .alle(workspace = leeresProjekt(), build = bauKette())
            .map { it.spec.name }

        assertEquals("rueckfrage", namen.first(), namen.toString())
        // Und das Ende der Kette bleibt am Ende.
        assertEquals(Fertig.NAME, namen.last(), namen.toString())
    }

    /**
     * Und die Beschreibung nennt den Fall, an dem es gescheitert ist.
     *
     * „Wenn du unsicher bist" ist als Auslöser wertlos — ein Modell, dem man Unsicherheit
     * als Bedingung gibt, ist immer unsicher. Es braucht die konkrete Gabelung.
     */
    @Test
    fun `die Rueckfrage nennt Android gegen Python als Beispiel`() {
        val text = Rueckfrage().spec.description

        assertTrue("Android" in text, text)
        assertTrue("Python" in text, text)
    }

    @Test
    fun `im leeren Projekt gibt es nichts zu lesen und nichts zu aendern`() {
        val namen = namen(leeresProjekt())

        assertTrue("datei-schreiben" in namen, "ohne Schreiben käme nie etwas zustande")
        assertTrue("rueckfrage" in namen, "die Rückfrage muss immer möglich sein")
        assertFalse("datei-lesen" in namen, "es gibt keine Datei zu lesen")
        assertFalse("datei-aendern" in namen, "es gibt keine Datei zu ändern")
        assertFalse("dateien-auflisten" in namen, "die Liste wäre leer")
    }

    @Test
    fun `sobald eine Datei da ist, kommen die Lesewerkzeuge dazu`() {
        val ws = leeresProjekt()
        ws.schreib("src/Main.kt", "fun main() {}")

        val namen = namen(ws)
        assertTrue("datei-lesen" in namen)
        assertTrue("datei-aendern" in namen)
        assertTrue("dateien-auflisten" in namen)
    }

    @Test
    fun `eine Speicherfreigabe macht das Lesen sinnvoll, auch ohne eigene Datei`() {
        val downloads = File.createTempFile("neon-dl", "")
            .apply { delete(); mkdirs(); deleteOnExit() }
        val ws = Workspace(
            File.createTempFile("neon-p", "").apply { delete(); mkdirs(); deleteOnExit() },
            weitereWurzeln = { listOf(downloads) },
        )

        val namen = namen(ws)
        assertTrue("datei-lesen" in namen, "in den Downloads gibt es etwas zu lesen")
        assertTrue("ordner-ansehen" in namen)
    }

    @Test
    fun `app-bauen wird erst angeboten, wenn es ein Projekt gibt`() {
        // Vorher antwortete dieses Werkzeug „Es gibt noch kein Android-Projekt" — eine halbe
        // Minute Erzeugungszeit für eine Auskunft, die schon vor dem Aufruf feststand.
        val ws = leeresProjekt()
        assertFalse("app-bauen" in namen(ws, mitBauKette = true))

        ws.schreib("AndroidManifest.xml", """<manifest package="de.neon.zaehler" />""")
        assertTrue("app-bauen" in namen(ws, mitBauKette = true))
    }

    /**
     * **Löschen darf nicht an der Zahl der Projekte hängen.**
     *
     * Genau das war die Klage: „Ich kann nur ein Projekt haben, das die Erstellung weiterer
     * verhindert, und ich kann auch nichts löschen." Ein Werkzeug, das erst ab zwei Projekten
     * erscheint, hilft demjenigen nicht, der mit einem falsch angelegten dasteht.
     */
    @Test
    fun `mit einem Projekt gibt es Loeschen, aber kein Wechseln`() {
        val bereich = Projektbereich(
            File.createTempFile("neon-bereich", "").apply { delete(); mkdirs(); deleteOnExit() }
        )
        bereich.anlegen("zaehler")

        val namen = WorkspaceToolset
            .alle(bereich, bereich.aktiverArbeitsbereich())
            .map { it.spec.name }
            .toSet()

        assertTrue("projekt-anlegen" in namen, "ein zweites Projekt muss möglich sein")
        assertTrue("projekt-loeschen" in namen)
        assertFalse("projekt-wechseln" in namen, "es gibt nichts, wohin gewechselt würde")
        assertFalse("projekte-auflisten" in namen, "die Liste steht schon im Prompt")
    }

    @Test
    fun `ab zwei Projekten kommen Auflisten und Wechseln dazu`() {
        val bereich = Projektbereich(
            File.createTempFile("neon-bereich", "").apply { delete(); mkdirs(); deleteOnExit() }
        )
        bereich.anlegen("zaehler")
        bereich.anlegen("notizen")

        val namen = WorkspaceToolset
            .alle(bereich, bereich.aktiverArbeitsbereich())
            .map { it.spec.name }
            .toSet()

        assertTrue("projekt-wechseln" in namen)
        assertTrue("projekte-auflisten" in namen)
    }

    /**
     * Ohne Projekt gibt es nichts zu wechseln und nichts wegzuräumen.
     *
     * Der Fall tritt praktisch nie ein — `aktiverArbeitsbereich` legt eines an, sobald jemand
     * eine Datei schreibt. Er steht hier, damit die Bedingung eine ist und nicht zwei: Die
     * Werkzeuge hängen an der Zahl der Projekte, nicht daran, ob ein Bereich da ist.
     */
    @Test
    fun `ohne Projekt gibt es nur das Anlegen`() {
        val bereich = Projektbereich(
            File.createTempFile("neon-bereich", "").apply { delete(); mkdirs(); deleteOnExit() }
        )

        val namen = WorkspaceToolset
            .alle(bereich, Workspace(bereich.wurzel))
            .map { it.spec.name }
            .toSet()

        assertTrue("projekt-anlegen" in namen)
        assertFalse("projekt-loeschen" in namen)
    }

    @Test
    fun `die Beschreibung nennt jedes Werkzeug in genau einer Zeile`() {
        val ws = leeresProjekt()
        ws.schreib("src/Main.kt", "fun main() {}")
        val registry = ToolRegistry(WorkspaceToolset.alle(workspace = ws))

        val zeilen = registry.promptDescription().trim().lines()
        assertEquals(
            registry.specs.size + 1,
            zeilen.size,
            "erwartet: eine Kopfzeile und je Werkzeug eine Zeile\n" + registry.promptDescription(),
        )
        // Und die Bedeutung der Parameter geht dabei nicht verloren.
        assertTrue(
            zeilen.any { "pfad" in it && "inhalt" in it },
            registry.promptDescription(),
        )
    }

    /**
     * **Das aktive Projekt gehört in den Prompt.**
     *
     * Alle Pfade beziehen sich darauf. Stand es nirgends, schrieb das Modell in ein Projekt,
     * das es nicht benennen konnte — und hatte keinen Anlass, `projekt-wechseln` zu wählen.
     * Ein Werkzeug, dessen Voraussetzung im Prompt fehlt, wird nicht benutzt.
     */
    @Test
    fun `die Kopfzeile steht vor der Werkzeugliste und ueberlebt das Aussortieren`() {
        val registry = ToolRegistry(
            WorkspaceToolset.alle(workspace = leeresProjekt()),
            kopfzeile = "Aktives Projekt: zaehler.",
        )

        assertTrue(registry.promptDescription().startsWith("Aktives Projekt: zaehler."))
        // `ohne` baut die Zusammenstellung neu. Ginge die Kopfzeile dabei verloren, wüsste
        // das Modell ausgerechnet in Runde 1 nicht, wo es steht.
        assertTrue(
            registry.ohne(Fertig.NAME).promptDescription().startsWith("Aktives Projekt:"),
        )
    }
}
