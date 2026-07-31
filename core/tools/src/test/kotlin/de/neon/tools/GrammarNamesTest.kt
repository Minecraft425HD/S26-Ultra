package de.neon.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Ein Unterstrich hat die ganze Grammatik gesprengt.
 *
 * **Der Anlass.** Auf dem Gerät antwortete llama-server auf jeden Werkzeugaufruf mit HTTP 400:
 *
 * ```
 * parse: error parsing grammar: expecting newline or end at _ab_heute "}}"
 * ```
 *
 * Das Terminwerkzeug hat einen Parameter `tage_ab_heute`, und daraus wurde die Regel
 * `arg-termin-tage_ab_heute`. In GBNF sind in Regelnamen nur Buchstaben, Ziffern und
 * Bindestriche erlaubt — der Unterstrich beendet den Namen, und der Rest steht für den Parser
 * mitten im Nichts. Kaputt war damit nicht diese eine Regel, sondern die **gesamte**
 * Grammatik: Auch das Nachrichtenwerkzeug, dessen Namen völlig in Ordnung sind, war nicht mehr
 * aufrufbar.
 *
 * Aufgefallen ist das erst, als Abbrüche protokolliert wurden. Vorher stand in der
 * Sprechblase „Da ging etwas schief" und in der Protokolldatei nichts.
 */
class GrammarNamesTest {

    /**
     * Der Anspruch in einem Satz: **Jeder** Regelname der echten Grammatik ist gültig.
     *
     * Genau die Regel, die llama.cpp anwendet (`is_word_char`: Buchstaben, Ziffern,
     * Bindestrich). Sie hier nachzubilden ist billiger als ein Gerät — und es ist derselbe
     * Test, der den Fehler in einer Sekunde gefunden hätte.
     */
    @Test
    fun `kein Regelname der echten Werkzeuge enthaelt ein verbotenes Zeichen`() {
        val grammatik = ToolRegistry(listOf(TerminAttrappe(), NachrichtAttrappe())).grammar()

        val namen = grammatik.lineSequence()
            .filter { it.contains("::=") }
            .map { it.substringBefore("::=").trim() }
            .toList()

        assertTrue(namen.isNotEmpty(), "keine Regeln gefunden:\n$grammatik")
        namen.forEach { name ->
            assertTrue(
                name.all { it.isLetterOrDigit() && it.code < 128 || it == '-' },
                "ungültiger Regelname \"$name\" in:\n$grammatik",
            )
        }
    }

    /**
     * Der JSON-Schlüssel bleibt, wie er ist.
     *
     * Der wichtigste Teil der Korrektur. Hätte man den Parameternamen selbst bereinigt, wäre
     * die Grammatik gültig geworden und das Werkzeug trotzdem kaputt: [Tool.execute] sucht
     * `tage_ab_heute` in den Argumenten. Aus einem sichtbaren Fehler wäre ein stiller
     * geworden — die schlechtere Sorte.
     */
    @Test
    fun `der Parametername im JSON bleibt unveraendert`() {
        val grammatik = ToolRegistry(listOf(TerminAttrappe())).grammar()

        assertTrue(grammatik.contains("tage_ab_heute"), grammatik)
        assertTrue(grammatik.contains("arg-termin-tage-ab-heute"), grammatik)
    }

    @Test
    fun `bereinigt werden nur die verbotenen Zeichen`() {
        assertEquals("arg-termin-tage-ab-heute", ToolRegistry.regelname("arg-termin-tage_ab_heute"))
        assertEquals("call-wecker", ToolRegistry.regelname("call-wecker"))
        // Mehrere verbotene Zeichen hintereinander werden zu einem Strich, nicht zu drei.
        assertEquals("a-b", ToolRegistry.regelname("a__.b"))
        // Umlaute sind in GBNF-Namen ebenfalls nicht erlaubt.
        assertEquals("arg-t-r", ToolRegistry.regelname("arg-tür"))
    }

    /**
     * Zwei Namen, die zusammenfallen, sind ein Fehler und keine Kleinigkeit.
     *
     * `tage_ab_heute` und `tage-ab-heute` ergeben dieselbe Regel. In der Grammatik stünde sie
     * zweimal, llama.cpp nähme eine davon, und das Werkzeug bekäme gelegentlich das falsche
     * Argument — ohne eine einzige Fehlermeldung.
     */
    @Test
    fun `zusammenfallende Parameternamen werden abgelehnt`() {
        val fehler = assertFailsWith<IllegalArgumentException> {
            ToolRegistry(listOf(ZwielichtAttrappe()))
        }

        assertTrue(fehler.message!!.contains("zusammen"), fehler.message!!)
    }

    private class TerminAttrappe : Tool {
        override val spec = ToolSpec(
            name = "termin",
            description = "Legt einen Termin an",
            parameters = listOf(
                ToolParameter("titel", ParameterType.STRING, "Worum es geht"),
                ToolParameter("tage_ab_heute", ParameterType.INTEGER, "In wie vielen Tagen"),
            ),
        )

        override suspend fun execute(arguments: Map<String, String>) = ToolResult.Ok("ok")
    }

    private class NachrichtAttrappe : Tool {
        override val spec = ToolSpec(
            name = "nachricht",
            description = "Schickt eine Nachricht",
            parameters = listOf(ToolParameter("text", ParameterType.STRING, "Der Inhalt")),
        )

        override suspend fun execute(arguments: Map<String, String>) = ToolResult.Ok("ok")
    }

    private class ZwielichtAttrappe : Tool {
        override val spec = ToolSpec(
            name = "termin",
            description = "Zwei Namen, eine Regel",
            parameters = listOf(
                ToolParameter("tage_ab_heute", ParameterType.INTEGER, "so"),
                ToolParameter("tage-ab-heute", ParameterType.INTEGER, "oder so"),
            ),
        )

        override suspend fun execute(arguments: Map<String, String>) = ToolResult.Ok("ok")
    }
}
