package de.neon.service

import de.neon.inference.GenerationChunk
import de.neon.inference.GenerationRequest
import de.neon.inference.InferenceEngine
import de.neon.inference.ModelFileResolver
import de.neon.inference.ModelLifecycleManager
import de.neon.router.ModelSpec
import de.neon.router.DeviceState
import de.neon.router.InMemoryRouteOutcomeStore
import de.neon.router.ModelRegistry
import de.neon.router.Router
import de.neon.router.SelectionPolicy
import de.neon.speech.AsrEngine
import de.neon.speech.Transcript
import de.neon.speech.TtsEngine
import de.neon.workspace.SourceSelection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * „Was macht das hier?" — mit der markierten Stelle im Prompt.
 *
 * **Warum das mehr ist als ein durchgereichtes Feld.** Wer drei Zeilen mitten aus einer
 * Funktion markiert und fragt, bekommt ohne Zusammenhang eine Antwort über drei Zeilen: Das
 * Modell weiß nicht, in welcher Funktion sie stehen und woher die Variablen kommen. Die
 * Antwort klingt dann richtig und ist es nicht — die unangenehmste Sorte Fehler.
 *
 * Geprüft wird deshalb, was tatsächlich im Prompt landet, und nicht nur, ob der Aufruf
 * durchläuft.
 */
class SelectionPromptTest {

    private class StummeAsr : AsrEngine {
        override suspend fun transcribe(samples: ShortArray, sampleRate: Int): Transcript? = null
        override fun close() = Unit
    }

    private class StummeTts : TtsEngine {
        override var isSpeaking = false
            private set
        override suspend fun speak(text: String) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }

    private class MitschreibendeEngine : InferenceEngine {
        override var loadedModelId: String? = null
            private set
        var prompt: String = ""

        override suspend fun load(model: ModelSpec, file: File, projector: File?): Boolean {
            loadedModelId = model.id
            return true
        }

        override suspend fun unload() { loadedModelId = null }

        override fun generate(request: GenerationRequest): Flow<GenerationChunk> = flow {
            prompt = request.messages.joinToString("\n") { it.content }
            emit(GenerationChunk.Token("Es zählt hoch."))
            emit(GenerationChunk.Done(1, 20.0))
        }
    }

    private val registry = ModelRegistry.defaultForS26Ultra()

    private fun aufbau(engine: InferenceEngine): ConversationOrchestrator {
        val lifecycle = ModelLifecycleManager(
            engine = engine,
            resolver = ModelFileResolver { File("/dev/null") },
            memoryBudgetBytes = { 16L * 1024 * 1024 * 1024 },
        )
        return ConversationOrchestrator(
            router = Router(registry, SelectionPolicy(registry)),
            asr = StummeAsr(),
            tts = StummeTts(),
            lifecycle = lifecycle,
            engine = engine,
            deviceState = { DeviceState.unknown() },
            actionExecutor = { null },
            outcomeStore = InMemoryRouteOutcomeStore(),
            clock = { 0L },
        )
    }

    private val quelle = """
        |fun zaehle(bis: Int) {
        |    var i = 0
        |    while (i < bis) {
        |        i += 1
        |    }
        |}
    """.trimMargin()

    @Test
    fun `die markierte Stelle steht mit Datei und Zeilen im Prompt`() = runTest {
        val engine = MitschreibendeEngine()
        val auswahl = SourceSelection("app/Zaehler.kt", quelle, vonZeile = 3, bisZeile = 5)

        aufbau(engine).handleText("was macht das hier?", selection = auswahl.alsPromptBlock())

        assertTrue(engine.prompt.contains("app/Zaehler.kt"), engine.prompt)
        assertTrue(engine.prompt.contains("Zeilen 3 bis 5"), engine.prompt)
        // Die Markierung ist gekennzeichnet, die Umgebung steht daneben.
        assertTrue(engine.prompt.contains("> 3      while (i < bis) {"), engine.prompt)
        assertTrue(engine.prompt.contains("fun zaehle"), engine.prompt)
    }

    @Test
    fun `die Anweisungen zur Markierung stehen dabei`() = runTest {
        val engine = MitschreibendeEngine()
        val auswahl = SourceSelection("a.kt", quelle, 2, 2)

        aufbau(engine).handleText("und das?", selection = auswahl.alsPromptBlock())

        // Ohne diese beiden Sätze beschreibt ein kleines Modell die ganze Datei und erfindet
        // Code dazu, den es nie gesehen hat.
        assertTrue(engine.prompt.contains("Erkläre nicht die ganze Datei"), engine.prompt)
        assertTrue(engine.prompt.contains("Was du hier nicht siehst"), engine.prompt)
    }

    @Test
    fun `ohne Markierung steht nichts davon im Prompt`() = runTest {
        val engine = MitschreibendeEngine()

        aufbau(engine).handleText("wie hoch ist der Eiffelturm?")

        // Der häufige Fall. Er darf den Prompt nicht um Anweisungen erweitern, die auf nichts
        // zeigen — jede Zeile kostet Kontext, und ein 4-B-Modell verliert bei langem Vorspann
        // den eigentlichen Auftrag.
        assertFalse(engine.prompt.contains("markiert"), engine.prompt)
        assertFalse(engine.prompt.contains("Editor"), engine.prompt)
    }

    @Test
    fun `die Markierung gilt nur fuer ihren Durchgang`() = runTest {
        val engine = MitschreibendeEngine()
        val orchestrator = aufbau(engine)

        orchestrator.handleText("was macht das?", selection = SourceSelection("a.kt", quelle, 2, 2).alsPromptBlock())
        orchestrator.handleText("wie hoch ist der Eiffelturm?")

        // Ein Feld, das über den Durchgang hinaus stehen bleibt, hängt der übernächsten
        // Frage einen Codeausschnitt an, den niemand mehr markiert hat.
        //
        // Die zweite Frage muss eine sein, die das Modell wirklich erreicht: "wie spät ist
        // es" beantwortet die Regelstufe ohne Modell, und dann stünde hier noch der Prompt
        // des ersten Durchgangs — der Test wäre rot, ohne dass etwas kaputt ist.
        assertFalse(engine.prompt.contains("markiert"), engine.prompt)
    }
}
