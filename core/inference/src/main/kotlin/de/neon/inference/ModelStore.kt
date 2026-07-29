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
        if (fileFor(model) == null) return false
        return !model.needsProjector || projectorFor(model) != null
    }

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

        /** Liest den Katalog, etwa aus `assets/models.json`. */
        fun parseCatalog(content: String): List<ModelDownload> =
            runCatching { json.decodeFromString<Catalog>(content).models }.getOrDefault(emptyList())
    }
}
