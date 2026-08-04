package de.neon.tools

import de.neon.workspace.AndroidProjectTemplate
import de.neon.workspace.Projektbereich

/**
 * Die Werkzeuge, mit denen Neon zwischen Projekten wechselt und aufräumt.
 *
 * **Warum es sie geben muss.** Bis hierher war `files/projekt/` das Projekt: ein flacher
 * Ordner mit einem Manifest darin. Ein zweites `app-anlegen` schrieb sein Manifest über das
 * erste, die Quelldateien der alten App blieben als Waisen daneben liegen, und weder Löschen
 * noch Verschieben gab es. Wer zwei Dinge ausprobierte, hatte danach ein Durcheinander, das
 * sich nicht mehr auflösen ließ.
 *
 * **Löschen heißt hier nie vernichten.** Die Namen kommen aus einem Sprachmodell — aus
 * derselben Quelle wie die Pfade, gegen die der Arbeitsbereich sich absichert. Ein Modell, das
 * das falsche Projekt löscht, tut das nicht aus Bosheit, sondern weil es sich verlesen hat.
 * Alles wandert deshalb in einen Papierkorb neben dem Projektbereich; der Fehlgriff ist dann
 * lästig statt endgültig.
 */
object ProjektWerkzeuge {

    /**
     * @param mehrere ob es mehr als ein Projekt gibt. Auflisten und Wechseln lohnen sich erst
     *   dann — bei einem einzigen Projekt sind es zwei Zeilen Prompt für eine Auskunft, die
     *   ohnehin schon im Prompt steht. **Löschen nicht:** Auch ein einziges Projekt darf man
     *   wegräumen, und genau das ging bisher nicht.
     */
    fun alle(bereich: Projektbereich, mehrere: Boolean): List<Tool> = buildList {
        if (mehrere) {
            add(ProjekteAuflisten(bereich))
            add(ProjektWechseln(bereich))
        }
        add(ProjektLoeschen(bereich))
    }
}

private class ProjekteAuflisten(private val bereich: Projektbereich) : Tool {
    override val spec = ToolSpec(
        name = NAME,
        description = "Nennt alle Projekte und sagt, in welchem gerade gearbeitet wird.",
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val projekte = bereich.projekte()
        if (projekte.isEmpty()) return ToolResult.Ok("Es gibt noch kein Projekt.")

        val aktiv = bereich.aktiv()?.name
        return ToolResult.Ok(
            projekte.joinToString("\n") { projekt ->
                buildString {
                    append(projekt.name)
                    if (projekt.name == aktiv) append("  (aktiv)")
                    append(" — ").append(projekt.dateizahl()).append(" Dateien")
                    projekt.paketname()?.let { append(", Android-App ").append(it) }
                }
            }
        )
    }

    companion object { const val NAME = "projekte-auflisten" }
}

private class ProjektWechseln(private val bereich: Projektbereich) : Tool {
    override val spec = ToolSpec(
        name = "projekt-wechseln",
        description = "Wechselt in ein anderes Projekt. Alle Dateipfade beziehen sich danach " +
            "auf dieses Projekt.",
        parameters = listOf(
            ToolParameter("name", ParameterType.STRING, "Name des Projekts"),
        ),
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val name = arguments["name"].orEmpty().trim()
        val projekt = bereich.waehle(name)
            ?: return ToolResult.Failed(
                // Mit der Liste: Ohne sie rät das Modell den nächsten Namen, und jeder
                // Fehlversuch kostet auf diesem Gerät eine halbe Minute.
                "Ein Projekt namens „$name\" gibt es nicht. Vorhanden sind: " +
                    bereich.projekte().joinToString(", ") { it.name }.ifBlank { "keines" },
                "unbekanntes Projekt: $name",
            )

        return ToolResult.Ok("Ich arbeite jetzt im Projekt ${projekt.name}.")
    }
}

/**
 * Legt ein Projekt in den Papierkorb.
 *
 * **Der Name muss wörtlich stimmen.** Kein Erraten, keine Ähnlichkeitssuche, kein „meintest du
 * vielleicht". Bei einem Werkzeug, das Arbeit wegräumt, ist Nachsicht gegenüber Tippfehlern
 * genau die falsche Freundlichkeit — sie räumt dann das falsche weg.
 */
private class ProjektLoeschen(private val bereich: Projektbereich) : Tool {
    override val spec = ToolSpec(
        name = "projekt-loeschen",
        description = "Legt ein ganzes Projekt in den Papierkorb. Der Name muss genau " +
            "stimmen. Nichts wird vernichtet — es lässt sich zurückholen.",
        parameters = listOf(
            ToolParameter("name", ParameterType.STRING, "Name des Projekts, genau so"),
        ),
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val name = arguments["name"].orEmpty().trim()
        val vorhanden = bereich.projekte().map { it.name }
        if (name !in vorhanden) {
            return ToolResult.Failed(
                "Ein Projekt namens „$name\" gibt es nicht. Vorhanden sind: " +
                    vorhanden.joinToString(", ").ifBlank { "keines" },
                "unbekanntes Projekt: $name",
            )
        }

        bereich.inDenPapierkorb(name)
            ?: return ToolResult.Failed(
                "Das Projekt ließ sich nicht wegräumen.",
                "Verschieben in den Papierkorb scheiterte",
            )

        val jetztAktiv = bereich.aktiv()?.name
        return ToolResult.Ok(
            buildString {
                append("Projekt $name liegt jetzt im Papierkorb.")
                if (jetztAktiv != null) append(" Ich arbeite jetzt in $jetztAktiv.")
            }
        )
    }
}

/**
 * Legt ein Projekt an — ohne Android-Gerüst.
 *
 * Getrennt von `app-anlegen`, weil nicht jedes Projekt eine App ist: Ein Ordner für
 * Python-Skripte braucht kein Manifest, keine Ressourcen und keine Activity. Sie in einen Topf
 * zu werfen hieße, für „leg mir einen Ordner für meine Skripte an" ein halbes Android-Projekt
 * zu erzeugen, das danach jeden Bauversuch mitschleppt.
 */
internal class ProjektAnlegen(private val bereich: Projektbereich) : Tool {
    override val spec = ToolSpec(
        name = "projekt-anlegen",
        description = "Legt ein neues, leeres Projekt an und wechselt hinein. Für alles, was " +
            "keine Android-App ist — Skripte, Notizen, Versuche. Für eine App nimm " +
            "app-anlegen.",
        parameters = listOf(
            ToolParameter("name", ParameterType.STRING, "Kurzer Name, etwa auswertung"),
        ),
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val wunsch = arguments["name"].orEmpty().trim()
        val projekt = bereich.anlegen(wunsch)
            ?: return ToolResult.Failed(
                "Aus „$wunsch\" lässt sich kein Projektname machen. Nimm Buchstaben und " +
                    "Ziffern, etwa auswertung oder mein-versuch.",
                "kein gültiger Ordnername: $wunsch",
            )

        return ToolResult.Ok("Projekt ${projekt.name} angelegt, ich arbeite jetzt darin.")
    }
}

/**
 * Legt ein Android-Projekt an — in einem **eigenen** Ordner.
 *
 * **Hier lag der Fehler, über den der Nutzer gestolpert ist.** Die Vorlage schrieb ihre vier
 * Dateien direkt in den Arbeitsbereich, und der war der ganze Projektbereich. Damit gab es
 * genau eine App; ein zweiter Aufruf überschrieb das Manifest der ersten und ließ deren
 * Quelltext verwaist zurück.
 *
 * Jetzt entsteht zuerst der Projektordner, und die Vorlage schreibt in dessen Arbeitsbereich.
 * Der Ordnername kommt aus dem App-Namen — „Zähler" wird zu `zaehler` —, denn unter diesem
 * Namen kennt der Nutzer sein Projekt, nicht unter `de.neon.zaehler`.
 */
internal class AppAnlegenImProjekt(private val bereich: Projektbereich) : Tool {
    override val spec = ToolSpec(
        name = "app-anlegen",
        description = "Legt ein neues Android-Projekt in einem eigenen Ordner an: Manifest, " +
            "Ressourcen und eine Start-Activity. Wechselt anschließend hinein.",
        parameters = listOf(
            ToolParameter("paketname", ParameterType.STRING, "etwa de.neon.zaehler"),
            ToolParameter("name", ParameterType.STRING, "Was unter dem Symbol steht, etwa Zähler"),
        ),
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val paket = arguments["paketname"].orEmpty().trim()
        val appName = arguments["name"].orEmpty().trim().ifBlank { "Meine App" }

        val vorgabe = runCatching { AndroidProjectTemplate.Vorgabe(paket, appName) }
            .getOrElse { fehler ->
                // Die Meldung der Prüfung sagt schon, was erwartet wird. Sie weiterzureichen
                // ist besser, als sie durch ein allgemeines „ging nicht" zu ersetzen — genau
                // daraus hat das Modell auf dem Gerät seinen Paketnamen berichtigt.
                return ToolResult.Failed(
                    fehler.message ?: "Der Paketname passt nicht.",
                    "ungültiger Paketname: $paket",
                )
            }

        // Der Ordner heißt wie die App, nicht wie das Paket. Fällt dabei nichts Brauchbares
        // ab, hilft der letzte Teil des Paketnamens.
        val projekt = bereich.anlegen(appName)
            ?: bereich.anlegen(paket.substringAfterLast('.'))
            ?: return ToolResult.Failed(
                "Aus „$appName\" lässt sich kein Projektname machen.",
                "kein gültiger Ordnername: $appName",
            )

        val angelegt = AndroidProjectTemplate.anlegen(bereich.arbeitsbereich(projekt), vorgabe)
        return ToolResult.Ok(
            "Projekt ${projekt.name} angelegt ($paket):\n" + angelegt.joinToString("\n") +
                "\n\nMit „bau die App\" wird daraus eine installierbare APK."
        )
    }
}
