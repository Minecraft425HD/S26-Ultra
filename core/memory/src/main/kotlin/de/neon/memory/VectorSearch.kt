package de.neon.memory

import kotlin.math.sqrt

/**
 * Ähnlichkeitssuche über die gespeicherten Erinnerungen.
 *
 * Bewusst eine schlichte lineare Suche und keine Vektordatenbank: Bei einigen tausend
 * Einträgen sind das ein paar Millisekunden, und die Alternative wäre eine zusätzliche
 * native Abhängigkeit für ein Problem, das Neon auf absehbare Zeit nicht hat. Sollte sich
 * das ändern, ist diese Klasse der einzige Ort, der angefasst werden muss.
 */
object VectorSearch {

    data class Hit<T>(val item: T, val similarity: Double)

    fun <T> nearest(
        query: FloatArray,
        items: List<T>,
        limit: Int,
        minSimilarity: Double = 0.35,
        embeddingOf: (T) -> FloatArray,
    ): List<Hit<T>> = items
        .asSequence()
        .map { Hit(it, cosine(query, embeddingOf(it))) }
        .filter { it.similarity >= minSimilarity }
        .sortedByDescending { it.similarity }
        .take(limit)
        .toList()

    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            normA += x * x
            normB += y * y
        }
        if (normA == 0.0 || normB == 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }
}
