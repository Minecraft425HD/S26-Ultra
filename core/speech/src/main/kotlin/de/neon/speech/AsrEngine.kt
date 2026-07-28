package de.neon.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.MainThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.OutputStream
import kotlin.coroutines.resume

/** Das Ergebnis einer Spracherkennung. */
data class Transcript(
    val text: String,
    val confidence: Float,
    val languageTag: String,
)

/**
 * Wandelt aufgenommene Abtastwerte in Text.
 *
 * Als Schnittstelle ausgelegt, weil Neon zwei Wege dafür kennt: Androids eingebaute
 * Erkennung (schnell, systemintegriert) und Whisper (genauer, vollständig unter eigener
 * Kontrolle). Welcher besser ist, entscheidet die Messung auf dem Gerät — nicht die
 * Architektur.
 */
interface AsrEngine : Closeable {
    suspend fun transcribe(samples: ShortArray, sampleRate: Int = 16_000): Transcript?
}

/**
 * Androids eingebaute Erkennung, auf dem Gerät gerechnet.
 *
 * Der entscheidende Punkt: Seit Android 13 kann man dem Erkenner fertige Abtastwerte über
 * eine Pipe übergeben, statt ihn selbst am Mikrofon horchen zu lassen. Das ist für Neon
 * zwingend — die Aufnahme enthält den Vorlauf aus dem Ringpuffer, und der ist bereits
 * vorbei, wenn die Erkennung startet.
 */
class AndroidOnDeviceAsr(
    private val context: Context,
    private val languageTag: String = "de-DE",
) : AsrEngine {

    private var recognizer: SpeechRecognizer? = null

    @MainThread
    private fun ensureRecognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        check(SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            "Auf diesem Gerät ist keine lokale Spracherkennung verfügbar"
        }
        return SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer = it }
    }

    override suspend fun transcribe(samples: ShortArray, sampleRate: Int): Transcript? =
        withContext(Dispatchers.Main) {
            val recognizer = ensureRecognizer()
            val pipe = ParcelFileDescriptor.createPipe()
            val readEnd = pipe[0]
            val writeEnd = pipe[1]

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                // Statt Mikrofon: die bereits aufgenommenen Abtastwerte.
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
            }

            // Der Schreib-Thread muss laufen, während der Erkenner liest — sonst blockiert
            // die Pipe, sobald ihr Puffer voll ist.
            val writer = Thread({
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { it.writePcm16(samples) }
            }, "neon-asr-feed").apply { start() }

            try {
                suspendCancellableCoroutine { continuation ->
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onResults(results: Bundle) {
                            val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val scores = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                            val best = texts?.firstOrNull()
                            if (continuation.isActive) {
                                continuation.resume(
                                    best?.let {
                                        Transcript(it, scores?.firstOrNull() ?: 0f, languageTag)
                                    }
                                )
                            }
                        }

                        override fun onError(error: Int) {
                            // Kein Ausnahmefall: Wer den Namen nur beiläufig sagt, erzeugt
                            // regelmäßig ERROR_NO_MATCH. Der Dienst geht dann still zurück
                            // ins Lauschen.
                            if (continuation.isActive) continuation.resume(null)
                        }

                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onPartialResults(partialResults: Bundle?) = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    })

                    continuation.invokeOnCancellation { recognizer.cancel() }
                    recognizer.startListening(intent)
                }
            } finally {
                writer.interrupt()
                runCatching { readEnd.close() }
            }
        }

    override fun close() {
        recognizer?.destroy()
        recognizer = null
    }

    private companion object {
        /** Entspricht AudioFormat.ENCODING_PCM_16BIT. */
        const val ENCODING_PCM_16BIT = 2
    }
}

/** Schreibt 16-Bit-PCM als Little Endian. */
internal fun OutputStream.writePcm16(samples: ShortArray) {
    val bytes = ByteArray(samples.size * 2)
    for (i in samples.indices) {
        val value = samples[i].toInt()
        bytes[i * 2] = (value and 0xFF).toByte()
        bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
    }
    write(bytes)
    flush()
}
