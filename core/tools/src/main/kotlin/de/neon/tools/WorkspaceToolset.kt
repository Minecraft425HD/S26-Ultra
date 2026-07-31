package de.neon.tools

import de.neon.workspace.AndroidBuild
import de.neon.workspace.AndroidProjectTemplate
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
        /**
         * `null`, wenn die Bau-Werkzeuge noch nicht ausgepackt sind. Dann fehlen die beiden
         * App-Werkzeuge in der Grammatik — aus demselben Grund wie beim Python-Werkzeug: Ein
         * Modell, dem ein Werkzeug angeboten wird, benutzt es.
         */
        build: AndroidBuild? = null,
    ): List<Tool> = buildList {
        val tools = WorkspaceTools(workspace)
        add(DateiLesen(tools))
        add(DateiSchreiben(tools))
        add(DateiAendern(tools))
        add(DateienAuflisten(tools))
        if (python != null) add(PythonAusfuehren(workspace, python))
        if (build != null) {
            add(AppAnlegen(workspace))
            add(AppBauen(workspace, build) { paketnameAus(workspace) })
        }
    }

    /**
     * Der Paketname des Projekts, aus dem Manifest gelesen.
     *
     * Nicht gemerkt, sondern jedes Mal nachgesehen: Wer das Manifest von Hand ändert, hat
     * danach recht, und ein gemerkter Wert wäre eine zweite Wahrheit.
     */
    private fun paketnameAus(workspace: Workspace): String? =
        workspace.lies("AndroidManifest.xml")
            ?.let { Regex("""package="([^"]+)"""").find(it)?.groupValues?.get(1) }
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

/**
 * Legt ein neues Android-Projekt an.
 *
 * **Warum eine Vorlage und nicht „das Modell schreibt alles".** Ein Android-Projekt hat vier
 * Dateien, die exakt stimmen müssen, bevor überhaupt etwas übersetzt wird. Ein 4-B-Modell
 * schreibt davon drei richtig und eine falsch, und der Fehler zeigt sich als aapt2-Meldung.
 * Bei 15 Token je Sekunde kostet jeder Anlauf Minuten.
 */
internal class AppAnlegen(private val workspace: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "app-anlegen",
        description = "Legt ein neues Android-Projekt an: Manifest, Ressourcen und eine " +
            "Start-Activity mit einer Oberfläche aus Code.",
        parameters = listOf(
            ToolParameter("paketname", ParameterType.STRING, "etwa de.neon.meineapp"),
            ToolParameter("name", ParameterType.STRING, "Was unter dem Symbol steht"),
        ),
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val paket = arguments["paketname"].orEmpty().trim()
        val name = arguments["name"].orEmpty().trim().ifBlank { "Meine App" }

        val vorgabe = runCatching { AndroidProjectTemplate.Vorgabe(paket, name) }
            .getOrElse { fehler ->
                // Die Meldung der Prüfung sagt schon, was erwartet wird. Sie weiterzureichen
                // ist besser, als sie durch ein allgemeines „ging nicht" zu ersetzen.
                return ToolResult.Failed(
                    fehler.message ?: "Der Paketname passt nicht.",
                    "ungültiger Paketname: $paket",
                )
            }

        val angelegt = AndroidProjectTemplate.anlegen(workspace, vorgabe)
        return ToolResult.Ok(
            "Projekt $paket angelegt:\n" + angelegt.joinToString("\n") +
                "\n\nMit „bau die App\" wird daraus eine installierbare APK."
        )
    }
}

/**
 * Baut das Projekt zu einer installierbaren APK.
 *
 * Der Bauvorgang dauert auf dem Telefon eine Minute oder länger — der Kotlin-Compiler ist der
 * langsamste Teil. Deshalb meldet das Werkzeug am Ende die gemessene Dauer: Eine Zahl macht
 * aus „das hat gedauert" eine Auskunft.
 */
internal class AppBauen(
    private val workspace: Workspace,
    private val build: AndroidBuild,
    private val paketname: () -> String?,
) : Tool {
    override val spec = ToolSpec(
        name = "app-bauen",
        description = "Baut das Android-Projekt zu einer installierbaren APK.",
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val paket = paketname()
            ?: return ToolResult.Failed(
                "Es gibt noch kein Android-Projekt. Leg zuerst eines an.",
                "kein AndroidManifest.xml im Arbeitsbereich",
            )

        val ergebnis = build.baue(workspace, paket)
        return if (ergebnis.gelungen) {
            ToolResult.Ok(ergebnis.bericht)
        } else {
            // Auch hier geht die Meldung des Werkzeugs als Text zurück und nicht als Fehler:
            // „error: unresolved reference: Buton" in Zeile 12 ist genau das, woraus das
            // Modell die Berichtigung ableiten kann.
            ToolResult.Failed(
                "Beim Schritt „${ergebnis.schritt}\" ging es schief:\n${ergebnis.bericht}",
                "${ergebnis.schritt} scheiterte",
            )
        }
    }
}
