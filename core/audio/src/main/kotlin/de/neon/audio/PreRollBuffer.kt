package de.neon.audio

/**
 * Ringpuffer, der die letzten Sekunden Audio vorhält.
 *
 * Wird gebraucht, weil das Weckwort erst *nach* dem Sprechen erkannt wird. Sagt jemand
 * "Neon, wie spät ist es?" in einem Zug, ist der Anfang der eigentlichen Frage bereits
 * vorbei, wenn die Erkennung anschlägt. Ohne diesen Vorlauf hörte Neon nur "spät ist es".
 */
class PreRollBuffer(
    private val capacitySamples: Int,
) {

    private val buffer = ShortArray(capacitySamples)
    private var writeIndex = 0
    private var filled = 0

    val size: Int get() = filled

    fun clear() {
        writeIndex = 0
        filled = 0
    }

    fun write(frame: ShortArray, length: Int = frame.size) {
        for (i in 0 until length) {
            buffer[writeIndex] = frame[i]
            writeIndex = (writeIndex + 1) % capacitySamples
        }
        filled = minOf(filled + length, capacitySamples)
    }

    /**
     * Gibt die letzten [samples] Abtastwerte in chronologischer Reihenfolge zurück.
     *
     * Sind weniger vorhanden, kommt zurück, was da ist — das ist kein Fehler, sondern der
     * Normalfall in den ersten Sekunden nach dem Start.
     */
    fun latest(samples: Int = filled): ShortArray {
        val count = minOf(samples, filled)
        val result = ShortArray(count)
        var index = (writeIndex - count + capacitySamples) % capacitySamples
        for (i in 0 until count) {
            result[i] = buffer[index]
            index = (index + 1) % capacitySamples
        }
        return result
    }

    companion object {
        /**
         * Zwei Sekunden reichen für "Hey Neon, ..." plus den Anfang der Frage.
         */
        fun forSeconds(seconds: Double, sampleRate: Int = 16_000): PreRollBuffer =
            PreRollBuffer((seconds * sampleRate).toInt())
    }
}
