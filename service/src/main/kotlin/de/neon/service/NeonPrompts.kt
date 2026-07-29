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

        if (memoryContext.isNotEmpty()) {
            appendLine()
            appendLine("Das weißt du über den Nutzer:")
            memoryContext.forEach { appendLine("- $it") }
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
