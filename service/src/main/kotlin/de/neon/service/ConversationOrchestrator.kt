package de.neon.service

import de.neon.inference.ChatMessage
import de.neon.inference.GenerationChunk
import de.neon.inference.GenerationRequest
import de.neon.inference.InferenceEngine
import de.neon.inference.ModelLifecycleManager
import de.neon.inference.Role
import de.neon.router.DeviceAction
import de.neon.router.DeviceState
import de.neon.router.RouteDecision
import de.neon.router.RouteOutcome
import de.neon.router.RouteAnalysis
import de.neon.router.RouteOutcomeStore
import de.neon.router.Router
import de.neon.router.TaskCategory
import de.neon.router.Utterance
import de.neon.router.UserSignal
import de.neon.speech.AsrEngine
import de.neon.tools.ToolRegistry
import de.neon.tools.ToolResult
import de.neon.speech.SentenceChunker
import de.neon.speech.TtsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Der Zustand, in dem Neon sich gerade befindet. Wird in der Oberfläche angezeigt. */
enum class NeonState {
    GESTOPPT,
    LAUSCHEN,
    GEWECKT,
    ERKENNUNG,
    ROUTING,
    ANTWORT,
    SPRECHEN,
}

/** Was während eines Durchgangs passiert ist — für Oberfläche und Diagnose. */
data class TurnReport(
    val transcript: String,
    val answer: String,
    val modelId: String?,
    val routeReason: String,
    val latencyMs: Long,
    val usedNoModel: Boolean,
)

/**
 * Führt einen Gesprächsdurchgang von den Abtastwerten bis zur gesprochenen Antwort.
 *
 * Alle Abhängigkeiten sind Schnittstellen — deshalb ist der komplette Ablauf mit Attrappen
 * prüfbar, ohne Mikrofon, ohne Modelle und ohne Gerät. Das ist der Grund, warum diese
 * Klasse und nicht der Android-Dienst die Logik enthält.
 */
class ConversationOrchestrator(
    private val router: Router,
    private val asr: AsrEngine,
    private val tts: TtsEngine,
    private val lifecycle: ModelLifecycleManager,
    private val engine: InferenceEngine,
    private val deviceState: () -> DeviceState,
    private val actionExecutor: (DeviceAction) -> String?,
    private val outcomeStore: RouteOutcomeStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Werkzeuge für Handlungen, die die Regelstufe nicht abdeckt. */
    private val tools: ToolRegistry? = null,
    /** Was Neon sich über den Nutzer gemerkt hat. */
    private val memory: MemoryRecall? = null,
    /** Wertet den Verlauf aus und füttert damit Stufe 1 des Routers. */
    private val learner: TurnLearner? = null,
) {

    private val _state = MutableStateFlow(NeonState.GESTOPPT)
    val state: StateFlow<NeonState> = _state.asStateFlow()

    private val _lastTurn = MutableStateFlow<TurnReport?>(null)
    val lastTurn: StateFlow<TurnReport?> = _lastTurn.asStateFlow()

    fun onWakeWord() {
        _state.value = NeonState.GEWECKT
    }

    fun onIdle() {
        _state.value = NeonState.LAUSCHEN
    }

    fun onStopped() {
        _state.value = NeonState.GESTOPPT
    }

    /**
     * Der komplette Durchgang.
     *
     * @return der Bericht, oder `null`, wenn nichts Verwertbares gesagt wurde — das ist bei
     * einem Fehlalarm des Weckworts der Normalfall und kein Fehler.
     */
    suspend fun handleUtterance(samples: ShortArray, hasImage: Boolean = false): TurnReport? {
        val startedAt = clock()

        _state.value = NeonState.ERKENNUNG
        val transcript = asr.transcribe(samples)
        if (transcript == null || transcript.text.isBlank()) {
            _state.value = NeonState.LAUSCHEN
            return null
        }

        // Vor dem Routen: Die neue Äußerung bewertet rückwirkend den vorherigen Durchgang.
        // Ein daraus gelerntes Beispiel kommt damit schon dieser Anfrage zugute.
        learner?.onNewUtterance(transcript.text, clock())

        _state.value = NeonState.ROUTING
        val utterance = Utterance(
            text = transcript.text,
            hasImage = hasImage,
            explicitDeepThinking = wantsDeeperAnswer(transcript.text),
        )
        val decision = router.route(utterance, deviceState())

        val report = when (decision) {
            is RouteDecision.Direct -> handleDirect(decision, transcript.text, startedAt)
            is RouteDecision.Generate -> handleGenerate(decision, utterance, transcript.text, startedAt)
        }

        _state.value = NeonState.LAUSCHEN
        _lastTurn.value = report
        return report
    }

    /** Stufe-0-Treffer: ausführen und antworten, ganz ohne Sprachmodell. */
    private suspend fun handleDirect(
        decision: RouteDecision.Direct,
        transcript: String,
        startedAt: Long,
    ): TurnReport {
        val spoken = actionExecutor(decision.action) ?: FALLBACK_ACTION_FAILED

        _state.value = NeonState.SPRECHEN
        tts.speak(spoken)

        val latency = clock() - startedAt
        record(transcript, decision.analysis, modelId = null, latency = latency, tokens = 0)

        return TurnReport(
            transcript = transcript,
            answer = spoken,
            modelId = null,
            routeReason = "Regelstufe — kein Modell nötig",
            latencyMs = latency,
            usedNoModel = true,
        )
    }

    /** Protokolliert den Durchgang und übergibt ihn der Lernschleife. */
    private fun record(
        transcript: String,
        analysis: RouteAnalysis,
        modelId: String?,
        latency: Long,
        tokens: Int,
    ) {
        val outcome = RouteOutcome(
            utteranceText = transcript,
            analysis = analysis,
            modelId = modelId,
            latencyMs = latency,
            tokensGenerated = tokens,
            // Das Signal steht erst fest, wenn die nächste Äußerung kommt.
            signal = UserSignal.UNBEKANNT,
            timestampMillis = clock(),
        )
        outcomeStore.record(outcome)
        learner?.onTurnCompleted(outcome)
    }

    private suspend fun handleGenerate(
        decision: RouteDecision.Generate,
        utterance: Utterance,
        transcript: String,
        startedAt: Long,
    ): TurnReport {
        val selection = decision.selection

        when (val loaded = lifecycle.ensureLoaded(selection.model)) {
            is ModelLifecycleManager.Result.Ready -> Unit

            is ModelLifecycleManager.Result.Missing -> return speakProblem(
                transcript = transcript,
                message = "Das Modell ${selection.model.displayName} ist noch nicht " +
                    "heruntergeladen. Ich kann die Frage gerade nicht beantworten.",
                selection = selection.reason,
                startedAt = startedAt,
            )

            is ModelLifecycleManager.Result.TooLarge -> return speakProblem(
                transcript = transcript,
                message = "Dafür bräuchte ich ein Modell, das nicht in den Speicher passt.",
                selection = selection.reason,
                startedAt = startedAt,
            )

            is ModelLifecycleManager.Result.Failed -> return speakProblem(
                transcript = transcript,
                message = "Das Modell ließ sich nicht laden: ${loaded.reason}",
                selection = selection.reason,
                startedAt = startedAt,
            )
        }

        _state.value = NeonState.ANTWORT

        // Erinnerungen zur Frage heraussuchen. Nur wenige — jeder Eintrag kostet Kontext,
        // und ein kleines Modell verliert bei zu viel Vorspann den eigentlichen Auftrag.
        val memoryContext = memory?.let {
            runCatching { it.recall(utterance.text, MEMORY_CONTEXT_LIMIT) }.getOrDefault(emptyList())
        } ?: emptyList()

        // Werkzeuge kommen nur ins Spiel, wenn eine Handlung gefragt ist. Sie immer
        // anzubieten würde den Prompt aufblähen und kleine Modelle dazu verleiten, auch
        // Wissensfragen als Werkzeugaufruf zu beantworten.
        val useTools = tools != null &&
            selection.analysis.category == TaskCategory.GERAETE_AKTION

        if (useTools) {
            return handleToolCall(selection, utterance, transcript, memoryContext, startedAt)
        }

        val answer = StringBuilder()
        val pending = StringBuilder()
        var tokens = 0
        var failure: String? = null

        engine.generate(
            GenerationRequest(
                messages = listOf(
                    ChatMessage(Role.SYSTEM, NeonPrompts.systemPrompt(memoryContext)),
                    ChatMessage(Role.USER, utterance.text),
                ),
            )
        ).collect { chunk ->
            when (chunk) {
                is GenerationChunk.Token -> {
                    tokens++
                    answer.append(chunk.text)
                    pending.append(chunk.text)

                    // Sobald ein ganzer Satz steht, wird er gesprochen. Bei rund zwanzig
                    // Token je Sekunde ist das der Unterschied zwischen einem Gespräch und
                    // einer Wartezeit.
                    val chunks = SentenceChunker.chunk(pending.toString())
                    if (chunks.size > 1) {
                        val complete = chunks.dropLast(1)
                        _state.value = NeonState.SPRECHEN
                        complete.forEach { tts.speak(it) }
                        pending.setLength(0)
                        pending.append(chunks.last())
                    }
                }

                is GenerationChunk.Done -> Unit
                is GenerationChunk.Failed -> failure = chunk.reason
            }
        }

        if (failure != null) {
            return speakProblem(transcript, "Da ging etwas schief: $failure", selection.reason, startedAt)
        }

        if (pending.isNotBlank()) {
            _state.value = NeonState.SPRECHEN
            tts.speak(pending.toString())
        }

        val latency = clock() - startedAt
        record(transcript, selection.analysis, selection.model.id, latency, tokens)

        return TurnReport(
            transcript = transcript,
            answer = answer.toString().trim(),
            modelId = selection.model.id,
            routeReason = selection.reason,
            latencyMs = latency,
            usedNoModel = false,
        )
    }

    /**
     * Der Weg für Handlungen, die die Regelstufe nicht abdeckt.
     *
     * Bewusst genau eine Runde: Das Modell gibt einen Werkzeugaufruf aus, der wird
     * ausgeführt, und Neon sagt das Ergebnis. Keine Schleife, in der das Modell auf eigene
     * Faust weitere Werkzeuge aufruft — auf einem Telefon wäre das teuer, schwer
     * nachvollziehbar und im Fehlerfall unangenehm, weil am Ende echte Geräte geschaltet
     * werden.
     */
    private suspend fun handleToolCall(
        selection: de.neon.router.ModelSelection,
        utterance: Utterance,
        transcript: String,
        memoryContext: List<String>,
        startedAt: Long,
    ): TurnReport {
        val registry = tools ?: error("handleToolCall ohne Werkzeuge aufgerufen")

        val raw = StringBuilder()
        var tokens = 0
        var failure: String? = null

        engine.generate(
            GenerationRequest(
                messages = listOf(
                    ChatMessage(Role.SYSTEM, NeonPrompts.systemPrompt(memoryContext, registry.promptDescription())),
                    ChatMessage(Role.USER, utterance.text),
                ),
                maxTokens = TOOL_CALL_MAX_TOKENS,
                temperature = 0f,
                // Erzwungene Grammatik: Auch ein 4B-Modell gibt damit einen gültigen
                // Aufruf aus, statt in Prosa zu beschreiben, was es tun würde.
                grammar = registry.grammar(),
            )
        ).collect { chunk ->
            when (chunk) {
                is GenerationChunk.Token -> {
                    tokens++
                    raw.append(chunk.text)
                }

                is GenerationChunk.Done -> Unit
                is GenerationChunk.Failed -> failure = chunk.reason
            }
        }

        if (failure != null) {
            return speakProblem(transcript, "Da ging etwas schief: $failure", selection.reason, startedAt)
        }

        val call = ToolRegistry.parseCall(raw.toString())
            ?: return speakProblem(
                transcript = transcript,
                message = "Das habe ich nicht als Befehl verstanden.",
                selection = selection.reason,
                startedAt = startedAt,
            )

        val spoken = when (val result = registry.execute(call)) {
            is ToolResult.Ok -> result.spoken
            is ToolResult.Failed -> result.spoken
        }

        _state.value = NeonState.SPRECHEN
        tts.speak(spoken)

        val latency = clock() - startedAt
        record(transcript, selection.analysis, selection.model.id, latency, tokens)

        return TurnReport(
            transcript = transcript,
            answer = spoken,
            modelId = selection.model.id,
            routeReason = "${selection.reason} — Werkzeug ${call.name}",
            latencyMs = latency,
            usedNoModel = false,
        )
    }

    private suspend fun speakProblem(
        transcript: String,
        message: String,
        selection: String,
        startedAt: Long,
    ): TurnReport {
        _state.value = NeonState.SPRECHEN
        tts.speak(message)
        return TurnReport(
            transcript = transcript,
            answer = message,
            modelId = null,
            routeReason = selection,
            latencyMs = clock() - startedAt,
            usedNoModel = true,
        )
    }

    /** Bricht die laufende Ausgabe ab, damit man Neon ins Wort fallen kann. */
    fun interrupt() {
        tts.stop()
        _state.value = NeonState.LAUSCHEN
    }

    private fun wantsDeeperAnswer(text: String): Boolean =
        DEEPER_ANSWER.containsMatchIn(text.lowercase())

    /** Beim Beenden: den letzten offenen Durchgang noch auswerten. */
    fun shutdown() {
        learner?.flush()
        _state.value = NeonState.GESTOPPT
    }

    private companion object {
        const val FALLBACK_ACTION_FAILED = "Das hat leider nicht geklappt."

        /** Mehr Erinnerungen lenken ein kleines Modell eher ab, als dass sie helfen. */
        const val MEMORY_CONTEXT_LIMIT = 3

        /** Ein Werkzeugaufruf ist kurz; die Grenze schützt vor einem entgleisten Modell. */
        const val TOOL_CALL_MAX_TOKENS = 128

        val DEEPER_ANSWER = Regex(
            "(?U)\\b(denk nochmal|denke nochmal|genauer|gründlicher|gruendlicher|" +
                "ausführlicher|ausfuehrlicher|streng dich an)\\b"
        )
    }
}
