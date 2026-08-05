package de.neon.tools

import de.neon.workspace.AndroidBuild
import de.neon.workspace.AndroidProjectTemplate
import de.neon.workspace.Projektbereich
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
        /**
         * Der Projektbereich. `null` nur in Tests, die sich für Projekte nicht interessieren
         * — dann gibt es die Projektwerkzeuge nicht und `workspace` ist der einzige Ort.
         */
        bereich: Projektbereich? = null,
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

        // **Nur, was gerade gelingen kann.** Jedes angebotene Werkzeug steht zweimal im
        // Prompt — als Beschreibung und in der Grammatik — und kostet damit Zeit vor dem
        // ersten Wort. Auf dem Gerät waren das bei 1057 Prompt-Token 16,2 Sekunden.
        //
        // Die Auswahl rät dabei nicht am Wortlaut der Frage herum. Sie sieht nach, was da
        // ist: In einem leeren Projekt ohne Freigabe kann `datei-lesen` nichts lesen und
        // `datei-aendern` nichts ändern — beide könnten nur scheitern. Ein Werkzeug
        // wegzulassen, das ohnehin nur eine Fehlermeldung liefert, nimmt niemandem etwas
        // und spart dem Modell die Versuchung: Ein 4-B-Modell, dem man `datei-aendern`
        // anbietet, benutzt es irgendwann, auch wenn es keine Datei gibt.
        val gibtDateien = workspace.dateien().isNotEmpty()
        val gibtOrte = workspace.erlaubteWurzeln().size > 1

        // **Die Rückfrage steht vorn, und das ist eine Umkehrung.**
        //
        // Sie stand am Ende, mit der Begründung, erst kämen die Handlungen und dann der
        // Ausweg. Das war falsch herum gedacht. Ein Modell wählt, was zuerst dasteht; am
        // Ende der Liste wurde die Rückfrage nie gewählt, und Neon riet stattdessen — auf
        // „mach mir eine QR-App" legte es ein Android-Projekt an, ohne zu fragen, ob nicht
        // ein Python-Skript gemeint war.
        //
        // Und das Fragen kommt vor dem Tun, nicht danach. Bei zwölf Token je Sekunde kostet
        // eine Rückfrage zehn Sekunden; ein falsch geratener Auftrag kostet Minuten und
        // muss danach noch einmal gemacht werden.
        add(Rueckfrage())

        add(DateiSchreiben(tools))
        if (gibtDateien) {
            // Beide setzen voraus, dass es etwas zu bewegen gibt. In einem leeren Projekt
            // könnten sie nur scheitern — und ein Modell, dem `datei-loeschen` angeboten
            // wird, benutzt es irgendwann.
            add(DateiVerschieben(tools))
            add(DateiLoeschen(tools))
        }
        if (gibtDateien || gibtOrte) {
            add(DateiLesen(tools))
            add(OrdnerAnsehen(tools))
        }
        if (gibtDateien) {
            add(DateiAendern(tools))
            add(DateienAuflisten(tools))
        }
        // **Die App-Werkzeuge vor Python, und das ist kein Schönheitsfrage.** Auf dem Gerät
        // rief das Modell auf „mach mir eine QR-App" das Werkzeug `python` auf — und schob
        // ihm Kotlin-Quelltext mit `import android.app.Activity` unter. Beides stand im
        // Prompt; `python` stand weiter oben, und seine Beschreibung endete auf „alles, was
        // man ausprobieren muss".
        //
        // Ein Modell wählt, was zuerst dasteht und am weitesten klingt. Also steht das
        // Werkzeug für Apps jetzt vor dem für Rechnungen, und Python sagt ausdrücklich, dass
        // es nur Python nimmt.
        if (build != null) {
            if (bereich != null) add(AppAnlegenImProjekt(bereich)) else add(AppAnlegen(workspace))
            // Bauen setzt ein Manifest voraus. Ohne eines antwortete dieses Werkzeug bisher
            // „Es gibt noch kein Android-Projekt" — eine halbe Minute Erzeugungszeit für
            // eine Auskunft, die schon vor dem Aufruf feststand.
            if (paketnameAus(workspace) != null) {
                add(AppBauen(workspace, build) { paketnameAus(workspace) })
            }
        }

        if (bereich != null) {
            val projekte = bereich.projekte()
            add(ProjektAnlegen(bereich))
            // Löschen gibt es, sobald es etwas zu löschen gibt; Auflisten und Wechseln erst
            // ab zwei. Siehe ProjektWerkzeuge.alle — dort steht, warum die Grenze für die
            // drei Werkzeuge nicht dieselbe ist.
            if (projekte.isNotEmpty()) {
                addAll(ProjektWerkzeuge.alle(bereich, mehrere = projekte.size > 1))
            }
        }

        if (python != null) add(PythonAusfuehren(workspace, python))
        // Das Ende der Kette zuletzt — dort gehört es hin.
        add(Fertig())
    }

    /**
     * Wie viele Werkzeugrunden ein Auftrag im Projekt haben darf.
     *
     * **Warum mehr als eine.** Eine Runde reicht für „trag mir einen Termin ein" und nicht
     * für eine Entwicklungsumgebung: „leg das Projekt an und bau es" sind zwei Handlungen,
     * „lies die Datei, ändere sie, prüf das Ergebnis" sind drei. Mit einer Runde brach Neon
     * nach der ersten ab und ließ den Rest des Satzes stillschweigend fallen.
     *
     * **Warum nicht mehr als vier.** Jede Runde ist eine Erzeugung von rund vierzig Token
     * plus die Arbeit des Werkzeugs. Ein Bauvorgang dauert für sich schon eine Minute; vier
     * Runden sind auf diesem Gerät bereits mehrere Minuten. Was darüber hinausginge, wäre
     * keine Kette mehr, sondern ein Modell, das sich verlaufen hat — und das merkt man
     * besser früh.
     *
     * Die Zahl gilt ausdrücklich **nur hier**. Die Gerätewerkzeuge bleiben bei einer Runde:
     * Dort werden am Ende echte Geräte geschaltet, und ein Modell, das aus eigenem Antrieb
     * nachlegt, ist dabei etwas anderes als eines, das eine zweite Datei schreibt.
     */
    const val RUNDEN = 4

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
            ToolParameter(
                "inhalt", ParameterType.STRING, "Der vollständige neue Inhalt",
                langerInhalt = true,
            ),
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
            ToolParameter(
                "alt", ParameterType.STRING, "Der zu ersetzende Text, wörtlich",
                langerInhalt = true,
            ),
            ToolParameter(
                "neu", ParameterType.STRING, "Was stattdessen dort stehen soll",
                langerInhalt = true,
            ),
        ),
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult = tools.aendere(
        arguments["pfad"].orEmpty(),
        arguments["alt"].orEmpty(),
        arguments["neu"].orEmpty(),
    ).alsToolResult()
}

private class DateiVerschieben(private val tools: WorkspaceTools) : Tool {
    override val spec = ToolSpec(
        name = "datei-verschieben",
        description = "Verschiebt eine Datei oder benennt sie um. Überschreibt nichts.",
        parameters = listOf(
            ToolParameter("von", ParameterType.STRING, "Bisheriger Pfad"),
            ToolParameter("nach", ParameterType.STRING, "Neuer Pfad"),
        ),
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult =
        tools.verschiebe(arguments["von"].orEmpty(), arguments["nach"].orEmpty())
            .alsToolResult()
}

/**
 * Legt eine Datei in den Papierkorb.
 *
 * Die Beschreibung sagt ausdrücklich, dass nichts vernichtet wird. Das ist kein Trost für den
 * Nutzer, sondern eine Angabe für das Modell: Ein Werkzeug, das als endgültig beschrieben ist,
 * wird zögerlicher benutzt als eines, dessen Wirkung sich zurücknehmen lässt — und Zögern an
 * der falschen Stelle heißt, dass Neon Müll liegen lässt.
 */
private class DateiLoeschen(private val tools: WorkspaceTools) : Tool {
    override val spec = ToolSpec(
        name = "datei-loeschen",
        description = "Legt eine Datei oder einen Ordner in den Papierkorb. Nichts wird " +
            "vernichtet — es lässt sich zurückholen.",
        parameters = listOf(
            ToolParameter("pfad", ParameterType.STRING, "Pfad im Projekt"),
        ),
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult =
        tools.loesche(arguments["pfad"].orEmpty()).alsToolResult()
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
 * Sieht in einem Verzeichnis nach — auch außerhalb des Projekts.
 *
 * Getrennt von `dateien-auflisten`, weil die beiden verschiedene Fragen beantworten: Das
 * Projekt lässt sich vollständig aufzählen, der Gerätespeicher nicht. Ein rekursiver Lauf
 * über `/storage/emulated/0` lieferte zehntausend Einträge, von denen keiner die Frage
 * beantwortet, und verbrauchte dabei das ganze Kontextfenster.
 */
private class OrdnerAnsehen(private val tools: WorkspaceTools) : Tool {
    override val spec = ToolSpec(
        name = "ordner-ansehen",
        description = "Zeigt, was in einem Verzeichnis liegt — im Projekt oder, wenn " +
            "freigegeben, im Gerätespeicher. Nicht rekursiv.",
        parameters = listOf(
            ToolParameter(
                "pfad",
                ParameterType.STRING,
                "Verzeichnis, etwa src oder /storage/emulated/0/Download",
            ),
        ),
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult =
        tools.ordner(arguments["pfad"].orEmpty()).alsToolResult()
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
        // **Eng gefasst, nach einem Fehlgriff auf dem Gerät.** Hier stand „und alles, was
        // man ausprobieren muss" — und das Modell hat darunter auch eine Android-Activity in
        // Kotlin verstanden. Was ein Werkzeug *nicht* nimmt, muss dastehen; ein Modell liest
        // eine weite Formulierung als Einladung.
        description = "Führt Python aus und gibt zurück, was es ausgibt. Nur Python — für " +
            "Rechnungen und Datenauswertung. Nicht für Kotlin, Java oder Android-Code.",
        parameters = listOf(
            ToolParameter(
                "quelltext", ParameterType.STRING, "Python-Code, sonst nichts",
                langerInhalt = true,
            ),
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
        description = "Legt ein neues Android-Projekt an: Manifest, Ressourcen und ein " +
            "Gerüst als Start-Activity. Das Gerüst ist noch nicht die gewünschte App.",
        parameters = listOf(
            // Siehe AppAnlegenImProjekt: Ein ausgeschriebenes Beispiel wird von einem
            // kleinen Modell nicht als Erläuterung gelesen, sondern als Vorschlag.
            ToolParameter(
                "paketname", ParameterType.STRING,
                "de.neon. gefolgt vom Thema der App in Kleinbuchstaben, ohne Umlaute",
            ),
            ToolParameter(
                "name", ParameterType.STRING,
                "Wie der Nutzer die App genannt hat, wörtlich aus seiner Anfrage",
            ),
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
                "\n\nDas ist erst das Gerüst. Schreib jetzt " +
                "src/${paket.replace('.', '/')}/MainActivity.kt neu, sodass die App das tut, " +
                "worum gebeten wurde. Danach bauen."
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
