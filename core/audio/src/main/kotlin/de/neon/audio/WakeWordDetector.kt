package de.neon.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer

/**
 * Dritte und teuerste Stufe der Kaskade: Wurde "Neon" gesagt?
 *
 * Läuft nur an Blöcken, die Energie-Gatter und VAD passiert haben.
 */
interface WakeWordDetector : Closeable {
    /** @return Wahrscheinlichkeit von 0.0 bis 1.0, dass das Weckwort gerade endete. */
    fun process(frame: FloatArray): Float
    fun reset()
}

/**
 * openWakeWord über ONNX Runtime.
 *
 * Das Verfahren besteht aus drei hintereinandergeschalteten Modellen:
 *
 *  1. **Melspektrogramm** — Rohaudio zu Mel-Bändern.
 *  2. **Einbettung** — ein vortrainierter Sprachmerkmals-Extraktor. Der ist gemeinsam für
 *     alle Weckwörter und macht den Großteil der Rechenzeit aus.
 *  3. **Weckwortmodell** — der einzige selbst trainierte Teil, winzig
 *     (wenige hundert Kilobyte) und austauschbar.
 *
 * Genau diese Aufteilung macht ein eigenes Weckwort praktikabel: Nur das dritte Modell muss
 * für "Neon" trainiert werden.
 *
 * Die Tensorformen folgen der openWakeWord-Referenz. Sie werden beim ersten Lauf auf dem
 * Gerät gegen die tatsächlich ausgelieferten Modelldateien geprüft — ONNX Runtime meldet
 * eine Abweichung unmissverständlich.
 */
class OpenWakeWordDetector(
    melModelBytes: ByteArray,
    embeddingModelBytes: ByteArray,
    wakeWordModelBytes: ByteArray,
    /**
     * Ab dieser Wahrscheinlichkeit gilt das Weckwort als erkannt.
     *
     * "Neon" ist mit zwei Silben kurz und damit anfällig für Fehlauslösungen. Der Wert ist
     * bewusst hoch angesetzt und wird nach der Messung auf dem Gerät nachjustiert.
     */
    val threshold: Float = 0.7f,
) : WakeWordDetector {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()

    private fun options() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
    }

    private val melSession = environment.createSession(melModelBytes, options())
    private val embeddingSession = environment.createSession(embeddingModelBytes, options())
    private val wakeWordSession = environment.createSession(wakeWordModelBytes, options())

    /** Rollendes Fenster der Mel-Bänder, aus dem die Einbettungen gebildet werden. */
    private val melFrames = ArrayDeque<FloatArray>()

    /** Rollendes Fenster der Einbettungen, das ins Weckwortmodell geht. */
    private val embeddings = ArrayDeque<FloatArray>()

    override fun process(frame: FloatArray): Float {
        appendMelFrames(frame)

        // Erst wenn genug Mel-Bänder für ein volles Einbettungsfenster da sind, geht es weiter.
        while (melFrames.size >= EMBEDDING_WINDOW) {
            embeddings.addLast(computeEmbedding())
            // Das Fenster rückt in Schritten vor, statt jedes Mal komplett neu zu rechnen.
            repeat(EMBEDDING_STRIDE) { melFrames.removeFirstOrNull() }
            while (embeddings.size > WAKEWORD_WINDOW) embeddings.removeFirst()
        }

        if (embeddings.size < WAKEWORD_WINDOW) return 0f
        return classify()
    }

    private fun appendMelFrames(frame: FloatArray) {
        val input = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(frame),
            longArrayOf(1, frame.size.toLong()),
        )
        input.use {
            melSession.run(mapOf(melSession.inputNames.first() to it)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val mel = result.get(0).value as Array<Array<Array<FloatArray>>>
                for (row in mel[0][0]) {
                    // Die Skalierung stammt aus der openWakeWord-Referenzimplementierung.
                    melFrames.addLast(FloatArray(row.size) { i -> row[i] / 10f + 2f })
                }
            }
        }
        // Nicht unbegrenzt wachsen lassen, falls das Weckwortfenster nie voll wird.
        while (melFrames.size > EMBEDDING_WINDOW * 4) melFrames.removeFirst()
    }

    private fun computeEmbedding(): FloatArray {
        val window = FloatArray(EMBEDDING_WINDOW * MEL_BINS)
        var offset = 0
        for (i in 0 until EMBEDDING_WINDOW) {
            melFrames.elementAt(i).copyInto(window, offset)
            offset += MEL_BINS
        }

        val input = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(window),
            longArrayOf(1, EMBEDDING_WINDOW.toLong(), MEL_BINS.toLong(), 1),
        )
        return input.use {
            embeddingSession.run(mapOf(embeddingSession.inputNames.first() to it)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val output = result.get(0).value as Array<Array<Array<FloatArray>>>
                output[0][0][0].copyOf()
            }
        }
    }

    private fun classify(): Float {
        val window = FloatArray(WAKEWORD_WINDOW * EMBEDDING_SIZE)
        var offset = 0
        for (embedding in embeddings) {
            embedding.copyInto(window, offset)
            offset += EMBEDDING_SIZE
        }

        val input = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(window),
            longArrayOf(1, WAKEWORD_WINDOW.toLong(), EMBEDDING_SIZE.toLong()),
        )
        return input.use {
            wakeWordSession.run(mapOf(wakeWordSession.inputNames.first() to it)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val output = result.get(0).value as Array<FloatArray>
                output[0][0]
            }
        }
    }

    override fun reset() {
        melFrames.clear()
        embeddings.clear()
    }

    override fun close() {
        runCatching { melSession.close() }
        runCatching { embeddingSession.close() }
        runCatching { wakeWordSession.close() }
    }

    companion object {
        const val MEL_BINS = 32
        const val EMBEDDING_WINDOW = 76
        const val EMBEDDING_STRIDE = 8
        const val EMBEDDING_SIZE = 96
        const val WAKEWORD_WINDOW = 16
    }
}
