package de.neon.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Die Frist, nach der Neon einen startenden Server aufgibt.
 *
 * Hier stand einmal eine feste Minute. Auf dem Gerät brauchte das Alltagsmodell **59,548
 * Sekunden** — Neon gab 450 Millisekunden später auf und erschlug einen Server, der gerade
 * fertig geworden war. Der nächste Versuch begann von vorn und scheiterte genauso: kein
 * Wackeln, sondern ein dauerhafter Ausfall.
 *
 * Diese Tests halten fest, dass die Frist zur Sache passt statt geraten zu sein. Sie können
 * nicht belegen, dass es auf dem Telefon reicht — das kann nur das Telefon. Sie belegen,
 * dass die Rechnung stimmt und in die richtige Richtung zeigt.
 */
class StartupBudgetTest {

    private val gb = 1024L * 1024 * 1024

    private fun budget(bytes: Long) = ProcessServerSupervisor.startupBudgetMillis(bytes)

    @Test
    fun `das Alltagsmodell bekommt deutlich mehr als die gemessene Ladezeit`() {
        // Gemessen: 59,5 s für 2,33 GiB. Die Frist muss klar darüber liegen, sonst wiederholt
        // sich der Ausfall beim nächsten kalten Start.
        val gemessen = 59_548L
        val frist = budget((2.33 * gb).toLong())

        assertTrue(
            frist > gemessen * 2,
            "Frist ${frist} ms ist nicht mindestens doppelt so lang wie die gemessenen $gemessen ms",
        )
    }

    @Test
    fun `die Frist waechst mit der Modellgroesse`() {
        // Eine feste Zahl kann für 0,4 GB und 5 GB nicht gleichzeitig richtig sein. Genau
        // daran ist die alte Fassung gescheitert.
        assertTrue(budget(5 * gb) > budget(2 * gb))
        assertTrue(budget(2 * gb) > budget(gb / 2))
    }

    @Test
    fun `auch ein winziges Modell bekommt die Grundzeit`() {
        // Der Serverstart selbst kostet Zeit, unabhängig vom Modell.
        assertEquals(ProcessServerSupervisor.STARTUP_BASE_MILLIS, budget(0))
        assertTrue(budget(1024) >= ProcessServerSupervisor.STARTUP_BASE_MILLIS)
    }

    @Test
    fun `der Denker bekommt ueber acht Minuten`() {
        // Bei den gemessenen 42 MB/s braucht ein 5-GB-Modell rund zwei Minuten. Die Frist
        // liegt weit darüber — bewusst: Zu früh aufzugeben kostet die ganze Antwort, zu spät
        // nur Geduld in einem Fall, der ohnehin schiefgeht.
        val frist = budget(5 * gb)
        assertTrue(frist >= 8 * 60_000L, "nur ${frist / 1000} s für ein 5-GB-Modell")
    }

    @Test
    fun `die Stillefrist ist kuerzer als jede Startfrist`() {
        // Sonst griffe sie nie: Ein hängender Server soll am Schweigen auffallen, nicht erst
        // am Ende der großzügigen Obergrenze.
        assertTrue(
            ProcessServerSupervisor.SILENCE_TIMEOUT_MILLIS < budget(0),
            "die Stillefrist ist länger als die kürzestmögliche Startfrist",
        )
    }

    @Test
    fun `die Rechnung ist nachvollziehbar`() {
        // Grundzeit plus Zuschlag je Gigabyte — keine versteckte Kurve.
        assertEquals(
            ProcessServerSupervisor.STARTUP_BASE_MILLIS +
                4 * ProcessServerSupervisor.STARTUP_PER_GIGABYTE_MILLIS,
            budget(4 * gb),
        )
    }
}
