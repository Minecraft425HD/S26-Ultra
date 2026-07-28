package de.neon.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Zweite Stufe der Kaskade: Ist das überhaupt Sprache?
 *
 * Läuft nur an Blöcken, die das Energie-Gatter durchgelassen hat. Sortiert Türenschlagen,
 * Musik und Straßenlärm aus, bevor das teurere Weckwortmodell anläuft.
 */
interface VoiceActivityDetector : Closeable {
    /** @return Sprachwahrscheinlichkeit von 0.0 bis 1.0. */
    fun probability(frame: FloatArray): Float
    fun reset()
}

/**
 * Silero-VAD über ONNX Runtime.
 *
 * Das Modell erwartet genau 512 Abtastwerte bei 16 kHz und führt einen rekurrenten Zustand
 * mit, der zwischen den Blöcken erhalten bleiben muss — deshalb ist diese Klasse
 * zustandsbehaftet und **nicht** threadsicher.
 *
 * Die Tensornamen und -formen entsprechen Silero VAD v5. Weicht eine andere Modellversion
 * davon ab, meldet ONNX Runtime das beim ersten Aufruf deutlich; verifiziert wird das beim
 * ersten Lauf auf dem Gerät.
 */
class SileroVadOnnx(
    modelBytes: ByteArray,
    private val sampleRate: Int = 16_000,
) : VoiceActivityDetector {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = environment.createSession(
        modelBytes,
        OrtSession.SessionOptions().apply {
            // Ein Thread genügt: Das Modell ist winzig, und dieser Pfad läuft dauerhaft.
            // Mehr Threads würden hier nur Aufweckvorgänge und damit Strom kosten.
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        },
    )

    /** Rekurrenter Zustand: (2, 1, 128). */
    private var state = FloatArray(2 * 1 * STATE_SIZE)

    override fun probability(frame: FloatArray): Float {
        require(frame.size == FRAME_SAMPLES) {
            "Silero-VAD erwartet $FRAME_SAMPLES Abtastwerte, bekam ${frame.size}"
        }

        val input = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(frame),
            longArrayOf(1, FRAME_SAMPLES.toLong()),
        )
        val stateTensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(state),
            longArrayOf(2, 1, STATE_SIZE.toLong()),
        )
        val srTensor = OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(longArrayOf(sampleRate.toLong())),
            longArrayOf(1),
        )

        return input.use { i ->
            stateTensor.use { s ->
                srTensor.use { sr ->
                    session.run(mapOf("input" to i, "state" to s, "sr" to sr)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val output = result.get(0).value as Array<FloatArray>

                        // Der neue Zustand muss übernommen werden, sonst verliert das Modell
                        // seinen Kontext und die Erkennung wird deutlich schlechter.
                        @Suppress("UNCHECKED_CAST")
                        val newState = result.get(1).value as Array<Array<FloatArray>>
                        var offset = 0
                        for (a in newState) {
                            for (b in a) {
                                b.copyInto(state, offset)
                                offset += b.size
                            }
                        }
                        output[0][0]
                    }
                }
            }
        }
    }

    override fun reset() {
        state = FloatArray(2 * 1 * STATE_SIZE)
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        const val FRAME_SAMPLES = 512
        private const val STATE_SIZE = 128
    }
}

/**
 * Entscheidet anhand der Wahrscheinlichkeiten, wann Sprache beginnt und endet.
 *
 * Zwei getrennte Schwellen mit Nachlauf: Einschalten ist streng, Ausschalten großzügig.
 * Sonst würde eine Atempause mitten im Satz als Satzende gewertet.
 */
class SpeechSegmenter(
    private val startThreshold: Float = 0.6f,
    private val endThreshold: Float = 0.35f,
    /** So viele stille Blöcke gelten als Satzende (32 × 32 ms ≈ 1 s). */
    private val silenceFramesToEnd: Int = 32,
) {

    private var speaking = false
    private var silentFrames = 0

    enum class Event { STILLE, SPRACHE_BEGINNT, SPRACHE_LAEUFT, SPRACHE_ENDET }

    val isSpeaking: Boolean get() = speaking

    fun reset() {
        speaking = false
        silentFrames = 0
    }

    fun update(probability: Float): Event {
        if (!speaking) {
            if (probability >= startThreshold) {
                speaking = true
                silentFrames = 0
                return Event.SPRACHE_BEGINNT
            }
            return Event.STILLE
        }

        if (probability >= endThreshold) {
            silentFrames = 0
            return Event.SPRACHE_LAEUFT
        }

        silentFrames++
        if (silentFrames >= silenceFramesToEnd) {
            speaking = false
            silentFrames = 0
            return Event.SPRACHE_ENDET
        }
        return Event.SPRACHE_LAEUFT
    }
}
