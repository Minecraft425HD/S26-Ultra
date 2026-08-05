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
import de.neon.tools.Fertig
import de.neon.tools.Rueckfrage
import de.neon.tools.ToolRegistry
import de.neon.tools.WorkspaceToolset
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
    /**
     * Ob im aktiven Projekt schon eine Android-App liegt.
     *
     * Nur für [Zielklaerung] gebraucht: Wer in einem Android-Projekt „schreib mir das noch
     * dazu" sagt, meint dieses Projekt und soll nicht nach der Sprache gefragt werden.
     */
    private val projektIstAndroid: () -> Boolean = { false },
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
     * Der Auftrag, zu dem Neon gerade eine Rückfrage gestellt hat.
     *
     * `null`, solange keine offen ist. Die nächste Äußerung wird damit zusammengesetzt und
     * das Feld wieder geleert — auch dann, wenn die Antwort mit der Frage nichts zu tun hat.
     * Das ist Absicht: Wer auf „Android oder Python?" mit „wie spät ist es" antwortet, hat
     * das Thema gewechselt, und eine Frage, die drei Durchgänge lang nachhängt, ist
     * schlimmer als eine, die verfällt.
     */
    private var offeneFrage: String? = null

    /** Wie oft zu diesem Auftrag schon nachgefragt wurde. Siehe [Zielklaerung.MAX_RUECKFRAGEN]. */
    private var rueckfragen: Int = 0

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

        // **Die Antwort auf eine Rückfrage gehört zum ursprünglichen Auftrag.**
        //
        // Ohne das ist die Rückfrage schlimmer als nutzlos: Auf „Android" allein folgt eine
        // neue Einordnung, und „Android" ist für sich genommen keine Programmieraufgabe —
        // die Werkzeugkette liefe gar nicht erst an, und der Auftrag wäre verloren.
        val offen = offeneFrage
        offeneFrage = null
        val auftrag = if (offen != null) {
            Zielklaerung.zusammengefuegt(offen, text).also {
                log("Rückfrage beantwortet, Auftrag wieder zusammengesetzt: $it")
            }
        } else {
            text
        }
        if (offen == null) rueckfragen = 0

        // **Fragen, bevor irgendetwas erzeugt wird.**
        //
        // Nicht das Modell entscheidet das, sondern eine Regel — siehe [Zielklaerung]. Das
        // Werkzeug `rueckfrage` gab es seit Tagen: an erster Stelle in der Liste, mit der
        // Gabelung in der Beschreibung, mit derselben Regel im Systemprompt. Auf
        // „programmiere eine QR-Generierungs-App" hat Neon trotzdem ungefragt ein
        // Python-Skript geschrieben. Ein 1.7-B-Modell trifft diese Wahl nicht; man kann ihm
        // anbieten zu fragen, sich darauf verlassen kann man nicht.
        //
        // Vor dem Routen, also **ohne eine einzige Erzeugung**: Die Frage ist ein fester
        // Satz. Sie kostet keinen Serverstart, kein Token und keine Wartezeit.
        // **Auch nach der Antwort geprüft, und das ist der Punkt.** Auf dem Gerät kam auf
        // „Android oder Python?" der ursprüngliche Auftrag ein zweites Mal zurück — und Neon
        // hat das kommentarlos als Antwort genommen und ohne Sprache weitergearbeitet. Eine
        // Frage, die man auch dadurch beantworten kann, dass man sie übergeht, ist keine.
        //
        // Zweimal, dann nicht mehr: Ein Assistent, der dieselbe Frage dreimal stellt, ist
        // kaputt.
        if (Zielklaerung.brauchtSprachfrage(auftrag, projektIstAndroid()) &&
            rueckfragen < Zielklaerung.MAX_RUECKFRAGEN
        ) {
            // Der **ursprüngliche** Auftrag wird gemerkt, nicht der zusammengesetzte. Sonst
            // wächst der Text mit jeder Nachfrage, und die Einordnung wandert mit.
            offeneFrage = offen ?: auftrag
            rueckfragen++
            val frage = if (rueckfragen == 1) {
                Zielklaerung.FRAGE_SPRACHE
            } else {
                Zielklaerung.FRAGE_NOCHMAL
            }
            log("Rückfrage $rueckfragen nach der Sprache: „${offeneFrage}\"")
            return@withLock rueckfrageBericht(frage, startedAt)
        }

        // Vor dem Routen: Die neue Äußerung bewertet rückwirkend den vorherigen Durchgang.
        // Ein daraus gelerntes Beispiel kommt damit schon dieser Anfrage zugute.
        learner?.onNewUtterance(auftrag, clock())

        _state.value = NeonState.ROUTING
        val utterance = Utterance(
            text = auftrag,
            hasImage = hasImage,
            explicitDeepThinking = wantsDeeperAnswer(auftrag),
            // **Ein Bauauftrag ist eine Programmieraufgabe — ohne Abstimmung.**
            //
            // Auf „programmiere eine QR-Generierungs-App für Android" hat Neon einen Aufsatz
            // geschrieben: eine Erklärung, eine Java-Klasse in einem Codeblock, ein Layout in
            // XML, am Ende der Hinweis, man bräuchte noch eine Bibliothek. Kein Projekt,
            // nichts Übersetzbares.
            //
            // Dass die Werkzeugkette dabei nie angelaufen ist, beweist die Antwort selbst: In
            // der Kette erzwingt Neon eine Grammatik, die ausschließlich Werkzeugaufrufe
            // zulässt — Prosa ist dort nicht erzeugbar. Die Einordnung hat den Satz also nicht
            // als CODE erkannt, und ein Modell, das nie gefragt wird, kann auch nicht richtig
            // antworten.
            //
            // Gilt auch nach einer beantworteten Rückfrage: „Android" für sich genommen ist
            // keine Programmieraufgabe, der Auftrag dahinter schon.
            bekannteKategorie = if (offen != null || Zielklaerung.istBauauftrag(auftrag)) {
                TaskCategory.CODE
            } else {
                null
            },
        )
        val decision = router.route(utterance, deviceState())

        val report = when (decision) {
            is RouteDecision.Direct -> handleDirect(decision, auftrag, startedAt)
            is RouteDecision.Generate ->
                handleGenerate(decision, utterance, auftrag, startedAt, images)
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
            // Wie viele Runden erlaubt sind, hängt daran, **was** getan wird. Die
            // Programmierwerkzeuge dürfen ketten: „leg das Projekt an und bau es" sind zwei
            // Handlungen, und mit einer Runde fiel die zweite still unter den Tisch. Die
            // Gerätewerkzeuge bleiben bei einer — dort werden am Ende echte Geräte
            // geschaltet, und ein Modell, das aus eigenem Antrieb nachlegt, ist etwas
            // anderes als eines, das eine zweite Datei schreibt.
            val runden = if (selection.analysis.category == TaskCategory.CODE) {
                WorkspaceToolset.RUNDEN
            } else {
                1
            }
            return handleToolCall(
                selection, utterance, transcript, memoryContext, startedAt,
                passendeWerkzeuge,
                // Die Zusammenstellung **je Runde** neu, nicht einmal für den Durchgang.
                // Siehe die Begründung an `werkzeugeJetzt` in `handleToolCall`.
                werkzeugeJetzt = when (selection.analysis.category) {
                    TaskCategory.GERAETE_AKTION -> tools
                    else -> codeTools
                },
                rundenGrenze = runden,
            )
        }

        val answer = StringBuilder()
        val pending = StringBuilder()
        var tokens = 0
        // `null`, bis das erste Token da ist. Davor liegt die Verarbeitung des Prompts, und
        // die dauert legitim lange — bei 1228 Token auf dem 4-B-Modell zwanzig Sekunden. Als
        // Abstand gezählt hätte das aus jeder zweiten Antwort einen gemeldeten Stillstand
        // gemacht, und eine Warnung, die immer angeht, liest bald niemand mehr.
        var letztesToken: Long? = null
        var laengsterAbstand = 0L
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
                maxTokens = ANTWORT_MAX_TOKENS,
            )
        ).collect { chunk ->
            when (chunk) {
                is GenerationChunk.Token -> {
                    tokens++
                    // **Wie lange nichts kam.** Am Gerät blieb eine Antwort mitten im Strom
                    // stehen: ein einziges Token in sechseinhalb Minuten, danach lief sie
                    // mit elf Token je Sekunde weiter, als sei nichts gewesen. Am Ende
                    // meldete der Durchgang 556 Sekunden und der Server 213 — die Differenz
                    // war der Stillstand, und niemand konnte sie benennen.
                    //
                    // Das ist keine Drosselung; Hitze bremst allmählich. So sieht es aus,
                    // wenn Android den Prozess einfriert. Gemessen wird deshalb der größte
                    // Abstand zwischen zwei Token, nicht der Durchschnitt: Ein Mittelwert
                    // verteilt sechs Minuten Stillstand über tausend Token und macht daraus
                    // eine unauffällige Verlangsamung.
                    val jetzt = clock()
                    letztesToken?.let { vorher ->
                        val abstand = jetzt - vorher
                        if (abstand > laengsterAbstand) laengsterAbstand = abstand
                    }
                    letztesToken = jetzt

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
                "${sichtbareAntwort.length} Zeichen · " + geraetelage() +
                stillstand(laengsterAbstand)
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
        /**
         * Dieselbe Zusammenstellung, aber jederzeit neu erfragbar.
         *
         * **Der Fehler, den das behebt, ist derselbe wie beim Container — eine Ebene
         * tiefer.** Ein Werkzeug wird nur angeboten, wenn es gerade gelingen kann;
         * `app-bauen` also erst, wenn ein Manifest im Projekt liegt. Geprüft wurde das aber
         * einmal, vor der ersten Runde.
         *
         * Auf dem Gerät sah das so aus: Runde 1 scheiterte am Paketnamen, Runde 2 legte das
         * Projekt an — und Runde 3 rief `fertig`, ohne zu bauen. `app-bauen` stand nicht zur
         * Wahl, weil beim Zusammenstellen noch kein Manifest da war. Die Kette hatte die
         * Voraussetzung für ihren zweiten Schritt gerade selbst geschaffen und durfte ihn
         * trotzdem nicht tun.
         *
         * Genau darum geht es bei einer Kette: Jede Runde verändert den Zustand, auf den die
         * nächste sich stützt. Eine Werkzeugliste, die diesen Zustand einmal am Anfang liest,
         * ist für Ketten unbrauchbar.
         */
        werkzeugeJetzt: () -> ToolRegistry?,
        /**
         * Wie viele Werkzeuge Neon für diesen Auftrag hintereinander benutzen darf.
         *
         * **Eins war zu wenig, und zwar sichtbar.** Auf „mach mir eine Zähler-App und
         * erstelle das Projekt, danach direkt kompilieren" legte Neon das Projekt an und
         * hörte auf — der zweite Halbsatz fiel stillschweigend unter den Tisch. Dasselbe bei
         * jedem „lies die Datei, ändere sie, prüf das Ergebnis".
         *
         * **Und mehr als eins ist nicht überall richtig.** Die Gerätewerkzeuge bleiben bei
         * einer Runde. Dort werden am Ende echte Geräte geschaltet, und ein Modell, das aus
         * eigenem Antrieb nachlegt, ist dabei etwas anderes als eines, das eine zweite Datei
         * schreibt. Deshalb entscheidet der Aufrufer und nicht diese Stelle.
         */
        rundenGrenze: Int,
    ): TurnReport {
        // Was in dieser Kette schon geschah, für das Modell lesbar. Ohne diesen Verlauf
        // begänne jede Runde bei null: Das Modell wüsste nicht, dass das Projekt bereits
        // angelegt ist, und legte es noch einmal an.
        val kettenVerlauf = mutableListOf<ChatMessage>()

        // Zur Schleifenerkennung: Werkzeugname samt Argumenten. Zweimal derselbe Aufruf
        // heißt, dass das Modell sich im Kreis dreht — und bei zwölf Token je Sekunde ist
        // eine Runde im Kreis eine halbe Minute, die niemand zurückbekommt.
        val schonDagewesen = mutableSetOf<String>()

        val gesprochenes = mutableListOf<String>()
        var tokensGesamt = 0
        var letztesWerkzeug: String? = null
        // Auch hier, aus demselben Grund wie im Antwortpfad: Am Gerät blieb eine Erzeugung
        // sechseinhalb Minuten stehen. Eine Kette darf vier Runden lang laufen, und ein
        // Stillstand darin wäre noch schwerer zuzuordnen als in einer einzelnen Antwort —
        // von außen sähe er aus wie ein besonders langsamer Bauvorgang.
        var laengsterAbstandKette = 0L

        for (runde in 1..rundenGrenze) {
            // **In Runde eins gibt es kein `fertig`.** Man kann nicht fertig sein, bevor
            // etwas geschehen ist — und das Gerät hat vorgeführt, warum das kein
            // theoretischer Einwand ist: Auf eine Programmieraufgabe der Komplexität 5 rief
            // das Modell in der ersten Runde `fertig` und erklärte die Arbeit für erledigt,
            // ohne eine Zeile geschrieben zu haben. Im Protokoll stand danach nur
            // „Werkzeugkette beendet nach 1 Runde(n)" und sonst nichts.
            //
            // Ein Modell wählt, was dasteht. Also darf in Runde eins nicht dastehen, was
            // dort nicht hingehört.
            val aktuell = werkzeugeJetzt() ?: registry
            val runFuerDieseRunde =
                if (runde == 1) aktuell.ohne(Fertig.NAME) else aktuell

            val raw = StringBuilder()
            var tokens = 0
            var failure: String? = null
            var failureDetail: String? = null
            // **Erst ab dem ersten Token.** Vor ihm liegt die Verarbeitung des Prompts,
            // und die dauert legitim lange: Auf dem Gerät waren es 9,3 Sekunden für 540
            // Token. Als Abstand gezählt ergab das in jeder Runde die Meldung „Stillstand:
            // 9 s" — für ganz gewöhnliche Rechenarbeit. `null` heißt: Es kam noch nichts,
            // also gibt es auch keinen Abstand zu messen.
            var letztesToken: Long? = null

            engine.generate(
                GenerationRequest(
                    messages = buildList {
                        add(
                            ChatMessage(
                                Role.SYSTEM,
                                NeonPrompts.systemPrompt(
                                    memoryContext = memoryContext,
                                    toolDescription = runFuerDieseRunde.promptDescription(),
                                    spoken = speakThisTurn,
                                    // Auch hier: "ändere die markierte Stelle" ist ein
                                    // Werkzeugaufruf, und ohne den Ausschnitt wüsste das
                                    // Modell nicht, welche Stelle gemeint ist.
                                    selection = selectionThisTurn,
                                ),
                            )
                        )
                        addAll(turnHistory)
                        add(ChatMessage(Role.USER, utterance.text))
                        addAll(kettenVerlauf)
                    },
                    // Die Grenze kommt von den Werkzeugen und nicht von einer Konstante
                    // hier. Bei festen 128 Token brach jeder `datei-schreiben`-Aufruf nach
                    // rund 400 Zeichen mitten im Inhalt ab; danach war es kein JSON mehr,
                    // und der Nutzer hörte „Das habe ich nicht als Befehl verstanden" — bei
                    // einem Aufruf, den das Modell völlig richtig begonnen hatte.
                    maxTokens = runFuerDieseRunde.maxAntwortToken(),
                    temperature = 0f,
                    // Erzwungene Grammatik: Auch ein 4B-Modell gibt damit einen gültigen
                    // Aufruf aus, statt in Prosa zu beschreiben, was es tun würde.
                    grammar = runFuerDieseRunde.grammar(),
                )
            ).collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Token -> {
                        tokens++
                        val jetzt = clock()
                        letztesToken?.let { vorher ->
                            val abstand = jetzt - vorher
                            if (abstand > laengsterAbstandKette) laengsterAbstandKette = abstand
                        }
                        letztesToken = jetzt
                        raw.append(chunk.text)
                    }

                    is GenerationChunk.Done -> Unit
                    is GenerationChunk.Failed -> {
                        failure = chunk.reason
                        failureDetail = chunk.detail
                    }
                }
            }
            tokensGesamt += tokens

            failure?.let { grund ->
                // Was vorher gelang, ist trotzdem geschehen — eine angelegte Datei bleibt
                // angelegt. Deshalb wird der Abbruch gemeldet und nicht so getan, als sei
                // der ganze Durchgang folgenlos gewesen.
                return abbruch(
                    transcript, selection, tokensGesamt, grund, failureDetail, startedAt,
                    weg = if (runde == 1) "Werkzeugaufruf" else "Werkzeugaufruf, Runde $runde",
                )
            }

            val call = ToolRegistry.parseCall(raw.toString())
            if (call == null) {
                // **Mit der Rohausgabe.** Ohne sie stand hier nur, dass etwas nicht
                // verstanden wurde — und die eigentliche Frage, *was* das Modell denn
                // ausgegeben hat, war nicht zu beantworten. Genau daran ließ sich der
                // abgeschnittene Aufruf nicht erkennen: Ein bei 128 Token gekapptes JSON
                // sieht in der Sprechblase aus wie ein Modell, das den Auftrag nicht
                // verstanden hat.
                log(
                    "Werkzeugaufruf unlesbar in Runde $runde — ${selection.model.id}, " +
                        "$tokens Token, ${runFuerDieseRunde.specs.size} Werkzeuge " +
                        "angeboten, " +
                        "Rohausgabe: " + raw.toString().gekuerzt(ROHAUSGABE_IM_PROTOKOLL)
                )
                if (gesprochenes.isEmpty()) {
                    return speakProblem(
                        transcript = transcript,
                        message = "Das habe ich nicht als Befehl verstanden.",
                        selection = selection.reason,
                        startedAt = startedAt,
                    )
                }
                break
            }

            letztesWerkzeug = call.name

            if (call.name == Fertig.NAME) {
                // Gegen die Liste **dieser Runde**, nicht gegen die vom Anfang. Sonst hört
                // die Ausführung ein Werkzeug nicht, das die Grammatik gerade angeboten hat.
                val abschluss = runFuerDieseRunde.execute(call)
                val satz = when (abschluss) {
                    is ToolResult.Ok -> abschluss.spoken
                    is ToolResult.Failed -> abschluss.spoken
                }
                log(
                    "Werkzeugkette beendet nach $runde Runde(n) — $tokensGesamt Token" +
                        stillstand(laengsterAbstandKette)
                )
                say(satz)
                gesprochenes += satz
                break
            }

            val schluessel = call.name + call.arguments.entries.sortedBy { it.key }
                .joinToString { "${it.key}=${it.value}" }
            if (!schonDagewesen.add(schluessel)) {
                log(
                    "Werkzeugkette dreht sich im Kreis — ${call.name} zum zweiten Mal mit " +
                        "denselben Angaben, Abbruch nach Runde $runde"
                )
                break
            }

            val vorAusfuehrung = clock()
            // **Auch die Ausführung braucht die Liste dieser Runde.** Hier stand
            // `registry` — der Stand vom Anfang des Durchgangs. Damit bot die Grammatik in
            // Runde 2 `app-bauen` an, das Modell wählte es, und die Ausführung antwortete
            // „unbekanntes Werkzeug: app-bauen". Die halbe Behebung ist hier schlimmer als
            // gar keine: Vorher fehlte das Werkzeug wenigstens sichtbar.
            val result = runFuerDieseRunde.execute(call)
            val ausgefuehrt = clock() - vorAusfuehrung

            // **Jeder Werkzeugaufruf hinterlässt eine Zeile.** Hier stand keine, und deshalb
            // war von der gescheiterten Projekterstellung im Protokoll nichts zu sehen:
            // nicht welches Werkzeug gewählt wurde, nicht mit welchen Angaben, nicht was
            // dabei herauskam. Das Protokoll endete beim `print_timing` des Servers und
            // schwieg über alles danach.
            //
            // Die Argumente gekürzt, aber vorhanden: Ein `datei-schreiben` mit dem falschen
            // Pfad sieht im Ergebnis genauso aus wie eines mit dem richtigen.
            val ergebnis = when (result) {
                is ToolResult.Ok -> "gelungen"
                is ToolResult.Failed -> "gescheitert — ${result.reason}"
            }
            log(
                "Runde $runde von $rundenGrenze: Werkzeug ${call.name} $ergebnis — " +
                    "$ausgefuehrt ms, ${selection.model.id}, $tokens Token für den Aufruf" +
                    stillstand(laengsterAbstandKette) +
                    call.arguments.entries.joinToString("") { (name, wert) ->
                        " · $name=${wert.gekuerzt(ARGUMENT_IM_PROTOKOLL)}"
                    }
            )

            val spoken = when (result) {
                is ToolResult.Ok -> result.spoken
                is ToolResult.Failed -> result.spoken
            }
            // **Jeder Zwischenschritt wird gesprochen, nicht nur das Ende.** Ein Bauvorgang
            // dauert auf dem Telefon über eine Minute; zwei Minuten Schweigen nach „mach mir
            // eine App" sind von einem Absturz nicht zu unterscheiden.
            say(spoken)
            gesprochenes += spoken

            // Eine Rückfrage ist das Ende der Kette, egal wie viele Runden noch offen wären:
            // Weiterzuarbeiten hieße, die eigene Frage zu übergehen.
            //
            // **Und der Auftrag wird gemerkt.** Ohne das war die Rückfrage des Modells
            // genauso wertlos wie die feste: Auf „Android" allein folgt eine neue
            // Einordnung, „Android" ist für sich keine Programmieraufgabe, und der
            // ursprüngliche Auftrag wäre verloren. Derselbe Weg wie bei [Zielklaerung].
            if (call.name == Rueckfrage.NAME) {
                offeneFrage = utterance.text
                break
            }

            // Das Ergebnis zurück ins Gespräch. Der Aufruf als das, was Neon getan hat, das
            // Ergebnis als das, was dabei herauskam — zwei Nachrichten und nicht eine,
            // damit das Modell den eigenen Zug von der Antwort der Welt unterscheiden kann.
            kettenVerlauf += ChatMessage(Role.ASSISTANT, raw.toString().trim())
            kettenVerlauf += ChatMessage(
                Role.USER,
                "Ergebnis von ${call.name}: ${spoken.gekuerzt(ERGEBNIS_IM_VERLAUF)}",
            )

            if (runde == rundenGrenze) {
                log("Werkzeugkette an der Rundengrenze ($rundenGrenze) beendet")
            }
        }

        val antwort = gesprochenes.joinToString("\n").ifBlank { "Ich habe nichts getan." }
        val latency = clock() - startedAt
        record(transcript, selection.analysis, selection.model.id, latency, tokensGesamt)

        return TurnReport(
            transcript = transcript,
            answer = antwort,
            modelId = selection.model.id,
            routeReason = "${selection.reason} — Werkzeug ${letztesWerkzeug ?: "keines"}",
            latencyMs = latency,
            usedNoModel = false,
            tokenCount = tokensGesamt,
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
    /**
     * Die längste Pause zwischen zwei Token — aber nur, wenn sie auffällig war.
     *
     * **Warum eine Schwelle und keine Zahl in jeder Zeile.** Zwischen zwei Token liegen im
     * Normalfall achtzig bis zweihundert Millisekunden; das jedes Mal zu melden wäre eine
     * Spalte, die immer dasselbe sagt. Interessant wird es erst, wenn eine Pause länger ist
     * als jede vertretbare Rechenzeit für ein einzelnes Token — dann rechnet nicht das
     * Modell langsam, sondern es rechnet gar nicht.
     */
    private fun stillstand(laengsterAbstandMillis: Long): String =
        if (laengsterAbstandMillis < STILLSTAND_SCHWELLE_MILLIS) ""
        else " · Stillstand: ${laengsterAbstandMillis / 1000} s zwischen zwei Token"

    /**
     * Akku und Wärme in einem Halbsatz.
     *
     * **Warum das an der Antwortzeile hängt.** Im Geräteprotokoll bricht die
     * Erzeugungsgeschwindigkeit innerhalb einer einzigen Antwort von 13,5 auf 7 bis 9 Token je
     * Sekunde ein — je länger die Antwort, desto langsamer, und beim nächsten Durchgang steht
     * sie wieder bei 13,5. Zwei Erklärungen kommen infrage: Der Prozessor drosselt wegen
     * Wärme, oder er fällt nach dem anfänglichen Schub in den Dauertakt. Die eine ließe sich
     * durch Abkühlen beheben, die andere nicht.
     *
     * Entscheiden lässt sich das nur mit dem Thermalzustand — und der wurde bisher zwar für
     * die Modellauswahl **gelesen**, aber nirgends geschrieben. Er stand in keiner einzigen
     * Zeile der Protokolle von zwei Tagen.
     */
    private fun geraetelage(): String = runCatching {
        val zustand = deviceState()
        val laden = if (zustand.isCharging) ", lädt" else ""
        "Akku ${zustand.batteryPercent} %$laden, Wärme ${zustand.thermalStatus.name.lowercase()}"
    }.getOrDefault("Gerätelage unbekannt")

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

    /**
     * Die Rückfrage als fertiger Durchgang.
     *
     * `usedNoModel`, weil genau das der Punkt ist: Kein Serverstart, keine Erzeugung, kein
     * Token. Die Frage steht als fester Satz im Code. Auf diesem Gerät ist das der
     * Unterschied zwischen sofort und einer halben Minute.
     */
    private suspend fun rueckfrageBericht(frage: String, startedAt: Long): TurnReport {
        say(frage)
        return TurnReport(
            transcript = frage,
            answer = frage,
            modelId = null,
            routeReason = "Rückfrage vor dem Bauen",
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
        /**
         * Wie lang eine Antwort werden darf.
         *
         * **Hier griff stillschweigend die Vorgabe von 512.** Im Geräteprotokoll enden zwei
         * Antworten bei exakt 512 erzeugten Token — beide die längsten des Tages, beide
         * mitten im Satz. Für den Nutzer sah das aus, als hätte das Modell den Faden
         * verloren.
         *
         * Verdoppelt und nicht weiter: Bei zwölf Token je Sekunde sind 1024 Token schon
         * anderthalb Minuten. Das ist eine Obergrenze und kein Ziel — das Modell hört von
         * selbst auf, wenn es fertig ist, und die längsten beobachteten Antworten lagen bei
         * rund 1800 Zeichen. Die Grenze fängt nur den Fall ab, dass es das nicht tut.
         */
        const val ANTWORT_MAX_TOKENS = 1_024

        const val ARGUMENT_IM_PROTOKOLL = 120

        /**
         * Wie viel eines Werkzeugergebnisses in die nächste Runde mitgeht.
         *
         * Knapp gehalten, weil es sich mit jeder Runde aufsummiert und der Prompt ohnehin
         * der teuerste Teil ist: Auf dem Gerät kosteten tausend Prompt-Token 16 Sekunden vor
         * dem ersten Wort. Für „Projekt angelegt: drei Dateien" oder eine Fehlermeldung des
         * Compilers reicht es; wer den vollen Inhalt braucht, liest die Datei.
         */
        const val ERGEBNIS_IM_VERLAUF = 400

        /**
         * Ab wann eine Pause zwischen zwei Token gemeldet wird.
         *
         * Fünf Sekunden. Das langsamste beobachtete Modell schafft acht Token je Sekunde,
         * also 125 Millisekunden je Token; selbst der zehnfache Wert bliebe weit darunter.
         * Wer fünf Sekunden auf ein einziges Token wartet, wartet nicht auf Rechenarbeit.
         */
        const val STILLSTAND_SCHWELLE_MILLIS = 5_000L


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
