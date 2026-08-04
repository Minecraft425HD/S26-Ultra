package de.neon.workspace

/**
 * Was Neon im Arbeitsbereich tun kann, als reine Vorgänge ohne Android.
 *
 * **Warum hier und nicht in `core/tools`.** Die Werkzeug-Schnittstelle dort hängt an Android
 * (`Context`, `Intent`), diese Vorgänge nicht. So lässt sich jeder Fall — auch der
 * fehlgeschlagene — ohne Gerät festhalten, und `core/tools` bindet sie nur noch ein. Dieselbe
 * Aufteilung wie zwischen `ConversationOrchestrator` und dem Android-Dienst.
 *
 * **Jeder Vorgang antwortet in einem Satz, den man vorlesen kann.** Das ist keine Kosmetik:
 * Ein Werkzeug, dessen Ergebnis eine Ausnahme ist, bringt das Gespräch zum Stehen. Und die
 * Anker-Fehlschläge müssen so formuliert sein, dass das **Modell** daraus den nächsten Versuch
 * ableiten kann — es liest diese Sätze.
 */
class WorkspaceTools(private val workspace: Workspace) {

    /** Ob etwas gelungen ist, und was Neon dazu sagt. */
    data class Ergebnis(
        val gelungen: Boolean,
        /** Der Satz für die Sprechblase und für das Modell. */
        val gesprochen: String,
        /** Für das Protokoll: was technisch passiert ist. */
        val grund: String = "",
    )

    /** Legt eine Datei an oder überschreibt sie. */
    fun schreib(pfad: String, inhalt: String): Ergebnis {
        val geschrieben = workspace.schreib(pfad, inhalt)
            ?: return draussen(pfad)

        val zeilen = inhalt.count { it == '\n' } + 1
        return Ergebnis(true, "$geschrieben geschrieben, $zeilen Zeilen.")
    }

    /** Liest eine Datei. Der Inhalt geht als Werkzeugergebnis zurück ins Gespräch. */
    fun lies(pfad: String): Ergebnis {
        if (workspace.datei(pfad) == null) return draussen(pfad)
        val inhalt = workspace.lies(pfad)
            ?: return Ergebnis(false, "Die Datei $pfad gibt es nicht.", "nicht vorhanden")

        return Ergebnis(true, inhalt, "${inhalt.length} Zeichen")
    }

    /**
     * Ändert eine Datei an einer verankerten Stelle.
     *
     * Die Fehlerfälle sind der wichtigere Teil. Sie sagen dem Modell, **was** schiefging und
     * damit, was es anders machen soll — ein bloßes „hat nicht geklappt" schickt es in
     * denselben Fehler zurück, und bei 15 Token je Sekunde kostet jeder Fehlversuch eine
     * halbe Minute.
     */
    fun aendere(pfad: String, alt: String, neu: String): Ergebnis {
        if (alt.isEmpty()) {
            return Ergebnis(
                false,
                "Ich brauche den Text, der ersetzt werden soll. Ohne ihn wüsste ich nicht, wo.",
                "leerer Anker",
            )
        }
        if (workspace.datei(pfad) == null) return draussen(pfad)

        return when (val ergebnis = workspace.aendere(pfad, alt, neu)) {
            null -> Ergebnis(false, "Die Datei $pfad gibt es nicht.", "nicht vorhanden")

            is AnchoredEdit.Result.Geaendert ->
                Ergebnis(true, "$pfad geändert, Zeile ${ergebnis.zeile}.")

            is AnchoredEdit.Result.NichtGefunden -> Ergebnis(
                false,
                buildString {
                    append("Diesen Text gibt es in $pfad nicht.")
                    ergebnis.aehnlichsteZeile?.let {
                        append(" Am nächsten kommt Zeile $it — sieh dort nach.")
                    }
                    append(" Lies die Datei und zitiere die Stelle wörtlich.")
                },
                "Anker nicht gefunden",
            )

            is AnchoredEdit.Result.Mehrdeutig -> Ergebnis(
                false,
                "Dieser Text steht in $pfad ${ergebnis.treffer.size} Mal, in den Zeilen " +
                    "${ergebnis.treffer.joinToString(", ")}. Nimm mehr Zeilen dazu, damit die " +
                    "Stelle eindeutig ist.",
                "Anker mehrdeutig: ${ergebnis.treffer}",
            )
        }
    }

    /**
     * Die Dateien des Projekts.
     *
     * Nach oben begrenzt, weil die Liste in den Prompt geht. Bei dreihundert Dateien wäre sie
     * länger als das, was das Modell danach noch lesen kann — und dann findet es keine.
     */
    fun dateien(grenze: Int = DATEI_GRENZE): Ergebnis {
        val alle = workspace.dateien()
        if (alle.isEmpty()) return Ergebnis(true, "Das Projekt ist noch leer.")

        val gezeigt = alle.take(grenze)
        return Ergebnis(
            true,
            buildString {
                append(gezeigt.joinToString("\n"))
                if (alle.size > gezeigt.size) {
                    append("\n… und ${alle.size - gezeigt.size} weitere")
                }
            },
            "${alle.size} Dateien",
        )
    }

    /**
     * Was in einem Verzeichnis liegt.
     *
     * Für den Gerätespeicher, den man nicht vollständig aufzählen kann: Wer eine Datei in den
     * Downloads sucht, nennt das Verzeichnis, und Neon sieht nach. Ohne das wäre eine
     * Freigabe wertlos — man müsste jeden Dateinamen schon kennen.
     */
    fun ordner(pfad: String): Ergebnis {
        val eintraege = workspace.ordner(pfad)
            ?: return if (workspace.datei(pfad) == null) draussen(pfad)
            else Ergebnis(false, "„$pfad\" ist kein Verzeichnis.", "kein Verzeichnis")

        if (eintraege.isEmpty()) return Ergebnis(true, "„$pfad\" ist leer.")
        return Ergebnis(true, eintraege.joinToString("\n"), "${eintraege.size} Einträge")
    }

    /**
     * Legt etwas in den Papierkorb.
     *
     * Die Antwort sagt ausdrücklich, dass nichts vernichtet wurde. Das ist keine Beschönigung,
     * sondern eine Auskunft: Wer hört „gelöscht", sucht nicht weiter; wer hört „im
     * Papierkorb", weiß, dass ein Fehlgriff behebbar ist.
     */
    fun loesche(pfad: String): Ergebnis {
        if (workspace.datei(pfad) == null) return draussen(pfad)
        val ablage = workspace.loesche(pfad)
            ?: return Ergebnis(false, "„$pfad\" gibt es nicht.", "nicht vorhanden")

        return Ergebnis(true, "$pfad liegt jetzt im Papierkorb.", "nach $ablage")
    }

    /**
     * Verschiebt oder benennt um.
     *
     * Ein Werkzeug für beides, weil es dasselbe ist. Die Fehlermeldungen unterscheiden die
     * Fälle trotzdem: Ein Modell, das nur „ging nicht" hört, versucht es unverändert noch
     * einmal.
     */
    fun verschiebe(von: String, nach: String): Ergebnis {
        if (workspace.datei(von) == null) return draussen(von)
        if (workspace.datei(nach) == null) return draussen(nach)

        val quelle = workspace.datei(von)
        if (quelle?.exists() != true) {
            return Ergebnis(false, "„$von\" gibt es nicht.", "Quelle fehlt")
        }
        if (workspace.datei(nach)?.exists() == true) {
            return Ergebnis(
                false,
                "„$nach\" gibt es schon. Ich überschreibe nichts — nimm einen anderen Namen " +
                    "oder lösche das Vorhandene zuerst.",
                "Ziel belegt",
            )
        }

        val neu = workspace.verschiebe(von, nach)
            ?: return Ergebnis(false, "Das Verschieben hat nicht geklappt.", "unbekannter Fehler")
        return Ergebnis(true, "$von heißt jetzt $neu.")
    }

    /**
     * Immer derselbe Satz, wenn ein Pfad aus allen erlaubten Orten hinausführt.
     *
     * **Mit der Aufzählung der erlaubten Orte.** Vorher stand hier nur „außerhalb des
     * Projekts", und das Modell hatte keine Möglichkeit, daraus einen gültigen Pfad zu
     * bilden — es riet weiter, und jeder Fehlversuch kostete eine halbe Minute. Seit es
     * mehrere Orte gibt, ist die Angabe erst recht nötig: Ob der Gerätespeicher freigegeben
     * ist, weiß das Modell sonst nicht.
     */
    private fun draussen(pfad: String): Ergebnis {
        val orte = workspace.erlaubteWurzeln()
        return Ergebnis(
            false,
            buildString {
                append("„$pfad\" liegt außerhalb dessen, worauf ich zugreifen darf. ")
                if (orte.size == 1) {
                    append("Erlaubt ist nur der Projektordner; Pfade dorthin sind relativ. ")
                    append("Für den übrigen Gerätespeicher fehlt die Freigabe.")
                } else {
                    append("Erlaubt sind: der Projektordner (relative Pfade) sowie ")
                    append(orte.drop(1).joinToString(", ") { it.absolutePath })
                    append(".")
                }
            },
            "Pfad außerhalb der erlaubten Orte",
        )
    }

    private companion object {
        const val DATEI_GRENZE = 200
    }
}
