package de.neon.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Prüft die Verkettung der Stufen: Wer kommt wann zum Zug, und wer wird übersprungen.
 *
 * Die Einbettung ist eine Abbildungstabelle statt eines echten Modells — dadurch ist exakt
 * steuerbar, ob Stufe 1 sicher, unsicher oder blind ist.
 */
class RouterTest {

    private val registry = ModelRegistry.defaultForS26Ultra()
    private val policy = SelectionPolicy(registry)
    private val state = DeviceState.unknown()

    /** Liefert für bekannte Texte einen festen Vektor, sonst einen orthogonalen. */
    private class MapEmbeddings(private val vectors: Map<String, FloatArray>) : EmbeddingProvider {
        override fun embed(text: String): FloatArray =
            vectors[text] ?: floatArrayOf(0f, 0f, 1f)
    }

    private val codeVector = floatArrayOf(1f, 0f, 0f)
    private val smalltalkVector = floatArrayOf(0f, 1f, 0f)

    private fun knnWithExamples() = KnnClassifier(
        listOf(
            LabeledExample("code a", codeVector, TaskCategory.CODE, 3),
            LabeledExample("code b", floatArrayOf(0.99f, 0.01f, 0f), TaskCategory.CODE, 3),
            LabeledExample("smalltalk a", smalltalkVector, TaskCategory.SMALLTALK, 1),
        ),
        k = 3,
    )

    @Test
    fun `Stufe 0 gewinnt und laesst gar kein Modell laufen`() {
        val router = Router(
            registry = registry,
            policy = policy,
            knn = knnWithExamples(),
            embeddings = MapEmbeddings(mapOf("licht aus" to codeVector)),
            routerLlm = { error("Stufe 2 darf hier nicht befragt werden") },
        )

        val decision = router.route(Utterance("licht aus"), state)
        val direct = assertIs<RouteDecision.Direct>(decision)
        assertEquals(DeviceAction.SwitchLight(on = false, room = null), direct.action)
        assertEquals(AnalysisSource.REGELN, direct.analysis.source)
    }

    @Test
    fun `Stufe 1 entscheidet und Stufe 2 bleibt aus`() {
        val router = Router(
            registry = registry,
            policy = policy,
            knn = knnWithExamples(),
            embeddings = MapEmbeddings(mapOf("schreib mir eine funktion" to codeVector)),
            routerLlm = { error("Stufe 2 darf hier nicht befragt werden") },
        )

        val decision = router.route(Utterance("schreib mir eine funktion"), state)
        val generate = assertIs<RouteDecision.Generate>(decision)
        assertEquals(TaskCategory.CODE, generate.selection.analysis.category)
        assertEquals(AnalysisSource.KNN, generate.selection.analysis.source)
        assertEquals("qwen3-coder-7b", generate.selection.model.id)
    }

    @Test
    fun `bei unsicherem kNN uebernimmt das Router-Modell`() {
        var befragt = false
        val router = Router(
            registry = registry,
            policy = policy,
            knn = knnWithExamples(),
            // Unbekannter Text -> orthogonaler Vektor -> kNN findet nichts.
            embeddings = MapEmbeddings(emptyMap()),
            routerLlm = {
                befragt = true
                RouteAnalysis(
                    category = TaskCategory.LOGIK_MATHE,
                    complexity = 5,
                    confidence = 0.75,
                    source = AnalysisSource.ROUTER_LLM,
                )
            },
        )

        val decision = router.route(Utterance("völlig neuartige frage"), state)
        val generate = assertIs<RouteDecision.Generate>(decision)
        assertTrue(befragt, "Stufe 2 hätte laufen müssen")
        assertEquals(AnalysisSource.ROUTER_LLM, generate.selection.analysis.source)
        assertEquals("qwen3-8b-thinking", generate.selection.model.id)
    }

    @Test
    fun `ohne Einbettung und ohne Router-Modell greift der Rueckfall`() {
        // So sieht es beim allerersten Start aus, bevor Modelle heruntergeladen sind.
        val router = Router(registry = registry, policy = policy)

        val decision = router.route(Utterance("irgendeine frage"), state)
        val generate = assertIs<RouteDecision.Generate>(decision)
        assertEquals(AnalysisSource.RUECKFALL, generate.selection.analysis.source)
        assertEquals("qwen3-4b-instruct", generate.selection.model.id)
    }

    @Test
    fun `ein anliegendes Bild sticht jede Schaetzung`() {
        val router = Router(
            registry = registry,
            policy = policy,
            knn = knnWithExamples(),
            embeddings = MapEmbeddings(mapOf("was ist das" to codeVector)),
        )

        // Der kNN würde hier auf CODE tippen — das Bild ist aber eine harte Tatsache.
        val decision = router.route(Utterance("was ist das", hasImage = true), state)
        val generate = assertIs<RouteDecision.Generate>(decision)
        assertEquals(TaskCategory.BILD, generate.selection.analysis.category)
        assertTrue(generate.selection.analysis.needsVision)
        assertEquals("gemma-3n-e4b", generate.selection.model.id)
    }

    @Test
    fun `die Bitte um Gruendlichkeit hebt die Komplexitaet an`() {
        val router = Router(
            registry = registry,
            policy = policy,
            knn = knnWithExamples(),
            embeddings = MapEmbeddings(mapOf("erklär mir das" to smalltalkVector)),
        )

        val normal = router.route(Utterance("erklär mir das"), state)
        val gruendlich = router.route(
            Utterance("erklär mir das", explicitDeepThinking = true),
            state,
        )

        assertEquals(1, assertIs<RouteDecision.Generate>(normal).selection.analysis.complexity)
        val angehoben = assertIs<RouteDecision.Generate>(gruendlich).selection
        assertEquals(4, angehoben.analysis.complexity)
        assertEquals("qwen3-8b-thinking", angehoben.model.id)
    }

    @Test
    fun `markiert sensible Aeusserungen unabhaengig vom Modell`() {
        val router = Router(
            registry = registry,
            policy = policy,
            // Das Router-Modell behauptet ausdrücklich, es sei nicht sensibel.
            routerLlm = {
                RouteAnalysis(
                    category = TaskCategory.WISSENSFRAGE,
                    complexity = 2,
                    isPrivate = false,
                    confidence = 0.75,
                    source = AnalysisSource.ROUTER_LLM,
                )
            },
        )

        val decision = router.route(Utterance("wie hoch ist mein kontostand"), state)
        val generate = assertIs<RouteDecision.Generate>(decision)
        assertTrue(
            generate.selection.analysis.isPrivate,
            "die Schlüsselwortprüfung muss das Modell überstimmen können",
        )
    }

    @Test
    fun `ein Fehler in Stufe 1 legt den Router nicht lahm`() {
        val router = Router(
            registry = registry,
            policy = policy,
            embeddings = { error("Einbettungsmodell nicht geladen") },
            routerLlm = {
                RouteAnalysis(
                    category = TaskCategory.SMALLTALK,
                    complexity = 1,
                    confidence = 0.75,
                    source = AnalysisSource.ROUTER_LLM,
                )
            },
        )

        val decision = router.route(Utterance("hallo"), state)
        val generate = assertIs<RouteDecision.Generate>(decision)
        assertEquals(AnalysisSource.ROUTER_LLM, generate.selection.analysis.source)
    }

    @Test
    fun `ein Fehler in Stufe 2 fuehrt zum Rueckfall statt zum Absturz`() {
        val router = Router(
            registry = registry,
            policy = policy,
            routerLlm = { error("Modell abgestürzt") },
        )

        val decision = router.route(Utterance("irgendwas"), state)
        val generate = assertIs<RouteDecision.Generate>(decision)
        assertEquals(AnalysisSource.RUECKFALL, generate.selection.analysis.source)
    }

    @Test
    fun `nimmt gelernte Beispiele auf`() {
        val router = Router(registry = registry, policy = policy, knn = knnWithExamples())
        val vorher = router.knownExampleCount
        router.learn(LabeledExample("neu", codeVector, TaskCategory.CODE, 3, weight = 2.0))
        assertEquals(vorher + 1, router.knownExampleCount)
    }
}
