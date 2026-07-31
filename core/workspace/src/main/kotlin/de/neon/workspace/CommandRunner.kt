package de.neon.workspace

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Was ein Aufruf hinterlassen hat. */
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMillis: Long,
    /** Ob die Frist abgelaufen ist und der Prozess erschlagen wurde. */
    val timedOut: Boolean = false,
    /** Ob die Ausgabe gekappt wurde, weil sie die Obergrenze überschritt. */
    val truncated: Boolean = false,
) {
    val gelungen: Boolean get() = exitCode == 0 && !timedOut

    /**
     * Die Ausgabe, wie sie ins Gespräch geht.
     *
     * `stderr` gehört dazu und nicht weggelassen: Bei einem Python-Fehler steht die ganze
     * Auskunft dort — Zeilennummer, Ausnahmeart, Meldung. Ein Werkzeug, das nur `stdout`
     * zurückgibt, meldet bei einem Syntaxfehler „kein Ergebnis" und verschweigt den Grund.
     */
    fun describe(): String = buildString {
        if (stdout.isNotBlank()) append(stdout.trimEnd())
        if (stderr.isNotBlank()) {
            if (isNotEmpty()) appendLine()
            append(stderr.trimEnd())
        }
        if (timedOut) {
            if (isNotEmpty()) appendLine()
            append("(abgebrochen nach $durationMillis ms)")
        }
        if (truncated) {
            if (isNotEmpty()) appendLine()
            append("(Ausgabe gekürzt)")
        }
        if (isEmpty()) append("(keine Ausgabe, Rückgabewert $exitCode)")
    }
}

/**
 * Führt ein Programm aus und bringt seine Ausgabe zurück.
 *
 * Als Schnittstelle, damit sich alles, was darauf aufbaut, ohne echte Prozesse prüfen lässt.
 */
interface CommandRunner {
    fun run(
        command: List<String>,
        workingDir: File,
        env: Map<String, String> = emptyMap(),
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): CommandResult

    companion object {
        /**
         * Eine Minute.
         *
         * Kein Skript, das ein Sprachmodell für eine Frage geschrieben hat, soll länger
         * laufen. Wer wirklich rechnen will, gibt die Frist ausdrücklich an — und dann ist
         * es eine Entscheidung und kein Versehen.
         */
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
    }
}

/**
 * Startet echte Prozesse.
 *
 * **Drei Dinge gehen hier üblicherweise schief, und alle drei sind hier behandelt.**
 *
 *  1. **Der Puffer läuft voll.** Wer die Ausgabe eines Prozesses nicht liest, während er
 *     läuft, bringt ihn zum Stehen: Die Pipe hat ein paar Kilobyte, danach blockiert jeder
 *     Schreibversuch. Der Aufrufer wartet dann auf einen Prozess, der auf ihn wartet.
 *     Genau dieser Fehler steht schon einmal in diesem Projekt — bei der Ausgabe von
 *     `llama-server`, wo er sich als „hängt gelegentlich" äußerte. Deshalb liest je ein
 *     eigener Faden `stdout` und `stderr`, von Anfang an.
 *  2. **Die Ausgabe wächst unbegrenzt.** `while True: print(1)` erzeugt Gigabyte. Auf einem
 *     Telefon heißt das: Die App wird vom System erschlagen, und niemand erfährt, warum.
 *     Deshalb eine Obergrenze je Strom; danach wird weitergelesen, aber nicht mehr
 *     aufbewahrt — nicht abgebrochen, denn ein Prozess, dessen Ausgabe niemand mehr liest,
 *     bleibt stehen (siehe 1).
 *  3. **Der Prozess endet nie.** `while True: pass` läuft, bis der Akku leer ist. Deshalb
 *     eine Frist, und danach erst `destroy`, dann `destroyForcibly` — dieselbe Reihenfolge
 *     wie beim llama-server.
 *
 * Ohne Android: `ProcessBuilder` gehört zur Java-Standardbibliothek. Damit lässt sich das
 * hier gegen `/bin/echo` prüfen, statt erst auf dem Telefon.
 */
class ProcessCommandRunner(
    /** Obergrenze je Ausgabestrom in Zeichen. */
    private val outputLimit: Int = DEFAULT_OUTPUT_LIMIT,
) : CommandRunner {

    override fun run(
        command: List<String>,
        workingDir: File,
        env: Map<String, String>,
        timeoutMillis: Long,
    ): CommandResult {
        require(command.isNotEmpty()) { "Kein Programm angegeben" }

        val begonnen = System.currentTimeMillis()
        val bauer = ProcessBuilder(command).directory(workingDir)
        bauer.environment().putAll(env)

        val prozess = runCatching { bauer.start() }.getOrElse { fehler ->
            // Ein fehlendes Programm ist der häufigste Fall und kein Grund für eine Ausnahme
            // beim Aufrufer: Er soll darauf mit einem Satz antworten können.
            return CommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "${command.first()} ließ sich nicht starten: ${fehler.message}",
                durationMillis = System.currentTimeMillis() - begonnen,
            )
        }

        val gekappt = AtomicBoolean(false)
        val ausgabe = StringBuilder()
        val fehler = StringBuilder()

        val leserAus = leseFaden("neon-cmd-out", prozess.inputStream, ausgabe, gekappt)
        val leserFehler = leseFaden("neon-cmd-err", prozess.errorStream, fehler, gekappt)

        // stdin sofort schließen. Ein Skript, das `input()` aufruft, wartet sonst bis zur
        // Frist auf eine Eingabe, die nie kommt — und meldet dann eine Zeitüberschreitung
        // statt des eigentlichen Problems.
        runCatching { prozess.outputStream.close() }

        val rechtzeitig = prozess.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!rechtzeitig) {
            prozess.destroy()
            if (!prozess.waitFor(GNADENFRIST_MILLIS, TimeUnit.MILLISECONDS)) {
                prozess.destroyForcibly()
            }
        }

        // Auf die Leser warten, damit die Ausgabe vollständig ist. Ohne das fehlten die
        // letzten Zeilen — und die letzte Zeile ist bei einem Fehler die interessanteste.
        leserAus.join(LESER_FRIST_MILLIS)
        leserFehler.join(LESER_FRIST_MILLIS)

        return CommandResult(
            exitCode = if (rechtzeitig) prozess.exitValue() else -1,
            stdout = ausgabe.toString(),
            stderr = fehler.toString(),
            durationMillis = System.currentTimeMillis() - begonnen,
            timedOut = !rechtzeitig,
            truncated = gekappt.get(),
        )
    }

    private fun leseFaden(
        name: String,
        strom: java.io.InputStream,
        ziel: StringBuilder,
        gekappt: AtomicBoolean,
    ): Thread = thread(name = name, isDaemon = true) {
        runCatching {
            strom.bufferedReader().use { leser ->
                val puffer = CharArray(4096)
                while (true) {
                    val gelesen = leser.read(puffer)
                    if (gelesen < 0) break
                    synchronized(ziel) {
                        val platz = outputLimit - ziel.length
                        if (platz > 0) {
                            ziel.append(puffer, 0, minOf(gelesen, platz))
                            if (gelesen > platz) gekappt.set(true)
                        } else {
                            // Weiterlesen und wegwerfen. Aufhören wäre der Fehler: Ein
                            // Prozess, dessen Ausgabe niemand abnimmt, blockiert.
                            gekappt.set(true)
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /**
         * 200.000 Zeichen je Strom.
         *
         * Etwa hundert Seiten Text. Mehr kann kein Mensch lesen und kein Modell verarbeiten;
         * das Kontextfenster liegt bei 16384 Token, also grob 60.000 Zeichen. Die Grenze ist
         * bewusst deutlich darüber: Was gekürzt wird, soll an der Ausgabe liegen und nicht
         * an einer knapp gesetzten Zahl.
         */
        const val DEFAULT_OUTPUT_LIMIT = 200_000

        const val GNADENFRIST_MILLIS = 2_000L
        const val LESER_FRIST_MILLIS = 2_000L
    }
}
