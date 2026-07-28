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
import de.neon.router.RouteOutcomeStore
import de.neon.router.Router
import de.neon.router.Utterance
import de.neon.router.UserSignal
import de.neon.speech.AsrEngine
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
        outcomeStore.record(
            RouteOutcome(
                utteranceText = transcript,
                analysis = decision.analysis,
                modelId = null,
                latencyMs = latency,
                tokensGenerated = 0,
                signal = UserSignal.UNBEKANNT,
                timestampMillis = clock(),
            )
        )

        return TurnReport(
            transcript = transcript,
            answer = spoken,
            modelId = null,
            routeReason = "Regelstufe — kein Modell nötig",
            latencyMs = latency,
            usedNoModel = true,
        )
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
        val answer = StringBuilder()
        val pending = StringBuilder()
        var tokens = 0
        var failure: String? = null

        engine.generate(
            GenerationRequest(
                messages = listOf(
                    ChatMessage(Role.SYSTEM, NeonPrompts.systemPrompt()),
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
        outcomeStore.record(
            RouteOutcome(
                utteranceText = transcript,
                analysis = selection.analysis,
                modelId = selection.model.id,
                latencyMs = latency,
                tokensGenerated = tokens,
                signal = UserSignal.UNBEKANNT,
                timestampMillis = clock(),
            )
        )

        return TurnReport(
            transcript = transcript,
            answer = answer.toString().trim(),
            modelId = selection.model.id,
            routeReason = selection.reason,
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

    private companion object {
        const val FALLBACK_ACTION_FAILED = "Das hat leider nicht geklappt."

        val DEEPER_ANSWER = Regex(
            "(?U)\\b(denk nochmal|denke nochmal|genauer|gründlicher|gruendlicher|" +
                "ausführlicher|ausfuehrlicher|streng dich an)\\b"
        )
    }
}
