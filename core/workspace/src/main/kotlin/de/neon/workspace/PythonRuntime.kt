package de.neon.workspace

import java.io.File
import java.util.zip.ZipInputStream

/**
 * Die Python-Umgebung auf dem Gerät.
 *
 * **Woraus sie besteht.** Der Starter (`libpython-launcher.so`) und die Bibliotheken
 * (`libpython3.x.so`, OpenSSL, SQLite) liegen im Verzeichnis, in das Androids Installer die
 * mitgelieferten Programme entpackt — dort darf ausgeführt werden, im Datenverzeichnis nicht.
 * Die Standardbibliothek dagegen kommt als ZIP aus den Assets und muss einmal entpackt werden:
 * Sie besteht aus über dreitausend Dateien, darunter 67 Erweiterungsmodule, deren Namen nicht
 * mit `lib` beginnen — die hätte der Installer gar nicht angefasst.
 *
 * **Kein Android in dieser Klasse.** Die Pfade kommen als Parameter herein, das ZIP als
 * `InputStream`. Damit lässt sich die ganze Einrichtung in einem Verzeichnis unter `/tmp`
 * prüfen, und auf dem Rechner sogar gegen ein echtes Python — dieselbe Bauart, die den
 * Gesprächsablauf ohne Mikrofon prüfbar macht.
 */
class PythonRuntime(
    /** Wohin Android die mitgelieferten Programme entpackt hat. */
    private val nativeDir: File,
    /** Das Datenverzeichnis der App. Hierhin kommt die Standardbibliothek. */
    private val dataDir: File,
    private val runner: CommandRunner = ProcessCommandRunner(),
) {

    /** Wo die Standardbibliothek nach dem Entpacken liegt. */
    val home: File get() = File(dataDir, HOME_NAME)

    /** Der Starter. Heißt `lib*.so`, ist aber ein Programm — wie llama-server und aapt2. */
    val launcher: File get() = File(nativeDir, LAUNCHER_NAME)

    /** Ob die Umgebung benutzbar ist: Starter vorhanden und Standardbibliothek entpackt. */
    val bereit: Boolean
        get() = launcher.canExecute() && markierung.isFile

    /**
     * Die Datei, die sagt, welche Fassung entpackt wurde.
     *
     * **Warum eine Markierung und nicht „liegt das Verzeichnis da".** Ein abgebrochenes
     * Entpacken hinterlässt ein Verzeichnis, das aussieht wie ein fertiges. Beim nächsten
     * Start würde Neon es benutzen, und irgendein Modul fehlte — ein Fehler, der sich als
     * „`import ssl` geht nicht" äußert und nichts über seine Ursache sagt. Die Markierung wird
     * **zuletzt** geschrieben; ohne sie gilt die Umgebung als nicht eingerichtet.
     */
    private val markierung: File get() = File(dataDir, MARKER_NAME)

    /**
     * Entpackt die Standardbibliothek, wenn nötig.
     *
     * @param zip die Assets-Datei `python-stdlib.zip`, als Strom.
     * @param fassung eine Kennung, die sich bei einer neuen Python-Fassung ändert. Meist der
     *   Baustand der App: Er ändert sich genau dann, wenn sich das ZIP geändert haben kann.
     * @return `true`, wenn entpackt wurde; `false`, wenn es schon passt.
     */
    fun einrichten(zip: () -> java.io.InputStream, fassung: String): Boolean {
        if (markierung.isFile && markierung.readText() == fassung) return false

        // Zuerst weg mit dem Alten. Ein Rest aus einer anderen Fassung wäre schlimmer als
        // nichts: Python fände dann Module aus zwei Fassungen gemischt.
        home.deleteRecursively()
        markierung.delete()
        home.mkdirs()

        entpacke(zip(), home)

        // Zuletzt die Markierung. Bricht das Entpacken vorher ab, gilt die Umgebung als nicht
        // eingerichtet und wird beim nächsten Mal neu gemacht.
        markierung.writeText(fassung)
        return true
    }

    /**
     * Führt Python-Quelltext aus.
     *
     * Der Text geht über eine Datei und nicht über `-c`: Eine Befehlszeile hat eine
     * Längengrenze, und die Fehlermeldungen von Python nennen bei einer Datei die Zeilennummer.
     * Bei `-c` steht dort `<string>`, und damit kann weder ein Mensch noch ein Modell etwas
     * anfangen.
     */
    fun fuehreAus(
        quelltext: String,
        workspace: Workspace,
        dateiname: String = "_neon_lauf.py",
        timeoutMillis: Long = CommandRunner.DEFAULT_TIMEOUT_MILLIS,
    ): CommandResult {
        val skript = workspace.datei(dateiname)
            ?: return fehlt("„$dateiname\" liegt außerhalb des Projekts.")
        skript.parentFile?.mkdirs()
        skript.writeText(quelltext)

        return fuehreSkriptAus(dateiname, workspace, timeoutMillis)
    }

    /** Führt eine Datei aus dem Projekt aus. */
    fun fuehreSkriptAus(
        pfad: String,
        workspace: Workspace,
        timeoutMillis: Long = CommandRunner.DEFAULT_TIMEOUT_MILLIS,
    ): CommandResult {
        if (!launcher.canExecute()) {
            return fehlt("Der Python-Starter fehlt unter ${launcher.absolutePath}.")
        }
        if (!markierung.isFile) {
            return fehlt("Die Standardbibliothek ist noch nicht entpackt.")
        }
        val skript = workspace.datei(pfad)
            ?: return fehlt("„$pfad\" liegt außerhalb des Projekts.")
        if (!skript.isFile) return fehlt("Die Datei $pfad gibt es nicht.")

        return runner.run(
            command = listOf(launcher.absolutePath, skript.absolutePath),
            workingDir = workspace.wurzel,
            env = umgebung(),
            timeoutMillis = timeoutMillis,
        )
    }

    /**
     * Die Umgebungsvariablen, die Python braucht.
     *
     * `PYTHONHOME` ist die wichtigste: Ohne sie sucht der Interpreter seine
     * Standardbibliothek an dem Pfad, der beim Übersetzen einkompiliert wurde — und der liegt
     * auf dem Rechner des Bauenden, nicht auf diesem Telefon. Python bricht dann mit
     * „unable to get the locale encoding" ab, einer Meldung, die nichts über die Ursache sagt.
     */
    private fun umgebung(): Map<String, String> = mapOf(
        "PYTHONHOME" to home.absolutePath,
        // Kein Bytecode neben die Quellen. Er brächte nichts — jedes Skript läuft meist
        // einmal — und würde das Projektverzeichnis mit __pycache__ zumüllen, das dann in
        // der Dateiliste auftaucht, die ans Modell geht.
        "PYTHONDONTWRITEBYTECODE" to "1",
        // Ausgabe sofort weiterreichen. Ohne das sammelt Python bis zu vier Kilobyte, und
        // bei einem Abbruch durch die Frist wäre die Ausgabe genau dann leer, wenn man sie
        // am dringendsten braucht.
        "PYTHONUNBUFFERED" to "1",
        // Der Linker findet libpython über den RUNPATH des Starters; für Erweiterungsmodule,
        // die untereinander verweisen, hilft der Pfad zusätzlich.
        "LD_LIBRARY_PATH" to nativeDir.absolutePath,
        "HOME" to dataDir.absolutePath,
        "TMPDIR" to File(dataDir, "tmp").apply { mkdirs() }.absolutePath,
    )

    private fun fehlt(grund: String) = CommandResult(
        exitCode = -1,
        stdout = "",
        stderr = grund,
        durationMillis = 0,
    )

    private fun entpacke(strom: java.io.InputStream, ziel: File) {
        val wurzel = ziel.canonicalFile
        ZipInputStream(strom.buffered()).use { zip ->
            while (true) {
                val eintrag = zip.nextEntry ?: break
                val datei = File(wurzel, eintrag.name).canonicalFile

                // Ein ZIP-Eintrag mit `../` im Namen schreibt sonst irgendwohin. Dass das ZIP
                // aus den eigenen Assets kommt, ist kein Argument: Diese Prüfung kostet
                // nichts und macht die Funktion für jedes ZIP richtig.
                if (!datei.path.startsWith(wurzel.path)) {
                    zip.closeEntry()
                    continue
                }

                if (eintrag.isDirectory) {
                    datei.mkdirs()
                } else {
                    datei.parentFile?.mkdirs()
                    datei.outputStream().buffered().use { zip.copyTo(it) }

                    // Erweiterungsmodule müssen lesbar sein, ausführbar nicht: Sie werden
                    // per dlopen geladen, nicht gestartet.
                    datei.setReadable(true)
                }
                zip.closeEntry()
            }
        }
    }

    companion object {
        const val LAUNCHER_NAME = "libpython-launcher.so"

        /** Der Name des Assets, wie ihn `scripts/fetch-python.sh` erzeugt. */
        const val STDLIB_ASSET = "python-stdlib.zip"

        private const val HOME_NAME = "python"
        private const val MARKER_NAME = "python/.fassung"
    }
}
