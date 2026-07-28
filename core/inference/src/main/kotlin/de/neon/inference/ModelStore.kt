package de.neon.inference

import de.neon.router.ModelSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

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

    fun isAvailable(model: ModelSpec): Boolean = fileFor(model) != null

    /** Wie viel Platz die heruntergeladenen Modelle belegen. */
    fun usedBytes(): Long = root.listFiles()?.sumOf { it.length() } ?: 0L

    fun delete(model: ModelSpec): Boolean = File(root, "${model.id}.gguf").delete()

    /**
     * Zielort für einen Download. Es wird immer in eine Teildatei geschrieben und erst nach
     * dem vollständigen Herunterladen umbenannt — sonst hielte ein Abbruch die halbe Datei
     * für ein gültiges Modell.
     */
    fun partialFileFor(modelId: String): File = File(root, "$modelId.gguf.part")

    fun finalize(modelId: String): Boolean {
        val part = partialFileFor(modelId)
        if (!part.isFile) return false
        return part.renameTo(File(root, "$modelId.gguf"))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Liest den Katalog, etwa aus `assets/models.json`. */
        fun parseCatalog(content: String): List<ModelDownload> =
            runCatching { json.decodeFromString<Catalog>(content).models }.getOrDefault(emptyList())
    }
}
