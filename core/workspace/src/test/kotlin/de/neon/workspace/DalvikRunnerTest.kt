package de.neon.workspace

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wo die Java-Laufzeit gesucht wird — und was gemeldet wird, wenn sie fehlt.
 *
 * **Der Fehler, den diese Tests festhalten, hat zwei Wochen gekostet.** Gesucht wurde in
 * `/system/bin`. Dort liegt `dalvikvm` seit Android 10 nicht mehr: ART ist seither ein
 * APEX-Modul, und die ausführbaren Dateien liegen unter `/apex/com.android.art/bin/`. Auf dem
 * Gerät scheiterte jeder Bauversuch am dritten Schritt, und die Meldung dazu lautete
 * „dalvikvm ist auf diesem Gerät nicht ausführbar" — inhaltlich richtig, als Hinweis wertlos.
 *
 * Deshalb prüft die zweite Hälfte dieser Datei die **Fehlermeldung**. Eine Meldung, die keinen
 * Pfad nennt, sagt nicht, dass etwas fehlt — sie sagt nur, dass jemand nicht nachgesehen hat.
 */
class DalvikRunnerTest {

    private fun tempDir(): File =
        File.createTempFile("neon-dalvik", "").apply { delete(); mkdirs(); deleteOnExit() }

    /** Eine Attrappe, die festhält, womit sie aufgerufen wurde. */
    private class Mitschrift : CommandRunner {
        var befehl: List<String> = emptyList()
        var umgebung: Map<String, String> = emptyMap()

        override fun run(
            command: List<String>,
            workingDir: File,
            env: Map<String, String>,
            timeoutMillis: Long,
        ): CommandResult {
            befehl = command
            umgebung = env
            return CommandResult(0, "fertig", "", 1)
        }
    }

    // ---- Wo gesucht wird ---------------------------------------------------------------

    /**
     * **Die APEX-Pfade müssen vorn stehen und dabei sein.**
     *
     * Sie gelten auf allem, was neuer als Android 9 ist — also auf dem Gerät, um das es geht.
     */
    @Test
    fun `die Suchliste beginnt im ART-APEX`() {
        val liste = DalvikRunner.DALVIKVM

        assertEquals("/apex/com.android.art/bin/dalvikvm64", liste.first())
        assertTrue(
            liste.any { it == "/apex/com.android.art/bin/dalvikvm" },
            "ohne die Fassung ohne Ziffern fehlt der Name, den manche Abbilder benutzen",
        )
        assertTrue(
            liste.any { it.startsWith("/apex/com.android.runtime/") },
            "com.android.runtime hieß das Modul in Android 10",
        )
        // Und der alte Ort bleibt, für ältere Abbilder — aber hinten.
        assertTrue(liste.any { it.startsWith("/system/bin/") })
        assertTrue(
            liste.indexOfFirst { it.startsWith("/apex/") }
                < liste.indexOfFirst { it.startsWith("/system/bin/") },
            "APEX zuerst: dort liegt sie auf jedem aktuellen Gerät",
        )
    }

    /** 64 Bit vor 32 Bit: Ein 32-Bit-Prozess bekommt nur vier Gigabyte Adressraum. */
    @Test
    fun `innerhalb eines Ortes kommt die 64-Bit-Fassung zuerst`() {
        val imApex = DalvikRunner.DALVIKVM.filter { it.startsWith("/apex/com.android.art/") }

        assertTrue(imApex.first().endsWith("dalvikvm64"), imApex.toString())
        assertTrue(
            imApex.indexOfFirst { it.endsWith("dalvikvm32") } == imApex.lastIndex,
            "32 Bit ist der letzte Ausweg: $imApex",
        )
    }

    @Test
    fun `genommen wird der erste Pfad, der ausfuehrbar ist`() {
        val ordner = tempDir()
        val gibtEsNicht = File(ordner, "fehlt").absolutePath
        val nichtAusfuehrbar = File(ordner, "stumpf").apply { writeText(""); setExecutable(false) }
        val echt = File(ordner, "dalvikvm64").apply { writeText(""); setExecutable(true) }

        val runner = DalvikRunner(
            cacheDir = tempDir(),
            kandidaten = listOf(gibtEsNicht, nichtAusfuehrbar.absolutePath, echt.absolutePath),
        )

        assertEquals(echt.absolutePath, runner.gefundeneLaufzeit())
    }

    @Test
    fun `ohne ausfuehrbare Laufzeit gibt es keine`() {
        val runner = DalvikRunner(
            cacheDir = tempDir(),
            kandidaten = listOf("/gibt/es/nicht", "/auch/nicht"),
        )

        assertNull(runner.gefundeneLaufzeit())
    }

    // ---- Was gemeldet wird -------------------------------------------------------------

    /**
     * **Die Meldung muss jeden geprüften Pfad nennen und sagen, was dort steht.**
     *
     * Das ist der Unterschied zwischen „es geht nicht" und einer Auskunft, aus der sich der
     * nächste Schritt ableiten lässt: Fehlt die Datei, oder ist sie da und darf nicht
     * ausgeführt werden? Das eine heißt „an der falschen Stelle gesucht", das andere „vom
     * System verboten" — und beides braucht eine andere Antwort.
     */
    @Test
    fun `die Fehlermeldung nennt jeden Pfad mit seinem Befund`() {
        val ordner = tempDir()
        val stumpf = File(ordner, "stumpf").apply { writeText(""); setExecutable(false) }

        val runner = DalvikRunner(
            cacheDir = tempDir(),
            kandidaten = listOf("/gibt/es/nicht", stumpf.absolutePath),
        )

        val ergebnis = runner.run(
            dexJar = File(ordner, "kotlinc.dex.jar").apply { writeText("") },
            mainClass = "egal",
            args = emptyList(),
            workingDir = ordner,
            timeoutMillis = 1000,
        )

        assertEquals(-1, ergebnis.exitCode)
        assertContains(ergebnis.stderr, "/gibt/es/nicht")
        assertContains(ergebnis.stderr, "gibt es nicht")
        assertContains(ergebnis.stderr, stumpf.absolutePath)
        assertContains(ergebnis.stderr, "nicht ausführbar")
    }

    @Test
    fun `der Befund zaehlt jeden Kandidaten auf, auch wenn einer gefunden wurde`() {
        val ordner = tempDir()
        val echt = File(ordner, "dalvikvm64").apply { writeText(""); setExecutable(true) }

        val befund = DalvikRunner(
            cacheDir = tempDir(),
            kandidaten = listOf("/gibt/es/nicht", echt.absolutePath),
        ).befund()

        assertEquals(2, befund.lines().size, befund)
        assertContains(befund, "vorhanden")
    }

    // ---- Die Befehlszeile --------------------------------------------------------------

    @Test
    fun `die Laufzeit bekommt Klassenpfad, Hauptklasse und Argumente in dieser Reihenfolge`() {
        val ordner = tempDir()
        val echt = File(ordner, "dalvikvm64").apply { writeText(""); setExecutable(true) }
        val dex = File(ordner, "kotlinc.dex.jar").apply { writeText("") }
        val mitschrift = Mitschrift()

        DalvikRunner(
            cacheDir = File(ordner, "cache"),
            runner = mitschrift,
            kandidaten = listOf(echt.absolutePath),
            heapMegabyte = 512,
        ).run(dex, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", listOf("-no-jdk"), ordner, 1000)

        assertEquals(
            listOf(
                echt.absolutePath,
                "-Xmx512m",
                "-cp", dex.absolutePath,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-no-jdk",
            ),
            mitschrift.befehl,
        )
        // Ohne ein beschreibbares ANDROID_DATA fällt die Laufzeit auf einen Pfad zurück, in
        // den eine App nicht schreiben darf — und scheitert mit einer ganz anderen Meldung.
        assertNotNull(mitschrift.umgebung["ANDROID_DATA"])
        assertTrue(File(mitschrift.umgebung["ANDROID_DATA"]!!).isDirectory)
        assertTrue(File(mitschrift.umgebung["TMPDIR"]!!).isDirectory)
    }

    @Test
    fun `ein fehlendes Werkzeugarchiv wird als solches gemeldet`() {
        val ordner = tempDir()
        val echt = File(ordner, "dalvikvm64").apply { writeText(""); setExecutable(true) }

        val ergebnis = DalvikRunner(
            cacheDir = tempDir(),
            kandidaten = listOf(echt.absolutePath),
        ).run(File(ordner, "fehlt.dex.jar"), "egal", emptyList(), ordner, 1000)

        assertEquals(-1, ergebnis.exitCode)
        assertContains(ergebnis.stderr, "fehlt.dex.jar")
    }
}
