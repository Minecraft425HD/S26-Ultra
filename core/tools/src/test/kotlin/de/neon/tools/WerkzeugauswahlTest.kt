package de.neon.tools

import de.neon.workspace.AndroidBuild
import de.neon.workspace.BuildTools
import de.neon.workspace.CommandResult
import de.neon.workspace.CommandRunner
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
        WorkspaceToolset.alle(workspace, build = if (mitBauKette) bauKette() else null)
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

    @Test
    fun `die Beschreibung nennt jedes Werkzeug in genau einer Zeile`() {
        val ws = leeresProjekt()
        ws.schreib("src/Main.kt", "fun main() {}")
        val registry = ToolRegistry(WorkspaceToolset.alle(ws))

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
}
