package de.neon.workspace

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Die drei Arten, wie ein Prozessaufruf schiefgeht.
 *
 * Geprüft wird gegen **echte** Prozesse, nicht gegen Attrappen. Das geht, weil
 * `ProcessBuilder` zur Java-Standardbibliothek gehört: Was hier grün ist, verhält sich auf
 * dem Telefon genauso. Eine Attrappe würde hier gerade die Fälle verschweigen, um die es
 * geht — ein volllaufender Puffer und eine abgelaufene Frist entstehen nur bei einem echten
 * Prozess.
 */
class CommandRunnerTest {

    private val runner = ProcessCommandRunner()

    private fun verzeichnis(): File = File.createTempFile("neon-cmd", "").apply {
        delete(); mkdirs(); deleteOnExit()
    }

    private val shell = listOf("/bin/sh", "/system/bin/sh").firstOrNull { File(it).canExecute() }

    @Test
    fun `Ausgabe und Rueckgabewert kommen an`() {
        val sh = shell ?: return

        val ergebnis = runner.run(listOf(sh, "-c", "echo hallo; exit 0"), verzeichnis())

        assertEquals(0, ergebnis.exitCode)
        assertTrue(ergebnis.gelungen)
        assertEquals("hallo", ergebnis.stdout.trim())
    }

    @Test
    fun `stderr geht nicht verloren`() {
        val sh = shell ?: return

        // Der wichtigste Strom bei einem Fehler: Bei Python steht dort die Zeilennummer, die
        // Ausnahmeart und die Meldung. Ein Werkzeug, das nur stdout zurückgibt, meldet
        // „kein Ergebnis" und verschweigt den Grund.
        val ergebnis = runner.run(listOf(sh, "-c", "echo raus; echo rein >&2; exit 3"), verzeichnis())

        assertEquals(3, ergebnis.exitCode)
        assertFalse(ergebnis.gelungen)
        assertEquals("rein", ergebnis.stderr.trim())
        assertTrue(ergebnis.describe().contains("raus"), ergebnis.describe())
        assertTrue(ergebnis.describe().contains("rein"), ergebnis.describe())
    }

    @Test
    fun `viel Ausgabe bringt den Prozess nicht zum Stehen`() {
        val sh = shell ?: return

        // **Der Fehler, den das ausschließt.** Wer die Ausgabe eines Prozesses nicht liest,
        // während er läuft, bringt ihn zum Stehen: Die Pipe hat wenige Kilobyte, danach
        // blockiert jeder Schreibversuch. Genau dieser Fehler steckte schon einmal in diesem
        // Projekt, bei der Ausgabe von llama-server, und äußerte sich als „hängt gelegentlich".
        //
        // Ein Megabyte ist weit mehr als jede Pipe fasst. Ohne die Leser-Fäden liefe dieser
        // Test in die Frist statt in ein Ergebnis.
        //
        // Die Obergrenze wird hier großzügig gesetzt, damit sie **nicht** das Ergebnis
        // bestimmt: Geprüft wird das Blockieren, nicht das Kappen — das hat seinen eigenen
        // Test. Mit der Vorgabe von 200.000 Zeichen hätte dieser Test das Kappen gemessen und
        // sich selbst für ein Blockieren gehalten.
        val grosszuegig = ProcessCommandRunner(outputLimit = 2_000_000)
        val ergebnis = grosszuegig.run(
            listOf(sh, "-c", "i=0; while [ \$i -lt 20000 ]; do echo 0123456789012345678901234567890123456789012345678; i=\$((i+1)); done"),
            verzeichnis(),
            timeoutMillis = 30_000,
        )

        assertFalse(ergebnis.timedOut, "in die Frist gelaufen — der Puffer ist volllgelaufen")
        assertEquals(0, ergebnis.exitCode)
        assertTrue(ergebnis.stdout.length > 500_000, "nur ${ergebnis.stdout.length} Zeichen")
    }

    @Test
    fun `unbegrenzte Ausgabe wird gekappt, ohne den Prozess zu haengen`() {
        val sh = shell ?: return

        // `while True: print(1)` erzeugt Gigabyte. Auf einem Telefon heißt das: Die App wird
        // vom System erschlagen, und niemand erfährt, warum.
        val klein = ProcessCommandRunner(outputLimit = 1_000)
        val ergebnis = klein.run(
            listOf(sh, "-c", "i=0; while [ \$i -lt 5000 ]; do echo abcdefghij; i=\$((i+1)); done"),
            verzeichnis(),
            timeoutMillis = 30_000,
        )

        assertTrue(ergebnis.truncated, "nicht als gekürzt gemeldet")
        assertTrue(ergebnis.stdout.length <= 1_000, "gekappt auf ${ergebnis.stdout.length}")
        // Und trotzdem zu Ende gelaufen: Aufhören zu lesen wäre der Fehler, denn ein Prozess,
        // dessen Ausgabe niemand abnimmt, blockiert.
        assertFalse(ergebnis.timedOut, "in die Frist gelaufen")
        assertTrue(ergebnis.describe().contains("gekürzt"), ergebnis.describe())
    }

    @Test
    fun `eine Endlosschleife wird nach der Frist beendet`() {
        val sh = shell ?: return

        val ergebnis = runner.run(
            listOf(sh, "-c", "while true; do :; done"),
            verzeichnis(),
            timeoutMillis = 1_000,
        )

        assertTrue(ergebnis.timedOut)
        assertFalse(ergebnis.gelungen)
        assertTrue(ergebnis.durationMillis >= 1_000, "${ergebnis.durationMillis} ms")
        // Nicht zu lange: destroy, dann destroyForcibly — nicht warten, bis der Akku leer ist.
        assertTrue(ergebnis.durationMillis < 10_000, "${ergebnis.durationMillis} ms")
        assertTrue(ergebnis.describe().contains("abgebrochen"), ergebnis.describe())
    }

    @Test
    fun `wer auf Eingabe wartet, wartet nicht bis zur Frist`() {
        val sh = shell ?: return

        // stdin wird sofort geschlossen. Ohne das liefe ein Skript mit `input()` in die
        // Frist und meldete eine Zeitüberschreitung statt des eigentlichen Problems.
        val ergebnis = runner.run(listOf(sh, "-c", "read zeile; echo \"[\$zeile]\""), verzeichnis(), timeoutMillis = 5_000)

        assertFalse(ergebnis.timedOut, "hat auf eine Eingabe gewartet, die nie kommt")
        assertTrue(ergebnis.durationMillis < 5_000)
    }

    @Test
    fun `ein fehlendes Programm ist keine Ausnahme, sondern eine Antwort`() {
        // Der häufigste Fall überhaupt, und der Aufrufer soll darauf mit einem Satz antworten
        // können statt mit einem Absturz.
        val ergebnis = runner.run(listOf("/gibt/es/nicht"), verzeichnis())

        assertEquals(-1, ergebnis.exitCode)
        assertTrue(ergebnis.stderr.contains("ließ sich nicht starten"), ergebnis.stderr)
    }

    @Test
    fun `das Arbeitsverzeichnis gilt`() {
        val sh = shell ?: return
        val verzeichnis = verzeichnis()
        File(verzeichnis, "beweis.txt").writeText("da")

        val ergebnis = runner.run(listOf(sh, "-c", "cat beweis.txt"), verzeichnis)

        assertEquals("da", ergebnis.stdout.trim())
    }

    @Test
    fun `Umgebungsvariablen kommen an`() {
        val sh = shell ?: return

        val ergebnis = runner.run(
            listOf(sh, "-c", "echo \$NEON_TEST"),
            verzeichnis(),
            env = mapOf("NEON_TEST" to "gesetzt"),
        )

        assertEquals("gesetzt", ergebnis.stdout.trim())
    }

    @Test
    fun `ohne Ausgabe sagt die Beschreibung den Rueckgabewert`() {
        val sh = shell ?: return

        // Eine leere Sprechblase erklärt nichts. „Rückgabewert 7" schon.
        val ergebnis = runner.run(listOf(sh, "-c", "exit 7"), verzeichnis())

        assertTrue(ergebnis.describe().contains("7"), ergebnis.describe())
    }
}
