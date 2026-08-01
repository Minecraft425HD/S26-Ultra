package de.neon.service

import de.neon.inference.GenerationChunk
import de.neon.inference.GenerationRequest
import de.neon.inference.InferenceEngine
import de.neon.inference.ModelFileResolver
import de.neon.inference.ModelLifecycleManager
import de.neon.router.AnalysisSource
import de.neon.router.DeviceState
import de.neon.router.InMemoryRouteOutcomeStore
import de.neon.router.ModelRegistry
import de.neon.router.ModelSpec
import de.neon.router.RouteAnalysis
import de.neon.router.Router
import de.neon.router.RouterLlm
import de.neon.router.SelectionPolicy
import de.neon.router.TaskCategory
import de.neon.router.Utterance
import de.neon.speech.AsrEngine
import de.neon.speech.Transcript
import de.neon.speech.TtsEngine
import de.neon.tools.ParameterType
import de.neon.tools.Tool
import de.neon.tools.ToolParameter
import de.neon.tools.ToolRegistry
import de.neon.tools.ToolResult
import de.neon.tools.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Zwei Zusammenstellungen von Werkzeugen, und sie dürfen sich nicht vermischen.
 *
 * **Warum das ein eigener Test ist.** Die Grammatik, die einen Werkzeugaufruf erzwingt,
 * enthält **jedes** angebotene Werkzeug, und die ganze Liste steht im Prompt. `termin` und
 * `nachricht` neben `datei-schreiben` und `python` zu stellen heißt: Ein 4-B-Modell legt bei
 * „was ist die Hauptstadt von Peru" gelegentlich eine Datei an. Es liegt schließlich da.
 *
 * Getrennt zu halten ist billig; den Fehler zu finden wäre teuer, denn er tritt selten auf
 * und sieht wie eine Modellschwäche aus.
 */
class CodeToolsRoutingTest {

    private class FakeAsr(private val text: String) : AsrEngine {
        override suspend fun transcribe(samples: ShortArray, sampleRate: Int) =
            Transcript(text, 0.9f, "de-DE")
        override fun close() = Unit
    }

    private class FakeTts : TtsEngine {
        val spoken = mutableListOf<String>()
        override var isSpeaking = false
            private set
        override suspend fun speak(text: String) { spoken += text }
        override fun stop() = Unit
        override fun close() = Unit
    }

    private class FakeEngine(private val ausgabe: String) : InferenceEngine {
        override var loadedModelId: String? = null
            private set
        var letzterPrompt: String = ""

        override suspend fun load(model: ModelSpec, file: File, projector: File?): Boolean {
            loadedModelId = model.id
            return true
        }

        override suspend fun unload() { loadedModelId = null }

        override fun generate(request: GenerationRequest): Flow<GenerationChunk> = flow {
            letzterPrompt = request.messages.joinToString("\n") { it.content }
            emit(GenerationChunk.Token(ausgabe))
            emit(GenerationChunk.Done(1, 20.0))
        }
    }

    private class NotierendesWerkzeug(name: String, private val antwort: String) : Tool {
        var aufgerufen: Map<String, String>? = null

        override val spec = ToolSpec(
            name = name,
            description = "Attrappe $name",
            parameters = listOf(ToolParameter("wert", ParameterType.STRING, "irgendetwas")),
        )

        override suspend fun execute(arguments: Map<String, String>): ToolResult {
            aufgerufen = arguments
            return ToolResult.Ok(antwort)
        }
    }

    private val registry = ModelRegistry.defaultForS26Ultra()

    private fun aufbau(
        text: String,
        kategorie: TaskCategory,
        engine: FakeEngine,
        tts: FakeTts,
        geraeteWerkzeuge: ToolRegistry? = null,
        codeWerkzeuge: ToolRegistry? = null,
    ): ConversationOrchestrator {
        val lifecycle = ModelLifecycleManager(
            engine = engine,
            resolver = ModelFileResolver { File("/dev/null") },
            memoryBudgetBytes = { 16L * 1024 * 1024 * 1024 },
        )
        val router = Router(
            registry,
            SelectionPolicy(registry),
            routerLlm = RouterLlm {
                RouteAnalysis(
                    category = kategorie,
                    complexity = 2,
                    confidence = 0.9,
                    source = AnalysisSource.ROUTER_LLM,
                )
            },
        )
        return ConversationOrchestrator(
            router = router,
            asr = FakeAsr(text),
            tts = tts,
            lifecycle = lifecycle,
            engine = engine,
            deviceState = { DeviceState.unknown() },
            actionExecutor = { null },
            outcomeStore = InMemoryRouteOutcomeStore(),
            clock = { 0L },
            tools = { geraeteWerkzeuge },
            codeTools = { codeWerkzeuge },
        )
    }

    private val samples = ShortArray(16_000)

    @Test
    fun `eine Programmierfrage erreicht die Programmierwerkzeuge`() = runTest {
        val werkzeug = NotierendesWerkzeug("python", "42")
        val engine = FakeEngine("""{"werkzeug":"python","argumente":{"wert":"6*7"}}""")
        val tts = FakeTts()

        val report = aufbau(
            "rechne mir sechs mal sieben aus",
            TaskCategory.CODE,
            engine, tts,
            codeWerkzeuge = ToolRegistry(listOf(werkzeug)),
        ).handleUtterance(samples)

        assertNotNull(report)
        assertEquals(mapOf("wert" to "6*7"), werkzeug.aufgerufen)
        assertEquals(listOf("42"), tts.spoken)
    }

    /**
     * Werkzeuge, die erst nach dem Aufbau fertig werden, müssen trotzdem ankommen.
     *
     * **Der Fehler, den das festhält, hat die IDE lahmgelegt.** Der Container reichte die
     * Zusammenstellung als Wert herein. Ausgewertet wurde sie genau einmal, beim Bauen — und
     * zu diesem Zeitpunkt packten Python und die Bau-Kette im Hintergrund noch fünfzig
     * Megabyte aus. Die Zusammenstellung enthielt vier Datei-Werkzeuge und blieb für die
     * ganze Laufzeit des Prozesses dabei.
     *
     * Die Folge war kein Fehler, sondern Stille: `app-anlegen`, `app-bauen` und `python`
     * standen weder im Prompt noch in der Grammatik. Das Modell konnte sie nicht wählen,
     * weil es sie für nicht vorhanden hielt. Im Protokoll stand „Bau-Kette bereit" — bereit
     * war sie, angeboten wurde sie nie.
     *
     * Deshalb kommt die Zusammenstellung hier als Funktion, und dieser Test liefert beim
     * Aufbau ausdrücklich `null`.
     */
    @Test
    fun `Werkzeuge, die erst spaeter fertig werden, sind trotzdem benutzbar`() = runTest {
        val werkzeug = NotierendesWerkzeug("app-anlegen", "Projekt angelegt.")
        val engine = FakeEngine(
            """{"werkzeug":"app-anlegen","argumente":{"wert":"de.neon.zaehler"}}"""
        )
        val tts = FakeTts()

        // Beim Aufbau ist noch nichts da — genau wie auf dem Gerät, wo das Auspacken der
        // Bau-Werkzeuge im Hintergrund läuft, während die App schon Fragen annimmt.
        var spaeter: ToolRegistry? = null

        val lifecycle = ModelLifecycleManager(
            engine = engine,
            resolver = ModelFileResolver { File("/dev/null") },
            memoryBudgetBytes = { 16L * 1024 * 1024 * 1024 },
        )
        val orchestrator = ConversationOrchestrator(
            router = Router(
                registry,
                SelectionPolicy(registry),
                routerLlm = RouterLlm {
                    RouteAnalysis(
                        category = TaskCategory.CODE,
                        complexity = 2,
                        confidence = 0.9,
                        source = AnalysisSource.ROUTER_LLM,
                    )
                },
            ),
            asr = FakeAsr("leg mir eine App an"),
            tts = tts,
            lifecycle = lifecycle,
            engine = engine,
            deviceState = { DeviceState.unknown() },
            actionExecutor = { null },
            outcomeStore = InMemoryRouteOutcomeStore(),
            clock = { 0L },
            codeTools = { spaeter },
        )

        // Und jetzt ist das Auspacken durch.
        spaeter = ToolRegistry(listOf(werkzeug))

        val report = orchestrator.handleUtterance(samples)

        assertNotNull(report)
        assertEquals(
            mapOf("wert" to "de.neon.zaehler"),
            werkzeug.aufgerufen,
            "das Werkzeug wurde nicht angeboten — die Zusammenstellung ist wieder eingefroren",
        )
        assertEquals(listOf("Projekt angelegt."), tts.spoken)
    }

    @Test
    fun `eine Geraetehandlung erreicht die Geraetewerkzeuge`() = runTest {
        val geraet = NotierendesWerkzeug("wlan", "WLAN ist an.")
        val engine = FakeEngine("""{"werkzeug":"wlan","argumente":{"wert":"an"}}""")

        val report = aufbau(
            "schalte das wlan an",
            TaskCategory.GERAETE_AKTION,
            engine, FakeTts(),
            geraeteWerkzeuge = ToolRegistry(listOf(geraet)),
        ).handleUtterance(samples)

        assertNotNull(report)
        assertEquals(mapOf("wert" to "an"), geraet.aufgerufen)
    }

    @Test
    fun `die Programmierwerkzeuge stehen nicht im Prompt einer Geraetehandlung`() = runTest {
        val engine = FakeEngine("""{"werkzeug":"wlan","argumente":{"wert":"an"}}""")

        aufbau(
            "schalte das wlan an",
            TaskCategory.GERAETE_AKTION,
            engine, FakeTts(),
            geraeteWerkzeuge = ToolRegistry(listOf(NotierendesWerkzeug("wlan", "an"))),
            codeWerkzeuge = ToolRegistry(listOf(NotierendesWerkzeug("datei-schreiben", "ok"))),
        ).handleUtterance(samples)

        // Der eigentliche Zweck der Trennung: Was nicht im Prompt steht, kann das Modell auch
        // nicht versehentlich aufrufen.
        assertFalse(engine.letzterPrompt.contains("datei-schreiben"), engine.letzterPrompt)
        assertTrue(engine.letzterPrompt.contains("wlan"), engine.letzterPrompt)
    }

    @Test
    fun `und umgekehrt genauso`() = runTest {
        val engine = FakeEngine("""{"werkzeug":"python","argumente":{"wert":"1"}}""")

        aufbau(
            "schreib mir ein skript",
            TaskCategory.CODE,
            engine, FakeTts(),
            geraeteWerkzeuge = ToolRegistry(listOf(NotierendesWerkzeug("termin", "ok"))),
            codeWerkzeuge = ToolRegistry(listOf(NotierendesWerkzeug("python", "1"))),
        ).handleUtterance(samples)

        assertFalse(engine.letzterPrompt.contains("termin"), engine.letzterPrompt)
        assertTrue(engine.letzterPrompt.contains("python"), engine.letzterPrompt)
    }

    @Test
    fun `ohne Programmierwerkzeuge antwortet Neon gewoehnlich`() = runTest {
        // Der Fall, solange die Python-Umgebung nicht eingerichtet ist: Es gibt keine
        // Werkzeuge, und dann soll eine Programmierfrage schlicht beantwortet werden statt
        // an einem fehlenden Werkzeug zu scheitern.
        val engine = FakeEngine("Nimm eine Schleife.")
        val tts = FakeTts()

        val report = aufbau("wie schreibe ich eine schleife", TaskCategory.CODE, engine, tts)
            .handleUtterance(samples)

        assertNotNull(report)
        assertEquals("Nimm eine Schleife.", report.answer)
        assertFalse(engine.letzterPrompt.contains("Verfügbare Werkzeuge"), engine.letzterPrompt)
    }

    @Test
    fun `eine Wissensfrage bekommt gar keine Werkzeuge`() = runTest {
        val engine = FakeEngine("Lima.")

        aufbau(
            "was ist die hauptstadt von peru",
            TaskCategory.WISSENSFRAGE,
            engine, FakeTts(),
            geraeteWerkzeuge = ToolRegistry(listOf(NotierendesWerkzeug("termin", "ok"))),
            codeWerkzeuge = ToolRegistry(listOf(NotierendesWerkzeug("python", "ok"))),
        ).handleUtterance(samples)

        // Werkzeuge im Prompt verleiten kleine Modelle dazu, sie zu benutzen. Bei einer
        // Wissensfrage gehört keines hinein.
        assertFalse(engine.letzterPrompt.contains("Verfügbare Werkzeuge"), engine.letzterPrompt)
    }
}
