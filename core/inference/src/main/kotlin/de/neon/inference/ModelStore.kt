package de.neon.inference

import de.neon.router.ModelSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream

/**
 * Beschreibt, woher eine Modelldatei kommt.
 *
 * Bewusst aus einer JSON-Datei geladen statt im Code festgeschrieben: Welche Quantisierung
 * und welche Ablage die beste ist, ändert sich schneller als der Rest dieser App — und die
 * Startaufstellung soll ohnehin nach den Messungen aus M1 angepasst werden, ohne dass dafür
 * jemand Kotlin anfassen muss.
 */
@Serializable
data class ModelDownload(
    val modelId: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long = 0,
    /** Optional, aber empfohlen: Ein abgebrochener Download fällt sonst erst beim Laden auf. */
    val sha256: String? = null,
)

@Serializable
private data class Catalog(val models: List<ModelDownload>)

/**
 * Verwaltet die Modelldateien auf der Platte.
 *
 * Bei einem Terabyte Speicher ist Platz kein Thema — alle Modelle des Ensembles zusammen
 * belegen rund dreißig Gigabyte. Knapp ist ausschließlich der Arbeitsspeicher, und dafür
 * ist der [ModelLifecycleManager] zuständig.
 */
class ModelStore(private val root: File) : ModelFileResolver {

    init {
        if (!root.exists()) root.mkdirs()
    }

    override fun fileFor(model: ModelSpec): File? =
        File(root, "${model.id}.gguf").takeIf { it.isFile && it.length() > 0 }

    /**
     * Die Projektordatei eines Bildmodells, falls vorhanden.
     *
     * Getrennt gehalten, weil sie getrennt importiert wird: zwei Dateien von zwei Links,
     * und dazwischen kann jemand die App schließen.
     */
    override fun projectorFor(model: ModelSpec): File? {
        if (!model.needsProjector) return null
        return File(root, "${projectorId(model.id)}.gguf").takeIf { it.isFile && it.length() > 0 }
    }

    /**
     * Ob das Modell benutzbar ist.
     *
     * Bei einem Bildmodell heißt das: **beide** Dateien. Nur die Gewichte zu haben wäre die
     * unangenehmere Hälfte — der Server startet, und erst die Bildfrage geht daneben.
     */
    fun isAvailable(model: ModelSpec): Boolean {
        val gewichte = fileFor(model) ?: return false
        if (istBruchstueck(model, gewichte.length())) return false
        return !model.needsProjector || projectorFor(model) != null
    }

    /**
     * Ob die Datei zu klein ist, um das Modell zu sein, das draufsteht.
     *
     * **Der Fall, der das nötig gemacht hat.** Im Slot `qwen3-coder-7b` lag eine Datei von
     * 378 MB, wo der Eintrag 4608 MB nennt — acht Prozent. Neon hat sie trotzdem geladen und
     * benutzt, denn `isFile && length > 0` war die ganze Prüfung. Der Router schickte jede
     * Programmierfrage dorthin, weil im Eintrag „Coder 7B" steht, und bekam Antworten von
     * etwas, das dieses Modell nicht ist: Auf „mach mir eine QR-App" rief es das
     * Python-Werkzeug auf und übergab ihm Kotlin-Quelltext.
     *
     * **Warum ein Viertel und nicht die Hälfte.** Wer bewusst stärker quantisiert importiert,
     * landet leicht bei der Hälfte des Eintrags — `Q4_K_M` gegen `Q8_0` sind rund 53 Prozent,
     * und so jemanden auszusperren wäre falsch. Unter einem Viertel gibt es dagegen keine
     * Quantisierung mehr, die das trüge: Ein 7-B-Modell ist auch in `Q2_K` über zwei
     * Gigabyte groß. Was darunter liegt, ist ein abgebrochener Download.
     *
     * **Und erst ab einer Größe, bei der es etwas zu schützen gibt.** Ein Eintrag unter
     * [BRUCHSTUECK_AB_BYTES] beschreibt kein Sprachmodell — dort gibt es keinen Download, der
     * abbrechen könnte, und ein Verhältnis von einem Viertel sagt nichts. Ohne diese Grenze
     * würde die Prüfung Attrappen aus den Tests aussortieren und damit etwas anderes messen
     * als das, wogegen sie gebaut ist.
     *
     * Einträge ohne Größenangabe werden durchgelassen — eine Prüfung ohne Bezugsgröße
     * vergliche nichts.
     */
    fun istBruchstueck(model: ModelSpec, gemessen: Long): Boolean =
        model.sizeBytes >= BRUCHSTUECK_AB_BYTES &&
            gemessen > 0 &&
            gemessen * BRUCHSTUECK_NENNER < model.sizeBytes

    /** Unter welcher Kennung die Projektordatei abgelegt wird. */
    fun projectorId(modelId: String): String = "$modelId-mmproj"

    /** Wie viel Platz die heruntergeladenen Modelle belegen. */
    fun usedBytes(): Long = root.listFiles()?.sumOf { it.length() } ?: 0L

    fun delete(model: ModelSpec): Boolean = File(root, "${model.id}.gguf").delete()

    /**
     * Zielort für einen Import. Es wird immer in eine Teildatei geschrieben und erst nach
     * vollständiger Übertragung umbenannt — sonst hielte ein Abbruch die halbe Datei
     * für ein gültiges Modell, und der Fehler fiele erst beim Laden auf.
     */
    fun partialFileFor(modelId: String): File = File(root, "$modelId.gguf.part")

    fun finalize(modelId: String): Boolean {
        val part = partialFileFor(modelId)
        if (!part.isFile) return false
        return part.renameTo(File(root, "$modelId.gguf"))
    }

    sealed interface ImportResult {
        data class Ok(val bytes: Long) : ImportResult
        data class Failed(val reason: String) : ImportResult
    }

    /**
     * Übernimmt eine Modelldatei aus einem beliebigen Datenstrom.
     *
     * Der Weg, wie das Modell ohne Entwicklerwerkzeuge auf das Gerät kommt: Die Datei wird
     * per USB in den Download-Ordner kopiert und hier über den Dateiauswahldialog
     * übernommen — kein adb, keine Entwicklereinstellungen.
     *
     * @param onProgress bekommt die bisher kopierten Bytes; bei mehreren Gigabyte über eine
     * langsame Verbindung ist eine Anzeige kein Schmuck, sondern der Unterschied zwischen
     * "arbeitet" und "hängt".
     */
    fun importFrom(
        modelId: String,
        source: InputStream,
        expectedBytes: Long = 0,
        onProgress: (Long) -> Unit = {},
    ): ImportResult {
        val target = partialFileFor(modelId)
        target.delete()

        val copied = runCatching {
            source.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                        onProgress(total)
                    }
                    total
                }
            }
        }.getOrElse {
            target.delete()
            return ImportResult.Failed(it.message ?: "Das Kopieren ist fehlgeschlagen.")
        }

        if (copied == 0L) {
            target.delete()
            return ImportResult.Failed("Die Datei war leer.")
        }
        if (expectedBytes > 0 && copied != expectedBytes) {
            target.delete()
            return ImportResult.Failed(
                "Unvollständig übertragen: $copied von $expectedBytes Bytes."
            )
        }
        if (!looksLikeGguf(target)) {
            target.delete()
            return ImportResult.Failed("Das ist keine GGUF-Datei.")
        }
        if (!finalize(modelId)) {
            target.delete()
            return ImportResult.Failed("Die Datei ließ sich nicht ablegen.")
        }
        return ImportResult.Ok(copied)
    }

    /**
     * Prüft die vier Kennbytes am Dateianfang.
     *
     * Billiger als eine Prüfsumme über mehrere Gigabyte und fängt den häufigsten Fehler ab:
     * versehentlich eine HTML-Fehlerseite statt des Modells heruntergeladen zu haben.
     */
    private fun looksLikeGguf(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val magic = ByteArray(4)
            if (stream.read(magic) != 4) return false
            magic.decodeToString() == GGUF_MAGIC
        }
    }.getOrDefault(false)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Die Kennbytes am Anfang jeder GGUF-Datei. */
        private const val GGUF_MAGIC = "GGUF"

        private const val COPY_BUFFER_BYTES = 1 shl 20

        /** Unter einem Viertel des Eintrags gilt eine Datei als abgebrochen. */
        const val BRUCHSTUECK_NENNER = 4

        /** Ab dieser Eintragsgröße wird überhaupt auf Bruchstücke geprüft. */
        const val BRUCHSTUECK_AB_BYTES = 256L * 1024 * 1024

        /** Liest den Katalog, etwa aus `assets/models.json`. */
        fun parseCatalog(content: String): List<ModelDownload> =
            runCatching { json.decodeFromString<Catalog>(content).models }.getOrDefault(emptyList())
    }
}
