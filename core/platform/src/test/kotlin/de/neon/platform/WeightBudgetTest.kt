package de.neon.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wie groß die Modellgewichte sein dürfen.
 *
 * **Diese Zahl war zweimal falsch, und beide Male kam sie an grünen Tests vorbei.**
 *
 * 1. Eine Konstante von fünf Gigabyte, mit dem Kommentar „von sechzehn". Das Gerät hat
 *    5,3 GB *insgesamt* — Android erschlug den Prozess sechsmal beim Laden.
 * 2. Danach `MemAvailable`, also 1,5 GB. Damit fiel das 2,5 GB schwere Alltagsmodell durch,
 *    und Neon antwortete nach **sechs Millisekunden** „Dafür bräuchte ich ein Modell, das
 *    nicht in den Speicher passt". Es gab keinen Ladeversuch mehr.
 *
 * Der Denkfehler in (2): Die Gewichte liegen per `mmap` als Dateiseiten im Seitencache und
 * dürfen jederzeit verdrängt werden. Sie müssen nicht in den freien Speicher passen. Was
 * hineinpassen muss, ist der anonyme Anteil — und darüber entscheidet die Kontextgröße.
 *
 * Deshalb prüft dieser Test **beide Richtungen**. Ein Budget, das alles ablehnt, verhindert
 * jede Antwort; eines, das alles zulässt, führt zurück zum Abschuss. Die Zahlen unten sind
 * die gemessenen.
 */
class WeightBudgetTest {

    private val gb = 1024L * 1024 * 1024

    /** Das gemessene Gerät: 5,3 GB insgesamt, 1,5 GB frei. */
    private val gemessen = MemoryReading(
        totalBytes = (5.3 * gb).toLong(),
        availableBytes = (1.5 * gb).toLong(),
    )

    private val alltagsmodell = (2.5 * gb).toLong()
    private val bildmodell = (3.11 * gb).toLong()
    private val coder7b = (4.5 * gb).toLong()
    private val denker8b = 5 * gb

    @Test
    fun `das Alltagsmodell passt auf dem gemessenen Geraet`() {
        val budget = DeviceStateProvider.weightBudget(gemessen)

        assertTrue(
            alltagsmodell <= budget,
            "Alltagsmodell (${alltagsmodell / 1024 / 1024} MB) über dem Budget " +
                "(${budget / 1024 / 1024} MB) — dann antwortet Neon auf nichts mehr",
        )
    }

    @Test
    fun `die grossen Modelle passen nicht`() {
        val budget = DeviceStateProvider.weightBudget(gemessen)

        assertTrue(denker8b > budget, "8B passt ins Budget — das endete im Abschuss")
        assertTrue(coder7b > budget, "Coder 7B passt ins Budget")
    }

    @Test
    fun `das Budget haengt am Gesamtspeicher, nicht am freien`() {
        // Der Kern der Sache. Wäre es der freie Speicher, käme 1,5 GB heraus und das
        // Alltagsmodell fiele durch — genau die Regression, die es zu verhindern gilt.
        val budget = DeviceStateProvider.weightBudget(gemessen)

        assertTrue(
            budget > gemessen.availableBytes,
            "das Budget entspricht dem freien Speicher (${budget / 1024 / 1024} MB) — " +
                "die Gewichte liegen aber per mmap im Seitencache und sind verdrängbar",
        )
        assertEquals(
            (gemessen.totalBytes * DeviceStateProvider.WEIGHT_BUDGET_SHARE).toLong(),
            budget,
        )
    }

    @Test
    fun `viel Speicher laesst auch die grossen Modelle zu`() {
        val sechzehn = MemoryReading(totalBytes = 16 * gb, availableBytes = 10 * gb)
        val budget = DeviceStateProvider.weightBudget(sechzehn)

        listOf(alltagsmodell, bildmodell, coder7b, denker8b).forEach {
            assertTrue(it <= budget, "${it / 1024 / 1024} MB fällt bei 16 GB durch")
        }
    }

    @Test
    fun `ohne Messung gilt der Rueckfallwert`() {
        val budget = DeviceStateProvider.weightBudget(MemoryReading(0, 0))

        assertEquals(DeviceStateProvider.FALLBACK_MODEL_BUDGET, budget)
        // Der Rückfall muss in derselben Klemme brauchbar bleiben: Alltagsmodell ja,
        // große nein. Ein zu kleiner Rückfall verweigert, ein zu großer riskiert den Tod.
        assertTrue(alltagsmodell <= budget, "der Rückfall lehnt das Alltagsmodell ab")
        assertTrue(denker8b > budget, "der Rückfall lässt das 8-B-Modell zu")
    }

    @Test
    fun `ein eigener Rueckfallwert wird benutzt`() {
        val budget = DeviceStateProvider.weightBudget(MemoryReading(0, 0), fallback = 7 * gb)

        assertEquals(7 * gb, budget)
    }
}
