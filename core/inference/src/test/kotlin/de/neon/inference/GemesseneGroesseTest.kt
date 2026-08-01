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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Die Datei schlägt den Eintrag.
 *
 * **Der Fall aus dem Gerät.** Der Registry-Eintrag `qwen3-coder-7b` nennt 4,5 GB. Importiert
 * wurde eine Datei von 378 MB, die mit 35,8 Token je Sekunde antwortete statt mit den
 * eingetragenen 13 — beides zusammen schließt ein 7-B-Modell aus. Bemerkt hat das niemand:
 * Neon rechnete überall mit der Behauptung aus dem Quelltext weiter.
 *
 * Diese Tests halten beide Hälften der Antwort fest. Die Größe kommt aus der Datei, sobald es
 * eine gibt — das behebt die Speicherentscheidung. Und die Abweichung wird gemeldet, denn ein
 * Eintrag verspricht mehr als eine Zahl: Stärken, ein Komplexitätsband, eine Geschwindigkeit.
 * Die kann kein Messwert korrigieren.
 */
class GemesseneGroesseTest {

    private class FakeEngine : InferenceEngine {
        override var loadedModelId: String? = null
            private set

        override suspend fun load(model: ModelSpec, file: File, projector: File?): Boolean {
            loadedModelId = model.id
            return true
        }

        override suspend fun unload() {
            loadedModelId = null
        }

        override fun generate(request: GenerationRequest): Flow<GenerationChunk> = emptyFlow()
    }

    private fun spec(id: String, deklariertBytes: Long) = ModelSpec(
        id = id,
        displayName = id,
        role = ModelRole.CODE,
        sizeBytes = deklariertBytes,
        capabilities = setOf(Capability.TEXT),
        tokensPerSecond = 13.0,
        loadCostMillis = 3_200,
        energyPerToken = 2.1,
    )

    /** Ein Resolver, der Dateien mit echtem Inhalt liefert — sonst gibt es nichts zu messen. */
    private class EchteDateien(private val groessen: Map<String, Long>) : ModelFileResolver {
        private val verzeichnis = File.createTempFile("neon-modelle", "").let {
            it.delete()
            it.mkdirs()
            it
        }

        override fun fileFor(model: ModelSpec): File? {
            val bytes = groessen[model.id] ?: return null
            val datei = File(verzeichnis, "${model.id}.gguf")
            if (!datei.exists()) {
                // Ein Loch statt echter Bytes: 378 MB wirklich zu schreiben wäre für eine
                // Längenprüfung eine sonderbare Art, Zeit und Platte zu verbrennen.
                java.io.RandomAccessFile(datei, "rw").use { it.setLength(bytes) }
                datei.deleteOnExit()
            }
            return datei.takeIf { it.isFile && it.length() > 0 }
        }
    }

    private val MB = 1024L * 1024L
    private val GB = 1024L * MB

    @Test
    fun `die gemessene Groesse zaehlt, nicht die eingetragene`() {
        val coder = spec("qwen3-coder-7b", (4.5 * GB).toLong())
        val resolver = EchteDateien(mapOf(coder.id to 378 * MB))

        assertEquals(378 * MB, resolver.gemesseneGroesse(coder))
    }

    @Test
    fun `ohne Datei gibt es keine Messung`() {
        val coder = spec("qwen3-coder-7b", (4.5 * GB).toLong())
        assertNull(EchteDateien(emptyMap()).gemesseneGroesse(coder))
    }

    @Test
    fun `eine leere Datei ist keine Messung von null Byte`() {
        // Sonst würde ausgerechnet der kaputte Fall jede Speicherprüfung durchwinken.
        val coder = spec("qwen3-coder-7b", (4.5 * GB).toLong())
        val resolver = ModelFileResolver { File("/dev/null") }
        assertNull(resolver.gemesseneGroesse(coder))
    }

    @Test
    fun `ein Modell, das kleiner ist als eingetragen, wird nicht mehr abgelehnt`() = runTest {
        // Genau der Fall vom Gerät: 4,5 GB im Eintrag, 378 MB auf der Platte, 2 GB Budget.
        // Vorher lehnte Neon das Laden ab — wegen Speichermangels für eine knappe halbe
        // Gigabyte, von der es glaubte, sie sei viereinhalb.
        val coder = spec("qwen3-coder-7b", (4.5 * GB).toLong())
        val manager = ModelLifecycleManager(
            engine = FakeEngine(),
            resolver = EchteDateien(mapOf(coder.id to 378 * MB)),
            memoryBudgetBytes = { 2 * GB },
            log = {},
        )

        assertIs<ModelLifecycleManager.Result.Ready>(manager.ensureLoaded(coder))
    }

    @Test
    fun `ein Modell, das groesser ist als eingetragen, wird abgelehnt`() = runTest {
        // Die gefährliche Richtung. Der Eintrag verspricht 1 GB, die Datei hat 5 — mit der
        // Behauptung gerechnet lädt Neon los, und Android erschlägt den Serverprozess.
        val untertrieben = spec("angeblich-klein", 1 * GB)
        val manager = ModelLifecycleManager(
            engine = FakeEngine(),
            resolver = EchteDateien(mapOf(untertrieben.id to 5 * GB)),
            memoryBudgetBytes = { 2 * GB },
            log = {},
        )

        val ergebnis = manager.ensureLoaded(untertrieben)
        assertIs<ModelLifecycleManager.Result.TooLarge>(ergebnis)
        assertEquals(2 * GB, ergebnis.budgetBytes)
    }

    @Test
    fun `die Abweichung landet im Protokoll, mit beiden Zahlen`() = runTest {
        val coder = spec("qwen3-coder-7b", (4.5 * GB).toLong())
        val zeilen = mutableListOf<String>()
        val manager = ModelLifecycleManager(
            engine = FakeEngine(),
            resolver = EchteDateien(mapOf(coder.id to 378 * MB)),
            memoryBudgetBytes = { 8 * GB },
            log = { zeilen += it },
        )

        manager.ensureLoaded(coder)

        assertEquals(1, zeilen.size, "genau eine Meldung erwartet, bekam: $zeilen")
        val meldung = zeilen.single()
        // Beide Zahlen müssen dastehen. Nur „passt nicht zusammen" zu melden hieße, den
        // Leser mit derselben Frage zurückzulassen, mit der er angefangen hat.
        assertTrue("378 MB" in meldung, meldung)
        assertTrue("4608 MB" in meldung, meldung)
        assertTrue(coder.id in meldung, meldung)
    }

    @Test
    fun `eine passende Datei erzeugt keine Meldung`() = runTest {
        val alltag = spec("qwen3-4b-instruct", (2.5 * GB).toLong())
        val zeilen = mutableListOf<String>()
        val manager = ModelLifecycleManager(
            engine = FakeEngine(),
            resolver = EchteDateien(mapOf(alltag.id to (2_336 * MB))),
            memoryBudgetBytes = { 8 * GB },
            log = { zeilen += it },
        )

        manager.ensureLoaded(alltag)

        assertTrue(zeilen.isEmpty(), "unerwartete Meldung: $zeilen")
    }

    @Test
    fun `dieselbe Abweichung wird nicht bei jedem Wechsel wiederholt`() = runTest {
        // Das Protokoll vom Gerät zeigt vier Modellwechsel in zwei Minuten. Viermal dieselbe
        // Zeile macht sie nicht wahrer, nur schwerer zu finden.
        val coder = spec("qwen3-coder-7b", (4.5 * GB).toLong())
        val anderes = spec("qwen3-1.7b-instruct", (1.1 * GB).toLong())
        val zeilen = mutableListOf<String>()
        val manager = ModelLifecycleManager(
            engine = FakeEngine(),
            resolver = EchteDateien(mapOf(coder.id to 378 * MB, anderes.id to 1_056 * MB)),
            memoryBudgetBytes = { 8 * GB },
            log = { zeilen += it },
        )

        manager.ensureLoaded(coder)
        manager.ensureLoaded(anderes)
        manager.ensureLoaded(coder)

        assertEquals(1, zeilen.size, "erwartet: genau einmal gemeldet, bekam: $zeilen")
    }

    @Test
    fun `eine andere Quantisierung gilt nicht als Abweichung`() {
        // Q4_K_M gegen Q5_K_M desselben Modells sind rund vierzig Prozent Unterschied. Das
        // ist eine Wahl und keine Verwechslung; eine Meldung darüber wäre reines Rauschen.
        assertFalse(ModelLifecycleManager.weichtAb(deklariert = 2_500 * MB, gemessen = 3_400 * MB))
        assertFalse(ModelLifecycleManager.weichtAb(deklariert = 2_500 * MB, gemessen = 1_800 * MB))
    }

    @Test
    fun `ein Faktor zwoelf gilt als Abweichung, in beide Richtungen`() {
        assertTrue(ModelLifecycleManager.weichtAb(deklariert = (4.5 * GB).toLong(), gemessen = 378 * MB))
        assertTrue(ModelLifecycleManager.weichtAb(deklariert = 378 * MB, gemessen = (4.5 * GB).toLong()))
    }
}
