package de.neon.router

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welche Modelle auf einem **echten** Gerät zugelassen sein müssen.
 *
 * **Der Anlass ist eine Regression, die 569 grüne Tests durchgelassen haben.** Nachdem
 * `availableMemoryBytes` mit dem freien Speicher (1,5 GB) statt mit einem Budget befüllt
 * wurde, fiel jedes Modell durch: Auf „hallo" antwortete Neon nach sechs Millisekunden
 * „Dafür bräuchte ich ein Modell, das nicht in den Speicher passt" — auf einem Gerät, auf
 * dem dasselbe Modell einen Tag zuvor geladen und geantwortet hatte.
 *
 * Der Denkfehler dahinter: Die Modellgewichte liegen per `mmap` als **Dateiseiten** im
 * Seitencache und dürfen jederzeit verdrängt und aus Flash nachgelesen werden. Sie müssen
 * nicht in den freien Speicher passen. Was hineinpassen muss, ist der **anonyme** Anteil —
 * Schlüssel-Wert-Speicher und Rechenpuffer —, und darüber entscheidet
 * `passendeKontextgroesse`, nicht dieses Kriterium.
 *
 * Deshalb prüft dieser Test **beide Richtungen**: Ein Kriterium, das alles ablehnt, ist
 * genauso falsch wie eines, das alles zulässt.
 */
class MemoryBudgetTest {

    private val gb = 1024L * 1024 * 1024

    private val registry = ModelRegistry.defaultForS26Ultra()

    private fun modell(id: String) = registry.generativeModels().first { spec -> spec.id == id }

    /**
     * Das gemessene Gerät: 5,3 GB insgesamt.
     *
     * Das daraus abgeleitete Gewichtsbudget muss das Alltagsmodell zulassen — sonst
     * antwortet Neon überhaupt nicht mehr.
     */
    private fun zustand(gesamtGb: Double) = DeviceState.unknown().copy(
        availableMemoryBytes = (gesamtGb * gb * WEIGHT_SHARE).toLong(),
    )

    @Test
    fun `auf dem gemessenen Geraet ist das Alltagsmodell zugelassen`() {
        val gemessen = zustand(gesamtGb = 5.3)

        assertTrue(
            gemessen.fitsInMemory(modell("qwen3-4b-instruct")),
            "das Alltagsmodell fällt durch — dann antwortet Neon auf nichts mehr",
        )
    }

    @Test
    fun `die grossen Modelle bleiben ausgeschlossen`() {
        val gemessen = zustand(gesamtGb = 5.3)

        // Die Gegenprobe. Ein Budget, das alles durchlässt, führt zurück zu dem Zustand, in
        // dem Android den Prozess sechsmal erschlug.
        assertFalse(gemessen.fitsInMemory(modell("qwen3-8b-thinking")), "8B zugelassen")
        assertFalse(gemessen.fitsInMemory(modell("qwen3-coder-7b")), "Coder 7B zugelassen")
    }

    @Test
    fun `auf einem grossen Geraet ist alles zugelassen`() {
        val reichlich = zustand(gesamtGb = 16.0)

        registry.generativeModels().forEach { spec ->
            assertTrue(reichlich.fitsInMemory(spec), "${spec.id} fällt bei 16 GB durch")
        }
    }

    @Test
    fun `ein geladenes Modell bleibt zugelassen`() {
        // Sonst würde ein Modell, das gerade antwortet, mitten im Gespräch unbrauchbar,
        // weil der freie Speicher zufällig gesunken ist.
        val eng = DeviceState.unknown().copy(
            availableMemoryBytes = 100L * 1024 * 1024,
            loadedModelIds = setOf("qwen3-4b-instruct"),
        )

        assertTrue(eng.fitsInMemory(modell("qwen3-4b-instruct")))
    }

    private companion object {
        /**
         * Derselbe Anteil, den `DeviceStateProvider` benutzt.
         *
         * Hier absichtlich noch einmal hingeschrieben statt importiert: `core/router` kennt
         * `core/platform` nicht, und dieser Test soll festhalten, welche Antwort
         * herauskommen muss — nicht, wie sie zustande kommt.
         */
        const val WEIGHT_SHARE = 0.6
    }
}
