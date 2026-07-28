package de.neon.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouterLlmProtocolTest {

    @Test
    fun `liest eine saubere Antwort`() {
        val analysis = RouterLlmProtocol.parse(
            """{"kategorie":"CODE","komplexitaet":4,"braucht_web":false,"braucht_bild":false,"privat":false}"""
        )
        assertNotNull(analysis)
        assertEquals(TaskCategory.CODE, analysis.category)
        assertEquals(4, analysis.complexity)
        assertEquals(AnalysisSource.ROUTER_LLM, analysis.source)
    }

    @Test
    fun `findet das JSON auch zwischen Geschwaetz`() {
        // Kleine Modelle leiten gern ein, selbst wenn man es ihnen verbietet.
        val raw = """
            Klar! Hier ist meine Einschätzung:
            {"kategorie":"WEB_AKTUELL","komplexitaet":2,"braucht_web":true,"braucht_bild":false,"privat":false}
            Ich hoffe das hilft.
        """.trimIndent()

        val analysis = RouterLlmProtocol.parse(raw)
        assertNotNull(analysis)
        assertEquals(TaskCategory.WEB_AKTUELL, analysis.category)
        assertTrue(analysis.needsWeb)
    }

    @Test
    fun `kommt mit verschachtelten Klammern zurecht`() {
        val raw = """prefix {"kategorie":"SMALLTALK","komplexitaet":1,"braucht_web":false,""" +
            """"braucht_bild":false,"privat":false,"extra":{"a":1}} suffix"""
        val analysis = RouterLlmProtocol.parse(raw)
        assertNotNull(analysis)
        assertEquals(TaskCategory.SMALLTALK, analysis.category)
    }

    @Test
    fun `begrenzt eine ausserhalb liegende Komplexitaet`() {
        val analysis = RouterLlmProtocol.parse(
            """{"kategorie":"LOGIK_MATHE","komplexitaet":9,"braucht_web":false,"braucht_bild":false,"privat":false}"""
        )
        assertNotNull(analysis)
        assertEquals(5, analysis.complexity)
    }

    @Test
    fun `macht aus einer unbekannten Kategorie kein Drama`() {
        val analysis = RouterLlmProtocol.parse(
            """{"kategorie":"KOCHREZEPT","komplexitaet":2,"braucht_web":false,"braucht_bild":false,"privat":false}"""
        )
        assertNotNull(analysis)
        assertEquals(TaskCategory.UNBEKANNT, analysis.category)
    }

    @Test
    fun `gibt null bei unbrauchbarer Ausgabe`() {
        assertNull(RouterLlmProtocol.parse("Ich weiß es nicht."))
        assertNull(RouterLlmProtocol.parse(""))
        assertNull(RouterLlmProtocol.parse("{kaputt"))
    }

    @Test
    fun `die Grammatik kennt alle vergebbaren Kategorien`() {
        // Kommt eine Kategorie hinzu, muss die Grammatik automatisch mitwachsen —
        // sonst könnte das Modell sie nie ausgeben.
        val grammar = RouterLlmProtocol.grammar
        TaskCategory.entries
            .filter { it != TaskCategory.UNBEKANNT }
            .forEach { category ->
                assertTrue(
                    grammar.contains(category.name),
                    "${category.name} fehlt in der Grammatik",
                )
            }
        // UNBEKANNT ist ein interner Rückfall und darf nicht vom Modell gewählt werden.
        assertTrue(!grammar.contains(TaskCategory.UNBEKANNT.name))
    }

    @Test
    fun `jede Grammatikregel steht in genau einer Zeile`() {
        // Der GBNF-Parser kennt keine Fortsetzung über Zeilenumbrüche. Eine umgebrochene
        // Regel lässt die ganze Grammatik scheitern — und der Server liefert dann eine
        // leere Antwort statt eines Hinweises auf die Ursache. Genau dieser Fehler ist
        // schon einmal passiert und war von außen kaum zu erkennen.
        RouterLlmProtocol.grammar
            .lines()
            .filter { it.isNotBlank() }
            .forEach { line ->
                assertTrue(
                    line.contains("::="),
                    "Zeile ohne Regelkopf — vermutlich eine umgebrochene Regel: \"$line\"",
                )
            }
    }

    @Test
    fun `der Systemprompt beschreibt jede vergebbare Kategorie`() {
        val prompt = RouterLlmProtocol.systemPrompt
        TaskCategory.entries
            .filter { it != TaskCategory.UNBEKANNT }
            .forEach { assertTrue(prompt.contains(it.name), "${it.name} fehlt im Prompt") }
    }
}
