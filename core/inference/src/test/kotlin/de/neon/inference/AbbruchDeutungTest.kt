package de.neon.inference

import de.neon.platform.MemoryReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ein Abbruch muss sagen, wer gestorben ist.
 *
 * **Der Anlass.** Gemeldet wurde:
 *
 * ```
 * unexpected end of stream on http://127.0.0.1:18080/
 * ```
 *
 * Port 18080 gehört `llama-server`, niemand sonst kann diese Verbindung abbrechen. Die
 * Meldung sagt also, dass die Gegenseite mitten in der Antwort weg war — und über die Ursache
 * nichts. Neon gab sie wörtlich weiter: *„Da ging etwas schief: unexpected end of stream on
 * http://127.0.0.1:18080/"*.
 *
 * Dabei hängt an der Ursache die nächste Runde. Prozess tot heißt Speichermangel und damit
 * kleineres Modell oder kleineres Fenster. Prozess lebt heißt: ein ganz anderer Fehler.
 * Dasselbe Muster wie bei der verschluckten Ausnahme in `isHealthy` — der Fehler war da, er
 * sagte nur nicht, welcher er ist.
 */
class AbbruchDeutungTest {

    private val abriss = "unexpected end of stream on http://127.0.0.1:18080/"

    @Test
    fun `ein toter Prozess wird als solcher benannt`() {
        val meldung = LlamaServerEngine.deuteAbbruch(abriss, lebt = false)

        assertTrue(meldung.contains("beendet"), meldung)
        // Die Empfehlung gehört dazu: Wer die Meldung liest, soll wissen, was zu tun ist.
        assertTrue(meldung.contains("Modell") || meldung.contains("Kontextfenster"), meldung)
    }

    @Test
    fun `ein lebender Prozess ergibt eine andere Auskunft`() {
        val tot = LlamaServerEngine.deuteAbbruch(abriss, lebt = false)
        val lebt = LlamaServerEngine.deuteAbbruch(abriss, lebt = true)

        // Der eigentliche Zweck der ganzen Übung: Die beiden Fälle dürfen nicht gleich
        // aussehen. Genau das war der Zustand vorher, denn die Rohmeldung ist in beiden
        // Fällen dieselbe.
        assertTrue(tot != lebt, "beide Fälle ergeben dieselbe Meldung: $tot")
        assertTrue(lebt.contains("noch läuft"), lebt)
    }

    @Test
    fun `unbekannter Zustand behauptet nichts`() {
        val meldung = LlamaServerEngine.deuteAbbruch(abriss, lebt = null)

        // Ein Supervisor, der den Prozess nicht besitzt, kann über ihn nichts sagen. Dann
        // darf hier auch nichts stehen, was wie eine Feststellung klingt.
        assertFalse(meldung.contains("beendet"), meldung)
        assertFalse(meldung.contains("noch läuft"), meldung)
        assertTrue(meldung.contains("brach ab"), meldung)
    }

    @Test
    fun `eine Ablehnung des Servers bleibt unverfaelscht`() {
        // Wer mit einem Statuscode antwortet, lebt. Über einen weggefallenen Prozess zu
        // reden wäre hier falsch — und die Meldung steht schon in verständlichem Deutsch da.
        val roh = "${LlamaServerClient.ABLEHNUNG_PRAEFIX} 500: context shift is disabled"

        assertEquals(roh, LlamaServerEngine.deuteAbbruch(roh, lebt = false))
        assertEquals(roh, LlamaServerEngine.deuteAbbruch(roh, lebt = true))
    }

    @Test
    fun `der Zustandsbericht nennt Kontext, Speicher und die letzte Serverzeile`() {
        val bericht = ServerZustand(
            lebt = false,
            kontextGroesse = 8_192,
            letzteZeile = "srv  log_server_r: request: POST /v1/chat/completions",
            speicher = MemoryReading(totalBytes = 5_700_000_000, availableBytes = 900_000_000),
        ).describe()

        assertTrue(bericht.contains("tot"), bericht)
        assertTrue(bericht.contains("8192"), bericht)
        assertTrue(bericht.contains("RAM"), bericht)
        assertTrue(bericht.contains("log_server_r"), bericht)
    }

    @Test
    fun `ohne Messung steht kein erfundener Wert im Bericht`() {
        val bericht = ServerZustand(lebt = null).describe()

        assertTrue(bericht.contains("unbekannt"), bericht)
        // Kontext 0 wäre eine Zahl, die nichts bedeutet. Sie gehört weggelassen, nicht
        // gemeldet — eine Null im Protokoll liest sich später wie eine Messung.
        assertFalse(bericht.contains("Kontext"), bericht)
    }

    /**
     * Ein Supervisor, der den Prozess nicht besitzt, sagt genau das.
     *
     * Dieser Test hält die Vorgabe der Schnittstelle fest. Sie auf `true` oder `false` zu
     * setzen wäre bequemer gewesen und hätte eine Vermutung zur Tatsache gemacht.
     */
    @Test
    fun `ein fremder Server gilt als unbekannt und nicht als tot`() {
        val fremd = RunningServerSupervisor("http://127.0.0.1:1")

        assertEquals(null, fremd.zustand().lebt)
    }
}
