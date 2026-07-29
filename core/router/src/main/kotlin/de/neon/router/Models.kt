package de.neon.router

import kotlinx.serialization.Serializable

/** Was ein Modell technisch kann. Harte Voraussetzung, kein Qualitätsmerkmal. */
@Serializable
enum class Capability {
    TEXT,
    VISION,
    TOOL_CALLING,
    /** Erzeugt vor der Antwort eine Gedankenkette. Teuer, aber stark bei Logik. */
    REASONING,
    EMBEDDING,
}

/** Die Rolle, die ein Modell im Ensemble spielt. */
@Serializable
enum class ModelRole {
    /** Winzig, dauerhaft geladen, klassifiziert nur. */
    ROUTER,

    /** Erzeugt Vektoren für Gedächtnis und kNN. Dauerhaft geladen. */
    EMBEDDING,

    /** Das Arbeitspferd für Alltagsfragen. */
    ALLTAG,

    /** Groß und langsam, für echtes Nachdenken. */
    DENKER,

    /** Spezialist für Programmierung. */
    CODE,

    /** Versteht Bilder. */
    VISION,
}

/**
 * Beschreibung eines lokalen Modells.
 *
 * [minComplexity] und [maxComplexity] beschreiben das Band, in dem der Einsatz sinnvoll ist.
 * Die Untergrenze verhindert, dass ein 8B-Modell eine Begrüßung beantwortet; die Obergrenze
 * verhindert, dass ein 0.6B-Modell an einer mehrstufigen Aufgabe scheitert.
 */
@Serializable
data class ModelSpec(
    val id: String,
    val displayName: String,
    val role: ModelRole,
    /** Dateigröße auf der Platte; zugleich die Untergrenze des RAM-Bedarfs. */
    val sizeBytes: Long,
    val capabilities: Set<Capability>,
    /** Kategorien, in denen dieses Modell besonders stark ist. */
    val strengths: Set<TaskCategory> = emptySet(),
    val minComplexity: Int = RouteAnalysis.MIN_COMPLEXITY,
    val maxComplexity: Int = RouteAnalysis.MAX_COMPLEXITY,
    /** Geschätzte Ausgabegeschwindigkeit auf dem Zielgerät. Wird in M1 gemessen. */
    val tokensPerSecond: Double,
    /** Kaltstartkosten in Millisekunden, wenn das Modell nicht im Seitencache liegt. */
    val loadCostMillis: Long,
    /** Relativer Energiebedarf je Token. 1.0 = Alltagsmodell. */
    val energyPerToken: Double,
    /** Darf dieses Modell dauerhaft im Speicher bleiben? */
    val residentByDefault: Boolean = false,
    /**
     * Kennung der Projektordatei, falls das Modell eine braucht.
     *
     * Bildmodelle bestehen in llama.cpp aus **zwei** Dateien: den Gewichten und einem
     * Projektor, der Bildkacheln in den Raum des Sprachmodells übersetzt. Ohne die zweite
     * startet der Server zwar, kann aber keine Bilder ansehen — ein Fehler, der sich sonst
     * erst zeigt, wenn jemand ein Bild anhängt und eine ratlose Antwort bekommt.
     */
    val projectorFileName: String? = null,
) {

    /** Ob dieses Modell ohne eine zweite Datei unvollständig wäre. */
    val needsProjector: Boolean get() = projectorFileName != null
    init {
        require(minComplexity <= maxComplexity) {
            "$id: minComplexity ($minComplexity) darf nicht über maxComplexity ($maxComplexity) liegen"
        }
    }

    fun supports(capability: Capability): Boolean = capability in capabilities

    /** Reicht dieses Modell für die geschätzte Komplexität aus? */
    fun handlesComplexity(complexity: Int): Boolean = complexity <= maxComplexity
}

/**
 * Die Liste der auf dem Gerät verfügbaren Modelle.
 *
 * Bewusst als Datenstruktur und nicht fest verdrahtet: Die Startaufstellung wird nach den
 * Messungen aus M1 angepasst, ohne dass Router-Logik angefasst werden muss.
 */
class ModelRegistry(models: List<ModelSpec>) {

    val models: List<ModelSpec> = models.toList()

    private val byId: Map<String, ModelSpec> = models.associateBy { it.id }

    init {
        require(models.isNotEmpty()) { "Die Modell-Registry darf nicht leer sein" }
        require(byId.size == models.size) { "Doppelte Modell-IDs in der Registry" }
    }

    operator fun get(id: String): ModelSpec? = byId[id]

    fun require(id: String): ModelSpec =
        byId[id] ?: throw IllegalArgumentException("Unbekanntes Modell: $id")

    fun withRole(role: ModelRole): List<ModelSpec> = models.filter { it.role == role }

    /** Modelle, die tatsächlich Antworten erzeugen — Router und Embedder gehören nicht dazu. */
    fun generativeModels(): List<ModelSpec> =
        models.filter { it.role != ModelRole.ROUTER && it.role != ModelRole.EMBEDDING }

    fun residentModels(): List<ModelSpec> = models.filter { it.residentByDefault }

    companion object {
        private const val GB = 1024L * 1024L * 1024L
        private const val MB = 1024L * 1024L

        /**
         * Startaufstellung für das Galaxy S26 Ultra (16 GB RAM, Snapdragon 8 Elite Gen 5).
         *
         * Die Zahlen für Geschwindigkeit und Energie sind begründete Schätzungen, keine
         * Messwerte. Der Diagnose-Screen aus M1 ersetzt sie durch echte Messungen auf dem
         * Gerät — bis dahin sind sie ausdrücklich vorläufig.
         */
        fun defaultForS26Ultra(): ModelRegistry = ModelRegistry(
            listOf(
                ModelSpec(
                    id = "qwen3-0.6b-router",
                    displayName = "Qwen3 0.6B (Router)",
                    role = ModelRole.ROUTER,
                    sizeBytes = 400 * MB,
                    capabilities = setOf(Capability.TEXT),
                    maxComplexity = 1,
                    tokensPerSecond = 90.0,
                    loadCostMillis = 300,
                    energyPerToken = 0.15,
                    residentByDefault = true,
                ),
                ModelSpec(
                    id = "embeddinggemma-300m",
                    displayName = "EmbeddingGemma 300M",
                    role = ModelRole.EMBEDDING,
                    sizeBytes = 200 * MB,
                    capabilities = setOf(Capability.EMBEDDING),
                    maxComplexity = 1,
                    tokensPerSecond = 0.0,
                    loadCostMillis = 200,
                    energyPerToken = 0.05,
                    residentByDefault = true,
                ),
                ModelSpec(
                    id = "qwen3-4b-instruct",
                    displayName = "Qwen3 4B Instruct",
                    role = ModelRole.ALLTAG,
                    sizeBytes = (2.5 * GB).toLong(),
                    capabilities = setOf(Capability.TEXT, Capability.TOOL_CALLING),
                    strengths = setOf(
                        TaskCategory.SMALLTALK,
                        TaskCategory.WISSENSFRAGE,
                        TaskCategory.PERSOENLICH,
                        TaskCategory.WEB_AKTUELL,
                        TaskCategory.GERAETE_AKTION,
                    ),
                    maxComplexity = 3,
                    tokensPerSecond = 22.0,
                    loadCostMillis = 1_800,
                    energyPerToken = 1.0,
                    residentByDefault = true,
                ),
                ModelSpec(
                    id = "qwen3-8b-thinking",
                    displayName = "Qwen3 8B (Denker)",
                    role = ModelRole.DENKER,
                    sizeBytes = 5 * GB,
                    capabilities = setOf(
                        Capability.TEXT,
                        Capability.REASONING,
                        Capability.TOOL_CALLING,
                    ),
                    strengths = setOf(TaskCategory.LOGIK_MATHE, TaskCategory.WISSENSFRAGE),
                    minComplexity = 3,
                    maxComplexity = 5,
                    tokensPerSecond = 11.0,
                    loadCostMillis = 3_500,
                    energyPerToken = 2.4,
                ),
                ModelSpec(
                    id = "qwen3-coder-7b",
                    displayName = "Qwen3 Coder 7B",
                    role = ModelRole.CODE,
                    sizeBytes = (4.5 * GB).toLong(),
                    capabilities = setOf(Capability.TEXT, Capability.TOOL_CALLING),
                    strengths = setOf(TaskCategory.CODE),
                    minComplexity = 2,
                    maxComplexity = 5,
                    tokensPerSecond = 13.0,
                    loadCostMillis = 3_200,
                    energyPerToken = 2.1,
                ),
                ModelSpec(
                    // Hier stand Gemma 3n. Das war eine Fehlbesetzung: Für Gemma 3n gibt
                    // es keine Projektordatei, ohne die llama.cpp gar keine Bilder
                    // verarbeitet — die Registry benannte also ein Bildmodell, mit dem
                    // Bilder unmöglich waren. Gemma 3 4B bringt beide Dateien mit und ist
                    // in llama.cpp gut abgehangen.
                    id = "gemma-3-4b-it",
                    displayName = "Gemma 3 4B (Bild)",
                    role = ModelRole.VISION,
                    // Modell und Projektor zusammen: 2,32 GB plus 0,79 GB.
                    sizeBytes = (3.11 * GB).toLong(),
                    capabilities = setOf(Capability.TEXT, Capability.VISION),
                    strengths = setOf(TaskCategory.BILD),
                    maxComplexity = 4,
                    tokensPerSecond = 15.0,
                    loadCostMillis = 2_600,
                    energyPerToken = 1.6,
                    /**
                     * Ohne diese Datei kann das Modell keine Bilder ansehen. Sie wird
                     * getrennt importiert und getrennt vorgehalten.
                     */
                    projectorFileName = "mmproj",
                ),
            )
        )
    }
}
