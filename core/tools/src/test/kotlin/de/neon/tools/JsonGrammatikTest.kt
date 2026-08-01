package de.neon.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Die Grammatik muss Quelltext durchlassen.
 *
 * **Der Fehler, den diese Tests festhalten.** Die Zeichenkettenregel lautete
 * `"\"" [^"]* "\""` — beliebig viele Zeichen, solange keines davon ein Anführungszeichen ist.
 * Damit war `println("hallo")` als Werkzeugargument buchstäblich nicht erzeugbar: Die
 * erzwungene Grammatik ließ das `"` nicht durch. Und ein roher Zeilenumbruch kam zwar durch,
 * ergab aber ungültiges JSON, an dem `parseCall` scheiterte.
 *
 * Zusammen hieß das: Die IDE konnte keine einzige echte Quelldatei schreiben — weder eine
 * Kotlin-Activity noch ein Python-Skript. Auf dem Gerät sah das aus wie „die Projekterstellung
 * funktioniert nicht".
 */
class JsonGrammatikTest {

    private val schreiben = object : Tool {
        override val spec = ToolSpec(
            name = "datei-schreiben",
            description = "Legt eine Datei an.",
            parameters = listOf(
                ToolParameter("pfad", ParameterType.STRING, "Pfad"),
                ToolParameter("inhalt", ParameterType.STRING, "Inhalt", langerInhalt = true),
            ),
        )

        override suspend fun execute(arguments: Map<String, String>) = ToolResult.Ok("ok")
    }

    private val registry = ToolRegistry(listOf(schreiben))

    @Test
    fun `die Zeichenkettenregel verbietet Anfuehrungszeichen nicht mehr`() {
        val grammatik = registry.grammar()

        assertFalse(
            "[^\"]*" in grammatik,
            "die alte Regel ist zurück — damit lässt sich kein Quelltext schreiben:\n$grammatik",
        )
        assertTrue("text-zeichen" in grammatik, grammatik)
    }

    @Test
    fun `die Zeichenkettenregel steht einmal da, nicht je Parameter`() {
        // Die Grammatik ist Teil des Prompts. Zwei Parameter mit identischer, ausgeschriebener
        // Regel wären zwei Zeilen für dieselbe Aussage — und jede kostet Zeit vor dem ersten
        // Wort.
        val grammatik = registry.grammar()
        assertEquals(
            1,
            grammatik.lines().count { it.startsWith("text-zeichen ::=") },
            grammatik,
        )
    }

    @Test
    fun `ein Aufruf mit Anfuehrungszeichen und Umbruechen laesst sich lesen`() {
        // So sieht aus, was ein Modell unter dieser Grammatik erzeugt: Umbrüche als \n,
        // Anführungszeichen als \". Genau das war vorher unmöglich.
        val roh = """
            {"werkzeug":"datei-schreiben","argumente":{"pfad":"src/Main.kt",
            "inhalt":"fun main() {\n    println(\"hallo\")\n}\n"}}
        """.trimIndent().replace("\n", "")

        val call = ToolRegistry.parseCall(roh)
        assertNotNull(call, "der Aufruf ließ sich nicht lesen")
        assertEquals("datei-schreiben", call.name)
        assertEquals("src/Main.kt", call.arguments["pfad"])
        assertEquals(
            "fun main() {\n    println(\"hallo\")\n}\n",
            call.arguments["inhalt"],
            "Fluchtfolgen müssen beim Lesen wieder zu echten Zeichen werden",
        )
    }

    @Test
    fun `ein Werkzeug mit langem Inhalt bekommt die weite Grenze`() {
        // 128 Token reichten für rund 400 Zeichen. Eine MainActivity aus der Vorlage hat
        // 1200 — der Aufruf brach mitten im Inhalt ab, und danach war es kein JSON mehr.
        assertEquals(ToolRegistry.LANGE_GRENZE, registry.maxAntwortToken())
    }

    @Test
    fun `ein Werkzeug ohne langen Inhalt behaelt die enge Grenze`() {
        val kurz = object : Tool {
            override val spec = ToolSpec(
                name = "termin",
                description = "Legt einen Termin an.",
                parameters = listOf(ToolParameter("titel", ParameterType.STRING, "Titel")),
            )

            override suspend fun execute(arguments: Map<String, String>) = ToolResult.Ok("ok")
        }

        assertEquals(ToolRegistry.KURZE_GRENZE, ToolRegistry(listOf(kurz)).maxAntwortToken())
    }
}
