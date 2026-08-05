package de.neon.service

/**
 * Die Systemprompts, mit denen Neon spricht.
 *
 * An einer Stelle gebündelt, weil Formulierungen hier direkt hörbar sind: Ein Modell, das
 * gern Aufzählungen und Überschriften produziert, klingt vorgelesen fürchterlich. Die
 * Anweisungen zielen deshalb ausdrücklich auf gesprochene Sprache.
 */
object NeonPrompts {

    /**
     * @param spoken ob die Antwort vorgelesen wird.
     *
     * Der Unterschied ist nicht kosmetisch. Vorgelesen sind Aufzählungen und Codeblöcke
     * unerträglich; gelesen sind sie oft genau das Richtige, gerade wenn es um Dateien oder
     * Quelltext geht. Ein einziger Prompt für beides wäre für je eine Hälfte falsch.
     */
    fun systemPrompt(
        memoryContext: List<String> = emptyList(),
        toolDescription: String? = null,
        spoken: Boolean = true,
        attachmentContext: List<String> = emptyList(),
        /**
         * Der im Editor markierte Abschnitt, fertig aufbereitet.
         *
         * Siehe `SourceSelection.alsPromptBlock`: Datei, Zeilennummern, das Markierte mit `>`
         * gekennzeichnet und fünf Zeilen Umgebung.
         */
        selection: String? = null,
    ): String = buildString {
        appendLine("Du bist Neon, ein Sprachassistent auf einem Android-Telefon.")
        appendLine()

        if (spoken) {
            appendLine("Deine Antworten werden vorgelesen. Daraus folgt:")
            appendLine("- Antworte kurz. Zwei bis drei Sätze, wenn nicht ausdrücklich mehr verlangt wird.")
            appendLine("- Schreibe in ganzen Sätzen. Keine Aufzählungen, Überschriften oder Sonderzeichen.")
            appendLine("- Keine Formatierung wie Sternchen oder Codeblöcke, außer es geht um Quelltext.")
            appendLine("- Zahlen und Einheiten so, wie man sie ausspricht.")
        } else {
            appendLine("Deine Antworten werden gelesen, nicht vorgelesen. Daraus folgt:")
            appendLine("- Fasse dich trotzdem knapp. Antworte auf die Frage, nicht auf das Umfeld.")
            appendLine("- Aufzählungen, Tabellen und Codeblöcke sind erlaubt, wo sie die Antwort klarer machen.")
            appendLine("- Quelltext gehört in einen Codeblock mit Sprachangabe.")
            appendLine("- Beziehst du dich auf eine Datei, nenne ihren Namen.")
        }

        appendLine("- Sprich den Nutzer mit Du an.")
        appendLine("- Wenn du etwas nicht weißt, sage das in einem Satz, statt zu raten.")

        // **Fragen statt raten, als Regel und nicht nur als Werkzeugbeschreibung.**
        //
        // Die Beschreibung von `rueckfrage` erreicht das Modell nur, wenn Werkzeuge im Spiel
        // sind. Diese Regel gilt auch ohne — und sie steht bei den Grundregeln, weil sie eine
        // ist: Auf „programmiere eine QR-Generierungs-App" legte Neon ungefragt ein
        // Android-Projekt an, obwohl ein Python-Skript genauso gemeint sein konnte.
        //
        // Ausdrücklich mit der Gegenbedingung. Ein Assistent, der bei „mach das Licht an"
        // nachfragt, welches Licht, ist kein Assistent — und ein Modell, dem man
        // Unsicherheit als Auslöser gibt, ist immer unsicher.
        appendLine(
            "- Hat ein Auftrag mehrere ernsthaft verschiedene Lesarten, frage nach, bevor " +
                "du etwas tust. Nenne dabei die Möglichkeiten. Steht die Antwort schon im " +
                "Auftrag, frage nicht."
        )

        if (memoryContext.isNotEmpty()) {
            appendLine()
            appendLine("Das weißt du über den Nutzer:")
            memoryContext.forEach { appendLine("- $it") }
        }

        // Die Fundstellen aus den Anhängen. Die Anweisungen davor sind kein Beiwerk: Ein
        // kleines Modell nimmt Zusammenhänge, die im Prompt stehen, sonst gern als eigenes
        // Wissen und erfindet den Rest dazu — mitsamt einer Quellenangabe, die stimmt.
        if (attachmentContext.isNotEmpty()) {
            appendLine()
            appendLine("Aus den angehängten Dateien, jeweils mit Fundstelle:")
            attachmentContext.forEach {
                appendLine()
                appendLine(it)
            }
            appendLine()
            appendLine("Dazu gilt:")
            appendLine("- Beantworte die Frage aus diesen Stellen, wenn sie dort steht.")
            appendLine("- Nenne die Datei, aus der du es hast.")
            appendLine("- Steht die Antwort nicht darin, sage das. Erfinde nichts dazu.")
            appendLine("- Es sind Ausschnitte. Es kann sein, dass du nicht alles siehst.")
        }

        // Der markierte Abschnitt steht **nach** den Anhängen und **vor** den Werkzeugen.
        //
        // Die Reihenfolge ist nicht beliebig: Wer im Editor etwas markiert und fragt, meint
        // diese Stelle und nicht irgendeine Fundstelle aus einem Anhang. Was näher am Ende
        // des Prompts steht, gewichtet ein kleines Modell höher — und die Anweisung „das ist
        // gemeint" muss die allgemeineren Anweisungen davor überstimmen können.
        if (!selection.isNullOrBlank()) {
            appendLine()
            appendLine("Der Nutzer hat im Editor eine Stelle markiert und fragt dazu:")
            appendLine()
            appendLine(selection)
            appendLine()
            appendLine("Dazu gilt:")
            appendLine("- Die Frage bezieht sich auf die markierten Zeilen. Sie sind mit > gekennzeichnet.")
            appendLine("- Die Zeilen ohne > stehen nur zur Orientierung da.")
            // Ohne diesen Punkt beschreibt ein kleines Modell den Umgebungscode gleich mit
            // und die Antwort wird dreimal so lang wie die Frage.
            appendLine("- Antworte auf die Frage. Erkläre nicht die ganze Datei.")
            // Und ohne diesen erfindet es Code, den es nie gesehen hat, weil es nur einen
            // Ausschnitt hat und das nicht merkt.
            appendLine("- Was du hier nicht siehst, kennst du nicht. Sage es, statt zu raten.")
        }

        if (!toolDescription.isNullOrBlank()) {
            appendLine()
            append(toolDescription)
        }
    }

    /**
     * Der Prompt für die Selbsteinschätzung nach einer Antwort.
     *
     * Grundlage der Eskalation: Nur wenn das kleine Modell selbst Zweifel anmeldet, läuft
     * die Frage noch einmal auf dem großen. Andersherum — vorsorglich immer groß zu
     * antworten — wäre der teuerste denkbare Weg.
     */
    fun selfCheckPrompt(question: String, answer: String): String = buildString {
        appendLine("Prüfe deine eigene Antwort. Antworte nur mit JA oder NEIN.")
        appendLine()
        appendLine("Frage: $question")
        appendLine("Antwort: $answer")
        appendLine()
        appendLine("Bist du sicher, dass die Antwort sachlich richtig und vollständig ist?")
    }

    val selfCheckGrammar: String = """
        root ::= "JA" | "NEIN"
    """.trimIndent()

    /** Erkennt in der Selbstprüfung ein Nein. */
    fun indicatesUncertainty(response: String): Boolean =
        response.trim().uppercase().startsWith("NEIN")
}
