package de.neon.router

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Stufe 2: das kleine Router-Modell.
 *
 * Wird nur befragt, wenn Regeln und kNN nichts Belastbares geliefert haben. Die
 * Implementierung auf dem Gerät lässt Qwen3 0.6B mit erzwungener JSON-Grammatik antworten;
 * in Tests tritt eine Attrappe an ihre Stelle.
 */
fun interface RouterLlm {
    /** Gibt `null` zurück, wenn das Modell nichts Verwertbares liefert. */
    fun analyze(utterance: Utterance): RouteAnalysis?
}

/**
 * Prompt, Grammatik und Antwort-Auswertung für das Router-Modell.
 *
 * Grammatik und Kategorienliste werden aus [TaskCategory] erzeugt, damit sie nicht
 * auseinanderlaufen können, wenn eine Kategorie hinzukommt.
 */
object RouterLlmProtocol {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Kategorien, die das Router-Modell vergeben darf. */
    private val routableCategories: List<TaskCategory> =
        TaskCategory.entries.filter { it != TaskCategory.UNBEKANNT }

    val systemPrompt: String = buildString {
        appendLine("Du bist der Router eines Sprachassistenten. Du beantwortest NICHTS.")
        appendLine("Du ordnest die Nutzeräußerung nur ein und antwortest ausschließlich mit JSON.")
        appendLine()
        appendLine("Kategorien:")
        appendLine("- SMALLTALK: Begrüßung, Geplauder, kurze persönliche Bemerkung")
        appendLine("- WISSENSFRAGE: Faktenfrage aus dem Allgemeinwissen")
        appendLine("- CODE: Programmieren, Shell, Konfiguration, Fehlersuche in Code")
        appendLine("- LOGIK_MATHE: Rechnen, mehrstufiges Schlussfolgern, Planen, Knobeln")
        appendLine("- BILD: braucht ein Bild als Eingabe")
        appendLine("- GERAETE_AKTION: Handlung auf dem Gerät oder im Smart Home")
        appendLine("- PERSOENLICH: bezieht sich auf gespeichertes Wissen über den Nutzer")
        appendLine("- WEB_AKTUELL: braucht tagesaktuelle Informationen aus dem Netz")
        appendLine()
        appendLine("komplexitaet: 1 = trivial, 3 = normale Frage, 5 = verlangt echtes Nachdenken.")
        appendLine("privat: true, wenn Kontakte, Nachrichten, Gesundheit, Finanzen oder Standort vorkommen.")
    }

    /**
     * GBNF-Grammatik für llama.cpp. Erzwingt gültiges JSON mit genau diesen Feldern —
     * damit kann auch ein kleines Modell nicht aus der Form fallen.
     *
     * **Jede Regel muss in einer Zeile stehen.** Der GBNF-Parser kennt keine Fortsetzung
     * über Zeilenumbrüche hinweg; eine umgebrochene Regel führt nicht etwa zu einer
     * Warnung, sondern lässt die gesamte Grammatik scheitern — und der Server antwortet
     * dann mit einem leeren Ergebnis statt mit einer Fehlermeldung, die auf die Ursache
     * zeigt.
     */
    val grammar: String = buildString {
        append("root ::= \"{\"")
        append(" ws \"\\\"kategorie\\\"\" ws \":\" ws kategorie ws \",\"")
        append(" ws \"\\\"komplexitaet\\\"\" ws \":\" ws komplexitaet ws \",\"")
        append(" ws \"\\\"braucht_web\\\"\" ws \":\" ws bool ws \",\"")
        append(" ws \"\\\"braucht_bild\\\"\" ws \":\" ws bool ws \",\"")
        append(" ws \"\\\"privat\\\"\" ws \":\" ws bool ws")
        appendLine(" \"}\"")
        append("kategorie ::= ")
        appendLine(routableCategories.joinToString(" | ") { "\"\\\"${it.name}\\\"\"" })
        appendLine("komplexitaet ::= [1-5]")
        appendLine("bool ::= \"true\" | \"false\"")
        appendLine("ws ::= [ \\t\\n]*")
    }

    fun userPrompt(utterance: Utterance): String = "Äußerung: ${utterance.text}"

    /**
     * Wertet die Modellantwort aus.
     *
     * Auch bei erzwungener Grammatik wird defensiv geparst: Das Modell könnte über eine
     * andere Laufzeit ohne Grammatikunterstützung laufen.
     */
    fun parse(raw: String): RouteAnalysis? {
        val payload = extractJsonObject(raw) ?: return null
        val decoded = runCatching { json.decodeFromString<Response>(payload) }.getOrNull() ?: return null
        val category = runCatching { TaskCategory.valueOf(decoded.category.uppercase()) }
            .getOrDefault(TaskCategory.UNBEKANNT)

        return RouteAnalysis(
            category = category,
            complexity = decoded.complexity.coerceIn(
                RouteAnalysis.MIN_COMPLEXITY,
                RouteAnalysis.MAX_COMPLEXITY,
            ),
            needsWeb = decoded.needsWeb,
            needsVision = decoded.needsVision,
            isPrivate = decoded.isPrivate,
            // Das Router-Modell ist verlässlicher als ein unsicherer kNN-Treffer, aber
            // schwächer als eine Regel. Ein fester mittlerer Wert bildet das ab.
            confidence = ROUTER_LLM_CONFIDENCE,
            source = AnalysisSource.ROUTER_LLM,
        )
    }

    /** Schneidet das erste ausgewogene JSON-Objekt aus der Rohausgabe heraus. */
    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private const val ROUTER_LLM_CONFIDENCE = 0.75

    @Serializable
    private data class Response(
        @SerialName("kategorie") val category: String,
        @SerialName("komplexitaet") val complexity: Int,
        @SerialName("braucht_web") val needsWeb: Boolean = false,
        @SerialName("braucht_bild") val needsVision: Boolean = false,
        @SerialName("privat") val isPrivate: Boolean = false,
    )
}
