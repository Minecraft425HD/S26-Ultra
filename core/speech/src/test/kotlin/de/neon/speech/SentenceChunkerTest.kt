package de.neon.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SentenceChunkerTest {

    @Test
    fun `zerlegt an Satzgrenzen`() {
        val chunks = SentenceChunker.chunk(
            "Der Eiffelturm ist 330 Meter hoch. Er steht in Paris und wurde 1889 gebaut."
        )
        assertEquals(2, chunks.size)
        assertTrue(chunks[0].startsWith("Der Eiffelturm"))
        assertTrue(chunks[1].startsWith("Er steht"))
    }

    @Test
    fun `haengt zu kurze Stuecke an statt sie einzeln zu sprechen`() {
        // "Ja." allein auszugeben würde abgehackt klingen.
        val chunks = SentenceChunker.chunk("Ja. Der Eiffelturm ist tatsächlich 330 Meter hoch.")
        assertEquals(1, chunks.size)
    }

    @Test
    fun `verliert keinen Text`() {
        val text = "Erstens dies. Zweitens das. Und drittens noch etwas ganz anderes."
        val chunks = SentenceChunker.chunk(text)
        val rejoined = chunks.joinToString(" ")
        listOf("Erstens", "Zweitens", "drittens", "anderes").forEach {
            assertTrue(rejoined.contains(it), "'$it' fehlt in: $rejoined")
        }
    }

    @Test
    fun `kommt mit leerem Text zurecht`() {
        assertTrue(SentenceChunker.chunk("").isEmpty())
        assertTrue(SentenceChunker.chunk("   \n  ").isEmpty())
    }

    @Test
    fun `gibt einen einzelnen kurzen Satz unveraendert zurueck`() {
        assertEquals(listOf("Klar."), SentenceChunker.chunk("Klar."))
    }
}
