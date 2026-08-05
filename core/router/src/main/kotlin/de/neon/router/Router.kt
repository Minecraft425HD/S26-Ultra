package de.neon.router

/** Was der Router entschieden hat. */
sealed interface RouteDecision {

    /** Stufe-0-Treffer: direkt ausführen, ohne jedes Sprachmodell. */
    data class Direct(
        val action: DeviceAction,
        val analysis: RouteAnalysis,
    ) : RouteDecision

    /** Ein Modell soll antworten. */
    data class Generate(
        val selection: ModelSelection,
    ) : RouteDecision
}

/**
 * Der Router: verkettet die Stufen 0 bis 3 und liefert eine Entscheidung.
 *
 * Die Stufen sind absichtlich nach Kosten geordnet. Jede Stufe darf abbrechen, sobald sie
 * sich sicher genug ist, sodass die teuren Schritte im Alltag selten laufen.
 *
 * [routerLlm] und [embeddings] dürfen `null` sein — dann fällt die jeweilige Stufe aus und
 * der Router arbeitet mit dem, was übrig ist. Beim ersten Start, bevor die Modelle
 * heruntergeladen sind, ist genau das der Fall.
 */
class Router(
    private val registry: ModelRegistry,
    private val policy: SelectionPolicy,
    private val ruleMatcher: RuleMatcher = RuleMatcher(),
    private val knn: KnnClassifier = KnnClassifier(),
    private val embeddings: EmbeddingProvider? = null,
    private val routerLlm: RouterLlm? = null,
    /** Unterhalb dieser Zuversicht wird von Stufe 1 auf Stufe 2 weitergereicht. */
    private val knnConfidenceThreshold: Double = 0.6,
) {

    fun route(utterance: Utterance, state: DeviceState): RouteDecision {
        ruleMatcher.match(utterance)?.let { match ->
            return RouteDecision.Direct(match.action, match.analysis)
        }

        val analysis = applyOverrides(analyze(utterance), utterance)
        return RouteDecision.Generate(policy.select(analysis, state))
    }

    /** Stufen 1 und 2, mit Rückfall auf eine neutrale Annahme. */
    private fun analyze(utterance: Utterance): RouteAnalysis {
        val fromKnn = embeddings?.let { provider ->
            runCatching { knn.classify(provider.embed(utterance.text)) }.getOrNull()
        }
        if (fromKnn != null && fromKnn.confidence >= knnConfidenceThreshold) return fromKnn

        routerLlm?.let { llm ->
            runCatching { llm.analyze(utterance) }.getOrNull()?.let { return it }
        }

        // Der kNN-Treffer war zwar unsicher, ist aber immer noch besser als nichts.
        return fromKnn ?: FALLBACK
    }

    /**
     * Harte Fakten schlagen jede Schätzung.
     *
     * Ob ein Bild anliegt, weiß die App sicher — dafür braucht es kein Modell. Und wenn der
     * Nutzer ausdrücklich um Gründlichkeit bittet, ist die geschätzte Komplexität irrelevant.
     */
    private fun applyOverrides(analysis: RouteAnalysis, utterance: Utterance): RouteAnalysis {
        var result = analysis

        // Eine Kategorie, die schon feststeht, schlägt jede Schätzung — siehe
        // [Utterance.bekannteKategorie]. Auf dem Gerät wurde die Antwort „Android" auf Neons
        // eigene Rückfrage als gewöhnliche Frage eingeordnet, und die Werkzeugkette lief
        // gar nicht erst an.
        utterance.bekannteKategorie?.let { kategorie ->
            result = result.copy(category = kategorie, confidence = maxOf(result.confidence, 0.9))
        }

        if (utterance.hasImage) {
            result = result.copy(
                category = TaskCategory.BILD,
                needsVision = true,
                confidence = maxOf(result.confidence, 0.9),
            )
        }

        if (utterance.explicitDeepThinking) {
            result = result.copy(
                complexity = maxOf(result.complexity, DEEP_THINKING_COMPLEXITY),
            )
        }

        if (!result.isPrivate && PrivacyDetector.isSensitive(utterance.text)) {
            result = result.copy(isPrivate = true)
        }

        return result
    }

    /** Neue Beispiele aus der Lernschleife übernehmen. */
    fun learn(example: LabeledExample) {
        knn.add(example)
    }

    val knownExampleCount: Int get() = knn.size

    fun models(): List<ModelSpec> = registry.models

    private companion object {
        /** "Denk nochmal nach" heißt: mindestens Stufe 4, damit der Denker in Frage kommt. */
        const val DEEP_THINKING_COMPLEXITY = 4

        /**
         * Weiß der Router nichts, nimmt er eine normale Frage mittlerer Komplexität an —
         * das führt zum Alltagsmodell und ist die günstigste vertretbare Annahme.
         */
        val FALLBACK = RouteAnalysis(
            category = TaskCategory.UNBEKANNT,
            complexity = 2,
            confidence = 0.0,
            source = AnalysisSource.RUECKFALL,
        )
    }
}
