package de.neon.tools

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolRegistryTest {

    private class RecordingTool(
        name: String,
        parameters: List<ToolParameter> = emptyList(),
        private val result: ToolResult = ToolResult.Ok("erledigt"),
    ) : Tool {
        var lastArguments: Map<String, String>? = null

        override val spec = ToolSpec(name, "Testwerkzeug $name", parameters)

        override suspend fun execute(arguments: Map<String, String>): ToolResult {
            lastArguments = arguments
            return result
        }
    }

    private val timerTool = RecordingTool(
        name = "timer",
        parameters = listOf(
            ToolParameter("sekunden", ParameterType.INTEGER, "Dauer in Sekunden"),
        ),
    )

    private val lichtTool = RecordingTool(
        name = "licht",
        parameters = listOf(
            ToolParameter("zustand", ParameterType.STRING, "an oder aus", allowedValues = listOf("an", "aus")),
            ToolParameter("raum", ParameterType.STRING, "Raumname", required = false),
        ),
    )

    private fun registry() = ToolRegistry(listOf(timerTool, lichtTool))

    @Test
    fun `fuehrt ein Werkzeug mit seinen Argumenten aus`() = runTest {
        val result = registry().execute(ToolCall("timer", mapOf("sekunden" to "300")))
        assertIs<ToolResult.Ok>(result)
        assertEquals(mapOf("sekunden" to "300"), timerTool.lastArguments)
    }

    @Test
    fun `meldet ein unbekanntes Werkzeug statt abzustuerzen`() = runTest {
        val result = registry().execute(ToolCall("teleportieren"))
        val failed = assertIs<ToolResult.Failed>(result)
        assertTrue(failed.reason.contains("teleportieren"))
    }

    @Test
    fun `prueft Pflichtargumente vor der Ausfuehrung`() = runTest {
        val result = registry().execute(ToolCall("timer", emptyMap()))
        val failed = assertIs<ToolResult.Failed>(result)
        assertTrue(failed.reason.contains("sekunden"))
    }

    @Test
    fun `optionale Argumente duerfen fehlen`() = runTest {
        val result = registry().execute(ToolCall("licht", mapOf("zustand" to "an")))
        assertIs<ToolResult.Ok>(result)
    }

    @Test
    fun `faengt eine Ausnahme aus einem Werkzeug ab`() = runTest {
        val kaputt = object : Tool {
            override val spec = ToolSpec("kaputt", "wirft immer")
            override suspend fun execute(arguments: Map<String, String>): ToolResult =
                error("Netzwerk nicht erreichbar")
        }
        val result = ToolRegistry(listOf(kaputt)).execute(ToolCall("kaputt"))
        val failed = assertIs<ToolResult.Failed>(result)
        assertTrue(failed.reason.contains("Netzwerk"))
    }

    @Test
    fun `liest einen Werkzeugaufruf aus der Modellausgabe`() {
        val call = ToolRegistry.parseCall(
            """Gerne! {"werkzeug":"timer","argumente":{"sekunden":"300"}}"""
        )
        assertEquals("timer", call?.name)
        assertEquals("300", call?.arguments?.get("sekunden"))
    }

    @Test
    fun `gibt null bei unbrauchbarer Ausgabe zurueck`() {
        assertNull(ToolRegistry.parseCall("Ich weiß nicht, wie das geht."))
        assertNull(ToolRegistry.parseCall(""))
    }

    @Test
    fun `die Grammatik nennt jedes Werkzeug und jeden erlaubten Wert`() {
        val grammar = registry().grammar()
        assertTrue(grammar.contains("timer"))
        assertTrue(grammar.contains("licht"))
        // Aufzählungen müssen in der Grammatik hart begrenzt sein, sonst erfindet das
        // Modell Zustände wie "gedimmt".
        assertTrue(grammar.contains("\\\"an\\\""))
        assertTrue(grammar.contains("\\\"aus\\\""))
    }

    @Test
    fun `die Werkzeugbeschreibung nennt Namen und Parameter`() {
        val description = registry().promptDescription()
        assertTrue(description.contains("timer"))
        assertTrue(description.contains("sekunden"))
        assertTrue(description.contains("optional"), "optionale Parameter müssen erkennbar sein")
    }

    @Test
    fun `verbietet doppelte Werkzeugnamen`() {
        val duplicate = runCatching {
            ToolRegistry(listOf(RecordingTool("gleich"), RecordingTool("gleich")))
        }
        assertTrue(duplicate.isFailure)
    }
}
