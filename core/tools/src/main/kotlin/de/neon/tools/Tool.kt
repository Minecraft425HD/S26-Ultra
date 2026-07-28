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
            appendLine("root ::= " + specs.joinToString(" | ") { "call-${it.name}" })
            for (spec in specs) {
                append("call-").append(spec.name).append(" ::= ")
                append("\"{\\\"werkzeug\\\":\\\"").append(spec.name).append("\\\",\\\"argumente\\\":{\"")
                spec.parameters.forEachIndexed { index, parameter ->
                    if (index > 0) append(" \",\" ")
                    append(" \"\\\"").append(parameter.name).append("\\\":\" ")
                    append(argumentRule(spec.name, parameter))
                }
                appendLine(" \"}}\"")
            }
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
                            else -> "\"\\\"\" [^\"]* \"\\\"\""
                        }
                    )
                }
            }
        }
    }

    private fun argumentRule(toolName: String, parameter: ToolParameter): String =
        "arg-$toolName-${parameter.name}"

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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
