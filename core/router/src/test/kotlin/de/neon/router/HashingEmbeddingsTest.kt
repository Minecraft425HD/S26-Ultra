package de.neon.router

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HashingEmbeddingProviderTest {

    private val embeddings = HashingEmbeddingProvider()

    private fun similarity(a: String, b: String): Double =
        KnnClassifier.cosineSimilarity(embeddings.embed(a), embeddings.embed(b))

    @Test
    fun `liefert Vektoren der Laenge eins`() {
        val vector = embeddings.embed("wie hoch ist der eiffelturm")
        val norm = Math.sqrt(vector.sumOf { it.toDouble() * it })
        assertTrue(abs(norm - 1.0) < 1e-5, "Norm war $norm")
    }

    @Test
    fun `derselbe Text ergibt denselben Vektor`() {
        val a = embeddings.embed("schreib mir ein python skript")
        val b = embeddings.embed("schreib mir ein python skript")
        assertEquals(1.0, KnnClassifier.cosineSimilarity(a, b), 1e-9)
    }

    @Test
    fun `Gross- und Kleinschreibung sowie Satzzeichen spielen keine Rolle`() {
        assertEquals(1.0, similarity("Licht aus!", "licht aus"), 1e-9)
    }

    @Test
    fun `verwandte Formulierungen liegen naeher beieinander als fremde`() {
        val verwandt = similarity(
            "schreib mir ein python skript",
            "schreib mir ein python programm",
        )
        val fremd = similarity(
            "schreib mir ein python skript",
            "wie wird das wetter morgen",
        )
        assertTrue(verwandt > fremd, "verwandt=$verwandt, fremd=$fremd")
        assertTrue(verwandt > 0.5, "verwandte Formulierungen zu weit auseinander: $verwandt")
    }

    @Test
    fun `Wortformen desselben Stammes teilen Merkmale`() {
        // Dafür sind die Zeichen-n-Gramme da: Im Deutschen unterscheiden sich Wortformen
        // oft nur in der Endung.
        val ähnlichkeit = similarity("programmieren", "programmiert")
        assertTrue(ähnlichkeit > 0.6, "zu unähnlich: $ähnlichkeit")
    }

    @Test
    fun `Fuellwoerter allein machen Aeusserungen nicht aehnlich`() {
        // "wie" und "ist" kommen in beiden vor. Ohne Stoppwortfilter wären sie sich
        // deshalb ähnlich — und genau das soll der Router nicht verwechseln.
        val ähnlichkeit = similarity(
            "wie hoch ist der eiffelturm",
            "wie kalt ist es in sibirien",
        )
        assertTrue(ähnlichkeit < 0.45, "Füllwörter dominieren: $ähnlichkeit")
    }

    @Test
    fun `leerer Text ergibt einen Nullvektor`() {
        val vector = embeddings.embed("   ")
        assertTrue(vector.all { it == 0f })
    }

    @Test
    fun `ein Text nur aus Fuellwoertern stuerzt nicht ab`() {
        val vector = embeddings.embed("und der die das")
        // Die Wortpaare bleiben erhalten, der Vektor ist also nicht zwingend leer —
        // wichtig ist nur, dass nichts explodiert.
        assertEquals(HashingEmbeddingProvider.DEFAULT_DIMENSIONS, vector.size)
    }
}
