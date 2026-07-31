package de.neon.tools

import de.neon.workspace.CommandRunner
import de.neon.workspace.PythonRuntime
import de.neon.workspace.Workspace
import de.neon.workspace.WorkspaceTools

/**
 * Die Werkzeuge, mit denen Neon im Projekt arbeitet.
 *
 * **Warum eine eigene Zusammenstellung und nicht alles in einem Topf.** Die Grammatik, die
 * einen Werkzeugaufruf erzwingt, enthält **jedes** angebotene Werkzeug. Termin und Nachricht
 * neben Dateilesen und Python zu stellen hieße: Bei jeder Frage steht die ganze Liste im
 * Prompt, und ein 4-B-Modell wählt dann bei "was ist die Hauptstadt von Peru" gelegentlich
 * `datei_schreiben`. Deshalb zwei Zusammenstellungen, und der Gesprächsablauf nimmt die, die
 * zur Kategorie passt.
 *
 * Die Vorgänge selbst liegen in `core/workspace` und sind dort ohne Android geprüft. Hier
 * steht nur, wie sie heißen, was sie brauchen und was sie antworten.
 */
object WorkspaceToolset {

    /**
     * Alle Projektwerkzeuge.
     *
     * @param python `null`, wenn die Python-Umgebung nicht eingerichtet ist. Dann fehlt das
     *   Werkzeug in der Grammatik — besser, als eines anzubieten, das jedes Mal scheitert:
     *   Ein Modell, dem ein Werkzeug angeboten wird, benutzt es.
     */
    fun alle(
        workspace: Workspace,
        python: PythonRuntime? = null,
    ): List<Tool> = buildList {
        val tools = WorkspaceTools(workspace)
        add(DateiLesen(tools))
        add(DateiSchreiben(tools))
        add(DateiAendern(tools))
        add(DateienAuflisten(tools))
        if (python != null) add(PythonAusfuehren(workspace, python))
    }
}

private class DateiLesen(private val tools: WorkspaceTools) : Tool {
    override val spec = ToolSpec(
        name = "datei-lesen",
        description = "Liest eine Datei aus dem Projekt und gibt ihren Inhalt zurück.",
        parameters = listOf(
            ToolParameter("pfad", ParameterType.STRING, "Pfad im Projekt, etwa src/main.py"),
        ),
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult =
        tools.lies(arguments["pfad"].orEmpty()).alsToolResult()
}

private class DateiSchreiben(private val tools: WorkspaceTools) : Tool {
    override val spec = ToolSpec(
        name = "datei-schreiben",
        description = "Legt eine Datei an oder überschreibt sie vollständig.",
        parameters = listOf(
            ToolParameter("pfad", ParameterType.STRING, "Pfad im Projekt"),
            ToolParameter("inhalt", ParameterType.STRING, "Der vollständige neue Inhalt"),
        ),
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult =
        tools.schreib(arguments["pfad"].orEmpty(), arguments["inhalt"].orEmpty()).alsToolResult()
}

/**
 * Ändert eine Stelle, statt die Datei neu zu schreiben.
 *
 * Die Beschreibung sagt dem Modell ausdrücklich, dass der alte Text **wörtlich** stimmen
 * muss. Das ist die häufigste Ursache für einen Fehlschlag: Ein Modell zitiert aus dem
 * Gedächtnis statt aus der Datei, und bei 15 Token je Sekunde kostet jeder Fehlversuch eine
 * halbe Minute.
 */
private class DateiAendern(private val tools: WorkspaceTools) : Tool {
    override val spec = ToolSpec(
        name = "datei-aendern",
        description = "Ersetzt eine Stelle in einer Datei. Der alte Text muss wörtlich so " +
            "in der Datei stehen und darf nur einmal vorkommen — sonst passiert nichts.",
        parameters = listOf(
            ToolParameter("pfad", ParameterType.STRING, "Pfad im Projekt"),
            ToolParameter("alt", ParameterType.STRING, "Der zu ersetzende Text, wörtlich"),
            ToolParameter("neu", ParameterType.STRING, "Was stattdessen dort stehen soll"),
        ),
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult = tools.aendere(
        arguments["pfad"].orEmpty(),
        arguments["alt"].orEmpty(),
        arguments["neu"].orEmpty(),
    ).alsToolResult()
}

private class DateienAuflisten(private val tools: WorkspaceTools) : Tool {
    override val spec = ToolSpec(
        name = "dateien-auflisten",
        description = "Nennt alle Dateien im Projekt.",
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult =
        tools.dateien().alsToolResult()
}

/**
 * Führt Python aus.
 *
 * **Warum Quelltext und nicht nur ein Dateiname.** Der häufigste Fall ist „rechne mir das
 * aus" — dafür schreibt das Modell drei Zeilen, die einmal laufen und danach niemanden mehr
 * interessieren. Sie erst in eine Datei schreiben zu lassen wäre ein zweiter Werkzeugaufruf,
 * also eine weitere halbe Minute. Der Quelltext landet trotzdem in einer Datei, damit die
 * Fehlermeldung eine Zeilennummer hat.
 */
private class PythonAusfuehren(
    private val workspace: Workspace,
    private val python: PythonRuntime,
) : Tool {
    override val spec = ToolSpec(
        name = "python",
        description = "Führt Python-Quelltext aus und gibt zurück, was er ausgibt. " +
            "Für Rechnungen, Datenauswertung und alles, was man ausprobieren muss.",
        parameters = listOf(
            ToolParameter("quelltext", ParameterType.STRING, "Der auszuführende Python-Code"),
        ),
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val quelltext = arguments["quelltext"].orEmpty()
        if (quelltext.isBlank()) {
            return ToolResult.Failed("Dazu fehlt mir der Code.", "leerer Quelltext")
        }

        val ergebnis = python.fuehreAus(
            quelltext = quelltext,
            workspace = workspace,
            timeoutMillis = TIMEOUT_MILLIS,
        )

        // Auch ein Fehlschlag geht als Text zurück und nicht als Fehler: Die Fehlermeldung
        // von Python ist das Wertvollste, was dieses Werkzeug liefern kann — sie nennt
        // Zeilennummer und Ausnahmeart, und damit kann das Modell den Code berichtigen.
        return if (ergebnis.gelungen) {
            ToolResult.Ok(ergebnis.describe())
        } else {
            ToolResult.Failed(ergebnis.describe(), "Rückgabewert ${ergebnis.exitCode}")
        }
    }

    private companion object {
        /**
         * Dreißig Sekunden.
         *
         * Knapper als die Vorgabe von einer Minute: Was aus einem Gespräch heraus gestartet
         * wird, soll den Gesprächsfaden nicht reißen. Wer länger rechnen will, legt ein
         * Skript ab und startet es ausdrücklich.
         */
        const val TIMEOUT_MILLIS = 30_000L
    }
}

/** Übersetzt ein Ergebnis aus `core/workspace` in das, was der Werkzeugaufruf erwartet. */
private fun WorkspaceTools.Ergebnis.alsToolResult(): ToolResult =
    if (gelungen) ToolResult.Ok(gesprochen) else ToolResult.Failed(gesprochen, grund)
