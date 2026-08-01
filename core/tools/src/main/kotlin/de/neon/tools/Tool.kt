package de.neon.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ParameterType {
    @SerialName("string") STRING,
    @SerialName("integer") INTEGER,
    @SerialName("boolean") BOOLEAN,
}

@Serializable
data class ToolParameter(
    val name: String,
    val type: ParameterType,
    val description: String,
    val required: Boolean = true,
    /** Wenn gesetzt, sind nur diese Werte erlaubt. Fließt direkt in die Grammatik ein. */
    val allowedValues: List<String> = emptyList(),
    /**
     * Ob hier ganze Dateien hineinpassen müssen.
     *
     * **Der Fehler, den das behebt.** Ein Werkzeugaufruf durfte 128 Token lang sein. Das
     * reicht für „Termin um 15 Uhr" und für keine einzige Quelldatei: Bei rund vier Zeichen
     * je Token ist bei 400 Zeichen Schluss, das JSON bricht mitten im Inhalt ab, und
     * `parseCall` gibt `null` zurück. Auf dem Gerät sah das aus wie „Das habe ich nicht als
     * Befehl verstanden" — bei einem Aufruf, den das Modell völlig richtig begonnen hatte.
     *
     * Die Grenze pauschal hochzusetzen wäre falsch herum: Ein Modell füllt den Platz, den
     * es bekommt, und bei zwölf Token je Sekunde ist jede unnötige Marke eine Sekunde
     * Wartezeit. Deshalb entscheidet der Parameter, nicht der Aufrufer.
     */
    val langerInhalt: Boolean = false,
)

@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter> = emptyList(),
)

sealed interface ToolResult {
    /** [spoken] ist das, was Neon dazu sagt — nicht unbedingt das technische Ergebnis. */
    data class Ok(val spoken: String, val data: String? = null) : ToolResult
    data class Failed(val spoken: String, val reason: String) : ToolResult
}

/**
 * Etwas, das Neon tun kann.
 *
 * Die Beschreibung geht an das Modell, die Ausführung an Android. Beides an einer Stelle zu
 * halten verhindert das übliche Auseinanderdriften zwischen dem, was ein Werkzeug laut
 * Prompt kann, und dem, was es tatsächlich tut.
 */
interface Tool {
    val spec: ToolSpec
    suspend fun execute(arguments: Map<String, String>): ToolResult
}

@Serializable
data class ToolCall(
    @SerialName("werkzeug") val name: String,
    @SerialName("argumente") val arguments: Map<String, String> = emptyMap(),
)

/**
 * Kennt alle Werkzeuge und übersetzt sie in etwas, das ein Modell zuverlässig ausgeben kann.
 *
 * Der wichtigste Teil ist [grammar]: Viele kleine Modelle können kein verlässliches
 * Function-Calling, geben aber mit erzwungener Grammatik trotzdem gültige Aufrufe aus. Damit
 * werden Werkzeuge auch für ein 4B-Modell benutzbar.
 */
class ToolRegistry(tools: List<Tool>) {

    private val byName: Map<String, Tool> = tools.associateBy { it.spec.name }

    val specs: List<ToolSpec> = tools.map { it.spec }

    init {
        require(byName.size == tools.size) { "Doppelte Werkzeugnamen in der Registry" }

        // Zwei Namen können nach dem Bereinigen zusammenfallen — `tage_ab_heute` und
        // `tage-ab-heute` ergeben dieselbe Regel. In der Grammatik stünde die Regel dann
        // zweimal, llama.cpp nähme eine davon, und der Fehler wäre still: ein Werkzeug,
        // das gelegentlich das falsche Argument bekommt. Lieber hier laut werden.
        val regeln = specs.flatMap { spec ->
            spec.parameters.map { regelname("arg-${spec.name}-${it.name}") to "${spec.name}.${it.name}" }
        }
        val gruppen = regeln.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
        require(gruppen.isEmpty()) {
            "Parameternamen fallen in der Grammatik zusammen: $gruppen"
        }
    }

    operator fun get(name: String): Tool? = byName[name]

    suspend fun execute(call: ToolCall): ToolResult {
        val tool = byName[call.name]
            ?: return ToolResult.Failed(
                spoken = "Das kann ich nicht.",
                reason = "unbekanntes Werkzeug: ${call.name}",
            )

        val missing = tool.spec.parameters
            .filter { it.required && call.arguments[it.name].isNullOrBlank() }
            .map { it.name }
        if (missing.isNotEmpty()) {
            return ToolResult.Failed(
                spoken = "Dazu fehlt mir noch eine Angabe.",
                reason = "fehlende Argumente: ${missing.joinToString()}",
            )
        }

        return runCatching { tool.execute(call.arguments) }.getOrElse {
            ToolResult.Failed(
                spoken = "Das hat leider nicht geklappt.",
                reason = it.message ?: "unbekannter Fehler",
            )
        }
    }

    /** Die Werkzeugbeschreibung für den Systemprompt. */
    fun promptDescription(): String = buildString {
        appendLine("Verfügbare Werkzeuge:")
        for (spec in specs) {
            append("- ").append(spec.name).append(": ").appendLine(spec.description)
            for (parameter in spec.parameters) {
                append("    ").append(parameter.name)
                append(" (").append(parameter.type.name.lowercase())
                if (!parameter.required) append(", optional")
                append("): ").appendLine(parameter.description)
            }
        }
    }

    /**
     * GBNF-Grammatik, die genau die gültigen Werkzeugaufrufe zulässt.
     *
     * Erzeugt aus den Spezifikationen, damit ein neues Werkzeug nicht an zwei Stellen
     * gepflegt werden muss.
     */
    fun grammar(): String {
        if (specs.isEmpty()) return ""

        return buildString {
            // Bereinigt auch hier: Ein Werkzeug namens `wecker_stellen` hätte denselben
            // Fehler ausgelöst wie der Parameter `tage_ab_heute`. Im JSON steht weiterhin
            // der echte Name — nur der Regelname ist ein anderer.
            appendLine("root ::= " + specs.joinToString(" | ") { regelname("call-${it.name}") })
            for (spec in specs) {
                append(regelname("call-${spec.name}")).append(" ::= ")
                append("\"{\\\"werkzeug\\\":\\\"").append(spec.name).append("\\\",\\\"argumente\\\":{\"")
                spec.parameters.forEachIndexed { index, parameter ->
                    if (index > 0) append(" \",\" ")
                    append(" \"\\\"").append(parameter.name).append("\\\":\" ")
                    append(argumentRule(spec.name, parameter))
                }
                appendLine(" \"}}\"")
            }
            var brauchtText = false
            for (spec in specs) {
                for (parameter in spec.parameters) {
                    val rule = argumentRule(spec.name, parameter)
                    append(rule).append(" ::= ")
                    appendLine(
                        when {
                            parameter.allowedValues.isNotEmpty() ->
                                parameter.allowedValues.joinToString(" | ") { "\"\\\"$it\\\"\"" }

                            parameter.type == ParameterType.INTEGER -> "\"\\\"\" [0-9]+ \"\\\"\""
                            parameter.type == ParameterType.BOOLEAN -> "\"\\\"true\\\"\" | \"\\\"false\\\"\""
                            else -> {
                                brauchtText = true
                                TEXT_REGEL
                            }
                        }
                    )
                }
            }
            if (brauchtText) append(TEXT_DEFINITION)
        }
    }

    private fun argumentRule(toolName: String, parameter: ToolParameter): String =
        regelname("arg-$toolName-${parameter.name}")

    /**
     * Wie lang ein Werkzeugaufruf mit diesen Werkzeugen werden darf.
     *
     * Siehe [ToolParameter.langerInhalt]. Kurze Zusammenstellungen behalten die enge Grenze;
     * sobald eine ganze Datei hineinpassen muss, gilt die weite. Dass die Grammatik den
     * Aufruf ohnehin nach `}}` beendet, macht die weite Grenze billig: Sie wird nur
     * ausgeschöpft, wenn wirklich so viel Inhalt kommt.
     */
    fun maxAntwortToken(): Int =
        if (specs.any { spec -> spec.parameters.any { it.langerInhalt } }) LANGE_GRENZE
        else KURZE_GRENZE

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Reicht für „Termin, 15 Uhr, morgen" und für jede Rückfrage. */
        const val KURZE_GRENZE = 128

        /**
         * Reicht für eine Quelldatei von rund sechstausend Zeichen.
         *
         * Nicht größer: Eine `MainActivity.kt` aus der Vorlage hat 1200 Zeichen, und was ein
         * Modell darüber hinaus schreibt, ist auf einem Telefon eher eine Endlosschleife als
         * ein Werk. Bei zwölf Token je Sekunde wären 1536 Token schon zwei Minuten.
         */
        const val LANGE_GRENZE = 1_536

        /**
         * Die Regel für eine Zeichenkette — und der Grund, warum die IDE keinen Code schreiben
         * konnte.
         *
         * **Hier stand `"\"" [^"]* "\""`.** Also: ein Anführungszeichen, beliebig viele
         * Zeichen die *kein* Anführungszeichen sind, ein Anführungszeichen. Das verbietet dem
         * Modell buchstabengenau, ein `"` in den Inhalt zu schreiben — und damit jedes
         * `println("hallo")`, jedes `android:name="..."`, jedes Python mit einer Zeichenkette
         * darin. Die erzwungene Grammatik ließ das Zeichen schlicht nicht durch.
         *
         * Und Zeilenumbrüche gingen zwar durch, ergaben aber ungültiges JSON: Ein roher
         * Umbruch innerhalb einer JSON-Zeichenkette ist nicht erlaubt, also scheiterte danach
         * `parseCall`. Mehrzeilige Dateien waren damit ebenso unmöglich wie Anführungszeichen.
         *
         * Beides behebt dieselbe Regel: eine **echte** JSON-Zeichenkette. Sie lässt normale
         * Zeichen durch, verlangt für `"` und `\` die üblichen Fluchtfolgen und verbietet rohe
         * Steuerzeichen — womit der Umbruch als `\n` geschrieben werden **muss**. Weil die
         * Grammatik das erzwingt, kann das Modell es nicht falsch machen.
         */
        const val TEXT_REGEL = "text"

        /**
         * Einmal definiert statt je Parameter.
         *
         * Nebeneffekt, der hier zählt: Die Grammatik ist Teil des Prompts. Elf Werkzeuge mit
         * je eigener, identischer Zeichenkettenregel wären elf Zeilen für dieselbe Aussage —
         * und jede kostet Kontext und Zeit vor dem ersten Wort.
         */
        val TEXT_DEFINITION: String = buildString {
            appendLine("$TEXT_REGEL ::= \"\\\"\" text-zeichen* \"\\\"\"")
            // Ausgeschlossen sind Anführungszeichen, der Rückstrich und die drei
            // Steuerzeichen, die ein Modell tatsächlich schreibt: Umbruch, Wagenrücklauf,
            // Tabulator. Roh sind sie in JSON verboten — also muss ein mehrzeiliger Inhalt
            // seine Umbrüche als `\n` schreiben, und die Grammatik lässt ihm keine Wahl.
            appendLine(
                "text-zeichen ::= [^\"\\\\\\n\\r\\t] | " +
                    "\"\\\\\" [\"\\\\/bfnrt] | " +
                    "\"\\\\u\" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]"
            )
        }

        /**
         * Macht aus einem beliebigen Namen einen gültigen GBNF-Regelnamen.
         *
         * **Der Fehler, den das behebt.** Das Terminwerkzeug hat einen Parameter
         * `tage_ab_heute`. Daraus wurde die Regel `arg-termin-tage_ab_heute`, und llama.cpp
         * antwortete:
         *
         * ```
         * parse: error parsing grammar: expecting newline or end at _ab_heute "}}"
         * ```
         *
         * In GBNF-Regelnamen sind nur Buchstaben, Ziffern und Bindestriche erlaubt. Der
         * Unterstrich beendet den Namen; alles danach steht für den Parser mitten im
         * Nichts. Damit war nicht diese eine Regel kaputt, sondern **die ganze Grammatik**
         * — und jeder Werkzeugaufruf endete mit HTTP 400, gleich um welches Werkzeug es
         * ging. Auf dem Gerät sah das aus wie „Neon kann keine Termine".
         *
         * Betroffen ist nur der Regelname. Der JSON-Schlüssel im Aufruf bleibt
         * `tage_ab_heute`, denn den erwartet [Tool.execute].
         */
        fun regelname(roh: String): String {
            val bereinigt = roh.map { if (it in ERLAUBT) it else '-' }.joinToString("")
            // Nicht bloß ersetzen, sondern zusammenfassen: Aus `a__b` würde sonst `a--b`,
            // was gültig aber unnötig hässlich ist.
            return bereinigt.replace(DOPPELTER_STRICH, "-").trim('-')
        }

        private val ERLAUBT: Set<Char> =
            (('a'..'z') + ('A'..'Z') + ('0'..'9') + '-').toSet()

        private val DOPPELTER_STRICH = Regex("-{2,}")

        /** Liest einen Werkzeugaufruf aus der Modellausgabe. */
        fun parseCall(raw: String): ToolCall? {
            val start = raw.indexOf('{')
            if (start < 0) return null
            val end = raw.lastIndexOf('}')
            if (end <= start) return null
            return runCatching {
                json.decodeFromString<ToolCall>(raw.substring(start, end + 1))
            }.getOrNull()
        }
    }
}
