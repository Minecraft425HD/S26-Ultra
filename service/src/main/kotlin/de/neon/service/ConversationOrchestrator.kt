package de.neon.service

import de.neon.inference.ChatMessage
import de.neon.inference.ImageAttachment
import de.neon.inference.GenerationChunk
import de.neon.inference.GenerationRequest
import de.neon.inference.InferenceEngine
import de.neon.inference.ModelLifecycleManager
import de.neon.inference.Role
import de.neon.router.Capability
import de.neon.router.DeviceAction
import de.neon.router.DeviceState
import de.neon.router.PortableRegex
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
import de.neon.workspace.gekuerzt
import de.neon.speech.SentenceChunker
import de.neon.speech.TtsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Der Zustand, in dem Neon sich gerade befindet. Wird in der Oberfläche angezeigt. */
enum class NeonState {
    GESTOPPT,
    LAUSCHEN,
    GEWECKT,
    ERKENNUNG,
    ROUTING,
    /**
     * Das Modell wird von der Platte gelesen.
     *
     * Ein eigener Zustand, weil das beim ersten Mal rund eine Minute dauert. Eine Minute
     * lang "Denke nach ..." stehen zu lassen sieht aus wie ein Absturz.
     */
    MODELL_LAEDT,
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
    /**
     * Wie viele Token die Antwort lang war.
     *
     * Zusammen mit [latencyMs] ergibt das die Zahl, an der sich Geschwindigkeit ablesen
     * lässt. Sie gehörte lange nur ins Protokoll — und stand deshalb nicht zur Verfügung,
     * als sich herausstellte, dass Neon mit 0,71 Token je Sekunde antwortete.
     */
    val tokenCount: Int = 0,
)

/**
 * Eine Zeile im sichtbaren Gesprächsverlauf.
 *
 * Getrennt von [TurnReport], weil beide verschiedene Fragen beantworten: Der Bericht sagt,
 * *wie* eine Antwort zustande kam, der Verlauf, *was* gesagt wurde. Für die Oberfläche
 * zählt der Verlauf, für die Diagnose der Bericht.
 */
data class ChatEntry(
    val fromUser: Boolean,
    val text: String,
    val timestampMillis: Long,
    /** Nur bei Neons Antworten gefüllt, für die kleine Zeile unter der Blase. */
    val modelId: String? = null,
    val routeReason: String? = null,
    val latencyMs: Long = 0,
    /** Für die Geschwindigkeitsangabe unter der Blase. Siehe [TurnReport.tokenCount]. */
    val tokenCount: Int = 0,
    /**
     * Eine Mitteilung von Neon über sich selbst, kein Gesprächsbeitrag.
     *
     * Etwa der Hinweis, dass das Modell gerade geladen wird. Sichtbar soll er sein — er
     * erklärt schließlich die Wartezeit —, aber er gehört **nicht** in den Prompt: Ein
     * Modell, das seine eigenen Statusmeldungen als frühere Antworten vorgesetzt bekommt,
     * fängt an, sie nachzuahmen.
     */
    val notice: Boolean = false,
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
    /**
     * Werkzeuge für Handlungen, die die Regelstufe nicht abdeckt — Termin, Nachricht.
     *
     * **Eine Funktion und kein Wert.** Siehe [codeTools] — dort steht, was ein
     * Schnappschuss angerichtet hat. Hier gäbe es das Problem heute nicht, aber zwei
     * Werkzeuglisten mit zwei verschiedenen Regeln sind eine Regel zu viel.
     */
    private val tools: () -> ToolRegistry? = { null },
    /**
     * Werkzeuge fürs Programmieren: Dateien lesen und ändern, Python ausführen.
     *
     * **Warum getrennt von [tools] und nicht alles in einem Topf.** Die Grammatik, die einen
     * Werkzeugaufruf erzwingt, enthält **jedes** angebotene Werkzeug, und die ganze Liste
     * steht im Prompt. Termin und Nachricht neben `datei-schreiben` und `python` zu stellen
     * heißt: Ein 4-B-Modell wählt bei „was ist die Hauptstadt von Peru" gelegentlich
     * `datei-schreiben`. Es liegt schließlich da.
     *
     * Welche Zusammenstellung dran ist, entscheidet die Kategorie: `GERAETE_AKTION` bekommt
     * die eine, `CODE` die andere. Beides gleichzeitig gibt es nicht.
     *
     * **Warum eine Funktion und kein Wert — der Fehler, der die IDE lahmgelegt hat.** Hier
     * stand `ToolRegistry?`, und der Container reichte den Wert beim Bauen herein. Zu diesem
     * Zeitpunkt waren Python und die Bau-Kette aber noch nicht eingerichtet: Beide packen
     * knapp fünfzig Megabyte aus und laufen deshalb im Hintergrund, während die App schon
     * startet. Die Zusammenstellung enthielt in diesem Moment vier Datei-Werkzeuge — und
     * blieb dabei, für die gesamte Laufzeit des Prozesses.
     *
     * Die Folge war nicht etwa ein Fehler, sondern Stille: `app-anlegen`, `app-bauen` und
     * `python` standen weder im Prompt noch in der Grammatik, also konnte das Modell sie
     * nicht wählen. Auf „leg mir eine App an" griff es zum nächstbesten Werkzeug, das
     * dalag. Im Protokoll stand `Bau-Kette bereit` — die Kette war bereit, sie wurde nur
     * niemandem angeboten.
     *
     * Als Funktion wird bei jedem Durchgang neu gefragt. Was fertig eingerichtet ist, ist
     * dann auch benutzbar.
     */
    private val codeTools: () -> ToolRegistry? = { null },
    /** Was Neon sich über den Nutzer gemerkt hat. */
    private val memory: MemoryRecall? = null,
    /**
     * Sucht in den angehängten Dateien die Stellen, die zur Frage passen.
     *
     * Der Kern der Entscheidung, Anhänge nicht dauerhaft im Kontext zu halten: Gefragt wird
     * erst, wenn die Frage feststeht — dann geht nur das Passende in den Prompt.
     */
    private val attachments: AttachmentRecall? = null,
    /** Wertet den Verlauf aus und füttert damit Stufe 1 des Routers. */
    private val learner: TurnLearner? = null,
    /**
     * Wie viele frühere Zeilen dem Modell mitgegeben werden.
     *
     * Bewusst knapp. Jede Zeile kostet Kontext, und ein 4B-Modell verliert bei langem
     * Vorspann den eigentlichen Auftrag — dieselbe Überlegung wie beim Gedächtnis.
     */
    private val historyLimit: Int = DEFAULT_HISTORY_LIMIT,
    /** Bekommt jede Zeile des Verlaufs, damit sie einen Neustart überdauern kann. */
    private val onEntry: ((ChatEntry) -> Unit)? = null,
    /**
     * Wohin Meldungen über einen Durchgang gehen.
     *
     * Eine Funktion und nicht `NeonLog`: Diese Klasse ist absichtlich frei von Android, damit
     * der ganze Ablauf mit Attrappen prüfbar bleibt — ohne Mikrofon, ohne Modelle, ohne
     * Gerät. Ein direkter Aufruf von `android.util.Log` hat genau das gebrochen, und
     * dreiundzwanzig Tests haben es sofort gemeldet.
     *
     * Der Container hängt `NeonLog` daran. In Tests bleibt es still.
     */
    private val log: (String) -> Unit = {},
) {

    /**
     * Wie weit das Laden des Modells ist.
     *
     * `null`, solange nichts geladen wird. Der Grund für dieses Feld ist ein Fehler, der
     * hier gemacht wurde: Die Startfrist wurde auf fünfeinhalb Minuten angehoben, ohne dass
     * in dieser Zeit irgendetwas zu sehen gewesen wäre. Wer nach zwei Minuten aufgab, sah
     * weder Antwort noch Fehler — und konnte nicht wissen, ob überhaupt etwas passiert.
     */
    private val _loading = MutableStateFlow<LoadingStatus?>(null)
    val loading: StateFlow<LoadingStatus?> = _loading.asStateFlow()

    /** Meldet den Ladefortschritt weiter. Wird vom Container an den Supervisor gehängt. */
    fun onServerProgress(elapsedMillis: Long, budgetMillis: Long, lastLine: String?) {
        _loading.value = LoadingStatus(elapsedMillis, budgetMillis, lastLine)
    }

    private val _state = MutableStateFlow(NeonState.GESTOPPT)
    val state: StateFlow<NeonState> = _state.asStateFlow()

    private val _lastTurn = MutableStateFlow<TurnReport?>(null)
    val lastTurn: StateFlow<TurnReport?> = _lastTurn.asStateFlow()

    /**
     * Welche Fundstellen die letzte Antwort benutzt hat.
     *
     * Sichtbar zu machen, was in den Prompt ging, ist der Unterschied zwischen einer
     * Antwort, der man glauben muss, und einer, die man nachschlagen kann.
     */
    private val _lastSources = MutableStateFlow<List<String>>(emptyList())
    val lastSources: StateFlow<List<String>> = _lastSources.asStateFlow()

    /** Der sichtbare Gesprächsverlauf, älteste Zeile zuerst. */
    private val _transcript = MutableStateFlow<List<ChatEntry>>(emptyList())
    val transcript: StateFlow<List<ChatEntry>> = _transcript.asStateFlow()

    /**
     * Ob die Antwort dieses Durchgangs gesprochen wird.
     *
     * Als Feld und nicht als Parameter, weil sonst durch fünf Funktionen durchgereicht
     * werden müsste, was eine Eigenschaft des ganzen Durchgangs ist. Es läuft immer nur
     * ein Durchgang — der Dienst ruft nacheinander auf.
     */
    @Volatile
    private var speakThisTurn: Boolean = true

    /**
     * Der markierte Abschnitt dieses Durchgangs, falls es einen gibt.
     *
     * Als Feld und aus demselben Grund wie [speakThisTurn]: Es ist eine Eigenschaft des
     * ganzen Durchgangs und müsste sonst durch fünf Funktionen durchgereicht werden. Es läuft
     * immer nur ein Durchgang — dafür sorgt die Sperre.
     */
    @Volatile
    private var selectionThisTurn: String? = null

    /**
     * Der Verlauf, wie er *vor* dieser Frage aussah.
     *
     * Muss festgehalten werden, bevor die neue Frage in den Verlauf wandert — sonst stünde
     * sie zweimal im Prompt: einmal als Vorgeschichte und einmal als eigentliche Frage.
     * Die Frage sofort anzuzeigen ist trotzdem richtig, niemand will erst nach der Antwort
     * sehen, was er gefragt hat.
     */
    @Volatile
    private var turnHistory: List<ChatMessage> = emptyList()

    /**
     * Genau ein Durchgang zur Zeit.
     *
     * Seit es zwei Einstiege gibt — Weckwort und Tastatur —, können zwei Durchgänge
     * zusammentreffen: Man tippt gerade, und das Weckwort löst aus. Ohne Sperre liefen
     * beide durch denselben Server, überschrieben sich gegenseitig `speakThisTurn` und
     * schrieben ihre Zeilen verschränkt in den Verlauf.
     */
    private val turnLock = Mutex()

    /**
     * Ob die Hörschleife läuft.
     *
     * Nötig, seit ein Durchgang auch getippt ausgelöst werden kann: Danach in den Zustand
     * „hört auf Neon" zurückzufallen wäre schlicht gelogen, wenn gar kein Dienst läuft.
     */
    @Volatile
    private var listening: Boolean = false

    private val idleState: NeonState
        get() = if (listening) NeonState.LAUSCHEN else NeonState.GESTOPPT

    /** Stellt einen gespeicherten Verlauf wieder her, etwa nach einem Neustart. */
    fun restoreTranscript(entries: List<ChatEntry>) {
        _transcript.value = entries
    }

    fun clearTranscript() {
        _transcript.value = emptyList()
    }

    private fun append(entry: ChatEntry) {
        _transcript.value = _transcript.value + entry

        // Hinweise werden nicht gespeichert. Ein „das Modell wird geladen" von letzter Woche
        // hat im Verlauf nichts verloren — und käme beim Wiederherstellen als gewöhnliche
        // Antwort zurück, also geradewegs in den Prompt.
        if (!entry.notice) onEntry?.invoke(entry)
    }

    /** Spricht nur, wenn dieser Durchgang gesprochen werden soll. */
    private suspend fun say(text: String) {
        if (!speakThisTurn) return
        _state.value = NeonState.SPRECHEN
        tts.speak(text)
    }

    /**
     * Die letzten Zeilen als Nachrichten für das Modell.
     *
     * Ohne das beantwortet Neon jede Frage für sich allein — „und wie hoch ist der?" nach
     * einer Frage zum Kölner Dom ginge ins Leere.
     */
    private fun historyMessages(): List<ChatMessage> =
        _transcript.value
            .filterNot { it.notice }
            .takeLast(historyLimit)
            .map { ChatMessage(if (it.fromUser) Role.USER else Role.ASSISTANT, it.text) }

    fun onWakeWord() {
        listening = true
        _state.value = NeonState.GEWECKT
    }

    fun onIdle() {
        listening = true
        _state.value = NeonState.LAUSCHEN
    }

    fun onStopped() {
        listening = false
        _state.value = NeonState.GESTOPPT
    }

    /**
     * Der komplette Durchgang.
     *
     * @return der Bericht, oder `null`, wenn nichts Verwertbares gesagt wurde — das ist bei
     * einem Fehlalarm des Weckworts der Normalfall und kein Fehler.
     */
    suspend fun handleUtterance(samples: ShortArray, hasImage: Boolean = false): TurnReport? {
        _state.value = NeonState.ERKENNUNG
        val transcript = asr.transcribe(samples)
        if (transcript == null || transcript.text.isBlank()) {
            _state.value = idleState
            return null
        }

        // Gesprochenes wird auch gesprochen beantwortet — sonst müsste man aufs Telefon
        // sehen, um zu erfahren, was Neon geantwortet hat.
        return handle(transcript.text, images = emptyList(), speak = true, hasImage = hasImage)
    }

    /**
     * Derselbe Durchgang, nur getippt.
     *
     * Die Antwort bleibt standardmäßig still: Wer tippt, sieht auf den Bildschirm, und ein
     * Telefon, das beim Schreiben lospredigt, ist in fremder Gesellschaft unbrauchbar.
     */
    suspend fun handleText(
        text: String,
        images: List<ImageAttachment> = emptyList(),
        speak: Boolean = false,
        /**
         * Der im Editor markierte Abschnitt, falls die Frage sich darauf bezieht.
         *
         * Fertig aufbereitet hereingereicht statt als Datei und Zeilenbereich: Das Zuschneiden
         * ist reine Textarbeit, sie liegt in `SourceSelection` und ist dort ohne Android
         * geprüft. Diese Klasse soll nicht wissen, wie ein Ausschnitt aussieht.
         */
        selection: String? = null,
    ): TurnReport? {
        if (text.isBlank()) return null
        return handle(text, images = images, speak = speak, selection = selection)
    }

    /**
     * Der gemeinsame Teil ab dem Routen.
     *
     * Sprache und Text gehen bewusst denselben Weg: Routing, Gedächtnis, Werkzeuge und
     * Lernschleife sollen sich nicht danach unterscheiden, wie die Frage hereinkam — sonst
     * gäbe es zwei Verhaltensweisen, von denen nur eine geprüft wird.
     */
    private suspend fun handle(
        text: String,
        images: List<ImageAttachment>,
        speak: Boolean,
        hasImage: Boolean = images.isNotEmpty(),
        selection: String? = null,
    ): TurnReport = turnLock.withLock {
        val startedAt = clock()
        speakThisTurn = speak
        selectionThisTurn = selection
        turnHistory = historyMessages()

        append(ChatEntry(fromUser = true, text = text, timestampMillis = startedAt))

        // Vor dem Routen: Die neue Äußerung bewertet rückwirkend den vorherigen Durchgang.
        // Ein daraus gelerntes Beispiel kommt damit schon dieser Anfrage zugute.
        learner?.onNewUtterance(text, clock())

        _state.value = NeonState.ROUTING
        val utterance = Utterance(
            text = text,
            hasImage = hasImage,
            explicitDeepThinking = wantsDeeperAnswer(text),
        )
        val decision = router.route(utterance, deviceState())

        val report = when (decision) {
            is RouteDecision.Direct -> handleDirect(decision, text, startedAt)
            is RouteDecision.Generate -> handleGenerate(decision, utterance, text, startedAt, images)
        }

        append(
            ChatEntry(
                fromUser = false,
                text = report.answer,
                timestampMillis = clock(),
                modelId = report.modelId,
                routeReason = report.routeReason,
                latencyMs = report.latencyMs,
                tokenCount = report.tokenCount,
            )
        )

        _state.value = idleState
        _lastTurn.value = report
        report
    }

    /** Stufe-0-Treffer: ausführen und antworten, ganz ohne Sprachmodell. */
    private suspend fun handleDirect(
        decision: RouteDecision.Direct,
        transcript: String,
        startedAt: Long,
    ): TurnReport {
        val spoken = actionExecutor(decision.action) ?: FALLBACK_ACTION_FAILED

        say(spoken)

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
        images: List<ImageAttachment> = emptyList(),
    ): TurnReport {
        val selection = decision.selection

        // Nur ansagen, wenn wirklich geladen wird. Liegt das Modell schon im Speicher,
        // waere der Hinweis eine Luege und das Flackern stoerend.
        val laedt = lifecycle.loadedModelId != selection.model.id
        if (laedt) {
            _state.value = NeonState.MODELL_LAEDT
            _loading.value = LoadingStatus(0, 0, null)

            // Eine Blase, keine bloße Statuszeile: Sie bleibt im Verlauf stehen und erklärt
            // auch hinterher noch, warum es so lange gedauert hat.
            append(
                ChatEntry(
                    fromUser = false,
                    text = "Ich lese das Modell zum ersten Mal von der Platte. Das dauert " +
                        "auf diesem Gerät etwa eine Minute — danach geht es schnell.",
                    timestampMillis = clock(),
                    routeReason = "Hinweis",
                    notice = true,
                )
            )
        }

        val loadResult = lifecycle.ensureLoaded(selection.model)
        _loading.value = null

        when (val loaded = loadResult) {
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

        val attachmentContext = attachments?.let {
            runCatching { it.recall(utterance.text, ATTACHMENT_CONTEXT_LIMIT) }
                .getOrDefault(emptyList())
        } ?: emptyList()
        _lastSources.value = attachmentContext.map { it.source }

        // Werkzeuge kommen nur ins Spiel, wenn sie zur Frage passen. Sie immer anzubieten
        // würde den Prompt aufblähen und kleine Modelle dazu verleiten, auch Wissensfragen
        // als Werkzeugaufruf zu beantworten.
        //
        // Und immer nur **eine** Zusammenstellung: Die erzwungene Grammatik enthält jedes
        // angebotene Werkzeug, und ein 4-B-Modell, dem `datei-schreiben` neben `termin`
        // angeboten wird, greift irgendwann daneben.
        val passendeWerkzeuge = when (selection.analysis.category) {
            TaskCategory.GERAETE_AKTION -> tools()
            TaskCategory.CODE -> codeTools()
            else -> null
        }

        if (passendeWerkzeuge != null) {
            return handleToolCall(
                selection, utterance, transcript, memoryContext, startedAt, passendeWerkzeuge,
            )
        }

        val answer = StringBuilder()
        val pending = StringBuilder()
        var tokens = 0
        // Wie viel der gefilterten Antwort schon ans Sprechen weitergegeben wurde.
        var gesprochen = 0
        var failure: String? = null
        var failureDetail: String? = null

        engine.generate(
            GenerationRequest(
                messages = buildList {
                    add(
                        ChatMessage(
                            Role.SYSTEM,
                            NeonPrompts.systemPrompt(
                                memoryContext = memoryContext,
                                spoken = speakThisTurn,
                                attachmentContext = attachmentContext.map { it.asPromptBlock() },
                                selection = selectionThisTurn,
                            ),
                        )
                    )
                    addAll(turnHistory)
                    // Bilder nur, wenn das gewählte Modell sie ansehen kann. Sie einem
                    // Textmodell zu schicken beantwortet llama-server mit einem Fehler —
                    // und der Nutzer bekäme statt einer Antwort eine Fehlmeldung, obwohl
                    // die Texterkennung längst gelesen hat, was im Bild steht.
                    val bilder = if (selection.model.supports(Capability.VISION)) images
                    else emptyList()
                    add(ChatMessage(Role.USER, utterance.text, bilder))
                },
            )
        ).collect { chunk ->
            when (chunk) {
                is GenerationChunk.Token -> {
                    tokens++
                    answer.append(chunk.text)

                    // Gesprochen wird nur, was nach dem Filtern übrig bleibt.
                    //
                    // Ein Denkblock wird dabei zurückgehalten, bis er geschlossen ist:
                    // Solange `<think>` offen steht, liefert der Filter nichts, und Neon
                    // schweigt statt seine Überlegungen vorzulesen. Deshalb wird über den
                    // gesamten bisherigen Text gefiltert und mitgezählt, wie viel davon
                    // schon gesprochen wurde — nicht über das letzte Stück, das für sich
                    // genommen nicht verrät, ob es in einem Block steckt.
                    val sichtbar = ThinkingFilter.strip(answer.toString())
                    if (sichtbar.length > gesprochen) {
                        pending.append(sichtbar, gesprochen, sichtbar.length)
                        gesprochen = sichtbar.length
                    }

                    // Sobald ein ganzer Satz steht, wird er gesprochen. Bei rund zwanzig
                    // Token je Sekunde ist das der Unterschied zwischen einem Gespräch und
                    // einer Wartezeit.
                    if (speakThisTurn) {
                        val chunks = SentenceChunker.chunk(pending.toString())
                        if (chunks.size > 1) {
                            chunks.dropLast(1).forEach { say(it) }
                            pending.setLength(0)
                            pending.append(chunks.last())
                        }
                    }
                }

                is GenerationChunk.Done -> Unit
                is GenerationChunk.Failed -> {
                    failure = chunk.reason
                    failureDetail = chunk.detail
                }
            }
        }

        failure?.let { grund ->
            return abbruch(
                transcript, selection, tokens, grund, failureDetail, startedAt,
                weg = "Antwort",
            )
        }

        if (pending.isNotBlank()) say(pending.toString())

        val latency = clock() - startedAt
        record(transcript, selection.analysis, selection.model.id, latency, tokens)

        val sichtbareAntwort = ThinkingFilter.strip(answer.toString())

        // Der Fall, der auf dem Gerät wie "die Frage wurde nicht beantwortet" aussah: Der
        // Server rechnet, meldet Token, beendet sauber — und übrig bleibt nichts, weil alles
        // Überlegung war. Eine leere Sprechblase erklärt das nicht; dieser Satz tut es.
        if (sichtbareAntwort.isBlank()) {
            log(
                "Antwort war nach dem Filtern leer — $tokens Token, $latency ms, " +
                    "Modell ${selection.model.id}"
            )
            return speakProblem(
                transcript,
                "Ich habe nachgedacht, aber keine Antwort zustande gebracht. Frag noch einmal.",
                selection.reason,
                startedAt,
            )
        }

        log(
            "Antwort fertig — ${selection.model.id}, $tokens Token, $latency ms, " +
                "${sichtbareAntwort.length} Zeichen"
        )

        return TurnReport(
            transcript = transcript,
            answer = sichtbareAntwort,
            modelId = selection.model.id,
            routeReason = selection.reason,
            latencyMs = latency,
            usedNoModel = false,
            tokenCount = tokens,
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
        /**
         * Die Zusammenstellung, die zu dieser Frage passt.
         *
         * Hereingereicht statt hier ausgewählt: Die Entscheidung fällt an der Kategorie, und
         * die kennt nur der Aufrufer. Ein `tools ?: error(...)` stand hier vorher und hätte
         * nach dem Hinzukommen der Programmierwerkzeuge stillschweigend die falsche
         * Zusammenstellung genommen.
         */
        registry: ToolRegistry,
    ): TurnReport {
        val raw = StringBuilder()
        var tokens = 0
        var failure: String? = null
        var failureDetail: String? = null

        engine.generate(
            GenerationRequest(
                messages = buildList {
                    add(
                        ChatMessage(
                            Role.SYSTEM,
                            NeonPrompts.systemPrompt(
                                memoryContext = memoryContext,
                                toolDescription = registry.promptDescription(),
                                spoken = speakThisTurn,
                                // Auch hier: "ändere die markierte Stelle" ist ein
                                // Werkzeugaufruf, und ohne den Ausschnitt wüsste das Modell
                                // nicht, welche Stelle gemeint ist.
                                selection = selectionThisTurn,
                            ),
                        )
                    )
                    addAll(turnHistory)
                    add(ChatMessage(Role.USER, utterance.text))
                },
                // Die Grenze kommt von den Werkzeugen und nicht von einer Konstante hier.
                // Bei festen 128 Token brach jeder `datei-schreiben`-Aufruf nach rund 400
                // Zeichen mitten im Inhalt ab; danach war es kein JSON mehr, und der Nutzer
                // hörte „Das habe ich nicht als Befehl verstanden" — bei einem Aufruf, den
                // das Modell völlig richtig begonnen hatte.
                maxTokens = registry.maxAntwortToken(),
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
                is GenerationChunk.Failed -> {
                    failure = chunk.reason
                    failureDetail = chunk.detail
                }
            }
        }

        failure?.let { grund ->
            return abbruch(
                transcript, selection, tokens, grund, failureDetail, startedAt,
                weg = "Werkzeugaufruf",
            )
        }

        val call = ToolRegistry.parseCall(raw.toString())
        if (call == null) {
            // **Mit der Rohausgabe.** Ohne sie stand hier nur, dass etwas nicht verstanden
            // wurde — und die eigentliche Frage, *was* das Modell denn ausgegeben hat, war
            // nicht zu beantworten. Genau daran ließ sich der abgeschnittene Aufruf nicht
            // erkennen: Ein bei 128 Token gekapptes JSON sieht in der Sprechblase aus wie
            // ein Modell, das den Auftrag nicht verstanden hat.
            log(
                "Werkzeugaufruf unlesbar — ${selection.model.id}, $tokens Token, " +
                    "${registry.specs.size} Werkzeuge angeboten, Rohausgabe: " +
                    raw.toString().gekuerzt(ROHAUSGABE_IM_PROTOKOLL)
            )
            return speakProblem(
                transcript = transcript,
                message = "Das habe ich nicht als Befehl verstanden.",
                selection = selection.reason,
                startedAt = startedAt,
            )
        }

        val vorAusfuehrung = clock()
        val result = registry.execute(call)
        val ausgefuehrt = clock() - vorAusfuehrung

        // **Jeder Werkzeugaufruf hinterlässt eine Zeile.** Hier stand keine, und deshalb war
        // von der gescheiterten Projekterstellung im Protokoll nichts zu sehen: nicht welches
        // Werkzeug gewählt wurde, nicht mit welchen Angaben, nicht was dabei herauskam. Das
        // Protokoll endete beim `print_timing` des Servers und schwieg über alles danach.
        //
        // Die Argumente gekürzt, aber vorhanden: Ein `datei-schreiben` mit dem falschen Pfad
        // sieht im Ergebnis genauso aus wie eines mit dem richtigen.
        val ergebnis = when (result) {
            is ToolResult.Ok -> "gelungen"
            is ToolResult.Failed -> "gescheitert — ${result.reason}"
        }
        log(
            "Werkzeug ${call.name} $ergebnis — ${ausgefuehrt} ms, " +
                "${selection.model.id}, $tokens Token für den Aufruf" +
                call.arguments.entries.joinToString("") { (name, wert) ->
                    " · $name=${wert.gekuerzt(ARGUMENT_IM_PROTOKOLL)}"
                }
        )

        val spoken = when (result) {
            is ToolResult.Ok -> result.spoken
            is ToolResult.Failed -> result.spoken
        }

        say(spoken)

        val latency = clock() - startedAt
        record(transcript, selection.analysis, selection.model.id, latency, tokens)

        return TurnReport(
            transcript = transcript,
            answer = spoken,
            modelId = selection.model.id,
            routeReason = "${selection.reason} — Werkzeug ${call.name}",
            latencyMs = latency,
            usedNoModel = false,
            tokenCount = tokens,
        )
    }

    /**
     * Eine Antwort ist mitten im Strom abgebrochen.
     *
     * **Warum das eine eigene Stelle ist.** Hier stand zweimal derselbe Einzeiler:
     * `speakProblem(transcript, "Da ging etwas schief: $failure", …)`. Damit landete die
     * Rohmeldung von OkHttp in der Sprechblase und **nirgends im Protokoll** — gemeldet wurde
     * `unexpected end of stream on http://127.0.0.1:18080/`, und in der Protokolldatei stand
     * dazu keine Zeile. Ein Fehler, der sich nicht wiederfinden lässt, ist einer, über den
     * man nur raten kann.
     *
     * Die Zahl der bis dahin angekommenen Token gehört dazu: Null Token heißt, es ging schon
     * beim Rechnen des Prompts schief; vierzig heißen, es lief und brach dann ab. Das sind
     * zwei verschiedene Fehler.
     *
     * Und [weg] sagt, welcher der beiden Wege es war. Auf dem Gerät standen zwei Abbrüche
     * fünfzig Sekunden auseinander, und die Zeilen ließen sich nicht unterscheiden: Der eine
     * kam von einem Werkzeugaufruf mit erzwungener Grammatik, der andere von einer
     * gewöhnlichen Antwort. Das ist derselbe Unterschied wie zwischen zwei Krankheiten mit
     * demselben Fieber.
     *
     * Ausdrücklich **kein** neuer Versuch. Solange die Ursache nicht feststeht, bedeutet ein
     * Wiederholen bei Speichermangel: dasselbe Modell, derselbe Kontext, dasselbe Ende — nur
     * doppelt so spät sichtbar.
     */
    private suspend fun abbruch(
        transcript: String,
        selection: de.neon.router.ModelSelection,
        tokens: Int,
        failure: String,
        detail: String?,
        startedAt: Long,
        weg: String,
    ): TurnReport {
        log(
            "Antwort abgebrochen — $weg, ${selection.model.id}, $tokens Token bis dahin, " +
                "${clock() - startedAt} ms: $failure" + detail?.let { " · $it" }.orEmpty()
        )
        return speakProblem(
            transcript = transcript,
            message = "Da ging etwas schief: $failure",
            selection = selection.reason,
            startedAt = startedAt,
        )
    }

    private suspend fun speakProblem(
        transcript: String,
        message: String,
        selection: String,
        startedAt: Long,
    ): TurnReport {
        say(message)
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
        _state.value = idleState
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

        /** Drei Wechselreden. Genug für Rückbezüge, wenig genug, um nicht abzulenken. */
        const val DEFAULT_HISTORY_LIMIT = 6

        /**
         * So viele Fundstellen gehen höchstens in den Prompt.
         *
         * Fünf Abschnitte à rund 400 Wörter sind grob 2700 Token — bei einem Fenster von
         * 16384 gut ein Sechstel. Mehr Stellen bringen selten mehr Antwort, kosten aber
         * sicher mehr Zeit, und ab einer gewissen Menge verliert ein kleines Modell den
         * Faden zwischen den Auszügen.
         */
        const val ATTACHMENT_CONTEXT_LIMIT = 5

        /** Ein Werkzeugaufruf ist kurz; die Grenze schützt vor einem entgleisten Modell. */
        /**
         * Wie viel eines Arguments ins Protokoll geht.
         *
         * Genug, um einen Pfad und den Anfang eines Inhalts zu erkennen — und wenig genug,
         * dass eine geschriebene Datei die Protokolldatei nicht zweimal enthält.
         */
        const val ARGUMENT_IM_PROTOKOLL = 120

        /**
         * Wie viel einer unlesbaren Rohausgabe ins Protokoll geht.
         *
         * Großzügiger als ein Argument: Hier ist die Rohausgabe das ganze Beweismaterial.
         * Ob ein Aufruf abgeschnitten wurde oder von vornherein Prosa war, sieht man erst
         * am Ende der Zeichenkette.
         */
        const val ROHAUSGABE_IM_PROTOKOLL = 300

        // Über PortableRegex, nicht über Regex: Die Wortgrenze muss auf dem Telefon
        // dasselbe bedeuten wie im Test, und das eingebettete Unicode-Flag, das hier
        // einmal stand, lässt Android gar nicht erst starten.
        val DEEPER_ANSWER = PortableRegex.compile(
            "\\b(denk nochmal|denke nochmal|genauer|gründlicher|gruendlicher|" +
                "ausführlicher|ausfuehrlicher|streng dich an)\\b"
        )
    }
}
