package de.neon.router

import kotlin.math.sqrt

/**
 * Wandelt Text in einen Vektor um.
 *
 * Auf dem Gerät liefert EmbeddingGemma die Vektoren; in Tests wird eine einfache
 * Attrappe eingesetzt. Deshalb ist das hier eine Schnittstelle und keine Klasse.
 */
fun interface EmbeddingProvider {
    fun embed(text: String): FloatArray
}

/**
 * Ein gelabeltes Beispiel im Gedächtnis des Routers.
 *
 * Bewusst keine `data class`: Das Embedding ist ein Array, dessen Gleichheit
 * feldweise verglichen werden müsste — eine automatisch erzeugte `equals` wäre hier
 * stillschweigend falsch.
 */
class LabeledExample(
    val text: String,
    val embedding: FloatArray,
    val category: TaskCategory,
    val complexity: Int,
    /** Beispiele aus echter Nutzung wiegen schwerer als die mitgelieferte Startmenge. */
    val weight: Double = 1.0,
) {
    override fun toString(): String = "LabeledExample(\"$text\" -> $category/$complexity)"
}

/**
 * Stufe 1: k-nächste-Nachbarn über Satz-Embeddings.
 *
 * Das ist der Arbeitspferd-Schritt des Routers: rund zehn Millisekunden, kein Sprachmodell,
 * und er wird mit jeder Rückmeldung besser. Erst wenn er sich nicht sicher ist, kommt das
 * Router-Modell aus Stufe 2 zum Zug.
 */
class KnnClassifier(
    examples: List<LabeledExample> = emptyList(),
    private val k: Int = 5,
    /**
     * Ist der beste Nachbar unähnlicher als das, kennt der Router die Frage schlicht nicht.
     */
    private val minSimilarity: Double = 0.55,
    /**
     * Abstand zwischen bester und zweitbester Kategorie. Liegen zwei Kategorien dicht
     * beieinander, ist die Entscheidung eine Münzwurf — dann lieber eskalieren.
     */
    private val minMargin: Double = 0.15,
) {

    private val examples = ArrayList(examples)

    val size: Int get() = examples.size

    fun add(example: LabeledExample) {
        examples.add(example)
    }

    fun addAll(newExamples: Collection<LabeledExample>) {
        examples.addAll(newExamples)
    }

    /**
     * Gibt `null` zurück, wenn die Zuordnung zu unsicher ist — das ist ein reguläres
     * Ergebnis und kein Fehler.
     */
    fun classify(embedding: FloatArray): RouteAnalysis? {
        if (examples.isEmpty()) return null

        val neighbours = examples
            .map { it to cosineSimilarity(embedding, it.embedding) }
            .sortedByDescending { it.second }
            .take(k)

        val best = neighbours.firstOrNull() ?: return null
        if (best.second < minSimilarity) return null

        // Nach Kategorie gewichtet abstimmen: Ähnlichkeit mal Beispielgewicht.
        val scores = HashMap<TaskCategory, Double>()
        for ((example, similarity) in neighbours) {
            if (similarity < minSimilarity) continue
            scores.merge(example.category, similarity * example.weight, Double::plus)
        }
        if (scores.isEmpty()) return null

        val ranked = scores.entries.sortedByDescending { it.value }
        val winner = ranked[0]
        val runnerUp = ranked.getOrNull(1)?.value ?: 0.0

        val total = ranked.sumOf { it.value }
        val margin = if (total > 0) (winner.value - runnerUp) / total else 0.0
        if (margin < minMargin) return null

        val winningNeighbours = neighbours.filter { it.first.category == winner.key }
        val complexity = weightedComplexity(winningNeighbours)

        return RouteAnalysis(
            category = winner.key,
            complexity = complexity,
            confidence = (best.second * (0.5 + 0.5 * margin)).coerceIn(0.0, 1.0),
            source = AnalysisSource.KNN,
        )
    }

    private fun weightedComplexity(neighbours: List<Pair<LabeledExample, Double>>): Int {
        val weightSum = neighbours.sumOf { it.second }
        if (weightSum <= 0.0) return 2
        val weighted = neighbours.sumOf { it.first.complexity * it.second } / weightSum
        return Math.round(weighted).toInt()
            .coerceIn(RouteAnalysis.MIN_COMPLEXITY, RouteAnalysis.MAX_COMPLEXITY)
    }

    companion object {

        /**
         * Kosinus-Ähnlichkeit zweier Vektoren.
         *
         * Normalisiert selbst, damit auch Embedder ohne Einheitsnorm richtig behandelt werden.
         */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
            require(a.size == b.size) {
                "Vektoren unterschiedlicher Länge: ${a.size} und ${b.size}"
            }
            var dot = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in a.indices) {
                dot += a[i].toDouble() * b[i].toDouble()
                normA += a[i].toDouble() * a[i].toDouble()
                normB += b[i].toDouble() * b[i].toDouble()
            }
            if (normA == 0.0 || normB == 0.0) return 0.0
            return dot / (sqrt(normA) * sqrt(normB))
        }
    }
}
