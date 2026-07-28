package de.neon.inference

import de.neon.router.Capability
import de.neon.router.ModelRole
import de.neon.router.ModelSpec
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelStoreTest {

    private lateinit var root: File
    private lateinit var store: ModelStore

    @BeforeTest
    fun setUp() {
        root = File.createTempFile("neon-models", "").apply {
            delete()
            mkdirs()
        }
        store = ModelStore(root)
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private val spec = ModelSpec(
        id = "qwen3-4b-instruct",
        displayName = "Qwen3 4B",
        role = ModelRole.ALLTAG,
        sizeBytes = 100,
        capabilities = setOf(Capability.TEXT),
        tokensPerSecond = 20.0,
        loadCostMillis = 1_000,
        energyPerToken = 1.0,
    )

    /** Eine Datei, die vorn wie eine GGUF-Datei aussieht. */
    private fun ggufBytes(payload: String = "inhalt"): InputStream =
        ByteArrayInputStream("GGUF$payload".toByteArray())

    @Test
    fun `uebernimmt eine gueltige Modelldatei`() {
        val result = store.importFrom(spec.id, ggufBytes())

        val ok = assertIs<ModelStore.ImportResult.Ok>(result)
        assertTrue(ok.bytes > 0)
        assertTrue(store.isAvailable(spec))
        assertEquals("${spec.id}.gguf", store.fileFor(spec)?.name)
    }

    @Test
    fun `meldet den Fortschritt`() {
        val seen = mutableListOf<Long>()
        store.importFrom(spec.id, ggufBytes("x".repeat(100)), onProgress = { seen += it })

        // Bei mehreren Gigabyte ist die Anzeige der Unterschied zwischen "arbeitet" und
        // "hängt" — deshalb muss sie überhaupt aufgerufen werden.
        assertTrue(seen.isNotEmpty())
        assertEquals(104L, seen.last())
    }

    @Test
    fun `weist eine Datei zurueck die keine GGUF ist`() {
        // Der häufigste Fehler beim Herunterladen: eine HTML-Fehlerseite statt des Modells.
        val result = store.importFrom(spec.id, ByteArrayInputStream("<!DOCTYPE html>".toByteArray()))

        assertIs<ModelStore.ImportResult.Failed>(result)
        assertFalse(store.isAvailable(spec))
    }

    @Test
    fun `weist eine leere Datei zurueck`() {
        val result = store.importFrom(spec.id, ByteArrayInputStream(ByteArray(0)))
        assertIs<ModelStore.ImportResult.Failed>(result)
        assertFalse(store.isAvailable(spec))
    }

    @Test
    fun `weist eine unvollstaendige Uebertragung zurueck`() {
        val result = store.importFrom(spec.id, ggufBytes(), expectedBytes = 999_999)

        val failed = assertIs<ModelStore.ImportResult.Failed>(result)
        assertTrue(failed.reason.contains("nvollständig"))
        assertFalse(store.isAvailable(spec))
    }

    @Test
    fun `laesst nach einem Fehlschlag keine Teildatei zurueck`() {
        store.importFrom(spec.id, ByteArrayInputStream("kaputt".toByteArray()))

        // Eine liegengebliebene .part-Datei würde beim nächsten Versuch verwirren und
        // belegt bei einem 2,5-GB-Modell erheblich Platz.
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun `ueberschreibt einen frueheren Fehlversuch`() {
        store.importFrom(spec.id, ByteArrayInputStream("kaputt".toByteArray()))
        val result = store.importFrom(spec.id, ggufBytes())

        assertIs<ModelStore.ImportResult.Ok>(result)
        assertTrue(store.isAvailable(spec))
    }

    @Test
    fun `meldet ein fehlendes Modell als nicht vorhanden`() {
        assertFalse(store.isAvailable(spec))
        assertEquals(null, store.fileFor(spec))
    }

    @Test
    fun `zaehlt den belegten Platz`() {
        assertEquals(0L, store.usedBytes())
        store.importFrom(spec.id, ggufBytes("x".repeat(50)))
        assertEquals(54L, store.usedBytes())
    }

    @Test
    fun `loescht ein Modell wieder`() {
        store.importFrom(spec.id, ggufBytes())
        assertTrue(store.delete(spec))
        assertFalse(store.isAvailable(spec))
    }
}
