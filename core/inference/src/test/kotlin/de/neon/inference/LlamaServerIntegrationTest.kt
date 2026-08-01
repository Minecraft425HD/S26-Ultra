package de.neon.inference

import de.neon.router.Capability
import de.neon.router.ModelRole
import de.neon.tools.ParameterType
import de.neon.tools.Tool
import de.neon.tools.ToolParameter
import de.neon.tools.ToolRegistry
import de.neon.tools.ToolResult
import de.neon.tools.ToolSpec
import de.neon.router.ModelSpec
import de.neon.router.RouterLlmProtocol
import de.neon.router.TaskCategory
import de.neon.router.Utterance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Prüft die Inferenzschicht gegen einen **echt laufenden** `llama-server`.
 *
 * Das ist der Test, den es für die alte JNI-Brücke nie geben konnte: Streaming, erzwungene
 * Grammatik und Abbruch werden hier gegen ein echtes Modell geprüft, nicht gegen eine
 * Attrappe. Damit verschiebt sich die erste echte Erprobung der Antworterzeugung vom
 * Telefon des Nutzers in diesen Testlauf.
 *
 * Der Test überspringt sich selbst, wenn Server oder Modell fehlen — auf einer Maschine
 * ohne beides soll `./gradlew test` trotzdem grün durchlaufen. Fehlt beides in der
 * Entwicklungsumgebung, sagt die Ausgabe das ausdrücklich, damit ein übersprungener Test
 * nicht als bestandener durchgeht.
 */
class LlamaServerIntegrationTest {

    private var process: Process? = null
    private var baseUrl: String? = null

    private val serverBinary = File(SERVER_PATH)
    private val modelFile = File(MODEL_PATH)

    private val available: Boolean
        get() = serverBinary.canExecute() && modelFile.isFile

    private val testModel = ModelSpec(
        id = "test",
        displayName = "Testmodell",
        role = ModelRole.ALLTAG,
        sizeBytes = modelFile.length().coerceAtLeast(1),
        capabilities = setOf(Capability.TEXT),
        tokensPerSecond = 30.0,
        loadCostMillis = 500,
        energyPerToken = 1.0,
    )

    @BeforeTest
    fun startServer() {
        if (!available) {
            println(
                "ÜBERSPRUNGEN: llama-server (${serverBinary.absolutePath}) oder Modell " +
                    "(${modelFile.absolutePath}) nicht vorhanden."
            )
            return
        }

        val port = freePort()
        val started = ProcessBuilder(
            serverBinary.absolutePath,
            "--model", modelFile.absolutePath,
            "--alias", "test",
            "--host", "127.0.0.1",
            "--port", port.toString(),
            "--ctx-size", "2048",
            // Bewusst sparsam: Debug- und Release-Variante laufen parallel und starten je
            // einen Server. Mit vier Threads je Instanz überlastet das den Rechner, und der
            // Test scheitert an einer Zeitüberschreitung statt an einem echten Fehler.
            "--threads", "2",
            // Genau die Schalter, mit denen ProcessServerSupervisor startet. Sie hier
            // wegzulassen hieße, etwas anderes zu prüfen als das, was auf dem Telefon
            // läuft — und ein Server, der eine Einstellung ablehnt, fiele erst dort auf.
            "--cache-type-k", ProcessServerSupervisor.KV_CACHE_TYPE,
            "--cache-type-v", ProcessServerSupervisor.KV_CACHE_TYPE,
            "--jinja",
            "--no-webui",
        ).redirectErrorStream(true).start()

        // Ausgabe abziehen, sonst blockiert der Server, wenn sein Puffer voll läuft.
        Thread { runCatching { started.inputStream.bufferedReader().forEachLine { } } }
            .apply { isDaemon = true }
            .start()

        process = started
        val url = "http://127.0.0.1:$port"
        val client = LlamaServerClient(url)

        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            check(started.isAlive) { "llama-server hat sich beim Start beendet" }
            if (client.isHealthy()) {
                baseUrl = url
                return
            }
            Thread.sleep(250)
        }
        error("llama-server war nicht rechtzeitig bereit")
    }

    @AfterTest
    fun stopServer() {
        process?.let {
            it.destroy()
            if (!it.waitFor(5, TimeUnit.SECONDS)) it.destroyForcibly()
        }
        process = null
    }

    private fun engine(): LlamaServerEngine? {
        val url = baseUrl ?: return null
        return LlamaServerEngine(RunningServerSupervisor(url))
    }

    @Test
    fun `erzeugt eine Antwort und meldet die Geschwindigkeit`() = runBlocking {
        val engine = engine() ?: return@runBlocking

        assertTrue(engine.load(testModel, modelFile))
        assertEquals("test", engine.loadedModelId)

        val chunks = withTimeout(TIMEOUT_MILLIS) {
            engine.generate(
                GenerationRequest(
                    messages = listOf(ChatMessage(Role.USER, "Sag genau ein Wort.")),
                    maxTokens = 24,
                    temperature = 0f,
                )
            ).toList()
        }

        val text = chunks.filterIsInstance<GenerationChunk.Token>().joinToString("") { it.text }
        assertTrue(text.isNotBlank(), "keine Token empfangen: $chunks")

        // Der erste Datensatz eines Stroms trägt nur die Rolle und ein leeres Inhaltsfeld.
        // Wird dessen JSON-Null als Zeichenkette gelesen, spricht Neon jede Antwort mit
        // einem "null" an — genau das ist hier schon einmal passiert.
        assertTrue(
            !text.startsWith("null"),
            "die Antwort beginnt mit einer als Text gelesenen JSON-Null: \"$text\"",
        )

        val done = chunks.filterIsInstance<GenerationChunk.Done>().singleOrNull()
        assertNotNull(done, "Abschlussmeldung fehlt: $chunks")
        assertTrue(done.tokenCount > 0)
        assertTrue(done.tokensPerSecond > 0)
        println("Durchsatz im Test: %.1f Token/s".format(done.tokensPerSecond))
    }

    @Test
    fun `kommt Stueck fuer Stueck statt am Stueck`() = runBlocking {
        val engine = engine() ?: return@runBlocking
        engine.load(testModel, modelFile)

        val chunks = withTimeout(TIMEOUT_MILLIS) {
            engine.generate(
                GenerationRequest(
                    messages = listOf(ChatMessage(Role.USER, "Zähle von eins bis zehn.")),
                    maxTokens = 40,
                    temperature = 0f,
                )
            ).toList()
        }

        // Darauf beruht, dass Neon zu sprechen beginnt, bevor die Antwort fertig ist.
        val tokens = chunks.filterIsInstance<GenerationChunk.Token>()
        assertTrue(tokens.size > 1, "die Antwort kam in einem Stück: ${tokens.size} Token")
    }

    @Test
    fun `die Grammatik erzwingt eine auswertbare Einordnung`() = runBlocking {
        val engine = engine() ?: return@runBlocking
        engine.load(testModel, modelFile)

        // Genau der Weg, den Stufe 2 des Routers geht. Ein 135M-Modell würde ohne Grammatik
        // nie eine brauchbare Einordnung liefern — mit Grammatik kann es gar nicht anders.
        //
        // **Die Form hat sich geändert, der Zweck nicht.** Hier wurde JSON erzwungen:
        // `{"kategorie":"CODE","komplexitaet":3,"braucht_web":false,…}`, rund 45 Token, von
        // denen die Grammatik über dreißig ohnehin festlegte. Das half nichts — ein
        // erzwungenes Token kostet denselben Rechendurchgang wie ein frei gewähltes. Jetzt
        // ist es eine Zeile aus sechs bis acht Token.
        val chunks = withTimeout(TIMEOUT_MILLIS) {
            engine.generate(
                GenerationRequest(
                    messages = listOf(
                        ChatMessage(Role.SYSTEM, RouterLlmProtocol.systemPrompt),
                        ChatMessage(Role.USER, RouterLlmProtocol.userPrompt(Utterance("hallo neon"))),
                    ),
                    maxTokens = 64,
                    temperature = 0f,
                    grammar = RouterLlmProtocol.grammar,
                )
            ).toList()
        }

        val raw = chunks.filterIsInstance<GenerationChunk.Token>().joinToString("") { it.text }
        val token = chunks.count { it is GenerationChunk.Token }
        println("Grammatik-Ausgabe: \"$raw\" ($token Token)")

        val analysis = RouterLlmProtocol.parse(raw)
        assertNotNull(analysis, "die Ausgabe ließ sich nicht auswerten: $raw")
        assertTrue(analysis.category in TaskCategory.entries)
        assertTrue(analysis.complexity in 1..5)

        // Die eigentliche Ersparnis, an einem echten Tokenizer gemessen statt geschätzt.
        // Auf dem Gerät kostete jedes Token der Einordnung 65 ms.
        assertTrue(
            token <= 12,
            "die Einordnung braucht $token Token — die kompakte Form soll unter zwölf " +
                "bleiben: \"$raw\"",
        )
    }

    @Test
    fun `meldet einen Fehler statt still zu scheitern wenn kein Modell geladen ist`() = runBlocking {
        val engine = engine() ?: return@runBlocking

        val chunks = engine.generate(
            GenerationRequest(messages = listOf(ChatMessage(Role.USER, "Hallo")))
        ).toList()

        val failed = chunks.filterIsInstance<GenerationChunk.Failed>().singleOrNull()
        assertNotNull(failed)
        assertTrue(failed.reason.contains("Modell"))
    }

    @Test
    fun `ein toter Server fuehrt zu einer Fehlermeldung statt zum Haenger`() = runBlocking {
        if (!available) return@runBlocking

        // Zeigt auf einen Port, auf dem nichts lauscht.
        val engine = LlamaServerEngine(RunningServerSupervisor("http://127.0.0.1:${freePort()}"))
        assertTrue(!engine.load(testModel, modelFile), "ein toter Server darf nicht als geladen gelten")

        val chunks = withTimeout(TIMEOUT_MILLIS) {
            engine.generate(
                GenerationRequest(messages = listOf(ChatMessage(Role.USER, "Hallo")))
            ).toList()
        }
        assertTrue(chunks.any { it is GenerationChunk.Failed })
    }

    @Test
    fun `der Server nimmt den komprimierten Schluessel-Wert-Speicher an`() = runBlocking {
        // Der Server startet in startServer() bereits mit q8_0 für Schlüssel und Wert. Läuft
        // dieser Test durch, ist das belegt — und zwar nicht daran, dass der Prozess lebt,
        // sondern daran, dass eine vollständige Antwort herauskommt. Manche Kombinationen
        // von Cache-Typ und Beschleuniger scheitern erst beim Rechnen, nicht beim Start.
        val engine = engine() ?: return@runBlocking
        assertTrue(engine.load(testModel, modelFile))

        val chunks = withTimeout(TIMEOUT_MILLIS) {
            engine.generate(
                GenerationRequest(
                    messages = listOf(ChatMessage(Role.USER, "Zähle von eins bis fünf.")),
                    maxTokens = 32,
                    temperature = 0f,
                )
            ).toList()
        }

        val text = chunks.filterIsInstance<GenerationChunk.Token>().joinToString("") { it.text }
        assertTrue(text.isNotBlank(), "keine Antwort mit komprimiertem Cache: ${'$'}chunks")
        assertTrue(chunks.none { it is GenerationChunk.Failed }, "Fehler: ${'$'}chunks")
    }

    @Test
    fun `Einbettungen kommen als Vektor zurueck`() = runBlocking {
        val url = baseUrl ?: return@runBlocking
        val client = LlamaServerClient(url)

        // Ein normales Sprachmodell liefert hier je nach Serverbetriebsart einen Vektor
        // oder nichts. Beides ist zulässig — geprüft wird, dass der Aufruf nicht abstürzt
        // und ein gültiger Vektor auch als solcher ankommt.
        val vector = client.embed("test", "Wie hoch ist der Kölner Dom?")
        if (vector == null) {
            println("HINWEIS: Server liefert keine Einbettungen (ohne --embeddings gestartet)")
        } else {
            assertTrue(vector.isNotEmpty())
            assertTrue(vector.any { it != 0f }, "Nullvektor zurückbekommen")
            println("Einbettung mit ${vector.size} Dimensionen")
        }
    }

    /**
     * llama.cpp muss die Grammatik annehmen, die Neon erzeugt — und Quelltext durchlassen.
     *
     * **Zwei Fehler, die genau hier hätten auffallen müssen.** Der erste war ein Parameter
     * namens `tage_ab_heute`: Der Unterstrich beendet einen GBNF-Regelnamen, und damit war
     * nicht eine Regel kaputt, sondern die ganze Grammatik. Jeder Werkzeugaufruf endete mit
     * HTTP 400, gleich um welches Werkzeug es ging.
     *
     * Der zweite war die Zeichenkettenregel `"\"" [^"]* "\""`. Sie ist gültig — llama.cpp
     * nimmt sie widerspruchslos an — und verbietet dem Modell trotzdem jedes
     * Anführungszeichen im Inhalt. Damit ließ sich keine Zeile Kotlin und kein Python
     * schreiben. Der Fehler war also nicht „Grammatik ungültig", sondern „Grammatik erlaubt
     * das Nötige nicht", und ein Test auf HTTP 200 allein hätte ihn durchgelassen.
     *
     * Deshalb prüft dieser Test beides: dass der Server die Grammatik annimmt **und** dass
     * das Ergebnis Fluchtfolgen enthalten darf. Kein Kotlin-Test kann das beantworten; das
     * entscheidet der Parser in llama.cpp.
     */
    @Test
    fun `llama-cpp nimmt die erzeugte Grammatik an und laesst Fluchtfolgen zu`() = runBlocking {
        val url = baseUrl ?: return@runBlocking
        val client = LlamaServerClient(url)

        val registry = ToolRegistry(
            listOf(
                object : Tool {
                    override val spec = ToolSpec(
                        name = "datei-schreiben",
                        description = "Legt eine Datei an.",
                        parameters = listOf(
                            ToolParameter("pfad", ParameterType.STRING, "Pfad"),
                            ToolParameter(
                                "inhalt", ParameterType.STRING, "Inhalt", langerInhalt = true,
                            ),
                        ),
                    )

                    override suspend fun execute(arguments: Map<String, String>) =
                        ToolResult.Ok("ok")
                },
            )
        )

        // Eine Engine für beide Abschnitte. Eine zweite hätte kein geladenes Modell und
        // lieferte einen Fehlschlag statt Token — was hier wie eine kaputte Grammatik
        // aussähe und keine wäre.
        val engine = engine() ?: return@runBlocking
        assertTrue(engine.load(testModel, modelFile))

        val chunks = withTimeout(TIMEOUT_MILLIS) {
            engine.generate(
                GenerationRequest(
                    messages = listOf(
                        ChatMessage(Role.USER, "Lege src/Main.kt an."),
                    ),
                    maxTokens = registry.maxAntwortToken(),
                    temperature = 0f,
                    grammar = registry.grammar(),
                )
            ).toList()
        }

        val gescheitert = chunks.filterIsInstance<GenerationChunk.Failed>().firstOrNull()
        assertTrue(
            gescheitert == null,
            "llama.cpp hat die Grammatik abgelehnt: ${gescheitert?.reason} " +
                "${gescheitert?.detail}\n\n${registry.grammar()}",
        )

        val roh = chunks.filterIsInstance<GenerationChunk.Token>().joinToString("") { it.text }
        val call = ToolRegistry.parseCall(roh)
        assertNotNull(call, "keine lesbare Ausgabe: $roh")
        assertEquals("datei-schreiben", call.name)

        // Und die zweite Hälfte: eine Grammatik, in der **nur** Fluchtfolgen ableitbar sind.
        // Kommt hier etwas heraus, lässt die Regel sie zu. Mit der alten Regel wäre schon
        // das Anführungszeichen unmöglich gewesen.
        val nurEscapes = withTimeout(TIMEOUT_MILLIS) {
            engine.generate(
                GenerationRequest(
                    messages = listOf(ChatMessage(Role.USER, "Gib eine Zeichenkette aus.")),
                    maxTokens = 32,
                    temperature = 0f,
                    grammar = "root ::= \"\\\"\" escape escape \"\\\"\"\n" +
                        "escape ::= \"\\\\\" [\"\\\\/bfnrt]\n",
                )
            ).toList()
        }
        val escapeText = nurEscapes.filterIsInstance<GenerationChunk.Token>()
            .joinToString("") { it.text }
        assertTrue(
            escapeText.length >= 4 && escapeText.startsWith("\""),
            "Fluchtfolgen sind nicht ableitbar: $escapeText",
        )
        println("Fluchtfolgen erzeugt: $escapeText")
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        /** Wird von `scripts/build-llama-server.sh` erzeugt. */
        val SERVER_PATH: String =
            System.getenv("NEON_TEST_SERVER") ?: "/opt/llama.cpp/build-x64/bin/llama-server"

        val MODEL_PATH: String =
            System.getenv("NEON_TEST_MODEL") ?: "/opt/testmodels/test.gguf"

        const val TIMEOUT_MILLIS = 180_000L
    }
}
