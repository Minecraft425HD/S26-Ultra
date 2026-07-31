package de.neon.inference

import de.neon.router.AnalysisSource
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

    private class ScriptedEngine(
        private val output: String,
        private val fails: Boolean = false,
        loaded: String? = "qwen3-4b-instruct",
    ) : InferenceEngine {

        override var loadedModelId: String? = loaded
            private set

        var lastRequest: GenerationRequest? = null
        var loadCount = 0
        var unloadCount = 0

        override suspend fun load(model: ModelSpec, file: File, projector: File?): Boolean {
            loadCount++
            loadedModelId = model.id
            return true
        }

        override suspend fun unload() {
            unloadCount++
            loadedModelId = null
        }

        override fun generate(request: GenerationRequest): Flow<GenerationChunk> = flow {
            lastRequest = request
            if (fails) {
                emit(GenerationChunk.Failed("Server nicht erreichbar"))
                return@flow
            }
            // Token für Token, wie es der echte Server auch liefert.
            output.chunked(7).forEach { emit(GenerationChunk.Token(it)) }
            emit(GenerationChunk.Done(output.length, 90.0))
        }
    }

    private val validJson =
        """{"kategorie":"CODE","komplexitaet":4,"braucht_web":false,"braucht_bild":false,"privat":false}"""

    @Test
    fun `liest die Kategorie aus der Modellantwort`() = runTest {
        val engine = ScriptedEngine(validJson)
        val analysis = LocalRouterLlm(engine).analyzeSuspending(Utterance("schreib mir eine funktion"))

        assertNotNull(analysis)
        assertEquals(TaskCategory.CODE, analysis.category)
        assertEquals(4, analysis.complexity)
        assertEquals(AnalysisSource.ROUTER_LLM, analysis.source)
    }

    @Test
    fun `erzwingt die Grammatik und schaltet die Kreativitaet ab`() = runTest {
        val engine = ScriptedEngine(validJson)
        LocalRouterLlm(engine).analyzeSuspending(Utterance("hallo"))

        val request = assertNotNull(engine.lastRequest)
        // Ohne Grammatik erklärt ein kleines Modell gern seine Wahl, statt JSON zu liefern.
        assertNotNull(request.grammar)
        assertTrue(request.grammar!!.contains("kategorie"))
        // Eine Klassifikation muss bei gleicher Frage gleich ausfallen.
        assertEquals(0f, request.temperature)
    }

    @Test
    fun `wechselt niemals das Modell`() = runTest {
        // Der wichtigste Test dieser Klasse. llama-server bedient je Lauf genau ein Modell;
        // für eine Einordnung das Antwortmodell zu tauschen würde die Einordnung teurer
        // machen als die Antwort selbst.
        val engine = ScriptedEngine(validJson)
        LocalRouterLlm(engine).analyzeSuspending(Utterance("hallo"))

        assertEquals(0, engine.loadCount)
        assertEquals(0, engine.unloadCount)
        assertEquals("qwen3-4b-instruct", engine.loadedModelId)
    }

    @Test
    fun `haelt sich zurueck wenn kein Modell laeuft`() = runTest {
        val engine = ScriptedEngine(validJson, loaded = null)
        assertNull(LocalRouterLlm(engine).analyzeSuspending(Utterance("hallo")))
        // Es darf auch nicht versucht werden, dafür eines zu laden.
        assertEquals(0, engine.loadCount)
    }

    @Test
    fun `gibt null zurueck wenn die Inferenz scheitert`() = runTest {
        val engine = ScriptedEngine("", fails = true)
        assertNull(LocalRouterLlm(engine).analyzeSuspending(Utterance("hallo")))
    }

    @Test
    fun `gibt null zurueck bei unbrauchbarer Ausgabe`() = runTest {
        val engine = ScriptedEngine("Das ist eine interessante Frage!")
        assertNull(LocalRouterLlm(engine).analyzeSuspending(Utterance("hallo")))
    }

    /**
     * Ein Rückfall auf die Regelstufe ist kein Fehler — aber er ist eine Auskunft.
     *
     * **Der Anlass.** Im Protokoll des Geräts stehen mehrere Serveranfragen, zu denen es
     * keine einzige Neon-Zeile gibt. Ob die Einordnung gelang oder still scheiterte, war
     * nicht zu entscheiden: Hier stand `return null`, und damit sah ein Fehlschlag genauso
     * aus wie „Stufe 2 war gar nicht dran".
     */
    @Test
    fun `ein Fehlschlag der Einordnung hinterlaesst eine Zeile`() = runTest {
        val zeilen = mutableListOf<String>()
        val engine = ScriptedEngine("", fails = true)

        assertNull(
            LocalRouterLlm(engine, log = { zeilen += it }).analyzeSuspending(Utterance("hallo"))
        )

        val zeile = zeilen.singleOrNull()
        assertNotNull(zeile, "nichts protokolliert")
        assertTrue(zeile.contains("Regeln"), zeile)
        assertTrue(zeile.contains("Server nicht erreichbar"), zeile)
    }

    @Test
    fun `auch eine unlesbare Ausgabe hinterlaesst eine Zeile`() = runTest {
        val zeilen = mutableListOf<String>()
        val engine = ScriptedEngine("Das ist eine interessante Frage!")

        LocalRouterLlm(engine, log = { zeilen += it }).analyzeSuspending(Utterance("hallo"))

        // Die Ausgabe selbst gehört dazu: Ohne sie ist nicht zu erkennen, ob das Modell die
        // Grammatik ignoriert hat oder ob etwas ganz anderes zurückkam.
        val zeile = zeilen.single()
        assertTrue(zeile.contains("unlesbar"), zeile)
        assertTrue(zeile.contains("interessante Frage"), zeile)
    }

    @Test
    fun `eine gelungene Einordnung schweigt`() = runTest {
        val zeilen = mutableListOf<String>()
        val engine = ScriptedEngine(validJson)

        LocalRouterLlm(engine, log = { zeilen += it }).analyzeSuspending(Utterance("hallo"))

        // Der häufige Fall darf die Protokolldatei nicht füllen — sonst verdrängt er genau
        // das, was man später lesen will.
        assertTrue(zeilen.isEmpty(), "$zeilen")
    }

    @Test
    fun `begrenzt die Ausgabelaenge`() = runTest {
        val engine = ScriptedEngine(validJson)
        LocalRouterLlm(engine).analyzeSuspending(Utterance("hallo"))

        // Ein Modell, das die Grammatik ignoriert, darf nicht endlos weiterreden.
        assertTrue(assertNotNull(engine.lastRequest).maxTokens <= 128)
    }
}
