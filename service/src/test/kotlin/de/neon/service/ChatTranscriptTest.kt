package de.neon.service

import de.neon.inference.GenerationChunk
import de.neon.inference.GenerationRequest
import de.neon.inference.InferenceEngine
import de.neon.inference.ModelFileResolver
import de.neon.inference.ModelLifecycleManager
import de.neon.inference.Role
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
import de.neon.speech.AsrEngine
import de.neon.speech.Transcript
import de.neon.speech.TtsEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Der Chat: Verlauf, Texteingabe und die Frage, wann gesprochen wird.
 *
 * Bis hierher gab es genau einen Einstieg — Mikrofon rein, Sprache raus. Mit der
 * Texteingabe kommt ein zweiter dazu, und mit dem Verlauf ein Zustand, der über einen
 * Durchgang hinaus lebt. Beides sind Stellen, an denen sich Fehler einnisten, ohne dass
 * die alten Tests etwas merken.
 */
class ChatTranscriptTest {

    private class StummeAsr(private val transcript: Transcript?) : AsrEngine {
        override suspend fun transcribe(samples: ShortArray, sampleRate: Int) = transcript
        override fun close() = Unit
    }

    private class MitschreibendeTts : TtsEngine {
        val gesprochen = mutableListOf<String>()
        override val isSpeaking = false
        override suspend fun speak(text: String) { gesprochen += text }
        override fun stop() = Unit
        override fun close() = Unit
    }

    private class AntwortendeEngine(private val tokens: List<String>) : InferenceEngine {
        override var loadedModelId: String? = null
            private set

        var letzteAnfrage: GenerationRequest? = null

        override suspend fun load(model: ModelSpec, file: File): Boolean {
            loadedModelId = model.id
            return true
        }

        override suspend fun unload() { loadedModelId = null }

        override fun generate(request: GenerationRequest): Flow<GenerationChunk> = flow {
            letzteAnfrage = request
            tokens.forEach { emit(GenerationChunk.Token(it)) }
            emit(GenerationChunk.Done(tokens.size, 20.0))
        }
    }

    private val registry = ModelRegistry.defaultForS26Ultra()

    private fun bauen(
        engine: InferenceEngine,
        tts: TtsEngine = MitschreibendeTts(),
        asr: AsrEngine = StummeAsr(Transcript("gesprochene frage", 0.9f, "de-DE")),
        onEntry: ((ChatEntry) -> Unit)? = null,
        attachments: AttachmentRecall? = null,
    ): ConversationOrchestrator {
        val router = Router(
            registry = registry,
            policy = SelectionPolicy(registry),
            routerLlm = RouterLlm {
                RouteAnalysis(
                    category = TaskCategory.WISSENSFRAGE,
                    complexity = 2,
                    confidence = 0.9,
                    source = AnalysisSource.ROUTER_LLM,
                )
            },
        )

        return ConversationOrchestrator(
            router = router,
            asr = asr,
            tts = tts,
            lifecycle = ModelLifecycleManager(engine, ModelFileResolver { File("/dev/null") }),
            engine = engine,
            deviceState = { DeviceState.unknown() },
            actionExecutor = { null },
            outcomeStore = InMemoryRouteOutcomeStore(),
            clock = { 0L },
            onEntry = onEntry,
            attachments = attachments,
        )
    }

    @Test
    fun `eine getippte Frage steht mit ihrer Antwort im Verlauf`() = runTest {
        val engine = AntwortendeEngine(listOf("Der ", "Dom ", "ist ", "hoch."))
        val orchestrator = bauen(engine)

        orchestrator.handleText("wie hoch ist der kölner dom")

        val verlauf = orchestrator.transcript.value
        assertEquals(2, verlauf.size, "erwartet Frage und Antwort: $verlauf")
        assertTrue(verlauf[0].fromUser)
        assertEquals("wie hoch ist der kölner dom", verlauf[0].text)
        assertTrue(!verlauf[1].fromUser)
        assertEquals("Der Dom ist hoch.", verlauf[1].text)
        assertNotNull(verlauf[1].modelId, "die Herkunft der Antwort fehlt")
    }

    @Test
    fun `getipptes wird nicht vorgelesen`() {
        val tts = MitschreibendeTts()
        runTest {
            bauen(AntwortendeEngine(listOf("Antwort.")), tts = tts).handleText("frage")
        }
        assertTrue(tts.gesprochen.isEmpty(), "es wurde gesprochen: ${tts.gesprochen}")
    }

    @Test
    fun `auf Wunsch wird auch Getipptes vorgelesen`() {
        val tts = MitschreibendeTts()
        runTest {
            bauen(AntwortendeEngine(listOf("Antwort.")), tts = tts)
                .handleText("frage", speak = true)
        }
        assertTrue(tts.gesprochen.isNotEmpty(), "es wurde nichts gesprochen")
    }

    @Test
    fun `gesprochenes wird weiterhin vorgelesen`() {
        val tts = MitschreibendeTts()
        runTest {
            bauen(AntwortendeEngine(listOf("Antwort.")), tts = tts)
                .handleUtterance(ShortArray(16))
        }
        assertTrue(tts.gesprochen.isNotEmpty(), "die gesprochene Antwort blieb stumm")
    }

    @Test
    fun `der bisherige Verlauf geht als Vorgeschichte in den Prompt`() = runTest {
        val engine = AntwortendeEngine(listOf("Antwort."))
        val orchestrator = bauen(engine)

        orchestrator.handleText("wie hoch ist der kölner dom")
        orchestrator.handleText("und wie alt ist er")

        val nachrichten = assertNotNull(engine.letzteAnfrage).messages

        // Genau eine Systemnachricht, dann die Vorgeschichte, dann die aktuelle Frage.
        assertEquals(Role.SYSTEM, nachrichten.first().role)
        assertEquals(Role.USER, nachrichten.last().role)
        assertEquals("und wie alt ist er", nachrichten.last().content)

        val texte = nachrichten.map { it.content }
        assertTrue(
            texte.any { it == "wie hoch ist der kölner dom" },
            "die frühere Frage fehlt im Prompt: $texte",
        )
        assertTrue(
            texte.any { it == "Antwort." },
            "die frühere Antwort fehlt im Prompt: $texte",
        )
    }

    @Test
    fun `die aktuelle Frage steht genau einmal im Prompt`() = runTest {
        // Die Frage wandert sofort in den sichtbaren Verlauf, damit man sie beim Absenden
        // sieht. Würde die Vorgeschichte danach gebildet, stünde sie zweimal im Prompt —
        // einmal als Rückblick, einmal als Auftrag. Kleine Modelle antworten darauf
        // erkennbar wirr.
        val engine = AntwortendeEngine(listOf("Antwort."))
        val orchestrator = bauen(engine)

        orchestrator.handleText("die eine frage")

        val texte = assertNotNull(engine.letzteAnfrage).messages.map { it.content }
        assertEquals(1, texte.count { it == "die eine frage" }, "Prompt: $texte")
    }

    @Test
    fun `ein wiederhergestellter Verlauf wird fortgesetzt`() = runTest {
        val engine = AntwortendeEngine(listOf("Antwort."))
        val orchestrator = bauen(engine)

        orchestrator.restoreTranscript(
            listOf(
                ChatEntry(fromUser = true, text = "frage von gestern", timestampMillis = 1),
                ChatEntry(fromUser = false, text = "antwort von gestern", timestampMillis = 2),
            )
        )

        orchestrator.handleText("heutige frage")

        assertEquals(4, orchestrator.transcript.value.size)
        val texte = assertNotNull(engine.letzteAnfrage).messages.map { it.content }
        assertTrue(texte.any { it == "frage von gestern" }, "Prompt: $texte")
    }

    @Test
    fun `jede Zeile wird zum Speichern gemeldet`() = runTest {
        val gemeldet = mutableListOf<ChatEntry>()
        val orchestrator = bauen(AntwortendeEngine(listOf("Antwort.")), onEntry = { gemeldet += it })

        orchestrator.handleText("frage")

        assertEquals(2, gemeldet.size, "gemeldet: $gemeldet")
        assertTrue(gemeldet[0].fromUser)
        assertTrue(!gemeldet[1].fromUser)
    }

    @Test
    fun `der Verlauf laesst sich leeren`() = runTest {
        val orchestrator = bauen(AntwortendeEngine(listOf("Antwort.")))
        orchestrator.handleText("frage")
        orchestrator.clearTranscript()
        assertTrue(orchestrator.transcript.value.isEmpty())
    }

    @Test
    fun `leerer Text loest keinen Durchgang aus`() = runTest {
        val engine = AntwortendeEngine(listOf("Antwort."))
        val orchestrator = bauen(engine)

        assertEquals(null, orchestrator.handleText("   "))
        assertTrue(orchestrator.transcript.value.isEmpty())
        assertEquals(null, engine.letzteAnfrage)
    }

    @Test
    fun `nach einer getippten Frage behauptet Neon nicht zu lauschen`() = runTest {
        // Ohne laufenden Dienst wäre "Hört auf Neon" schlicht falsch — das Mikrofon ist aus.
        val orchestrator = bauen(AntwortendeEngine(listOf("Antwort.")))

        orchestrator.handleText("frage")
        assertEquals(NeonState.GESTOPPT, orchestrator.state.value)

        orchestrator.onIdle()
        orchestrator.handleText("noch eine frage")
        assertEquals(NeonState.LAUSCHEN, orchestrator.state.value)
    }

    @Test
    fun `Fundstellen aus Anhaengen gehen mit Quellenangabe in den Prompt`() = runTest {
        val engine = AntwortendeEngine(listOf("Antwort."))
        val orchestrator = bauen(
            engine,
            attachments = { _, _ ->
                listOf(
                    AttachmentExcerpt("notizen/urlaub.txt:1-3", "Flug nach Lissabon am 14. September."),
                )
            },
        )

        orchestrator.handleText("wann geht mein flug")

        val system = assertNotNull(engine.letzteAnfrage).messages.first().content
        assertTrue(system.contains("notizen/urlaub.txt:1-3"), "Quellenangabe fehlt:\n" + system)
        assertTrue(system.contains("Lissabon"), "der Auszug fehlt:\n" + system)
        // Ohne diese Anweisung nimmt ein kleines Modell den Auszug als eigenes Wissen und
        // dichtet den Rest dazu — mitsamt einer Quellenangabe, die dann stimmt.
        assertTrue(system.contains("Erfinde nichts dazu"), "die Warnung fehlt:\n" + system)
    }

    @Test
    fun `die benutzten Fundstellen sind danach ablesbar`() = runTest {
        val orchestrator = bauen(
            AntwortendeEngine(listOf("Antwort.")),
            attachments = { _, _ -> listOf(AttachmentExcerpt("a/b.txt:5-9", "inhalt")) },
        )

        orchestrator.handleText("frage")
        assertEquals(listOf("a/b.txt:5-9"), orchestrator.lastSources.value)
    }

    @Test
    fun `ohne Anhaenge bleibt der Prompt unveraendert`() = runTest {
        val engine = AntwortendeEngine(listOf("Antwort."))
        bauen(engine, attachments = { _, _ -> emptyList() }).handleText("frage")

        val system = assertNotNull(engine.letzteAnfrage).messages.first().content
        assertTrue(!system.contains("angehängten Dateien"), "leerer Anhangsblock im Prompt:\n" + system)
    }

    @Test
    fun `ein Fehler beim Suchen bricht den Durchgang nicht ab`() = runTest {
        // Eine unlesbare Datenbank darf höchstens die Fundstellen kosten, nicht die Antwort.
        val engine = AntwortendeEngine(listOf("Antwort."))
        val orchestrator = bauen(engine, attachments = { _, _ -> error("Datenbank kaputt") })

        val bericht = orchestrator.handleText("frage")
        assertEquals("Antwort.", bericht?.answer)
    }

    @Test
    fun `der Systemprompt unterscheidet vorgelesen und gelesen`() {
        val gesprochen = NeonPrompts.systemPrompt(spoken = true)
        val gelesen = NeonPrompts.systemPrompt(spoken = false)

        assertTrue(gesprochen.contains("vorgelesen"))
        assertTrue(gesprochen.contains("Keine Aufzählungen"))

        // Beim Lesen sind Aufzählungen und Codeblöcke genau richtig — eine vorgelesene
        // Anweisung dagegen klingt mit Sternchen und Backticks fürchterlich.
        assertTrue(gelesen.contains("Codeblock"))
        assertTrue(!gelesen.contains("Keine Aufzählungen"))
    }
}
