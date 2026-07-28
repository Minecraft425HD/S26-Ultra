package de.neon.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Der kNN-Klassifikator wird mit von Hand gebauten Vektoren geprüft statt mit einem echten
 * Einbettungsmodell. Nur so sind die Fälle "sicher", "unsicher" und "unbekannt" exakt
 * ansteuerbar — mit einem echten Embedder wären die Tests von dessen Eigenheiten abhängig.
 */
class KnnClassifierTest {

    /** Ein Einheitsvektor in Richtung [axis] einer dreidimensionalen Basis. */
    private fun axis(axis: Int, noise: Float = 0f): FloatArray =
        FloatArray(3) { if (it == axis) 1f else noise }

    private fun example(
        axis: Int,
        category: TaskCategory,
        complexity: Int = 2,
        noise: Float = 0f,
        weight: Double = 1.0,
    ) = LabeledExample("beispiel", axis(axis, noise), category, complexity, weight)

    @Test
    fun `ordnet einen eindeutigen Vektor der richtigen Kategorie zu`() {
        val knn = KnnClassifier(
            listOf(
                example(0, TaskCategory.CODE),
                example(0, TaskCategory.CODE, noise = 0.05f),
                example(1, TaskCategory.SMALLTALK),
                example(2, TaskCategory.WEB_AKTUELL),
            ),
            k = 3,
        )

        val result = knn.classify(axis(0))
        assertNotNull(result)
        assertEquals(TaskCategory.CODE, result.category)
        assertEquals(AnalysisSource.KNN, result.source)
    }

    @Test
    fun `gibt null zurueck wenn nichts aehnlich genug ist`() {
        val knn = KnnClassifier(listOf(example(0, TaskCategory.CODE)))
        // Orthogonal zu allem Bekannten: Ähnlichkeit 0.
        assertNull(knn.classify(axis(1)))
    }

    @Test
    fun `gibt null zurueck wenn zwei Kategorien gleichauf liegen`() {
        // Genau zwischen zwei Kategorien — hier wäre jede Entscheidung ein Münzwurf,
        // also soll lieber Stufe 2 übernehmen.
        val knn = KnnClassifier(
            listOf(
                example(0, TaskCategory.CODE),
                example(1, TaskCategory.SMALLTALK),
            ),
            k = 2,
        )
        val between = floatArrayOf(1f, 1f, 0f)
        assertNull(knn.classify(between))
    }

    @Test
    fun `ist bei einer leeren Beispielmenge still`() {
        assertNull(KnnClassifier().classify(axis(0)))
    }

    @Test
    fun `mittelt die Komplexitaet der Nachbarn`() {
        val knn = KnnClassifier(
            listOf(
                example(0, TaskCategory.LOGIK_MATHE, complexity = 4),
                example(0, TaskCategory.LOGIK_MATHE, complexity = 4, noise = 0.02f),
                example(0, TaskCategory.LOGIK_MATHE, complexity = 5, noise = 0.03f),
            ),
            k = 3,
        )
        val result = knn.classify(axis(0))
        assertNotNull(result)
        assertEquals(4, result.complexity)
    }

    @Test
    fun `gelernte Beispiele wiegen schwerer als die Startmenge`() {
        // Gleiche Ähnlichkeit, aber das gelernte Beispiel hat das doppelte Gewicht —
        // so überstimmt echte Nutzung die mitgelieferten Vorgaben.
        val knn = KnnClassifier(
            listOf(
                example(0, TaskCategory.WISSENSFRAGE, weight = 1.0),
                example(0, TaskCategory.PERSOENLICH, weight = 2.0, noise = 0.01f),
            ),
            k = 2,
            minMargin = 0.1,
        )
        val result = knn.classify(axis(0))
        assertNotNull(result)
        assertEquals(TaskCategory.PERSOENLICH, result.category)
    }

    @Test
    fun `nimmt neue Beispiele zur Laufzeit auf`() {
        val knn = KnnClassifier()
        assertEquals(0, knn.size)
        knn.add(example(0, TaskCategory.CODE))
        assertEquals(1, knn.size)
        assertNotNull(knn.classify(axis(0)))
    }

    @Test
    fun `Kosinus-Aehnlichkeit rechnet unabhaengig von der Vektorlaenge`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(7f, 0f, 0f)
        assertEquals(1.0, KnnClassifier.cosineSimilarity(a, b), 1e-9)

        val orthogonal = floatArrayOf(0f, 3f, 0f)
        assertEquals(0.0, KnnClassifier.cosineSimilarity(a, orthogonal), 1e-9)
    }

    @Test
    fun `Nullvektoren fuehren nicht zu einer Division durch Null`() {
        val zero = FloatArray(3)
        assertEquals(0.0, KnnClassifier.cosineSimilarity(zero, axis(0)), 1e-9)
    }

    @Test
    fun `die Zuversicht steigt mit der Eindeutigkeit`() {
        val eindeutig = KnnClassifier(
            listOf(
                example(0, TaskCategory.CODE),
                example(0, TaskCategory.CODE, noise = 0.01f),
                example(1, TaskCategory.SMALLTALK),
            ),
            k = 3,
        ).classify(axis(0))

        val knapp = KnnClassifier(
            listOf(
                example(0, TaskCategory.CODE),
                example(0, TaskCategory.SMALLTALK, noise = 0.9f),
            ),
            k = 2,
            minMargin = 0.05,
        ).classify(axis(0))

        assertNotNull(eindeutig)
        assertNotNull(knapp)
        assertTrue(
            eindeutig.confidence > knapp.confidence,
            "eindeutig=${eindeutig.confidence} sollte über knapp=${knapp.confidence} liegen",
        )
    }
}
