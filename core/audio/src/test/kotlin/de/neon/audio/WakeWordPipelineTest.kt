package de.neon.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Prüft die Zustandslogik der Hörschleife ohne Mikrofon und ohne ONNX-Modelle.
 *
 * VAD und Weckwortmodell sind hier steuerbare Attrappen. Dadurch sind Fälle wie
 * "Weckwort erkannt, aber danach kommt nichts" gezielt herstellbar — auf einem echten
 * Gerät wären sie kaum reproduzierbar.
 */
class WakeWordPipelineTest {

    /** Sagt für jeden Block genau die Wahrscheinlichkeit, die vorgegeben wurde. */
    private class ScriptedVad(var probability: Float = 0f) : VoiceActivityDetector {
        var resetCount = 0
        override fun probability(frame: FloatArray): Float = probability
        override fun reset() { resetCount++ }
        override fun close() = Unit
    }

    private class ScriptedWakeWord(var probability: Float = 0f) : WakeWordDetector {
        var calls = 0
        override fun process(frame: FloatArray): Float {
            calls++
            return probability
        }
        override fun reset() = Unit
        override fun close() = Unit
    }

    private val frameSamples = 512

    /** Ein lauter Block, der das Energie-Gatter sicher passiert. */
    private fun loudFrame() = ShortArray(frameSamples) { if (it % 2 == 0) 8000 else -8000 }

    /** Ein stiller Block. */
    private fun silentFrame() = ShortArray(frameSamples)

    private fun pipeline(
        vad: ScriptedVad,
        wakeWord: ScriptedWakeWord,
        silenceTimeoutFrames: Int = 90,
        maxCaptureFrames: Int = 900,
    ) = WakeWordPipeline(
        gate = EnergyGate(),
        vad = vad,
        wakeWord = wakeWord,
        segmenter = SpeechSegmenter(silenceFramesToEnd = 5),
        preRoll = PreRollBuffer.forSeconds(0.5),
        silenceTimeoutFrames = silenceTimeoutFrames,
        maxCaptureFrames = maxCaptureFrames,
    )

    @Test
    fun `Stille loest gar nichts aus und erreicht das Weckwortmodell nie`() {
        val vad = ScriptedVad(probability = 0f)
        val wakeWord = ScriptedWakeWord(probability = 1f)
        val pipeline = pipeline(vad, wakeWord)

        repeat(200) { assertNull(pipeline.process(silentFrame())) }

        // Das ist der Kern der Akku-Strategie: Ohne Geräusch läuft das teuerste Modell nie.
        assertEquals(0, wakeWord.calls)
        assertEquals(0L, pipeline.stats.framesPastGate)
    }

    @Test
    fun `Laerm passiert das Gatter aber nicht den VAD`() {
        val vad = ScriptedVad(probability = 0.1f)
        val wakeWord = ScriptedWakeWord(probability = 1f)
        val pipeline = pipeline(vad, wakeWord)

        repeat(50) { pipeline.process(loudFrame()) }

        assertTrue(pipeline.stats.framesPastGate > 0, "lautes Signal muss das Gatter passieren")
        assertEquals(0L, pipeline.stats.framesPastVad)
        assertEquals(0, wakeWord.calls, "der VAD muss das Weckwortmodell abschirmen")
    }

    @Test
    fun `erkennt das Weckwort und beginnt die Aufnahme`() {
        val vad = ScriptedVad(probability = 0.9f)
        val wakeWord = ScriptedWakeWord(probability = 0f)
        val pipeline = pipeline(vad, wakeWord)

        // Ein paar Blöcke Sprache ohne Weckwort.
        repeat(5) { assertNull(pipeline.process(loudFrame())) }

        wakeWord.probability = 0.95f
        val event = pipeline.process(loudFrame())
        val detected = assertIs<ListeningEvent.WakeWordDetected>(event)
        assertEquals(0.95f, detected.probability)
        assertEquals(1L, pipeline.stats.wakeWordHits)
    }

    @Test
    fun `nimmt den Vorlauf mit in die Aufnahme`() {
        val vad = ScriptedVad(probability = 0.9f)
        val wakeWord = ScriptedWakeWord(probability = 0f)
        val pipeline = pipeline(vad, wakeWord)

        repeat(10) { pipeline.process(loudFrame()) }
        wakeWord.probability = 0.95f
        pipeline.process(loudFrame())

        // Weiter sprechen, dann verstummen.
        wakeWord.probability = 0f
        repeat(3) { pipeline.process(loudFrame()) }
        vad.probability = 0.0f
        var captured: ListeningEvent.SpeechCaptured? = null
        repeat(10) {
            val event = pipeline.process(silentFrame())
            if (event is ListeningEvent.SpeechCaptured) captured = event
        }

        val result = requireNotNull(captured) { "die Aufnahme hätte enden müssen" }
        // Der Vorlauf muss deutlich mehr Material liefern als die Blöcke nach dem Weckwort.
        assertTrue(
            result.samples.size > 4 * frameSamples,
            "zu wenig Vorlauf: ${result.samples.size} Abtastwerte",
        )
    }

    @Test
    fun `beendet die Aufnahme bei einer Sprechpause`() {
        val vad = ScriptedVad(probability = 0.9f)
        val wakeWord = ScriptedWakeWord(probability = 0.95f)
        val pipeline = pipeline(vad, wakeWord)

        assertIs<ListeningEvent.WakeWordDetected>(pipeline.process(loudFrame()))

        wakeWord.probability = 0f
        repeat(5) { assertNull(pipeline.process(loudFrame())) }

        // Verstummen: Nach fünf stillen Blöcken gilt die Äußerung als beendet.
        vad.probability = 0.0f
        var captured: ListeningEvent? = null
        repeat(6) { captured = pipeline.process(silentFrame()) ?: captured }
        assertIs<ListeningEvent.SpeechCaptured>(captured)
    }

    @Test
    fun `bricht ab wenn nach dem Weckwort nichts kommt`() {
        val vad = ScriptedVad(probability = 0.9f)
        val wakeWord = ScriptedWakeWord(probability = 0.95f)
        val pipeline = pipeline(vad, wakeWord, silenceTimeoutFrames = 8)

        assertIs<ListeningEvent.WakeWordDetected>(pipeline.process(loudFrame()))

        // Sofort still — der Nutzer hat den Namen nur beiläufig gesagt.
        vad.probability = 0f
        var timeout: ListeningEvent? = null
        repeat(12) { timeout = pipeline.process(silentFrame()) ?: timeout }

        assertIs<ListeningEvent.CaptureTimedOut>(timeout)
    }

    @Test
    fun `begrenzt die Aufnahmelaenge`() {
        val vad = ScriptedVad(probability = 0.9f)
        val wakeWord = ScriptedWakeWord(probability = 0.95f)
        val pipeline = pipeline(vad, wakeWord, maxCaptureFrames = 20)

        pipeline.process(loudFrame())
        wakeWord.probability = 0f

        var captured: ListeningEvent? = null
        repeat(30) { captured = pipeline.process(loudFrame()) ?: captured }

        // Ein Dauerton darf nicht unbegrenzt Speicher belegen.
        assertIs<ListeningEvent.SpeechCaptured>(captured)
    }

    @Test
    fun `kehrt nach der Aufnahme ins Lauschen zurueck`() {
        val vad = ScriptedVad(probability = 0.9f)
        val wakeWord = ScriptedWakeWord(probability = 0.95f)
        val pipeline = pipeline(vad, wakeWord)

        pipeline.process(loudFrame())

        // Erst sprechen, dann verstummen — sonst endet die Aufnahme nicht, sondern
        // läuft in den Zeitablauf.
        wakeWord.probability = 0f
        repeat(3) { pipeline.process(loudFrame()) }
        vad.probability = 0f
        var captured: ListeningEvent? = null
        repeat(6) { captured = pipeline.process(silentFrame()) ?: captured }
        assertIs<ListeningEvent.SpeechCaptured>(captured)

        // Zweite Runde muss genauso funktionieren wie die erste.
        vad.probability = 0.9f
        wakeWord.probability = 0.95f
        assertIs<ListeningEvent.WakeWordDetected>(pipeline.process(loudFrame()))
        assertEquals(2L, pipeline.stats.wakeWordHits)
    }

    @Test
    fun `der Handausloeser startet die Aufnahme ohne Weckwortmodell`() {
        // Genau der Zustand nach dem Klonen des Projekts: kein trainiertes Weckwort.
        // Ohne diesen Weg wäre Neon vollständig stumm.
        val vad = ScriptedVad(probability = 0.9f)
        val wakeWord = ScriptedWakeWord(probability = 0f)
        val pipeline = pipeline(vad, wakeWord)

        assertNull(pipeline.process(loudFrame()))

        pipeline.triggerManually()
        assertIs<ListeningEvent.WakeWordDetected>(pipeline.process(loudFrame()))
    }

    @Test
    fun `der Handausloeser wirkt auch in vollkommener Stille`() {
        // Das Energie-Gatter würde diese Blöcke verwerfen. Wer den Knopf drückt, will aber
        // aufnehmen — die Filter dürfen ihn nicht überstimmen.
        val vad = ScriptedVad(probability = 0f)
        val wakeWord = ScriptedWakeWord(probability = 0f)
        val pipeline = pipeline(vad, wakeWord)

        pipeline.triggerManually()
        assertIs<ListeningEvent.WakeWordDetected>(pipeline.process(silentFrame()))
    }

    @Test
    fun `der Handausloeser wirkt genau einmal`() {
        val vad = ScriptedVad(probability = 0.9f)
        val pipeline = pipeline(vad, ScriptedWakeWord(probability = 0f))

        pipeline.triggerManually()
        assertIs<ListeningEvent.WakeWordDetected>(pipeline.process(loudFrame()))

        // Aufnahme beenden.
        vad.probability = 0f
        repeat(8) { pipeline.process(silentFrame()) }

        // Danach ist wieder Ruhe, bis erneut ausgelöst wird.
        vad.probability = 0.9f
        assertNull(pipeline.process(loudFrame()))
    }

    @Test
    fun `nimmt nach dem Handausloeser eine vollstaendige Aeusserung auf`() {
        val vad = ScriptedVad(probability = 0.9f)
        val pipeline = pipeline(vad, ScriptedWakeWord(probability = 0f))

        pipeline.triggerManually()
        pipeline.process(loudFrame())
        repeat(5) { pipeline.process(loudFrame()) }

        vad.probability = 0f
        var captured: ListeningEvent? = null
        repeat(8) { captured = pipeline.process(silentFrame()) ?: captured }

        val result = assertIs<ListeningEvent.SpeechCaptured>(captured)
        assertTrue(result.samples.isNotEmpty())
    }

    @Test
    fun `zaehlt die Durchlassquoten der Stufen`() {
        val vad = ScriptedVad(probability = 0f)
        val pipeline = pipeline(vad, ScriptedWakeWord())

        repeat(100) { pipeline.process(silentFrame()) }
        assertEquals(100L, pipeline.stats.framesRead)
        assertEquals(0.0, pipeline.stats.gatePassRate)
        assertEquals(0.0, pipeline.stats.vadPassRate)
    }
}
