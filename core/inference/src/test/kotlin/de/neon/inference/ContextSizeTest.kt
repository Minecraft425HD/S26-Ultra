package de.neon.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Die Kontextgröße muss in den Speicher passen, der wirklich da ist.
 *
 * **Der Fehler, den das verhindert.** Auf dem Gerät standen 1,6 GB frei. Kontext 16384
 * verlangt davon 1152 MB für den Schlüssel-Wert-Speicher — und das ist anonymer Speicher,
 * den der Kernel nicht verdrängen kann, anders als die per `mmap` eingebundenen
 * Modellgewichte. Damit war Neon der dickste Brocken im System, und Androids
 * Low-Memory-Killer nahm genau den: **sechs Anläufe, sechsmal erschlagen**, keiner erreichte
 * `model loaded`.
 *
 * Die Zahlen in diesen Tests stammen aus dem Protokoll vom 30. Juli.
 */
class ContextSizeTest {

    private val mb = 1024L * 1024
    private val gb = 1024L * mb

    private fun waehlen(verfuegbarMb: Long, obergrenze: Int = 16_384) =
        ProcessServerSupervisor.passendeKontextgroesse(verfuegbarMb * mb, obergrenze)

    @Test
    fun `der gemessene Fall ergibt 4096`() {
        // 1600 MB frei. Ein Fünftel davon sind 320 MB; 8192 bräuchte 576 MB, 4096 nur 288.
        assertEquals(4_096, waehlen(1_600))
    }

    @Test
    fun `bei 1,7 GB frei wird nicht 8192 gewaehlt`() {
        // **Der Fall, der auf dem Gerät zum Abschuss führte.** Mit der ersten Fassung dieser
        // Regel (ein Drittel) waren 580 MB erlaubt, 8192 brauchte 576 — vier Megabyte
        // Spielraum. Der Prozess wurde erschlagen. Bei 1,9 GB frei überlebte derselbe Wert
        // zufällig; das war Glück, keine Entscheidung.
        //
        // Was in der Rechnung fehlte, sind die Rechenpuffer: ebenfalls anonym, ebenfalls
        // nicht verdrängbar, und für ein 4-B-Modell mehrere hundert Megabyte.
        assertEquals(4_096, waehlen(1_700), "8192 bei 1,7 GB frei — genau das wurde getötet")
        assertEquals(4_096, waehlen(1_900), "8192 bei 1,9 GB frei — das überlebte nur knapp")
    }

    @Test
    fun `die KV-Groesse kommt vom Modell`() {
        // Qwen3 1.7B hat 28 Schichten statt 36, also 57344 Byte je Token statt 73728.
        // Wäre die Konstante des 4-B-Modells fest verdrahtet, würde für das kleinere Modell
        // 22 Prozent zu viel gerechnet — und das Kontextfenster grundlos gekürzt.
        val kleines = 57_344L

        assertEquals(
            8_192,
            ProcessServerSupervisor.passendeKontextgroesse(
                verfuegbar = 2_400 * mb,
                obergrenze = 16_384,
                kvBytesPerToken = kleines,
            ),
            "mit 57344 Byte je Token passen bei 2400 MB frei 8192 Token",
        )

        // Dasselbe Speicherangebot, das größere Modell: Da passt nur 4096.
        assertEquals(
            4_096,
            ProcessServerSupervisor.passendeKontextgroesse(
                verfuegbar = 2_400 * mb,
                obergrenze = 16_384,
                kvBytesPerToken = ProcessServerSupervisor.KV_BYTES_PER_TOKEN,
            ),
        )
    }

    @Test
    fun `der Schluessel-Wert-Speicher bleibt unter einem Fuenftel`() {
        // Die eigentliche Zusicherung, unabhängig von den Stufen: Was gewählt wird, darf
        // nicht mehr als ein Fünftel beanspruchen. Der Rest ist für Rechenpuffer, die App
        // und alles, was Android sonst noch vorhat — und die Rechenpuffer waren der Posten,
        // der in der ersten Fassung fehlte und den Prozess kostete.
        listOf(300L, 800L, 1_600L, 3_000L, 6_000L, 12_000L).forEach { frei ->
            val gewaehlt = waehlen(frei, obergrenze = 32_768)
            val kv = gewaehlt.toLong() * ProcessServerSupervisor.KV_BYTES_PER_TOKEN

            // Ausnahme: die kleinste Stufe wird auch dann genommen, wenn selbst sie nicht
            // passt — ein Fenster unter 4096 wäre nicht mehr benutzbar.
            if (gewaehlt > ProcessServerSupervisor.MIN_CONTEXT_SIZE) {
                assertTrue(
                    kv <= frei * mb / ProcessServerSupervisor.KV_ANTEIL,
                    "$gewaehlt bei $frei MB frei: ${kv / mb} MB sind mehr als ein Fünftel",
                )
            }
        }
    }

    @Test
    fun `viel Speicher laesst die Obergrenze zu`() {
        // 12 GB frei — dann darf der eingestellte Wunsch gelten.
        assertEquals(16_384, waehlen(12_000, obergrenze = 16_384))
        assertEquals(32_768, waehlen(12_000, obergrenze = 32_768))
    }

    @Test
    fun `die Obergrenze wird nie ueberschritten`() {
        // Der Regler ist eine Obergrenze, keine Untergrenze: Viel freier Speicher darf nicht
        // dazu führen, dass mehr genommen wird als eingestellt.
        assertEquals(4_096, waehlen(12_000, obergrenze = 4_096))
        assertEquals(8_192, waehlen(64_000, obergrenze = 8_192))
    }

    @Test
    fun `ohne Messung bleibt der eingestellte Wert`() {
        // Eine fehlende Auskunft darf keine stille Verschlechterung auslösen. Sonst kürzt
        // Neon auf jedem Gerät, dessen /proc/meminfo sich nicht lesen lässt, grundlos das
        // Kontextfenster — und niemand käme darauf, warum.
        assertEquals(16_384, ProcessServerSupervisor.passendeKontextgroesse(0, 16_384))
        assertEquals(32_768, ProcessServerSupervisor.passendeKontextgroesse(-1, 32_768))
    }

    @Test
    fun `bei sehr wenig Speicher bleibt die kleinste Stufe`() {
        // 200 MB frei: Selbst 4096 bräuchte 288 MB. Kleiner zu werden hilft nicht — unter
        // 4096 passt kaum eine Seite Text. Dann soll der Abschuss sichtbar werden statt in
        // einer unbrauchbaren Einstellung zu verschwinden.
        assertEquals(ProcessServerSupervisor.MIN_CONTEXT_SIZE, waehlen(200))
    }

    @Test
    fun `die Rechnung ist nachvollziehbar`() {
        // 288 MB bei 4096 Token, mit q8_0: 4096 * 73728 Byte.
        val kv4096 = 4_096L * ProcessServerSupervisor.KV_BYTES_PER_TOKEN
        assertEquals(288L * mb, kv4096)

        // Und 1152 MB bei 16384 — genau die Zahl aus der Abschussmeldung.
        val kv16384 = 16_384L * ProcessServerSupervisor.KV_BYTES_PER_TOKEN
        assertEquals(1_152L * mb, kv16384)
    }

    @Test
    fun `alle Stufen liegen im erlaubten Bereich`() {
        ProcessServerSupervisor.KONTEXT_STUFEN.forEach {
            assertTrue(
                it in ProcessServerSupervisor.MIN_CONTEXT_SIZE..ProcessServerSupervisor.MAX_CONTEXT_SIZE,
                "Stufe $it liegt außerhalb der erlaubten Grenzen",
            )
        }
        // Die kleinste Stufe muss die Untergrenze sein, sonst könnte der Rückfall in
        // MIN_CONTEXT_SIZE eine Größe liefern, die es als Stufe gar nicht gibt.
        assertEquals(
            ProcessServerSupervisor.MIN_CONTEXT_SIZE,
            ProcessServerSupervisor.KONTEXT_STUFEN.min(),
        )
        assertEquals(
            ProcessServerSupervisor.MAX_CONTEXT_SIZE,
            ProcessServerSupervisor.KONTEXT_STUFEN.max(),
        )
    }

    /**
     * **Ein gespeicherter Zwischenwert wird stillschweigend berichtigt.**
     *
     * Der Regler rastet seit einer Weile; ein früher gespeicherter Wert nicht. Auf dem Gerät
     * stand 18432 in den Einstellungen, und weil nichts ihn je berichtigte, protokollierte
     * jeder einzelne Serverstart „Kontext auf 16384 statt 18432 — 18432 ist keine der
     * möglichen Stufen". Sachlich richtig, und trotzdem eine Zeile, die dauerhaft nach einem
     * Fehler aussieht, wo keiner ist.
     */
    @Test
    fun `ein Wert zwischen den Stufen wird auf die naechstkleinere gerastet`() {
        assertEquals(16_384, ProcessServerSupervisor.naechsteStufe(18_432))
        assertEquals(16_384, ProcessServerSupervisor.naechsteStufe(25_600))
        assertEquals(8_192, ProcessServerSupervisor.naechsteStufe(11_264))
        // Und die Stufen selbst bleiben, wie sie sind.
        ProcessServerSupervisor.KONTEXT_STUFEN.forEach {
            assertEquals(it, ProcessServerSupervisor.naechsteStufe(it))
        }
    }
}
