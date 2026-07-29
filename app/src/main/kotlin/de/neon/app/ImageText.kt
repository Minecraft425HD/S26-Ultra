package de.neon.app

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import de.neon.platform.NeonLog
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Liest Text aus Bildern.
 *
 * **Warum das überhaupt nötig ist.** Qwen3 4B ist ein reines Textmodell — es kann ein Bild
 * technisch nicht sehen. Ein Bildmodell wäre der vollständigere Weg, kostet aber einen
 * weiteren Download und bei jeder Bildfrage einen Serverneustart, weil llama-server je Lauf
 * genau ein Modell bedient. Die Texterkennung deckt den häufigsten Fall sofort ab:
 * Bildschirmfotos, abfotografierte Briefe, Zettel, Rechnungen.
 *
 * **Was sie nicht kann.** Sie liest Buchstaben, sie versteht keine Bilder. Ein Foto vom Hund
 * ergibt nichts — und das steht dann auch so im Befund, statt als leerer Anhang durchzugehen.
 *
 * Läuft vollständig im Gerät. Das Modell liegt in der APK; es wird nichts nachgeladen und
 * kein Bild verschickt.
 */
class ImageText(private val context: Context) {

    private val erkenner by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** @return der erkannte Text, oder `null`, wenn nichts zu lesen war. */
    suspend fun read(uri: Uri): String? = runCatching {
        val bild = InputImage.fromFilePath(context, uri)
        val ergebnis = suspendCoroutine { fortsetzung ->
            erkenner.process(bild)
                .addOnSuccessListener { fortsetzung.resume(it) }
                .addOnFailureListener {
                    NeonLog.e(TAG, "Texterkennung fehlgeschlagen", it)
                    fortsetzung.resume(null)
                }
        }

        // Blockweise zusammensetzen statt über `text`: So bleiben Absätze erhalten, und
        // eine Tabelle oder ein Formular liest sich hinterher noch wie eines.
        ergebnis?.textBlocks
            ?.joinToString("\n\n") { block -> block.lines.joinToString("\n") { it.text } }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.getOrElse {
        NeonLog.e(TAG, "Bild nicht lesbar", it)
        null
    }

    private companion object {
        const val TAG = "NeonOcr"
    }
}
