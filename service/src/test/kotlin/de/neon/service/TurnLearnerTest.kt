package de.neon.service

import de.neon.router.AnalysisSource
import de.neon.router.HashingEmbeddingProvider
import de.neon.router.LabeledExample
import de.neon.router.RouteAnalysis
import de.neon.router.RouteOutcome
import de.neon.router.TaskCategory
import de.neon.router.UserSignal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnLearnerTest {

    private val embeddings = HashingEmbeddingProvider()
    private val learned = mutableListOf<LabeledExample>()

    private fun learner() = TurnLearner(embeddings) { learned += it }

    private fun outcome(
        text: String,
        timestamp: Long,
        category: TaskCategory = TaskCategory.WISSENSFRAGE,
        complexity: Int = 2,
        source: AnalysisSource = AnalysisSource.KNN,
    ) = RouteOutcome(
        utteranceText = text,
        analysis = RouteAnalysis(
            category = category,
            complexity = complexity,
            confidence = 0.8,
            source = source,
        ),
        modelId = "qwen3-4b-instruct",
        latencyMs = 800,
        tokensGenerated = 60,
        signal = UserSignal.UNBEKANNT,
        timestampMillis = timestamp,
    )

    @Test
    fun `lernt aus einem Durchgang ohne Widerspruch`() {
        val turnLearner = learner()

        turnLearner.onTurnCompleted(outcome("wie hoch ist der kölner dom", 0))
        // Etwas völlig anderes, deutlich später: Der vorherige Durchgang war offenbar gut.
        turnLearner.onNewUtterance("was gibt es heute für nachrichten", 60_000)

        assertEquals(1, learned.size)
        assertEquals(TaskCategory.WISSENSFRAGE, learned.single().category)
        assertTrue(learned.single().weight > 1.0)
    }

    @Test
    fun `lernt nichts aus einer schnellen Umformulierung`() {
        val turnLearner = learner()

        turnLearner.onTurnCompleted(outcome("wie hoch ist der kölner dom", 0))
        // Fast dieselbe Frage, fünf Sekunden später: Die Antwort hat nicht getaugt.
        turnLearner.onNewUtterance("also wie hoch ist der kölner dom wirklich", 5_000)

        // Dass die Route falsch war, ist bekannt — welche richtig gewesen wäre, nicht.
        // Ein geratenes Label würde den Klassifikator verschlechtern.
        assertTrue(learned.isEmpty(), "hätte nichts lernen dürfen: $learned")
    }

    @Test
    fun `lernt auch im Graubereich nichts`() {
        val turnLearner = learner()

        turnLearner.onTurnCompleted(outcome("wie hoch ist der kölner dom", 0))
        // Klingt nach Nachhaken, ist aber nicht eindeutig genug für ein Urteil.
        turnLearner.onNewUtterance("nein ich meinte den kölner dom", 4_000)

        assertTrue(learned.isEmpty(), "im Zweifel wird nicht gelernt: $learned")
    }

    @Test
    fun `merkt sich eine Bitte um mehr Tiefe als hoehere Komplexitaet`() {
        val turnLearner = learner()

        turnLearner.onTurnCompleted(outcome("erklär mir quantenverschränkung", 0, complexity = 2))
        turnLearner.onNewUtterance("denk nochmal nach", 4_000)

        assertEquals(1, learned.size)
        // Beim nächsten Mal soll gleich das stärkere Modell drankommen.
        assertEquals(3, learned.single().complexity)
    }

    @Test
    fun `lernt nichts aus Regeltreffern`() {
        val turnLearner = learner()

        turnLearner.onTurnCompleted(
            outcome("licht aus", 0, TaskCategory.GERAETE_AKTION, 1, AnalysisSource.REGELN)
        )
        turnLearner.onNewUtterance("wie wird das wetter", 60_000)

        // Stufe 0 fängt diese Äußerung ohnehin ab, bevor der kNN drankommt.
        assertTrue(learned.isEmpty())
    }

    @Test
    fun `bewertet jeden Durchgang nur einmal`() {
        val turnLearner = learner()

        turnLearner.onTurnCompleted(outcome("wie hoch ist der kölner dom", 0))
        turnLearner.onNewUtterance("was gibt es heute für nachrichten", 60_000)
        turnLearner.onNewUtterance("und morgen", 120_000)

        assertEquals(1, learned.size)
    }

    @Test
    fun `wertet einen offenen Durchgang beim Beenden als zufrieden`() {
        val turnLearner = learner()

        turnLearner.onTurnCompleted(outcome("wie hoch ist der kölner dom", 0))
        turnLearner.flush()

        assertEquals(1, learned.size)
        assertEquals(1, turnLearner.learnedCount)
    }

    @Test
    fun `mehrfaches Beenden lernt nicht doppelt`() {
        val turnLearner = learner()

        turnLearner.onTurnCompleted(outcome("wie hoch ist der kölner dom", 0))
        turnLearner.flush()
        turnLearner.flush()

        assertEquals(1, learned.size)
    }

    @Test
    fun `eine Aeusserung ohne Vorgeschichte aendert nichts`() {
        val turnLearner = learner()
        turnLearner.onNewUtterance("hallo", 0)
        assertTrue(learned.isEmpty())
    }
}
