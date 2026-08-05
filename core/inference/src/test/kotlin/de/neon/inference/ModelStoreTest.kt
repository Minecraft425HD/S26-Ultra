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

    // ---- Bruchstuecke ------------------------------------------------------------------

    /**
     * **Eine Datei, die viel zu klein ist, ist kein Modell.**
     *
     * Der Fall vom Gerät: Im Slot `qwen3-coder-7b` lag eine Datei von 378 MB, wo der Eintrag
     * 4608 MB nennt — acht Prozent. Neon hat sie geladen und benutzt, denn `isFile && length
     * > 0` war die ganze Prüfung. Der Router schickte jede Programmierfrage dorthin, weil im
     * Eintrag „Coder 7B" steht, und bekam Antworten von etwas, das dieses Modell nicht ist:
     * Auf „mach mir eine QR-App" rief es das Python-Werkzeug auf und übergab ihm Kotlin.
     */
    @Test
    fun `ein Bruchstueck gilt nicht als vorhanden`() {
        val gross = spec.copy(id = "qwen3-coder-7b", sizeBytes = 4608L * 1024 * 1024)
        File(root, "${gross.id}.gguf").writeBytes(ByteArray(378 * 1024) { 0 })

        assertTrue(store.istBruchstueck(gross, 378L * 1024 * 1024))
        assertFalse(store.isAvailable(gross), "acht Prozent des Eintrags ist ein Abbruch")
    }

    /**
     * **Wer bewusst stärker quantisiert importiert, wird nicht ausgesperrt.**
     *
     * `Q4_K_M` gegen `Q8_0` sind rund 53 Prozent des Eintrags. Das ist eine legitime Wahl und
     * darf nicht als kaputt gelten — deshalb liegt die Grenze bei einem Viertel und nicht bei
     * der Hälfte.
     */
    @Test
    fun `eine staerker quantisierte Fassung bleibt gueltig`() {
        val gross = spec.copy(id = "qwen3-coder-7b", sizeBytes = 4608L * 1024 * 1024)

        assertFalse(store.istBruchstueck(gross, 2400L * 1024 * 1024), "gut die Hälfte")
        assertFalse(store.istBruchstueck(gross, 1200L * 1024 * 1024), "ein Viertel, gerade noch")
        assertTrue(store.istBruchstueck(gross, 1100L * 1024 * 1024), "darunter nicht mehr")
    }

    /**
     * Kleine Einträge werden nicht geprüft.
     *
     * Unter 256 MB beschreibt kein Eintrag ein Sprachmodell. Dort gibt es keinen Download,
     * der abbrechen könnte, und ein Verhältnis von einem Viertel sagt nichts.
     */
    @Test
    fun `unterhalb der Modellgroesse wird nicht geprueft`() {
        assertFalse(store.istBruchstueck(spec.copy(sizeBytes = 100), 1))
        assertFalse(store.istBruchstueck(spec.copy(sizeBytes = 0), 1))
    }
}
