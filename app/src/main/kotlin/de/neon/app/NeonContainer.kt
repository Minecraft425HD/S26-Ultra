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
import de.neon.inference.LlamaCppEngine
import de.neon.inference.LocalRouterLlm
import de.neon.inference.ModelLifecycleManager
import de.neon.inference.ModelStore
import de.neon.platform.DeviceStateProvider
import de.neon.router.HashingEmbeddingProvider
import de.neon.router.InMemoryRouteOutcomeStore
import de.neon.router.KnnClassifier
import de.neon.router.ModelRegistry
import de.neon.router.ModelRole
import de.neon.router.Router
import de.neon.router.RouterStats
import de.neon.router.SeedExamples
import de.neon.router.SelectionPolicy
import de.neon.service.ConversationOrchestrator
import de.neon.service.NeonForegroundService
import de.neon.service.TurnLearner
import de.neon.speech.AndroidOnDeviceAsr
import de.neon.speech.AndroidTts
import de.neon.tools.CalendarEventTool
import de.neon.tools.ComposeMessageTool
import de.neon.tools.DeviceActionExecutor
import de.neon.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
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

    val registry: ModelRegistry = ModelRegistry.defaultForS26Ultra()

    val modelStore = ModelStore(File(appContext.filesDir, "models"))

    /** Motor für die Antwortmodelle. */
    private val answerEngine = LlamaCppEngine()

    /**
     * Ein **zweiter** Motor allein für das Router-Modell.
     *
     * Liefe die Einordnung über denselben Motor, müsste für jede Klassifikation das
     * Alltagsmodell entladen und danach wieder geladen werden — die Frage einzuordnen wäre
     * dann teurer als sie zu beantworten. Das 0.6B-Modell belegt dauerhaft rund 400 MB;
     * dafür kostet Stufe 2 nur noch Millisekunden.
     */
    private val routerEngine = LlamaCppEngine(threadCount = 2)

    val lifecycle = ModelLifecycleManager(answerEngine, modelStore)

    private val deviceStateProvider = DeviceStateProvider(
        context = appContext,
        warmModelIds = { lifecycle.warmModelIds() },
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

    private val routerLlm = registry.withRole(ModelRole.ROUTER).firstOrNull()?.let { spec ->
        LocalRouterLlm(routerEngine, spec, modelStore)
    }

    val router = Router(
        registry = registry,
        policy = SelectionPolicy(registry),
        knn = knn,
        embeddings = embeddings,
        routerLlm = routerLlm,
    )

    val outcomeStore = InMemoryRouteOutcomeStore()

    private val learner = TurnLearner(embeddings) { router.learn(it) }

    private val asr = AndroidOnDeviceAsr(appContext)
    private val tts = AndroidTts(appContext)
    private val actionExecutor = DeviceActionExecutor(appContext)

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
        learner = learner,
    )

    /** Die laufende Hörschleife, solange der Dienst aktiv ist. Für den Diagnose-Screen. */
    @Volatile
    private var pipeline: WakeWordPipeline? = null

    val cascadeStats: CascadeStats? get() = pipeline?.stats

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
            assets.open("wakeword/neon.onnx").use { it.readBytes() },
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
            ).also { pipeline = it }

            override val orchestrator = this@NeonContainer.orchestrator

            override fun listen(): Flow<ListeningEvent> = ownPipeline.listen(source)

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
