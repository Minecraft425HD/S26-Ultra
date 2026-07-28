package de.neon.service

import de.neon.inference.GenerationChunk
import de.neon.inference.GenerationRequest
import de.neon.inference.InferenceEngine
import de.neon.inference.ModelFileResolver
import de.neon.inference.ModelLifecycleManager
import de.neon.router.DeviceAction
import de.neon.router.DeviceState
import de.neon.router.InMemoryRouteOutcomeStore
import de.neon.router.ModelRegistry
import de.neon.router.ModelSpec
import de.neon.router.Router
import de.neon.router.SelectionPolicy
import de.neon.speech.AsrEngine
import de.neon.speech.Transcript
import de.neon.speech.TtsEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Prüft den kompletten Weg von den Abtastwerten bis zur gesprochenen Antwort — mit
 * Attrappen für Erkennung, Ausgabe und Modell. Ohne Gerät, ohne Modelldateien.
 */
class ConversationOrchestratorTest {

    private class FakeAsr(var transcript: Transcript?) : AsrEngine {
        override suspend fun transcribe(samples: ShortArray, sampleRate: Int) = transcript
        override fun close() = Unit
    }

    private class FakeTts : TtsEngine {
        val spoken = mutableListOf<String>()
        var stopCount = 0
        override var isSpeaking = false
            private set

        override suspend fun speak(text: String) {
            spoken += text
        }

        override fun stop() {
            stopCount++
        }

        override fun close() = Unit
    }

    private class FakeEngine(
        private val tokens: List<String> = emptyList(),
        private val failure: String? = null,
    ) : InferenceEngine {
        override var loadedModelId: String? = null
            private set

        override suspend fun load(model: ModelSpec, file: File): Boolean {
            loadedModelId = model.id
            return true
        }

        override suspend fun unload() {
            loadedModelId = null
        }

        override fun generate(request: GenerationRequest): Flow<GenerationChunk> = flow {
            if (failure != null) {
                emit(GenerationChunk.Failed(failure))
                return@flow
            }
            tokens.forEach { emit(GenerationChunk.Token(it)) }
            emit(GenerationChunk.Done(tokens.size, 20.0))
        }
    }

    private val registry = ModelRegistry.defaultForS26Ultra()

    private fun orchestrator(
        asr: AsrEngine,
        tts: TtsEngine,
        engine: InferenceEngine,
        modelsAvailable: Boolean = true,
        actionResult: String? = "Timer läuft.",
        outcomeStore: InMemoryRouteOutcomeStore = InMemoryRouteOutcomeStore(),
        actions: MutableList<DeviceAction> = mutableListOf(),
    ): Pair<ConversationOrchestrator, InMemoryRouteOutcomeStore> {
        val resolver = ModelFileResolver { if (modelsAvailable) File("/dev/null") else null }
        val lifecycle = ModelLifecycleManager(engine, resolver)
        val router = Router(registry, SelectionPolicy(registry))

        return ConversationOrchestrator(
            router = router,
            asr = asr,
            tts = tts,
            lifecycle = lifecycle,
            engine = engine,
            deviceState = { DeviceState.unknown() },
            actionExecutor = { action ->
                actions += action
                actionResult
            },
            outcomeStore = outcomeStore,
            clock = { 0L },
        ) to outcomeStore
    }

    private val samples = ShortArray(16_000)

    @Test
    fun `ein Regelbefehl laeuft ohne jedes Modell durch`() = runTest {
        val tts = FakeTts()
        val engine = FakeEngine(listOf("sollte", " nicht", " laufen"))
        val actions = mutableListOf<DeviceAction>()
        val (orchestrator, store) = orchestrator(
            asr = FakeAsr(Transcript("stell einen timer auf fünf minuten", 0.9f, "de-DE")),
            tts = tts,
            engine = engine,
            actions = actions,
        )

        val report = orchestrator.handleUtterance(samples)

        assertNotNull(report)
        assertTrue(report.usedNoModel)
        assertNull(report.modelId)
        assertEquals(DeviceAction.SetTimer(300), actions.single())
        assertEquals(listOf("Timer läuft."), tts.spoken)
        // Der wichtigste Nachweis: Das Sprachmodell wurde nie angefasst.
        assertNull(engine.loadedModelId)
        assertNull(store.recent(1).single().modelId)
    }

    @Test
    fun `eine echte Frage geht an ein Modell und wird gesprochen`() = runTest {
        val tts = FakeTts()
        val engine = FakeEngine(listOf("Der Eiffelturm ist 330 Meter hoch."))
        val (orchestrator, store) = orchestrator(
            asr = FakeAsr(Transcript("wie hoch ist der eiffelturm", 0.9f, "de-DE")),
            tts = tts,
            engine = engine,
        )

        val report = orchestrator.handleUtterance(samples)

        assertNotNull(report)
        assertFalse(report.usedNoModel)
        assertNotNull(report.modelId)
        assertEquals("Der Eiffelturm ist 330 Meter hoch.", report.answer)
        assertTrue(tts.spoken.isNotEmpty())
        assertEquals(report.modelId, store.recent(1).single().modelId)
    }

    @Test
    fun `spricht schon waehrend die Antwort noch entsteht`() = runTest {
        // Der Unterschied zwischen einem Gespräch und einem Ladebalken: Der erste Satz
        // wird ausgegeben, bevor der zweite überhaupt fertig ist.
        val tts = FakeTts()
        val engine = FakeEngine(
            listOf(
                "Der Eiffelturm ist 330 Meter hoch. ",
                "Er steht in Paris und wurde 1889 eröffnet.",
            )
        )
        val (orchestrator, _) = orchestrator(
            asr = FakeAsr(Transcript("erzähl mir vom eiffelturm", 0.9f, "de-DE")),
            tts = tts,
            engine = engine,
        )

        orchestrator.handleUtterance(samples)

        assertTrue(
            tts.spoken.size >= 2,
            "die Antwort hätte in mehreren Stücken gesprochen werden müssen: ${tts.spoken}",
        )
    }

    @Test
    fun `schweigt bei einem Fehlalarm des Weckworts`() = runTest {
        val tts = FakeTts()
        val (orchestrator, store) = orchestrator(
            asr = FakeAsr(null),
            tts = tts,
            engine = FakeEngine(),
        )

        val report = orchestrator.handleUtterance(samples)

        assertNull(report)
        assertTrue(tts.spoken.isEmpty(), "ein Fehlalarm darf nicht zu einer Ansage führen")
        assertTrue(store.recent(10).isEmpty())
        assertEquals(NeonState.LAUSCHEN, orchestrator.state.value)
    }

    @Test
    fun `sagt Bescheid wenn das Modell nicht heruntergeladen ist`() = runTest {
        val tts = FakeTts()
        val (orchestrator, _) = orchestrator(
            asr = FakeAsr(Transcript("wie hoch ist der eiffelturm", 0.9f, "de-DE")),
            tts = tts,
            engine = FakeEngine(),
            modelsAvailable = false,
        )

        val report = orchestrator.handleUtterance(samples)

        assertNotNull(report)
        assertTrue(report.answer.contains("heruntergeladen"))
        assertTrue(tts.spoken.single().contains("heruntergeladen"))
    }

    @Test
    fun `meldet einen Inferenzfehler statt still zu scheitern`() = runTest {
        val tts = FakeTts()
        val (orchestrator, _) = orchestrator(
            asr = FakeAsr(Transcript("wie hoch ist der eiffelturm", 0.9f, "de-DE")),
            tts = tts,
            engine = FakeEngine(failure = "native Bibliothek fehlt"),
        )

        val report = orchestrator.handleUtterance(samples)

        assertNotNull(report)
        assertTrue(report.answer.contains("native Bibliothek fehlt"))
    }

    @Test
    fun `sagt etwas wenn die Geraetehandlung scheitert`() = runTest {
        val tts = FakeTts()
        val (orchestrator, _) = orchestrator(
            asr = FakeAsr(Transcript("taschenlampe an", 0.9f, "de-DE")),
            tts = tts,
            engine = FakeEngine(),
            actionResult = null,
        )

        val report = orchestrator.handleUtterance(samples)

        assertNotNull(report)
        assertTrue(tts.spoken.single().isNotBlank(), "Neon darf nicht wortlos aufgeben")
    }

    @Test
    fun `erkennt die Bitte um eine gruendlichere Antwort`() = runTest {
        val (orchestrator, store) = orchestrator(
            asr = FakeAsr(Transcript("erklär mir das bitte genauer", 0.9f, "de-DE")),
            tts = FakeTts(),
            engine = FakeEngine(listOf("Gerne.")),
        )

        orchestrator.handleUtterance(samples)

        // Die angehobene Komplexität muss im Protokoll sichtbar sein.
        assertTrue(store.recent(1).single().analysis.complexity >= 4)
    }

    @Test
    fun `bricht die Ausgabe auf Wunsch ab`() {
        val tts = FakeTts()
        val (orchestrator, _) = orchestrator(FakeAsr(null), tts, FakeEngine())

        orchestrator.interrupt()

        assertEquals(1, tts.stopCount)
        assertEquals(NeonState.LAUSCHEN, orchestrator.state.value)
    }

    @Test
    fun `durchlaeuft die Zustaende und endet im Lauschen`() = runTest {
        val (orchestrator, _) = orchestrator(
            asr = FakeAsr(Transcript("wie hoch ist der eiffelturm", 0.9f, "de-DE")),
            tts = FakeTts(),
            engine = FakeEngine(listOf("330 Meter.")),
        )

        assertEquals(NeonState.GESTOPPT, orchestrator.state.value)
        orchestrator.onWakeWord()
        assertEquals(NeonState.GEWECKT, orchestrator.state.value)

        orchestrator.handleUtterance(samples)
        assertEquals(NeonState.LAUSCHEN, orchestrator.state.value)
        assertNotNull(orchestrator.lastTurn.value)
    }
}
