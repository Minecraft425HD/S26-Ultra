package de.neon.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.io.Closeable
import java.io.InputStream

/**
 * Liefert Audio in festen Blöcken.
 *
 * Als Schnittstelle ausgelegt, damit Tests WAV-Dateien einspeisen können, statt ein
 * Mikrofon zu brauchen. Ohne das wäre die gesamte Weckwort-Kaskade nur auf einem echten
 * Gerät prüfbar.
 */
interface AudioSource : Closeable {

    val sampleRate: Int

    /** Blockgröße in Abtastwerten. Silero-VAD und openWakeWord erwarten beide 512 bei 16 kHz. */
    val frameSamples: Int

    fun start()

    /**
     * Füllt [into] mit dem nächsten Block.
     *
     * @return Anzahl gelesener Abtastwerte, oder -1 am Ende der Quelle.
     */
    fun read(into: ShortArray): Int

    fun stop()
}

/**
 * Das Mikrofon des Geräts.
 *
 * Bewusst 16 kHz Mono: Das ist genau das, was VAD, Weckwort und Whisper erwarten. Höher
 * abzutasten und danach herunterzurechnen würde nur Strom kosten — und dieser Pfad läuft
 * dauerhaft.
 */
class MicrophoneAudioSource(
    override val sampleRate: Int = SAMPLE_RATE,
    override val frameSamples: Int = FRAME_SAMPLES,
    /**
     * Echokompensation, damit Neon sich beim Sprechen nicht selbst hört. Voraussetzung
     * dafür, dass man ihn unterbrechen kann.
     */
    private val enableEchoCancellation: Boolean = true,
) : AudioSource {

    private var record: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO wird vom Dienst vor dem Start geprüft.
    override fun start() {
        if (record != null) return

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "AudioRecord meldet keine gültige Puffergröße ($minBuffer)" }

        // Etwas Reserve, damit ein kurzzeitig verzögerter Lese-Thread keine Blöcke verliert.
        val bufferSize = maxOf(minBuffer, frameSamples * BYTES_PER_SAMPLE * BUFFER_FRAMES)

        val created = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        check(created.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord konnte nicht initialisiert werden"
        }

        if (enableEchoCancellation) {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(created.audioSessionId)
                    ?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(created.audioSessionId)
                    ?.apply { enabled = true }
            }
        }

        created.startRecording()
        record = created
    }

    override fun read(into: ShortArray): Int =
        record?.read(into, 0, into.size, AudioRecord.READ_BLOCKING) ?: -1

    override fun stop() {
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        echoCanceler?.release()
        echoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null
    }

    override fun close() = stop()

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 512
        private const val BYTES_PER_SAMPLE = 2
        private const val BUFFER_FRAMES = 10
    }
}

/**
 * Spielt 16-Bit-PCM aus einem Datenstrom ab.
 *
 * Der Grund, warum die Kaskade ohne Gerät prüfbar ist: Testfälle sind Audiodateien mit
 * bekanntem Inhalt statt gesprochener Sprache im richtigen Moment.
 */
class StreamAudioSource(
    private val stream: InputStream,
    override val sampleRate: Int = MicrophoneAudioSource.SAMPLE_RATE,
    override val frameSamples: Int = MicrophoneAudioSource.FRAME_SAMPLES,
    /** Bei WAV-Dateien die 44 Byte des Kopfs überspringen. */
    private val skipBytes: Int = 0,
) : AudioSource {

    private val byteBuffer = ByteArray(frameSamples * 2)
    private var started = false

    override fun start() {
        if (started) return
        if (skipBytes > 0) stream.skip(skipBytes.toLong())
        started = true
    }

    override fun read(into: ShortArray): Int {
        var read = 0
        while (read < byteBuffer.size) {
            val n = stream.read(byteBuffer, read, byteBuffer.size - read)
            if (n < 0) break
            read += n
        }
        if (read < 2) return -1

        val samples = read / 2
        for (i in 0 until samples) {
            // 16-Bit PCM, Little Endian.
            val low = byteBuffer[i * 2].toInt() and 0xFF
            val high = byteBuffer[i * 2 + 1].toInt()
            into[i] = ((high shl 8) or low).toShort()
        }
        // Ein angebrochener letzter Block wird mit Stille aufgefüllt.
        for (i in samples until into.size) into[i] = 0
        return samples
    }

    override fun stop() {
        started = false
    }

    override fun close() {
        stop()
        stream.close()
    }
}
