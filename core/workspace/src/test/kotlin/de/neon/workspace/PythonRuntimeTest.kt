package de.neon.workspace

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Die Einrichtung der Python-Umgebung, ohne Telefon.
 *
 * **Was hier wirklich geprüft wird.** Der echte Interpreter läuft nur auf dem Gerät — er ist
 * ein arm64-Programm. Prüfbar ist alles andere, und das ist der Teil, in dem die Fehler
 * stecken: ob die Standardbibliothek vollständig ausgepackt wird, ob ein abgebrochenes
 * Auspacken erkannt wird, ob die Umgebungsvariablen stimmen, und ob ein ZIP-Eintrag mit `../`
 * im Namen aus dem Zielverzeichnis herausschreiben kann.
 *
 * Der Starter wird durch ein kleines Shell-Programm ersetzt, das seine Umgebung ausgibt. Damit
 * ist die Verdrahtung nachprüfbar, ohne zu behaupten, der Interpreter sei getestet.
 */
class PythonRuntimeTest {

    private val shell = listOf("/bin/sh", "/system/bin/sh").firstOrNull { File(it).canExecute() }

    private class Aufbau(val nativeDir: File, val dataDir: File, val projekt: File)

    private fun aufbau(): Aufbau {
        val wurzel = File.createTempFile("neon-py", "").apply { delete(); mkdirs(); deleteOnExit() }
        return Aufbau(
            nativeDir = File(wurzel, "lib").apply { mkdirs() },
            dataDir = File(wurzel, "data").apply { mkdirs() },
            projekt = File(wurzel, "projekt").apply { mkdirs() },
        )
    }

    /** Ein ZIP, das aussieht wie `python-stdlib.zip`. */
    private fun stdlibZip(vararg eintraege: Pair<String, String>): () -> java.io.InputStream {
        val bytes = java.io.ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            eintraege.forEach { (name, inhalt) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(inhalt.toByteArray())
                zip.closeEntry()
            }
        }
        return { bytes.toByteArray().inputStream() }
    }

    /** Ein Starter, der statt Python die Umgebung und seine Argumente ausgibt. */
    private fun attrappenStarter(nativeDir: File): Boolean {
        val sh = shell ?: return false
        File(nativeDir, PythonRuntime.LAUNCHER_NAME).apply {
            writeText(
                """
                |#!$sh
                |echo "ARGS=${'$'}*"
                |echo "PYTHONHOME=${'$'}PYTHONHOME"
                |echo "PYTHONDONTWRITEBYTECODE=${'$'}PYTHONDONTWRITEBYTECODE"
                |echo "PYTHONUNBUFFERED=${'$'}PYTHONUNBUFFERED"
                |echo "PWD=${'$'}(pwd)"
                """.trimMargin()
            )
            setExecutable(true)
        }
        return true
    }

    @Test
    fun `die Standardbibliothek wird ausgepackt`() {
        val a = aufbau()
        val runtime = PythonRuntime(a.nativeDir, a.dataDir)

        val entpackt = runtime.einrichten(
            stdlibZip("python3.14/os.py" to "# os", "python3.14/json/__init__.py" to "# json"),
            fassung = "abc123",
        )

        assertTrue(entpackt)
        assertEquals("# os", File(runtime.home, "python3.14/os.py").readText())
        assertEquals("# json", File(runtime.home, "python3.14/json/__init__.py").readText())
    }

    @Test
    fun `beim zweiten Start wird nicht erneut ausgepackt`() {
        val a = aufbau()
        val runtime = PythonRuntime(a.nativeDir, a.dataDir)
        runtime.einrichten(stdlibZip("python3.14/os.py" to "# os"), "abc123")

        // Auspacken kostet auf dem Telefon Sekunden. Es bei jedem Start zu tun wäre eine
        // Wartezeit ohne Gegenwert.
        assertFalse(runtime.einrichten(stdlibZip("python3.14/os.py" to "# neu"), "abc123"))
        assertEquals("# os", File(runtime.home, "python3.14/os.py").readText())
    }

    @Test
    fun `eine neue Fassung wird ausgepackt und die alte weggeraeumt`() {
        val a = aufbau()
        val runtime = PythonRuntime(a.nativeDir, a.dataDir)
        runtime.einrichten(stdlibZip("python3.13/alt.py" to "# alt"), "erste")

        assertTrue(runtime.einrichten(stdlibZip("python3.14/neu.py" to "# neu"), "zweite"))

        // Ein Rest aus einer anderen Fassung wäre schlimmer als nichts: Python fände dann
        // Module aus zwei Fassungen gemischt.
        assertFalse(File(runtime.home, "python3.13/alt.py").exists())
        assertTrue(File(runtime.home, "python3.14/neu.py").exists())
    }

    @Test
    fun `ein abgebrochenes Auspacken gilt nicht als eingerichtet`() {
        val a = aufbau()
        val runtime = PythonRuntime(a.nativeDir, a.dataDir)

        // Ein Verzeichnis, das aussieht wie ein fertiges — genau der Zustand nach einem
        // Abbruch. Ohne die Markierung darf das nicht als eingerichtet gelten, sonst fehlt
        // irgendein Modul und der Fehler heißt später „import ssl geht nicht".
        File(runtime.home, "python3.14").mkdirs()
        File(runtime.home, "python3.14/os.py").writeText("# halb")

        assertFalse(runtime.bereit)
        assertTrue(runtime.einrichten(stdlibZip("python3.14/os.py" to "# ganz"), "abc"))
        assertEquals("# ganz", File(runtime.home, "python3.14/os.py").readText())
    }

    @Test
    fun `ein ZIP-Eintrag kann nicht aus dem Zielverzeichnis herausschreiben`() {
        val a = aufbau()
        val runtime = PythonRuntime(a.nativeDir, a.dataDir)
        val opfer = File(a.dataDir, "wichtig.txt").apply { writeText("unberührt") }

        runtime.einrichten(
            stdlibZip("../wichtig.txt" to "überschrieben", "python3.14/os.py" to "# os"),
            "abc",
        )

        // Dass das ZIP aus den eigenen Assets kommt, ist kein Argument: Die Prüfung kostet
        // nichts und macht die Funktion für jedes ZIP richtig.
        assertEquals("unberührt", opfer.readText())
        assertTrue(File(runtime.home, "python3.14/os.py").exists())
    }

    @Test
    fun `ohne Starter kommt eine Erklaerung statt eines Absturzes`() {
        val a = aufbau()
        val runtime = PythonRuntime(a.nativeDir, a.dataDir)
        runtime.einrichten(stdlibZip("python3.14/os.py" to "#"), "abc")

        val ergebnis = runtime.fuehreAus("print(1)", Workspace(a.projekt))

        assertFalse(ergebnis.gelungen)
        assertTrue(ergebnis.stderr.contains("Starter fehlt"), ergebnis.stderr)
    }

    @Test
    fun `ohne ausgepackte Standardbibliothek ebenso`() {
        val a = aufbau()
        if (!attrappenStarter(a.nativeDir)) return
        val runtime = PythonRuntime(a.nativeDir, a.dataDir)

        val ergebnis = runtime.fuehreAus("print(1)", Workspace(a.projekt))

        assertFalse(ergebnis.gelungen)
        assertTrue(ergebnis.stderr.contains("Standardbibliothek"), ergebnis.stderr)
    }

    @Test
    fun `der Starter bekommt PYTHONHOME und die Skriptdatei`() {
        val a = aufbau()
        if (!attrappenStarter(a.nativeDir)) return
        val runtime = PythonRuntime(a.nativeDir, a.dataDir)
        runtime.einrichten(stdlibZip("python3.14/os.py" to "#"), "abc")
        val workspace = Workspace(a.projekt)

        val ergebnis = runtime.fuehreAus("print('hallo')", workspace)

        // PYTHONHOME ist die wichtigste Variable: Ohne sie sucht der Interpreter seine
        // Standardbibliothek an dem Pfad, der beim Übersetzen einkompiliert wurde — und bricht
        // mit „unable to get the locale encoding" ab, einer Meldung ohne Aussage.
        assertTrue(ergebnis.stdout.contains("PYTHONHOME=${runtime.home.absolutePath}"), ergebnis.stdout)
        assertTrue(ergebnis.stdout.contains("PYTHONDONTWRITEBYTECODE=1"), ergebnis.stdout)
        assertTrue(ergebnis.stdout.contains("PYTHONUNBUFFERED=1"), ergebnis.stdout)
        // Und das Projekt ist das Arbeitsverzeichnis, damit relative Pfade im Skript stimmen.
        assertTrue(ergebnis.stdout.contains("PWD=${workspace.wurzel.absolutePath}"), ergebnis.stdout)
        assertTrue(ergebnis.stdout.contains("_neon_lauf.py"), ergebnis.stdout)
    }

    @Test
    fun `der Quelltext landet in einer Datei und nicht in der Befehlszeile`() {
        val a = aufbau()
        if (!attrappenStarter(a.nativeDir)) return
        val runtime = PythonRuntime(a.nativeDir, a.dataDir)
        runtime.einrichten(stdlibZip("python3.14/os.py" to "#"), "abc")
        val workspace = Workspace(a.projekt)

        runtime.fuehreAus("print('hallo')", workspace)

        // Über eine Datei und nicht über -c: Eine Befehlszeile hat eine Längengrenze, und
        // Pythons Fehlermeldungen nennen bei einer Datei die Zeilennummer. Bei -c steht dort
        // <string>, und damit kann weder ein Mensch noch ein Modell etwas anfangen.
        assertEquals("print('hallo')", workspace.lies("_neon_lauf.py"))
    }

    @Test
    fun `ein Pfad aus dem Projekt heraus wird abgewiesen`() {
        val a = aufbau()
        if (!attrappenStarter(a.nativeDir)) return
        val runtime = PythonRuntime(a.nativeDir, a.dataDir)
        runtime.einrichten(stdlibZip("python3.14/os.py" to "#"), "abc")

        val ergebnis = runtime.fuehreSkriptAus("../../etc/passwd", Workspace(a.projekt))

        assertFalse(ergebnis.gelungen)
        assertTrue(ergebnis.stderr.contains("außerhalb"), ergebnis.stderr)
    }

    /**
     * Und einmal gegen ein echtes Python, wenn eines da ist.
     *
     * Nicht der Interpreter aus der APK — der ist ein arm64-Programm und läuft hier nicht.
     * Aber der Weg von „Quelltext" über eine Datei zu „Ausgabe und Fehlermeldung" ist
     * derselbe, und ein Test, der ihn einmal ganz durchläuft, findet mehr als zehn, die ihn
     * in Stücken prüfen. `PYTHONHOME` wird dabei ausdrücklich geleert: Das Python dieses
     * Rechners hat sein eigenes.
     */
    @Test
    fun `ein echtes Python liefert Ausgabe und Fehlermeldung durch`() {
        val sh = shell ?: return
        val python = listOf("/usr/bin/python3", "/usr/local/bin/python3")
            .firstOrNull { File(it).canExecute() } ?: return

        val a = aufbau()
        File(a.nativeDir, PythonRuntime.LAUNCHER_NAME).apply {
            writeText("#!$sh\nunset PYTHONHOME\nexec $python \"\$@\"\n")
            setExecutable(true)
        }
        val runtime = PythonRuntime(a.nativeDir, a.dataDir)
        runtime.einrichten(stdlibZip("python3.14/os.py" to "#"), "abc")
        val workspace = Workspace(a.projekt)

        val gut = runtime.fuehreAus("print(6 * 7)", workspace)
        assertTrue(gut.gelungen, gut.describe())
        assertEquals("42", gut.stdout.trim())

        // Der Fehlerfall ist der wichtigere: Die Zeilennummer muss durchkommen, sonst kann
        // das Modell die Stelle nicht finden.
        val schlecht = runtime.fuehreAus("x = 1\ny = x / 0\n", workspace)
        assertFalse(schlecht.gelungen)
        assertTrue(schlecht.stderr.contains("ZeroDivisionError"), schlecht.describe())
        assertTrue(schlecht.stderr.contains("line 2"), schlecht.describe())
    }
}
