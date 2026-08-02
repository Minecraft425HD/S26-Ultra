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
        // **Die Entwicklungsumgebung stand hier nicht drin, und das hat sie unerreichbar
        // gemacht.** „Programmieren, Shell, Konfiguration, Fehlersuche" beschreibt jemanden,
        // der über Code redet — nicht jemanden, der eine App bauen lässt. Auf „mach mir eine
        // Zähler-App für Android und erstelle das Projekt, danach direkt kompilieren"
        // antwortete das Router-Modell mit WISSENSFRAGE, und Neon gab eine Anleitung aus, wie
        // man das in Android Studio macht.
        //
        // Das war keine Fehlleistung des Modells: Es konnte nicht wissen, dass Neon Projekte
        // anlegt, Dateien schreibt und APKs baut. Hier steht, was Neon kann.
        appendLine(
            "- CODE: Programmieren, Fehlersuche — und alles, was Neon selbst im Projekt tut: " +
                "eine App oder ein Projekt anlegen, Dateien lesen, schreiben und ändern, " +
                "bauen, kompilieren, eine APK erzeugen, Python ausführen"
        )
        appendLine("- LOGIK_MATHE: Rechnen, mehrstufiges Schlussfolgern, Planen, Knobeln")
        appendLine("- BILD: braucht ein Bild als Eingabe")
        appendLine("- GERAETE_AKTION: Handlung auf dem Gerät oder im Smart Home")
        appendLine("- PERSOENLICH: bezieht sich auf gespeichertes Wissen über den Nutzer")
        appendLine("- WEB_AKTUELL: braucht tagesaktuelle Informationen aus dem Netz")
        appendLine()
        appendLine()
        appendLine("Antworte in genau einer Zeile, in dieser Form:")
        appendLine("KATEGORIE komplexitaet web bild privat")
        appendLine()
        appendLine("komplexitaet: 1 = trivial, 3 = normale Frage, 5 = verlangt echtes Nachdenken.")
        appendLine("web, bild, privat: j oder n.")
        appendLine("privat ist j, wenn Kontakte, Nachrichten, Gesundheit, Finanzen oder Standort vorkommen.")
        appendLine()
        appendLine("Beispiel: WISSENSFRAGE 2 n n n")
    }

    /**
     * GBNF-Grammatik für llama.cpp. Erzwingt genau eine Zeile der Form
     * `KATEGORIE komplexitaet web bild privat`.
     *
     * **Hier stand JSON, und das war teuer.** Die Antwort lautete
     * `{"kategorie":"CODE","komplexitaet":3,"braucht_web":false,…}` — rund 45 Token, von denen
     * die Grammatik über dreißig ohnehin erzwang. Das half nichts: Ein erzwungenes Token
     * kostet denselben vollständigen Rechendurchgang durch das Modell wie ein frei gewähltes.
     * llama.cpp überspringt festgelegte Fortsetzungen nicht.
     *
     * Auf dem Gerät waren das gemessene 45 bis 50 Ausgabe-Token je Einordnung, bei 15 Token
     * je Sekunde auf dem 4-B-Modell also **rund drei Sekunden** — für eine Auskunft, die aus
     * einer Kategorie, einer Ziffer und drei Ja-Nein-Angaben besteht. Die kompakte Form
     * braucht sechs bis acht Token.
     *
     * Die Kategorie bleibt ausgeschrieben. Ein Buchstabenkürzel wäre noch kürzer, verlangte
     * dem Modell aber eine Übersetzung ab, die es schlechter kann als das Einordnen selbst —
     * gespart wären zwei Token, bezahlt mit Treffgenauigkeit.
     *
     * **Jede Regel muss in einer Zeile stehen.** Der GBNF-Parser kennt keine Fortsetzung
     * über Zeilenumbrüche hinweg; eine umgebrochene Regel führt nicht etwa zu einer
     * Warnung, sondern lässt die gesamte Grammatik scheitern — und der Server antwortet
     * dann mit einem leeren Ergebnis statt mit einer Fehlermeldung, die auf die Ursache
     * zeigt.
     */
    val grammar: String = buildString {
        appendLine("root ::= kategorie \" \" [1-5] \" \" ja-nein \" \" ja-nein \" \" ja-nein")
        append("kategorie ::= ")
        appendLine(routableCategories.joinToString(" | ") { "\"${it.name}\"" })
        appendLine("ja-nein ::= \"j\" | \"n\"")
    }

    fun userPrompt(utterance: Utterance): String = "Äußerung: ${utterance.text}"

    /**
     * Wertet die Modellantwort aus.
     *
     * Auch bei erzwungener Grammatik wird defensiv geparst: Das Modell könnte über eine
     * andere Laufzeit ohne Grammatikunterstützung laufen.
     */
    fun parse(raw: String): RouteAnalysis? = parseKompakt(raw) ?: parseJson(raw)

    /**
     * Die kurze Form: `KATEGORIE komplexitaet web bild privat`.
     *
     * Nachsichtig bei den Trennzeichen und bei der Groß- und Kleinschreibung. Die Grammatik
     * lässt zwar nur eine Form zu, aber `parse` ist ausdrücklich auch für den Fall gebaut,
     * dass keine Grammatik wirkt — und dann ist ein zusätzliches Leerzeichen kein Grund,
     * eine sonst brauchbare Einordnung wegzuwerfen.
     */
    private fun parseKompakt(raw: String): RouteAnalysis? {
        val teile = raw.trim().split(Regex("\\s+"))
        if (teile.size < 5) return null

        val category = runCatching { TaskCategory.valueOf(teile[0].uppercase()) }.getOrNull()
            ?: return null
        if (category == TaskCategory.UNBEKANNT) return null
        val komplexitaet = teile[1].toIntOrNull() ?: return null

        fun jaNein(wert: String): Boolean? = when (wert.lowercase()) {
            "j", "ja", "true" -> true
            "n", "nein", "false" -> false
            else -> null
        }

        return RouteAnalysis(
            category = category,
            complexity = komplexitaet.coerceIn(
                RouteAnalysis.MIN_COMPLEXITY,
                RouteAnalysis.MAX_COMPLEXITY,
            ),
            needsWeb = jaNein(teile[2]) ?: return null,
            needsVision = jaNein(teile[3]) ?: return null,
            isPrivate = jaNein(teile[4]) ?: return null,
            confidence = ROUTER_LLM_CONFIDENCE,
            source = AnalysisSource.ROUTER_LLM,
        )
    }

    /**
     * Die alte JSON-Form, weiterhin lesbar.
     *
     * Kostet nichts, solange sie nicht vorkommt, und rettet den Fall, dass ein Modell ohne
     * wirksame Grammatik antwortet — kleine Modelle haben JSON millionenfach gesehen und
     * fallen im Zweifel darauf zurück.
     */
    private fun parseJson(raw: String): RouteAnalysis? {
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
