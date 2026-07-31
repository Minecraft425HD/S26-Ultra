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
     * Immer derselbe Satz, wenn ein Pfad aus dem Projekt hinausführt.
     *
     * Bewusst ohne Vorwurf und mit der Angabe, was gilt: Das Modell soll daraus einen
     * gültigen Pfad bilden können. Und bewusst ohne den absoluten Pfad des Projekts — den
     * bräuchte es nicht, und was ein Modell sieht, schreibt es irgendwann hin.
     */
    private fun draussen(pfad: String) = Ergebnis(
        false,
        "„$pfad\" liegt außerhalb des Projekts. Pfade sind immer relativ zum Projektordner.",
        "Pfad außerhalb der Wurzel",
    )

    private companion object {
        const val DATEI_GRENZE = 200
    }
}
