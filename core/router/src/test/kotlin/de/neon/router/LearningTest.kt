package de.neon.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedbackLearnerTest {

    private val learner = FeedbackLearner()
    private val embedding = floatArrayOf(1f, 0f, 0f)

    private fun outcome(
        signal: UserSignal,
        source: AnalysisSource = AnalysisSource.KNN,
        complexity: Int = 3,
    ) = RouteOutcome(
        utteranceText = "wie funktioniert ein wechselrichter",
        analysis = RouteAnalysis(
            category = TaskCategory.WISSENSFRAGE,
            complexity = complexity,
            confidence = 0.8,
            source = source,
        ),
        modelId = "qwen3-4b-instruct",
        latencyMs = 900,
        tokensGenerated = 120,
        signal = signal,
        timestampMillis = 0,
    )

    @Test
    fun `lernt aus zufriedenen Durchgaengen`() {
        val example = learner.exampleFrom(outcome(UserSignal.ZUFRIEDEN), embedding)
        assertNotNull(example)
        assertEquals(TaskCategory.WISSENSFRAGE, example.category)
        assertEquals(3, example.complexity)
        assertTrue(example.weight > 1.0, "Gelerntes muss die Startmenge überstimmen können")
    }

    @Test
    fun `lernt nicht aus Regeltreffern`() {
        // Stufe 0 fängt diese Äußerungen ohnehin ab, bevor der kNN überhaupt drankommt.
        assertNull(
            learner.exampleFrom(outcome(UserSignal.ZUFRIEDEN, AnalysisSource.REGELN), embedding)
        )
    }

    @Test
    fun `merkt sich einen Eskalationswunsch als hoehere Komplexitaet`() {
        val example = learner.exampleFrom(outcome(UserSignal.ESKALATION_VERLANGT), embedding)
        assertNotNull(example)
        assertEquals(4, example.complexity)
    }

    @Test
    fun `deckelt die angehobene Komplexitaet`() {
        val example = learner.exampleFrom(
            outcome(UserSignal.ESKALATION_VERLANGT, complexity = 5),
            embedding,
        )
        assertNotNull(example)
        assertEquals(5, example.complexity)
    }

    @Test
    fun `lernt bewusst nichts aus einer Umformulierung`() {
        // Dass die Route falsch war, ist bekannt — welche richtig gewesen wäre, nicht.
        // Ein geratenes Label würde den Klassifikator verschlechtern.
        assertNull(learner.exampleFrom(outcome(UserSignal.UMFORMULIERT), embedding))
        assertNull(learner.exampleFrom(outcome(UserSignal.ABGEBROCHEN), embedding))
        assertNull(learner.exampleFrom(outcome(UserSignal.UNBEKANNT), embedding))
    }
}

class SignalInferenceTest {

    private fun outcome(timestamp: Long) = RouteOutcome(
        utteranceText = "wie hoch ist der eiffelturm",
        analysis = RouteAnalysis(
            category = TaskCategory.WISSENSFRAGE,
            complexity = 2,
            confidence = 0.8,
            source = AnalysisSource.KNN,
        ),
        modelId = "qwen3-4b-instruct",
        latencyMs = 500,
        tokensGenerated = 40,
        signal = UserSignal.UNBEKANNT,
        timestampMillis = timestamp,
    )

    @Test
    fun `erkennt die schnelle Wiederholung als Unzufriedenheit`() {
        val signal = SignalInference.infer(
            previous = outcome(0),
            nextText = "nein, wie hoch ist der eiffelturm wirklich",
            nextTimestampMillis = 5_000,
            similarityToPrevious = 0.92,
        )
        assertEquals(UserSignal.UMFORMULIERT, signal)
    }

    @Test
    fun `eine spaetere aehnliche Frage ist keine Umformulierung`() {
        val signal = SignalInference.infer(
            previous = outcome(0),
            nextText = "wie hoch ist der eiffelturm",
            nextTimestampMillis = 60_000,
            similarityToPrevious = 0.95,
        )
        assertEquals(UserSignal.ZUFRIEDEN, signal)
    }

    @Test
    fun `eine andere Frage kurz danach ist keine Umformulierung`() {
        val signal = SignalInference.infer(
            previous = outcome(0),
            nextText = "wie wird das wetter",
            nextTimestampMillis = 3_000,
            similarityToPrevious = 0.2,
        )
        assertEquals(UserSignal.ZUFRIEDEN, signal)
    }

    @Test
    fun `erkennt die ausdrueckliche Bitte um mehr Tiefe`() {
        listOf(
            "denk nochmal nach",
            "erklär das bitte genauer",
            "das war zu oberflächlich, gründlicher bitte",
        ).forEach { text ->
            assertEquals(
                UserSignal.ESKALATION_VERLANGT,
                SignalInference.infer(outcome(0), text, 2_000, 0.1),
                "fehlgeschlagen für: $text",
            )
        }
    }

    @Test
    fun `ohne Vorgeschichte gibt es kein Signal`() {
        assertEquals(
            UserSignal.UNBEKANNT,
            SignalInference.infer(null, "hallo", 0, 0.0),
        )
    }
}

class RouterStatsTest {

    private fun outcome(modelId: String?, latency: Long, tokens: Int = 10) = RouteOutcome(
        utteranceText = "test",
        analysis = RouteAnalysis(
            category = TaskCategory.GERAETE_AKTION,
            complexity = 1,
            confidence = 1.0,
            source = AnalysisSource.REGELN,
        ),
        modelId = modelId,
        latencyMs = latency,
        tokensGenerated = tokens,
        signal = UserSignal.ZUFRIEDEN,
        timestampMillis = 0,
    )

    @Test
    fun `zaehlt den Anteil der Anfragen ohne Sprachmodell`() {
        // Das ist die zentrale Effizienzkennzahl: Wie viel schafft Neon ohne Inferenz?
        val stats = RouterStats.from(
            listOf(
                outcome(null, 5),
                outcome(null, 8),
                outcome("qwen3-4b-instruct", 900),
                outcome("qwen3-8b-thinking", 3000),
            )
        )
        assertEquals(4, stats.total)
        assertEquals(0.5, stats.directActionShare)
    }

    @Test
    fun `schluesselt nach Modell auf`() {
        val stats = RouterStats.from(
            listOf(
                outcome("qwen3-4b-instruct", 800, tokens = 50),
                outcome("qwen3-4b-instruct", 1000, tokens = 70),
                outcome("qwen3-8b-thinking", 4000, tokens = 200),
            )
        )
        val alltag = stats.perModel.getValue("qwen3-4b-instruct")
        assertEquals(2, alltag.count)
        assertEquals(900, alltag.medianLatencyMs)
        assertEquals(120, alltag.totalTokens)
    }

    @Test
    fun `kommt mit einer leeren Historie zurecht`() {
        val stats = RouterStats.from(emptyList())
        assertEquals(0, stats.total)
        assertEquals(0.0, stats.directActionShare)
        assertTrue(stats.perModel.isEmpty())
    }
}

class InMemoryRouteOutcomeStoreTest {

    private fun outcome(index: Int) = RouteOutcome(
        utteranceText = "frage $index",
        analysis = RouteAnalysis(
            category = TaskCategory.SMALLTALK,
            complexity = 1,
            confidence = 1.0,
            source = AnalysisSource.KNN,
        ),
        modelId = null,
        latencyMs = 1,
        tokensGenerated = 0,
        signal = UserSignal.ZUFRIEDEN,
        timestampMillis = index.toLong(),
    )

    @Test
    fun `gibt die neuesten Eintraege zuerst zurueck`() {
        val store = InMemoryRouteOutcomeStore()
        repeat(5) { store.record(outcome(it)) }

        val recent = store.recent(3)
        assertEquals(listOf("frage 4", "frage 3", "frage 2"), recent.map { it.utteranceText })
    }

    @Test
    fun `wirft alte Eintraege weg statt unbegrenzt zu wachsen`() {
        val store = InMemoryRouteOutcomeStore(capacity = 3)
        repeat(10) { store.record(outcome(it)) }

        val all = store.recent(100)
        assertEquals(3, all.size)
        assertEquals("frage 9", all.first().utteranceText)
    }
}
