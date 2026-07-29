package de.neon.app

import android.content.Context
import de.neon.audio.CascadeStats
import de.neon.audio.EnergyGate
import de.neon.audio.ListeningEvent
import de.neon.audio.MicrophoneAudioSource
import de.neon.audio.OpenWakeWordDetector
import de.neon.audio.SileroVadOnnx
import de.neon.audio.SpeechSegmenter
import de.neon.audio.VoiceActivityDetector
import de.neon.audio.WakeWordDetector
import de.neon.audio.WakeWordPipeline
import de.neon.inference.ImageAttachment
import de.neon.inference.LlamaServerEngine
import de.neon.inference.LocalRouterLlm
import de.neon.inference.ModelLifecycleManager
import de.neon.inference.ModelStore
import de.neon.inference.ProcessServerSupervisor
import de.neon.attachments.AttachmentIngest
import de.neon.attachments.BytesSource
import de.neon.attachments.AttachmentKind
import de.neon.attachments.IngestResult
import de.neon.memory.AttachmentRepository
import de.neon.memory.ChatHistoryRepository
import de.neon.memory.MemoryRepository
import de.neon.memory.NeonDatabase
import de.neon.memory.RoutingExampleRepository
import de.neon.platform.DeviceStateProvider
import de.neon.platform.NeonLog
import de.neon.router.Capability
import de.neon.router.HashingEmbeddingProvider
import de.neon.router.InMemoryRouteOutcomeStore
import de.neon.router.KnnClassifier
import de.neon.router.ModelRegistry
import de.neon.router.ModelRole
import de.neon.router.Router
import de.neon.router.RouterStats
import de.neon.router.SeedExamples
import de.neon.router.SelectionPolicy
import de.neon.service.AttachmentExcerpt
import de.neon.service.ConversationOrchestrator
import de.neon.service.NeonForegroundService
import de.neon.service.TurnLearner
import de.neon.speech.AndroidOnDeviceAsr
import de.neon.speech.AndroidTts
import de.neon.tools.CalendarEventTool
import de.neon.tools.ComposeMessageTool
import de.neon.tools.DeviceActionExecutor
import de.neon.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Baut die Objekte zusammen, aus denen Neon besteht.
 *
 * Bewusst von Hand statt mit einem Injektions-Rahmenwerk: Es gibt genau einen Graphen, er
 * wird genau einmal gebaut, und wer verstehen will, wie Neon funktioniert, kann ihn hier in
 * einem Stück lesen.
 */
class NeonContainer(context: Context) {

    private val appContext = context.applicationContext

    private val einstellungen = appContext.getSharedPreferences("neon", Context.MODE_PRIVATE)

    val registry: ModelRegistry = ModelRegistry.defaultForS26Ultra()

    val modelStore = ModelStore(File(appContext.filesDir, "models"))

    /**
     * Startet und überwacht den mitgelieferten `llama-server`.
     *
     * Er läuft als eigener Prozess: Ein Modell, das den Speicher sprengt, reißt damit nicht
     * die Hörschleife mit — Neon kann weiter zuhören und melden, dass es nicht geklappt hat.
     */
    private val serverSupervisor = ProcessServerSupervisor(
        context = appContext,
        contextSize = einstellungen.getInt(SCHLUESSEL_KONTEXT, ProcessServerSupervisor.DEFAULT_CONTEXT_SIZE),
    )

    /**
     * Die Größe des Kontextfensters, dauerhaft gemerkt.
     *
     * Anders als der Weckwort-Schwellwert überdauert dieser Wert einen Neustart der App:
     * Wer ihn einmal an sein Gerät angepasst hat, will das nicht bei jedem Start wiederholen
     * — und ein falscher Wert fällt erst auf, wenn der Speicher knapp wird.
     */
    var contextSize: Int
        get() = serverSupervisor.contextSize
        set(value) {
            serverSupervisor.contextSize = value
            einstellungen.edit().putInt(SCHLUESSEL_KONTEXT, serverSupervisor.contextSize).apply()
        }

    val inferenceAvailable: Boolean get() = serverSupervisor.isAvailable

    private val answerEngine = LlamaServerEngine(serverSupervisor)

    val lifecycle = ModelLifecycleManager(answerEngine, modelStore)

    private val deviceStateProvider = DeviceStateProvider(
        context = appContext,
        warmModelIds = { lifecycle.warmModelIds() },
        // Damit der Router nur unter dem wählt, was tatsächlich importiert wurde. Ohne das
        // gewinnt bei einer schweren Frage der Denker, dessen Datei niemand geladen hat,
        // und Neon sagt „nicht heruntergeladen", statt mit dem Alltagsmodell zu antworten.
        //
        // Bei jedem Durchgang neu gelesen und nicht gemerkt: Ein Import soll sofort wirken,
        // nicht erst nach einem Neustart.
        availableModelIds = {
            registry.models.filter { modelStore.isAvailable(it) }.map { it.id }.toSet()
        },
    )

    /**
     * Stufe 1 arbeitet ohne Modelldatei.
     *
     * Das Verfahren misst lexikalische Ähnlichkeit, keine Bedeutung — dafür funktioniert es
     * vom ersten Start an, ohne Download und ohne Tokenizer. Auf ungesehenen deutschen
     * Äußerungen trifft es rund neun von zehn Kategorien; die Fehlgriffe landen in Stufe 2.
     */
    private val embeddings = HashingEmbeddingProvider()

    private val knn = KnnClassifier(
        examples = SeedExamples.materialize(embeddings),
        k = 5,
        minSimilarity = 0.30,
        minMargin = 0.10,
    )

    /**
     * Stufe 2 läuft auf demselben Modell, das gerade antwortet.
     *
     * Ein eigenes Router-Modell wäre schneller, bräuchte aber einen zweiten Serverprozess —
     * llama-server bedient je Lauf genau ein Modell. Solange nur das Alltagsmodell geladen
     * ist, wäre das ein zweiter Speicherblock für eine Einordnung, die auch so wenige
     * hundert Millisekunden dauert.
     */
    private val routerLlm = LocalRouterLlm(answerEngine)

    val router = Router(
        registry = registry,
        policy = SelectionPolicy(registry),
        knn = knn,
        embeddings = embeddings,
        routerLlm = routerLlm,
    )

    val outcomeStore = InMemoryRouteOutcomeStore()

    /**
     * Träge erzeugt — Room öffnet die Datei erst beim ersten Zugriff, und ein Fehler dabei
     * darf nicht den Start der ganzen App verhindern.
     */
    private val database by lazy { NeonDatabase.create(appContext) }

    private val routingExamples by lazy {
        RoutingExampleRepository(
            dao = database.routingExamples(),
            expectedDimensions = HashingEmbeddingProvider.DEFAULT_DIMENSIONS,
        )
    }

    private val memory by lazy { MemoryRepository(database.memoryFacts(), embeddings) }

    private val chatHistory by lazy { ChatHistoryRepository(database.chatEntries()) }

    private val attachments by lazy { AttachmentRepository(database.attachmentChunks(), embeddings) }

    /** Der Zustand der Anhänge für die Oberfläche. */
    val attachmentState = MutableStateFlow(AttachmentState())

    /**
     * Bilder, die mit der **nächsten** Frage mitgehen.
     *
     * Nur mit der nächsten, nicht mit allen folgenden: Ein Bild kostet je nach Größe
     * Tausende Token. Es stillschweigend bei jeder weiteren Frage mitzuschicken würde den
     * Kontext füllen und jede Antwort verlangsamen, ohne dass jemand den Grund sähe.
     *
     * Gilt nur, wenn ein Bildmodell mit Projektor vorliegt. Sonst bleibt es beim Text aus
     * der Bilderkennung, der ohnehin schon im Index steht.
     */
    private val wartendeBilder = mutableListOf<ImageAttachment>()

    val visionAvailable: Boolean
        get() = registry.models.any {
            it.supports(Capability.VISION) && modelStore.isAvailable(it)
        }

    /** Nimmt die wartenden Bilder heraus und leert die Liste. */
    fun takePendingImages(): List<ImageAttachment> = synchronized(wartendeBilder) {
        val kopie = wartendeBilder.toList()
        wartendeBilder.clear()
        attachmentState.value = attachmentState.value.copy(pendingImages = 0)
        kopie
    }

    /**
     * Lebt so lange wie die Anwendung.
     *
     * Bewusst kein Scope des Dienstes: Die gelernten Beispiele sollen auch dann noch
     * geschrieben werden, wenn Neon gerade beendet wurde.
     *
     * Aus demselben Grund läuft hier auch eine getippte Frage. Sie lief einmal in
     * `lifecycleScope` der Activity — wer die App verließ oder das Gerät drehte, während
     * Neon rechnete, verlor die Antwort. Im Protokoll stand dann nur
     * `JobCancellationException: Job was cancelled`. Bei einer Minute Ladezeit ist das
     * nicht der seltene Fall, sondern der wahrscheinliche.
     */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val learner = TurnLearner(embeddings) { example ->
        router.learn(example)
        scope.launch { routingExamples.save(example) }
    }

    init {
        // Gelernte Beispiele nachladen. Ohne das begänne der Router nach jedem Start wieder
        // bei der mitgelieferten Startmenge und die Lernschleife bliebe folgenlos.
        //
        // Im Hintergrund und gekapselt: Eine unlesbare Datenbank darf höchstens das
        // Gelernte kosten, nicht den Start.
        scope.launch {
            runCatching { routingExamples.loadAll().forEach { router.learn(it) } }
                .onFailure { NeonLog.e(TAG, "Gelernte Beispiele nicht ladbar", it) }
        }
    }

    /**
     * Ebenfalls träge. `AndroidTts` erzeugt im Konstruktor eine Verbindung zur
     * Sprachausgabe des Systems, die Erkennung eine zum Erkennerdienst — beides braucht
     * niemand, bevor zum ersten Mal gesprochen wird, und beides kann auf einem fremden
     * Gerät fehlschlagen.
     */
    private val asr by lazy { AndroidOnDeviceAsr(appContext) }
    private val tts by lazy { AndroidTts(appContext) }
    private val actionExecutor = DeviceActionExecutor(appContext)

    /** Träge: Der Erkenner lädt sein Modell beim ersten Gebrauch, nicht beim Start. */
    private val imageText by lazy { ImageText(appContext) }

    /**
     * Werkzeuge für Handlungen, die die Regelstufe nicht abdeckt.
     *
     * Termine und Nachrichten enthalten freien Text und relative Zeitangaben — daran
     * scheitert eine feste Grammatik, und genau dafür lohnt sich ein Modellaufruf.
     */
    private val tools = ToolRegistry(
        listOf(
            CalendarEventTool(appContext),
            ComposeMessageTool(appContext),
        )
    )

    val orchestrator = ConversationOrchestrator(
        router = router,
        asr = asr,
        tts = tts,
        lifecycle = lifecycle,
        engine = answerEngine,
        deviceState = { deviceStateProvider.current() },
        actionExecutor = { actionExecutor.execute(it) },
        outcomeStore = outcomeStore,
        tools = tools,
        memory = { query, limit ->
            runCatching { memory.recall(query, limit) }.getOrDefault(emptyList())
        },
        attachments = { query, limit ->
            runCatching {
                attachments.recall(query, limit).map {
                    AttachmentExcerpt(source = it.chunk.quelle, text = it.chunk.text)
                }
            }.getOrDefault(emptyList())
        },
        learner = learner,
        // Jede Zeile wandert auf die Platte, damit der Verlauf einen Neustart überlebt.
        // Im Hintergrund: Eine hakende Datenbank darf das Gespräch nicht aufhalten.
        onEntry = { entry ->
            scope.launch {
                runCatching { chatHistory.append(entry.toEntity()) }
                    .onFailure { NeonLog.e(TAG, "Verlauf nicht speicherbar", it) }
            }
        },
    )

    init {
        // Den Ladefortschritt vom Server bis auf den Bildschirm durchreichen. Ohne das
        // steht bei einem Ladevorgang von einer Minute fünfeinhalb Minuten lang nichts da.
        serverSupervisor.onLoadingProgress = { fortschritt ->
            orchestrator.onServerProgress(
                elapsedMillis = fortschritt.elapsedMillis,
                budgetMillis = fortschritt.budgetMillis,
                lastLine = fortschritt.lastLine,
            )
        }

        // Den gespeicherten Verlauf zurückholen. Ohne das begänne jedes Gespräch bei null,
        // und der Chat wäre nach jedem Schließen der App leer.
        scope.launch {
            runCatching {
                val entries = chatHistory.recent().map { it.toEntry() }
                if (entries.isNotEmpty()) orchestrator.restoreTranscript(entries)
            }.onFailure { NeonLog.e(TAG, "Verlauf nicht ladbar", it) }
        }
    }

    /**
     * Nimmt Anhänge auf: erkennen, auspacken, zerlegen, einbetten, ablegen.
     *
     * Läuft im Hintergrund und meldet den Fortschritt, weil ein Ordner mit ein paar hundert
     * Dateien durchaus einige Sekunden braucht — und eine Oberfläche, die dabei nur steht,
     * sieht aus wie abgestürzt.
     */
    fun addAttachments(sources: List<de.neon.attachments.AttachmentSource>) {
        if (sources.isEmpty()) return
        scope.launch {
            attachmentState.value = attachmentState.value.copy(busy = true, message = null)
            runCatching {
                val ergebnis = AttachmentIngest().ingest(mitTextAusBildern(sources))
                attachments.add(ergebnis.chunks)
                ergebnis
            }.onSuccess { ergebnis ->
                attachmentState.value = AttachmentState(
                    busy = false,
                    files = attachments.paths(),
                    chunkCount = attachments.chunkCount(),
                    message = zusammenfassung(ergebnis),
                    pendingImages = synchronized(wartendeBilder) { wartendeBilder.size },
                )
            }.onFailure {
                NeonLog.e(TAG, "Anhänge nicht aufnehmbar", it)
                attachmentState.value = attachmentState.value.copy(
                    busy = false,
                    message = "Fehlgeschlagen: ${it.message}",
                )
            }
        }
    }

    /**
     * Ersetzt Bilder durch den Text, der darin steht.
     *
     * Das Alltagsmodell kann Bilder technisch nicht sehen. Statt sie als „Binärdatei, nicht
     * lesbar" abzulegen, wird gelesen, was zu lesen ist — Bildschirmfotos, abfotografierte
     * Briefe und Zettel decken den weitaus häufigsten Grund ab, warum jemand ein Bild
     * anhängt.
     *
     * Ein Bild ohne Text bleibt ein Bild und wird als solches vermerkt. Es stillschweigend
     * verschwinden zu lassen wäre schlimmer als es abzulehnen.
     */
    private suspend fun mitTextAusBildern(
        sources: List<de.neon.attachments.AttachmentSource>,
    ): List<de.neon.attachments.AttachmentSource> = sources.map { quelle ->
        if (quelle !is UriSource || !quelle.istBild) return@map quelle

        // Fürs Bildmodell aufheben — aber nur, wenn es eines gibt. Sonst wäre es Ballast,
        // den niemand ansehen kann.
        if (visionAvailable) {
            runCatching {
                val bytes = quelle.open().use { it.readBytes() }
                synchronized(wartendeBilder) {
                    wartendeBilder += ImageAttachment(bytes, quelle.mimeType ?: "image/jpeg")
                }
            }.onFailure { NeonLog.e(TAG, "Bild nicht lesbar für das Bildmodell", it) }
        }

        val text = imageText.read(quelle.uri)
        if (text.isNullOrBlank()) {
            NeonLog.i(TAG, "Kein Text in ${quelle.name}")
            return@map quelle
        }

        // Der Pfad behält den Bildnamen: In einer Antwort soll „aus rechnung.jpg" stehen
        // und nicht ein erfundener Textdateiname.
        BytesSource(
            name = quelle.name,
            path = quelle.path,
            bytes = "Text aus dem Bild ${quelle.name}:\n\n$text".toByteArray(),
        )
    }

    /**
     * Was aufgenommen wurde, in einem Satz.
     *
     * Übersprungenes wird ausdrücklich genannt. Stillschweigend die Hälfte wegzulassen wäre
     * die unangenehmste Art von Fehler: Man fragt etwas, bekommt "steht da nicht" und hat
     * keine Ahnung, dass die Datei nie gelesen wurde.
     */
    private fun zusammenfassung(ergebnis: IngestResult): String = buildString {
        append("${ergebnis.textFileCount} Dateien gelesen, ${ergebnis.chunks.size} Abschnitte")
        val binaer = ergebnis.files.count { it.kind == AttachmentKind.BINAER }
        if (binaer > 0) append(", $binaer nicht lesbar")
        if (ergebnis.skippedCount > 0) append(", ${ergebnis.skippedCount} übersprungen")
        append(".")

        ergebnis.files
            .filter { it.kind == AttachmentKind.UEBERSPRUNGEN }
            .take(3)
            .forEach { append("\n${it.name}: ${it.note}") }
    }

    fun clearAttachments() {
        scope.launch {
            runCatching { attachments.clear() }
            attachmentState.value = AttachmentState()
        }
    }

    fun refreshAttachments() {
        scope.launch {
            runCatching {
                attachmentState.value = AttachmentState(
                    files = attachments.paths(),
                    chunkCount = attachments.chunkCount(),
                )
            }
        }
    }

    /** Löscht den sichtbaren Verlauf, hier und auf der Platte. */
    fun clearChat() {
        orchestrator.clearTranscript()
        scope.launch { runCatching { chatHistory.clear() } }
    }

    /** Die laufende Hörschleife, solange der Dienst aktiv ist. Für den Diagnose-Screen. */
    @Volatile
    private var pipeline: WakeWordPipeline? = null

    val cascadeStats: CascadeStats? get() = pipeline?.stats

    /**
     * Der Schwellwert, ab dem „Hey Neon" als gesagt gilt.
     *
     * Wird hier gehalten und nicht nur in der Hörschleife, damit eine Änderung auch den
     * nächsten Dienststart überdauert. Der richtige Wert hängt von der Umgebung ab — in
     * einer stillen Wohnung darf er niedriger stehen als bei laufendem Fernseher.
     */
    @Volatile
    var wakeWordThreshold: Float = WakeWordPipeline.DEFAULT_WAKE_WORD_THRESHOLD
        set(value) {
            val clamped = value.coerceIn(WakeWordPipeline.MIN_THRESHOLD, WakeWordPipeline.MAX_THRESHOLD)
            field = clamped
            pipeline?.wakeWordThreshold = clamped
        }

    fun routerStats(): RouterStats = RouterStats.from(outcomeStore.recent(200))

    val learnedExampleCount: Int get() = learner.learnedCount

    val knownExampleCount: Int get() = router.knownExampleCount

    /**
     * Die Weckwort-Modelle liegen in `assets/wakeword/`.
     *
     * Fehlen sie, läuft Neon ohne Weckwort weiter — auslösen lässt er sich dann über die
     * Oberfläche. Das ist der Zustand direkt nach dem Klonen des Projekts und darf kein
     * Grund sein, den Start zu verweigern.
     */
    private fun loadWakeWordAssets(): Triple<ByteArray, ByteArray, ByteArray>? = runCatching {
        val assets = appContext.assets
        Triple(
            assets.open("wakeword/melspectrogram.onnx").use { it.readBytes() },
            assets.open("wakeword/embedding_model.onnx").use { it.readBytes() },
            assets.open("wakeword/hey_neon.onnx").use { it.readBytes() },
        )
    }.getOrNull()

    private fun loadVadAsset(): ByteArray? = runCatching {
        appContext.assets.open("vad/silero_vad.onnx").use { it.readBytes() }
    }.getOrNull()

    val wakeWordAvailable: Boolean get() = loadWakeWordAssets() != null && loadVadAsset() != null

    fun serviceDependencies(): NeonForegroundService.Dependencies =
        object : NeonForegroundService.Dependencies {

            private val source = MicrophoneAudioSource()

            private val vad: VoiceActivityDetector = loadVadAsset()
                ?.let { SileroVadOnnx(it) }
                ?: AlwaysSpeechVad

            private val wakeWord: WakeWordDetector = loadWakeWordAssets()
                ?.let { (mel, embedding, model) -> OpenWakeWordDetector(mel, embedding, model) }
                ?: NeverWakeWord

            private val ownPipeline = WakeWordPipeline(
                gate = EnergyGate(),
                vad = vad,
                wakeWord = wakeWord,
                segmenter = SpeechSegmenter(),
                wakeWordThreshold = wakeWordThreshold,
            ).also { pipeline = it }

            override val orchestrator = this@NeonContainer.orchestrator

            override fun listen(): Flow<ListeningEvent> = ownPipeline.listen(source)

            override fun triggerManually() = ownPipeline.triggerManually()

            override fun release() {
                orchestrator.shutdown()
                source.close()
                vad.close()
                wakeWord.close()
                pipeline = null
            }
        }

    fun close() {
        asr.close()
        tts.close()
    }

    private companion object {
        const val TAG = "NeonContainer"
        const val SCHLUESSEL_KONTEXT = "kontextfenster"
    }

    /** Ersatz, solange das VAD-Modell fehlt: lässt alles durch. */
    private object AlwaysSpeechVad : VoiceActivityDetector {
        override fun probability(frame: FloatArray): Float = 1f
        override fun reset() = Unit
        override fun close() = Unit
    }

    /** Ersatz, solange das Weckwortmodell fehlt: löst nie aus. */
    private object NeverWakeWord : WakeWordDetector {
        override fun process(frame: FloatArray): Float = 0f
        override fun reset() = Unit
        override fun close() = Unit
    }
}
