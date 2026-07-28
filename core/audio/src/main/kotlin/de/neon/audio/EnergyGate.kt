package de.neon.audio

import kotlin.math.sqrt

/**
 * Die erste und billigste Stufe der Weckwort-Kaskade.
 *
 * Rechnet nichts weiter als die Lautstärke eines Blocks aus — ein paar hundert
 * Multiplikationen, praktisch kostenlos. In einem stillen Raum verwirft dieses Gatter über
 * neunundneunzig Prozent aller Blöcke, sodass VAD und Weckwortmodell gar nicht erst
 * anlaufen. Genau daran hängt die Akkulaufzeit im Dauerbetrieb.
 *
 * Der Schwellwert ist nicht fest, sondern folgt dem Grundrauschen: Ein fester Wert wäre in
 * einer stillen Wohnung zu hoch und im Zug zu niedrig.
 */
class EnergyGate(
    /** Wie weit über dem Grundrauschen ein Block liegen muss, um durchzukommen. */
    private val thresholdFactor: Double = 2.5,
    /** Untergrenze, damit absolute Stille nicht zu einem Rauschpegel von null führt. */
    private val minimumFloor: Double = 40.0,
    /** Wie schnell sich der Rauschpegel nach oben anpasst (träge). */
    private val riseRate: Double = 0.002,
    /** Wie schnell er nach unten folgt (flott, damit Neon nach Lärm wieder empfindlich wird). */
    private val fallRate: Double = 0.02,
    /**
     * Blöcke, die nach dem letzten lauten Block noch durchgelassen werden.
     *
     * Ohne diesen Nachlauf würde das Gatter in Sprechpausen zwischen zwei Silben zufallen
     * und das Weckwort in der Mitte zerschneiden.
     */
    private val hangoverFrames: Int = 12,
) {

    private var noiseFloor = minimumFloor
    private var hangover = 0

    /** Der aktuell geschätzte Rauschpegel. Nur für Diagnose und Tests. */
    val currentNoiseFloor: Double get() = noiseFloor

    fun reset() {
        noiseFloor = minimumFloor
        hangover = 0
    }

    /** @return true, wenn dieser Block an die nächste Stufe weitergereicht werden soll. */
    fun accepts(frame: ShortArray, length: Int = frame.size): Boolean {
        val level = rms(frame, length)

        val loud = level > noiseFloor * thresholdFactor

        // Der Rauschpegel wird nur an leisen Blöcken nachgeführt. Würde man ihn auch bei
        // Sprache anheben, würde das Gatter mitten im Satz taub.
        if (!loud) {
            val rate = if (level > noiseFloor) riseRate else fallRate
            noiseFloor += (level - noiseFloor) * rate
            if (noiseFloor < minimumFloor) noiseFloor = minimumFloor
        }

        if (loud) {
            hangover = hangoverFrames
            return true
        }
        if (hangover > 0) {
            hangover--
            return true
        }
        return false
    }

    companion object {
        fun rms(frame: ShortArray, length: Int = frame.size): Double {
            if (length <= 0) return 0.0
            var sum = 0.0
            for (i in 0 until length) {
                val v = frame[i].toDouble()
                sum += v * v
            }
            return sqrt(sum / length)
        }
    }
}
