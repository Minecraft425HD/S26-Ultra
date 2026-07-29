package de.neon.inference

import de.neon.router.Capability
import de.neon.router.ModelRole
import de.neon.router.ModelSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelLifecycleManagerTest {

    private class FakeEngine(var loadSucceeds: Boolean = true) : InferenceEngine {
        override var loadedModelId: String? = null
            private set

        var loadCount = 0
        var unloadCount = 0

        override suspend fun load(model: ModelSpec, file: File, projector: File?): Boolean {
            loadCount++
            if (!loadSucceeds) return false
            loadedModelId = model.id
            return true
        }

        override suspend fun unload() {
            unloadCount++
            loadedModelId = null
        }

        override fun generate(request: GenerationRequest): Flow<GenerationChunk> = emptyFlow()
    }

    private fun model(id: String, sizeGb: Double) = ModelSpec(
        id = id,
        displayName = id,
        role = ModelRole.ALLTAG,
        sizeBytes = (sizeGb * 1024 * 1024 * 1024).toLong(),
        capabilities = setOf(Capability.TEXT),
        tokensPerSecond = 20.0,
        loadCostMillis = 1_000,
        energyPerToken = 1.0,
    )

    private val klein = model("klein", 2.0)
    private val gross = model("gross", 4.5)
    private val riesig = model("riesig", 9.0)

    /** Tut so, als läge jede Datei bereit — außer den ausdrücklich fehlenden. */
    private fun resolver(missing: Set<String> = emptySet()) = ModelFileResolver { spec ->
        if (spec.id in missing) null else File("/dev/null")
    }

    @Test
    fun `laedt ein Modell und meldet es als bereit`() = runTest {
        val engine = FakeEngine()
        val manager = ModelLifecycleManager(engine, resolver())

        val result = manager.ensureLoaded(klein)
        val ready = assertIs<ModelLifecycleManager.Result.Ready>(result)
        assertFalse(ready.wasAlreadyLoaded)
        assertEquals("klein", manager.loadedModelId)
        assertEquals(1, engine.loadCount)
    }

    @Test
    fun `laedt ein bereits geladenes Modell nicht erneut`() = runTest {
        val engine = FakeEngine()
        val manager = ModelLifecycleManager(engine, resolver())

        manager.ensureLoaded(klein)
        val result = manager.ensureLoaded(klein)

        val ready = assertIs<ModelLifecycleManager.Result.Ready>(result)
        assertTrue(ready.wasAlreadyLoaded)
        assertEquals(1, engine.loadCount, "es hätte kein zweiter Ladevorgang laufen dürfen")
    }

    @Test
    fun `entlaedt das alte Modell bevor das neue kommt`() = runTest {
        // Zwei große Modelle gleichzeitig im Speicher sind auf einem Telefon der
        // zuverlässigste Weg, vom Low-Memory-Killer abgeräumt zu werden.
        val engine = FakeEngine()
        val manager = ModelLifecycleManager(engine, resolver())

        manager.ensureLoaded(klein)
        manager.ensureLoaded(gross)

        assertEquals(1, engine.unloadCount)
        assertEquals("gross", manager.loadedModelId)
    }

    @Test
    fun `lehnt Modelle ueber dem Speicherbudget ab`() = runTest {
        val manager = ModelLifecycleManager(FakeEngine(), resolver())
        val result = manager.ensureLoaded(riesig)
        assertIs<ModelLifecycleManager.Result.TooLarge>(result)
    }

    @Test
    fun `meldet fehlende Modelldateien statt abzustuerzen`() = runTest {
        val manager = ModelLifecycleManager(FakeEngine(), resolver(missing = setOf("klein")))
        val result = manager.ensureLoaded(klein)
        assertIs<ModelLifecycleManager.Result.Missing>(result)
    }

    @Test
    fun `meldet einen Ladefehler als Ergebnis statt als Ausnahme`() = runTest {
        val manager = ModelLifecycleManager(FakeEngine(loadSucceeds = false), resolver())
        val result = manager.ensureLoaded(klein)
        assertIs<ModelLifecycleManager.Result.Failed>(result)
        // Der Dienst muss weiterlaufen und den Nutzer informieren können.
        assertEquals(null, manager.loadedModelId)
    }

    @Test
    fun `merkt sich kuerzlich benutzte Modelle als warm`() = runTest {
        val manager = ModelLifecycleManager(FakeEngine(), resolver())

        manager.ensureLoaded(klein)
        manager.ensureLoaded(gross)

        // "gross" ist geladen, "klein" liegt sehr wahrscheinlich noch im Seitencache.
        // Genau diese Information braucht der Router für die Hysterese.
        val warm = manager.warmModelIds()
        assertTrue("gross" in warm)
        assertTrue("klein" in warm)
    }

    @Test
    fun `vergisst laengst nicht benutzte Modelle wieder`() = runTest {
        val manager = ModelLifecycleManager(FakeEngine(), resolver())

        listOf(klein, gross, model("a", 1.0), model("b", 1.0), model("c", 1.0))
            .forEach { manager.ensureLoaded(it) }

        assertFalse("klein" in manager.warmModelIds(), "zu lange her, um noch warm zu sein")
    }

    @Test
    fun `Vorladen meldet Erfolg wenn das Modell schon da ist`() = runTest {
        val engine = FakeEngine()
        val manager = ModelLifecycleManager(engine, resolver())

        manager.ensureLoaded(klein)
        assertTrue(manager.preload(klein))
        assertEquals(1, engine.loadCount)
    }

    @Test
    fun `entlaedt alles auf Wunsch`() = runTest {
        val engine = FakeEngine()
        val manager = ModelLifecycleManager(engine, resolver())

        manager.ensureLoaded(klein)
        manager.unloadAll()

        assertEquals(null, manager.loadedModelId)
        assertEquals(1, engine.unloadCount)
    }
}
