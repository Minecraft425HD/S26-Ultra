package de.neon.app

import android.content.Context
import de.neon.audio.EnergyGate
import de.neon.audio.ListeningEvent
import de.neon.audio.MicrophoneAudioSource
import de.neon.audio.SpeechSegmenter
import de.neon.audio.VoiceActivityDetector
import de.neon.audio.WakeWordDetector
import de.neon.audio.WakeWordPipeline
import de.neon.inference.LlamaCppEngine
import de.neon.inference.ModelLifecycleManager
import de.neon.inference.ModelStore
import de.neon.platform.DeviceStateProvider
import de.neon.router.InMemoryRouteOutcomeStore
import de.neon.router.ModelRegistry
import de.neon.router.Router
import de.neon.router.SelectionPolicy
import de.neon.service.ConversationOrchestrator
import de.neon.service.NeonForegroundService
import de.neon.speech.AndroidOnDeviceAsr
import de.neon.speech.AndroidTts
import de.neon.tools.DeviceActionExecutor
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

    private val engine = LlamaCppEngine()

    val lifecycle = ModelLifecycleManager(engine, modelStore)

    private val deviceStateProvider = DeviceStateProvider(
        context = appContext,
        warmModelIds = { lifecycle.warmModelIds() },
    )

    /**
     * Stufen 1 und 2 bleiben vorerst leer.
     *
     * Ohne Einbettungs- und Router-Modell fällt der Router auf seine Standardannahme
     * zurück und schickt alles an das Alltagsmodell — die Regelstufe funktioniert
     * trotzdem vollständig. Damit ist Neon vom ersten Start an nutzbar, auch bevor
     * irgendein Modell heruntergeladen wurde.
     */
    val router = Router(
        registry = registry,
        policy = SelectionPolicy(registry),
    )

    val outcomeStore = InMemoryRouteOutcomeStore()

    private val asr = AndroidOnDeviceAsr(appContext)
    private val tts = AndroidTts(appContext)
    private val actionExecutor = DeviceActionExecutor(appContext)

    val orchestrator = ConversationOrchestrator(
        router = router,
        asr = asr,
        tts = tts,
        lifecycle = lifecycle,
        engine = engine,
        deviceState = { deviceStateProvider.current() },
        actionExecutor = { actionExecutor.execute(it) },
        outcomeStore = outcomeStore,
    )

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
                ?.let { de.neon.audio.SileroVadOnnx(it) }
                ?: AlwaysSpeechVad

            private val wakeWord: WakeWordDetector = loadWakeWordAssets()
                ?.let { (mel, embedding, model) ->
                    de.neon.audio.OpenWakeWordDetector(mel, embedding, model)
                }
                ?: NeverWakeWord

            private val pipeline = WakeWordPipeline(
                gate = EnergyGate(),
                vad = vad,
                wakeWord = wakeWord,
                segmenter = SpeechSegmenter(),
            )

            override val orchestrator = this@NeonContainer.orchestrator

            override fun listen(): Flow<ListeningEvent> = pipeline.listen(source)

            override fun release() {
                source.close()
                vad.close()
                wakeWord.close()
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
