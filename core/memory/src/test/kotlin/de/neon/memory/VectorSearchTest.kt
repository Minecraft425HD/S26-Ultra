package de.neon.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VectorSearchTest {

    private data class Fact(val text: String, val vector: FloatArray)

    private val facts = listOf(
        Fact("mag keinen koriander", floatArrayOf(1f, 0f, 0f)),
        Fact("hausarzt heißt müller", floatArrayOf(0f, 1f, 0f)),
        Fact("urlaub war in portugal", floatArrayOf(0f, 0f, 1f)),
        Fact("mag kein zimt", floatArrayOf(0.9f, 0.1f, 0f)),
    )

    @Test
    fun `findet die aehnlichsten Eintraege in der richtigen Reihenfolge`() {
        val hits = VectorSearch.nearest(
            query = floatArrayOf(1f, 0f, 0f),
            items = facts,
            limit = 2,
            embeddingOf = { it.vector },
        )
        assertEquals(2, hits.size)
        assertEquals("mag keinen koriander", hits[0].item.text)
        assertEquals("mag kein zimt", hits[1].item.text)
        assertTrue(hits[0].similarity > hits[1].similarity)
    }

    @Test
    fun `filtert zu unaehnliche Eintraege weg`() {
        val hits = VectorSearch.nearest(
            query = floatArrayOf(1f, 0f, 0f),
            items = facts,
            limit = 10,
            minSimilarity = 0.9,
            embeddingOf = { it.vector },
        )
        // Nur die beiden Geschmacks-Einträge liegen nah genug beieinander.
        assertTrue(hits.all { it.similarity >= 0.9 })
        assertTrue(hits.none { it.item.text.contains("portugal") })
    }

    @Test
    fun `gibt nichts zurueck wenn nichts passt`() {
        val hits = VectorSearch.nearest(
            query = floatArrayOf(0f, 0f, 0f),
            items = facts,
            limit = 5,
            embeddingOf = { it.vector },
        )
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `kommt mit unterschiedlichen Vektorlaengen zurecht`() {
        // Kann passieren, wenn das Einbettungsmodell gewechselt wurde. Ein Absturz wäre
        // hier die schlechteste aller Reaktionen.
        assertEquals(0.0, VectorSearch.cosine(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f, 0f)))
    }
}
