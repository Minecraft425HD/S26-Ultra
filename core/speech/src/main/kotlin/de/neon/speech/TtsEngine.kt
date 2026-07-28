package de.neon.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Gibt Text als Sprache aus.
 *
 * Wie bei der Erkennung eine Schnittstelle: Androids Stimme funktioniert sofort und ohne
 * Zusatzdateien, Piper klingt besser. Der Wechsel soll später keine Umbauten am Dienst
 * verlangen.
 */
interface TtsEngine : Closeable {
    suspend fun speak(text: String)

    /** Bricht die laufende Ausgabe sofort ab — Voraussetzung dafür, Neon unterbrechen zu können. */
    fun stop()

    val isSpeaking: Boolean
}

/**
 * Androids eingebaute Sprachausgabe.
 *
 * Verbraucht kaum Strom, weil die Synthese im Systemdienst läuft und nicht im Prozess von
 * Neon. Das ist für den Anfang genau die richtige Wahl.
 */
class AndroidTts(
    context: Context,
    private val locale: Locale = Locale.GERMAN,
    private val speechRate: Float = 1.05f,
) : TtsEngine {

    private val ready = CompletableDeferred<Boolean>()
    private val counter = AtomicLong()
    private var speaking = false

    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    override val isSpeaking: Boolean get() = speaking

    override suspend fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready.await()) return

        tts.language = locale
        tts.setSpeechRate(speechRate)

        val id = "neon-${counter.incrementAndGet()}"
        speaking = true
        try {
            suspendCancellableCoroutine { continuation ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id && continuation.isActive) continuation.resume(Unit)
                    }

                    @Deprecated("Von der Basisklasse gefordert", ReplaceWith(""))
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id && continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == id && continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        if (utteranceId == id && continuation.isActive) continuation.resume(Unit)
                    }
                })

                continuation.invokeOnCancellation { tts.stop() }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            }
        } finally {
            speaking = false
        }
    }

    override fun stop() {
        tts.stop()
        speaking = false
    }

    override fun close() {
        tts.stop()
        tts.shutdown()
    }
}

/**
 * Teilt einen Antworttext in Stücke, die einzeln gesprochen werden können.
 *
 * Damit beginnt Neon zu sprechen, sobald der erste Satz fertig ist, statt auf die komplette
 * Antwort zu warten. Bei einem lokalen Modell mit rund zwanzig Token je Sekunde macht das
 * den Unterschied zwischen einem Gespräch und einer Wartezeit aus.
 */
object SentenceChunker {

    private val boundary = Regex("(?<=[.!?…])\\s+|(?<=:)\\s+|\\n{2,}")

    /** Zu kurze Bruchstücke werden angehängt statt einzeln gesprochen. */
    private const val MIN_CHUNK_LENGTH = 24

    fun chunk(text: String): List<String> {
        val pieces = boundary.split(text.trim()).filter { it.isNotBlank() }
        if (pieces.isEmpty()) return emptyList()

        val result = ArrayList<String>()
        val current = StringBuilder()
        for (piece in pieces) {
            if (current.isNotEmpty()) current.append(' ')
            current.append(piece.trim())
            if (current.length >= MIN_CHUNK_LENGTH) {
                result += current.toString()
                current.clear()
            }
        }
        if (current.isNotEmpty()) {
            // Ein zu kurzer Rest wird an das letzte Stück gehängt, statt abgehackt zu klingen.
            if (result.isEmpty()) result += current.toString()
            else result[result.lastIndex] = result.last() + " " + current
        }
        return result
    }
}
