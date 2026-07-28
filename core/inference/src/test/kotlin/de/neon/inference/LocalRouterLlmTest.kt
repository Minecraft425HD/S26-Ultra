package de.neon.inference

import de.neon.router.AnalysisSource
import de.neon.router.Capability
import de.neon.router.ModelRole
import de.neon.router.ModelSpec
import de.neon.router.TaskCategory
import de.neon.router.Utterance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalRouterLlmTest {

    private val routerModel = ModelSpec(
        id = "qwen3-0.6b-router",
        displayName = "Router",
        role = ModelRole.ROUTER,
        sizeBytes = 400L * 1024 * 1024,
        capabilities = setOf(Capability.TEXT),
        tokensPerSecond = 90.0,
        loadCostMillis = 300,
        energyPerToken = 0.15,
    )

    private class ScriptedEngine(
        private val output: String,
        private val fails: Boolean = false,
        private val loadSucceeds: Boolean = true,
    ) : InferenceEngine {
        override var loadedModelId: String? = null
            private set

        var lastRequest: GenerationRequest? = null
        var loadCount = 0

        override suspend fun load(model: ModelSpec, file: File): Boolean {
            loadCount++
            if (!loadSucceeds) return false
            loadedModelId = model.id
            return true
        }

        override suspend fun unload() {
            loadedModelId = null
        }

        override fun generate(request: GenerationRequest): Flow<GenerationChunk> = flow {
            lastRequest = request
            if (fails) {
                emit(GenerationChunk.Failed("kein Modell"))
                return@flow
            }
            // Token für Token, wie es das echte Modell auch täte.
            output.chunked(7).forEach { emit(GenerationChunk.Token(it)) }
            emit(GenerationChunk.Done(output.length, 90.0))
        }
    }

    private val resolver = ModelFileResolver { File("/dev/null") }

    @Test
    fun `liest die Kategorie aus der Modellantwort`() = runTest {
        val engine = ScriptedEngine(
            """{"kategorie":"CODE","komplexitaet":4,"braucht_web":false,"braucht_bild":false,"privat":false}"""
        )
        val router = LocalRouterLlm(engine, routerModel, resolver)

        val analysis = router.analyzeSuspending(Utterance("schreib mir eine funktion"))

        assertNotNull(analysis)
        assertEquals(TaskCategory.CODE, analysis.category)
        assertEquals(4, analysis.complexity)
        assertEquals(AnalysisSource.ROUTER_LLM, analysis.source)
    }

    @Test
    fun `erzwingt die Grammatik und schaltet die Kreativitaet ab`() = runTest {
        val engine = ScriptedEngine(
            """{"kategorie":"SMALLTALK","komplexitaet":1,"braucht_web":false,"braucht_bild":false,"privat":false}"""
        )
        LocalRouterLlm(engine, routerModel, resolver).analyzeSuspending(Utterance("hallo"))

        val request = assertNotNull(engine.lastRequest)
        // Ohne Grammatik erklärt ein kleines Modell gern seine Wahl, statt JSON zu liefern.
        assertNotNull(request.grammar)
        assertTrue(request.grammar!!.contains("kategorie"))
        // Eine Klassifikation muss bei gleicher Frage gleich ausfallen.
        assertEquals(0f, request.temperature)
    }

    @Test
    fun `laedt das Router-Modell nur einmal`() = runTest {
        val engine = ScriptedEngine(
            """{"kategorie":"SMALLTALK","komplexitaet":1,"braucht_web":false,"braucht_bild":false,"privat":false}"""
        )
        val router = LocalRouterLlm(engine, routerModel, resolver)

        router.analyzeSuspending(Utterance("hallo"))
        router.analyzeSuspending(Utterance("guten tag"))

        assertEquals(1, engine.loadCount)
    }

    @Test
    fun `gibt null zurueck wenn das Modell nicht da ist`() = runTest {
        val router = LocalRouterLlm(
            ScriptedEngine("egal"),
            routerModel,
            ModelFileResolver { null },
        )
        assertNull(router.analyzeSuspending(Utterance("hallo")))
    }

    @Test
    fun `gibt null zurueck wenn das Laden scheitert`() = runTest {
        val router = LocalRouterLlm(
            ScriptedEngine("egal", loadSucceeds = false),
            routerModel,
            resolver,
        )
        assertNull(router.analyzeSuspending(Utterance("hallo")))
    }

    @Test
    fun `gibt null zurueck wenn die Inferenz scheitert`() = runTest {
        val router = LocalRouterLlm(ScriptedEngine("", fails = true), routerModel, resolver)
        assertNull(router.analyzeSuspending(Utterance("hallo")))
    }

    @Test
    fun `gibt null zurueck bei unbrauchbarer Ausgabe`() = runTest {
        // Ohne Grammatikunterstützung in der Laufzeit kann so etwas herauskommen.
        val router = LocalRouterLlm(
            ScriptedEngine("Das ist eine interessante Frage!"),
            routerModel,
            resolver,
        )
        assertNull(router.analyzeSuspending(Utterance("hallo")))
    }

    @Test
    fun `begrenzt die Ausgabelaenge`() = runTest {
        val engine = ScriptedEngine(
            """{"kategorie":"SMALLTALK","komplexitaet":1,"braucht_web":false,"braucht_bild":false,"privat":false}"""
        )
        LocalRouterLlm(engine, routerModel, resolver).analyzeSuspending(Utterance("hallo"))

        // Ein Modell, das die Grammatik ignoriert, darf nicht endlos weiterreden.
        assertTrue(assertNotNull(engine.lastRequest).maxTokens <= 128)
    }
}
