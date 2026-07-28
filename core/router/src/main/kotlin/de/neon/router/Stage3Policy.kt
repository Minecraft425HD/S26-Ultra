package de.neon.router

/** Ein bewertetes Modell samt Begründung — Grundlage für den Diagnose-Screen. */
data class ScoredCandidate(
    val model: ModelSpec,
    val score: Double,
    val breakdown: Map<String, Double>,
) {
    val isLoadedBonus: Boolean get() = (breakdown["ladekosten"] ?: 0.0) == 0.0
}

/** Das Ergebnis der Modellauswahl. */
data class ModelSelection(
    val model: ModelSpec,
    val analysis: RouteAnalysis,
    val reason: String,
    /** Darf bei Unsicherheit auf ein größeres Modell nachgezogen werden? */
    val allowEscalation: Boolean,
    /** Alle bewerteten Kandidaten, absteigend sortiert. Nur für Diagnose. */
    val candidates: List<ScoredCandidate> = emptyList(),
    /** Musste eine Sparregel gelockert werden, um überhaupt antworten zu können? */
    val constraintsRelaxed: Boolean = false,
)

/**
 * Stufe 3: die eigentliche Modellauswahl.
 *
 * Der Kern ist bewusst eine Punktebewertung und keine Wenn-Dann-Kaskade: Qualität,
 * Ladekosten und Energie sind gegeneinander abzuwägen, und diese Abwägung soll in den
 * Einstellungen sichtbar und veränderbar sein.
 *
 * Die wichtigste Regel ist die **Hysterese** über [loadPenaltyWeight]: Ein bereits geladenes
 * Modell, das die Aufgabe bewältigt, schlägt ein geringfügig besseres, das erst mehrere
 * Sekunden lang von der Platte gelesen werden müsste. Ein vermiedener Modellwechsel spart
 * mehr Energie, als die etwas bessere Antwort wert ist.
 */
class SelectionPolicy(
    private val registry: ModelRegistry,
    private val weights: Weights = Weights(),
) {

    data class Weights(
        /** Zuschlag, wenn das Modell in dieser Kategorie ausdrücklich stark ist. */
        val strengthBonus: Double = 3.0,
        /** Zuschlag für echtes Schlussfolgern bei Logik- und Mathefragen. */
        val reasoningBonus: Double = 1.5,
        /** Abzug je Komplexitätsstufe, um die ein Modell überdimensioniert ist. */
        val overkillPenalty: Double = 1.5,
        /** Abzug je Sekunde Ladezeit. Das ist die Hysterese. */
        val loadPenaltyWeight: Double = 0.6,
        /** Energie-Gewicht beim Laden — Verbrauch spielt kaum eine Rolle. */
        val energyWeightCharging: Double = 0.2,
        /** Energie-Gewicht im Normalbetrieb. */
        val energyWeightNormal: Double = 0.8,
        /** Energie-Gewicht bei wenig Akku oder Hitze. */
        val energyWeightConstrained: Double = 2.5,
    )

    /**
     * Wählt das Modell für eine analysierte Anfrage.
     *
     * Liefert immer ein Ergebnis: Lieber eine Antwort vom kleinen Modell als gar keine.
     */
    fun select(analysis: RouteAnalysis, state: DeviceState): ModelSelection {
        val all = registry.generativeModels()

        val strict = all.filter { eligible(it, analysis, state, relaxed = false) }
        val relaxed = strict.ifEmpty { all.filter { eligible(it, analysis, state, relaxed = true) } }
        val pool = relaxed.ifEmpty { fallbackPool(all, analysis) }

        val energyWeight = energyWeight(state)
        val scored = pool
            .map { score(it, analysis, state, energyWeight) }
            .sortedByDescending { it.score }

        val winner = scored.first()
        return ModelSelection(
            model = winner.model,
            analysis = analysis,
            reason = explain(winner, analysis, state),
            allowEscalation = canEscalate(winner.model, analysis, state),
            candidates = scored,
            constraintsRelaxed = strict.isEmpty(),
        )
    }

    /**
     * Harte Voraussetzungen. Wer hier durchfällt, kann die Aufgabe nicht erfüllen —
     * im Gegensatz zur Punktebewertung, die nur abwägt.
     */
    private fun eligible(
        model: ModelSpec,
        analysis: RouteAnalysis,
        state: DeviceState,
        relaxed: Boolean,
    ): Boolean {
        if (analysis.needsVision && !model.supports(Capability.VISION)) return false
        if (!model.handlesComplexity(analysis.complexity)) return false
        if (!state.fitsInMemory(model)) return false

        // Das Bildmodell ist ein Spezialist und kein günstiges Allzweckmodell. Ohne diese
        // Regel gewinnt es reine Textaufgaben allein deshalb, weil es weniger Energie je
        // Token braucht als der Denker — und antwortet dann schlechter als beide.
        if (!relaxed && !analysis.needsVision && model.role == ModelRole.VISION) return false

        // Im Sparmodus bleiben große Modelle außen vor — es sei denn, sie liegen ohnehin
        // schon im Speicher, dann kostet ihre Nutzung keine Ladeenergie mehr.
        if (!relaxed && state.isConstrained && !state.isLoaded(model)) {
            if (model.sizeBytes > CONSTRAINED_MAX_SIZE_BYTES) return false
        }
        return true
    }

    /**
     * Letzte Rettung: Es passt buchstäblich nichts. Dann gilt nur noch, ob das Modell die
     * nötige Fähigkeit hat — und von denen das kleinste.
     */
    private fun fallbackPool(all: List<ModelSpec>, analysis: RouteAnalysis): List<ModelSpec> {
        val capable = all.filter { !analysis.needsVision || it.supports(Capability.VISION) }
        val pool = capable.ifEmpty { all }
        return listOf(pool.minBy { it.sizeBytes })
    }

    private fun energyWeight(state: DeviceState): Double = when {
        state.isConstrained -> weights.energyWeightConstrained
        state.isUnconstrained -> weights.energyWeightCharging
        else -> weights.energyWeightNormal
    }

    private fun score(
        model: ModelSpec,
        analysis: RouteAnalysis,
        state: DeviceState,
        energyWeight: Double,
    ): ScoredCandidate {
        val breakdown = LinkedHashMap<String, Double>()

        breakdown["stärke"] =
            if (analysis.category in model.strengths) weights.strengthBonus else 0.0

        // Schlussfolgern lohnt bei Logikaufgaben — und bei allem, was ohnehin als schwer
        // eingestuft wurde. Hohe Komplexität ist genau der Fall, für den ein Denkmodell da
        // ist, unabhängig von der Kategorie.
        val benefitsFromReasoning = analysis.category == TaskCategory.LOGIK_MATHE ||
            analysis.complexity >= DEEP_COMPLEXITY
        breakdown["schlussfolgern"] =
            if (benefitsFromReasoning && model.supports(Capability.REASONING)) {
                weights.reasoningBonus
            } else {
                0.0
            }

        // Überdimensioniert: Das Modell ist erst ab einer höheren Komplexität gedacht.
        val overkillSteps = (model.minComplexity - analysis.complexity).coerceAtLeast(0)
        breakdown["überdimensioniert"] = -overkillSteps * weights.overkillPenalty

        // Hysterese: Ladezeit zählt nur, wenn das Modell nicht schon im Speicher liegt.
        breakdown["ladekosten"] = if (state.isLoaded(model)) {
            0.0
        } else {
            -(model.loadCostMillis / 1000.0) * weights.loadPenaltyWeight
        }

        breakdown["energie"] = -model.energyPerToken * energyWeight

        return ScoredCandidate(model, breakdown.values.sum(), breakdown)
    }

    private fun canEscalate(
        model: ModelSpec,
        analysis: RouteAnalysis,
        state: DeviceState,
    ): Boolean {
        if (state.isConstrained) return false
        if (analysis.needsVision) return false
        val strongest = registry.generativeModels().maxOf { it.maxComplexity }
        return model.maxComplexity < strongest
    }

    private fun explain(
        winner: ScoredCandidate,
        analysis: RouteAnalysis,
        state: DeviceState,
    ): String {
        val parts = mutableListOf<String>()
        parts += "${analysis.category.name.lowercase()}, Komplexität ${analysis.complexity}"
        if (analysis.category in winner.model.strengths) parts += "Spezialgebiet des Modells"
        if (state.isLoaded(winner.model)) parts += "bereits geladen"
        if (state.isConstrained) parts += "Sparmodus aktiv"
        if (analysis.needsVision) parts += "Bild erforderlich"
        return parts.joinToString(", ")
    }

    private companion object {
        /** Im Sparmodus wird nichts Neues über dieser Größe geladen. */
        const val CONSTRAINED_MAX_SIZE_BYTES = 3L * 1024 * 1024 * 1024

        /** Ab dieser Komplexität gilt eine Aufgabe als Fall für ein Denkmodell. */
        const val DEEP_COMPLEXITY = 4
    }
}
