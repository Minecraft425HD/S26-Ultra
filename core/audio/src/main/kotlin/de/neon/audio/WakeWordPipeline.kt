package de.neon.audio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.concurrent.thread

/** Was die Hörschleife nach außen meldet. */
sealed interface ListeningEvent {

    /** Das Weckwort wurde erkannt — Zeit für den Signalton. */
    data class WakeWordDetected(val probability: Float) : ListeningEvent

    /** Der Nutzer hat zu Ende gesprochen. Diese Abtastwerte gehen an die Spracherkennung. */
    class SpeechCaptured(val samples: ShortArray) : ListeningEvent {
        val durationSeconds: Double get() = samples.size / 16_000.0
    }

    /** Nach dem Weckwort kam nichts. Zurück ins Lauschen, ohne die Spracherkennung zu starten. */
    data object CaptureTimedOut : ListeningEvent
}

/**
 * Zählt, wie viele Blöcke welche Stufe erreicht haben.
 *
 * Das ist die Messgröße für die Akku-Strategie: Je kleiner der Anteil, der bis zum
 * Weckwortmodell durchkommt, desto weniger Strom kostet der Dauerbetrieb. Der
 * Diagnose-Screen zeigt diese Werte direkt an.
 */
data class CascadeStats(
    val framesRead: Long = 0,
    val framesPastGate: Long = 0,
    val framesPastVad: Long = 0,
    val wakeWordHits: Long = 0,
) {
    val gatePassRate: Double get() = if (framesRead == 0L) 0.0 else framesPastGate.toDouble() / framesRead
    val vadPassRate: Double get() = if (framesRead == 0L) 0.0 else framesPastVad.toDouble() / framesRead
}

/**
 * Die Hörschleife.
 *
 * Aufgeteilt in eine reine Schrittfunktion ([process]) und einen Treiber ([listen]). Dadurch
 * ist die gesamte Zustandslogik ohne Mikrofon und ohne Nebenläufigkeit prüfbar — Tests
 * schieben einfach Block für Block hinein.
 */
class WakeWordPipeline(
    private val gate: EnergyGate,
    private val vad: VoiceActivityDetector,
    private val wakeWord: WakeWordDetector,
    private val segmenter: SpeechSegmenter = SpeechSegmenter(),
    private val preRoll: PreRollBuffer = PreRollBuffer.forSeconds(2.0),
    /** Ab dieser Wahrscheinlichkeit gilt ein Block als Sprache. */
    private val vadThreshold: Float = 0.5f,
    /** Ab dieser Wahrscheinlichkeit gilt das Weckwort als gefallen. */
    private val wakeWordThreshold: Float = 0.7f,
    /** Kommt nach dem Weckwort so lange nichts, geht es zurück ins Lauschen. */
    private val silenceTimeoutFrames: Int = 90,
    /** Harte Obergrenze für eine Äußerung, damit ein Dauerton nicht den Speicher füllt. */
    private val maxCaptureFrames: Int = 900,
) {

    private enum class State { LAUSCHEN, AUFNAHME }

    private var state = State.LAUSCHEN
    private val captured = ArrayList<Short>()
    private var framesSinceWake = 0

    /**
     * Von außen ausgelöste Aufnahme, ohne Weckwort.
     *
     * `@Volatile`, weil der Auslöser vom Oberflächen-Thread kommt und der Audio-Thread ihn
     * liest.
     */
    @Volatile
    private var manualTrigger = false

    var stats = CascadeStats(); private set

    /**
     * Startet die Aufnahme sofort, als wäre das Weckwort gefallen.
     *
     * Damit ist Neon auch ohne trainiertes Weckwortmodell benutzbar — und bleibt es in
     * lauten Umgebungen, in denen ein Weckwort ohnehin unzuverlässig ist.
     */
    fun triggerManually() {
        manualTrigger = true
    }

    /**
     * Verarbeitet genau einen Audioblock.
     *
     * @return ein Ereignis, oder `null`, wenn nichts Berichtenswertes passiert ist —
     * das ist im Ruhezustand der Normalfall.
     */
    fun process(frame: ShortArray, length: Int = frame.size): ListeningEvent? {
        stats = stats.copy(framesRead = stats.framesRead + 1)

        return when (state) {
            State.LAUSCHEN -> listenStep(frame, length)
            State.AUFNAHME -> captureStep(frame, length)
        }
    }

    private fun listenStep(frame: ShortArray, length: Int): ListeningEvent? {
        // Der Vorlaufpuffer wird immer gefüllt — auch von Blöcken, die das Gatter
        // verwirft. Sonst fehlte nach dem Weckwort der Anfang der Frage.
        preRoll.write(frame, length)

        // Der Handauslöser steht vor allen Filtern: Wer den Knopf drückt, will
        // aufnehmen — unabhängig davon, was Gatter, VAD und Weckwortmodell meinen.
        if (manualTrigger) {
            manualTrigger = false
            return beginCapture(probability = 1f)
        }

        if (!gate.accepts(frame, length)) return null
        stats = stats.copy(framesPastGate = stats.framesPastGate + 1)

        val samples = toFloat(frame, length)
        if (vad.probability(samples) < vadThreshold) return null
        stats = stats.copy(framesPastVad = stats.framesPastVad + 1)

        val probability = wakeWord.process(samples)
        if (probability < wakeWordThreshold) return null

        return beginCapture(probability)
    }

    /**
     * Wechselt in die Aufnahme.
     *
     * Der Vorlauf kommt mit hinein, damit auch "Neon, wie spät ist es" in einem Zug
     * funktioniert — beim Handauslöser fängt er zusätzlich ab, wer schon losredet, während
     * er noch tippt.
     */
    private fun beginCapture(probability: Float): ListeningEvent {
        stats = stats.copy(wakeWordHits = stats.wakeWordHits + 1)
        state = State.AUFNAHME
        framesSinceWake = 0
        captured.clear()
        preRoll.latest().forEach { captured.add(it) }
        segmenter.reset()
        wakeWord.reset()

        return ListeningEvent.WakeWordDetected(probability)
    }

    private fun captureStep(frame: ShortArray, length: Int): ListeningEvent? {
        framesSinceWake++
        for (i in 0 until length) captured.add(frame[i])

        val probability = vad.probability(toFloat(frame, length))
        val event = segmenter.update(probability)

        val tooLong = framesSinceWake >= maxCaptureFrames
        val nothingCame = !segmenter.isSpeaking && framesSinceWake >= silenceTimeoutFrames

        return when {
            event == SpeechSegmenter.Event.SPRACHE_ENDET || tooLong -> finishCapture()
            nothingCame -> {
                resetToListening()
                ListeningEvent.CaptureTimedOut
            }
            else -> null
        }
    }

    private fun finishCapture(): ListeningEvent {
        val samples = ShortArray(captured.size) { captured[it] }
        resetToListening()
        return ListeningEvent.SpeechCaptured(samples)
    }

    private fun resetToListening() {
        state = State.LAUSCHEN
        captured.clear()
        framesSinceWake = 0
        manualTrigger = false
        preRoll.clear()
        segmenter.reset()
        vad.reset()
        wakeWord.reset()
    }

    /** Beendet eine laufende Aufnahme sofort — etwa, wenn der Nutzer abbricht. */
    fun cancel() {
        resetToListening()
        gate.reset()
    }

    /**
     * Treibt die Schleife auf einem eigenen Thread.
     *
     * Bewusst ein normaler Thread und kein Coroutine-Dispatcher: Das Lesen aus AudioRecord
     * blockiert, und dieser Thread soll die ganze Zeit genau hier stehen, statt einen
     * Pool-Thread zu belegen.
     */
    fun listen(
        source: AudioSource,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Flow<ListeningEvent> = callbackFlow {
        source.start()
        val buffer = ShortArray(source.frameSamples)

        val worker = thread(name = "neon-audio", priority = Thread.MAX_PRIORITY - 2) {
            try {
                while (isActive) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    process(buffer, read)?.let { trySend(it) }
                }
            } catch (_: InterruptedException) {
                // Regulärer Weg, die Schleife zu beenden.
            } finally {
                close()
            }
        }

        awaitClose {
            worker.interrupt()
            source.stop()
        }
    }.flowOn(dispatcher)

    private fun toFloat(frame: ShortArray, length: Int): FloatArray =
        FloatArray(length) { frame[it] / 32768f }
}
