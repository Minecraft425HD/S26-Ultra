package de.neon.platform

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Der Absturzbericht ist die letzte Verteidigungslinie: Er muss gerade dann funktionieren,
 * wenn sonst nichts mehr geht. Deshalb wird er ebenso geprüft wie alles andere.
 */
class CrashReporterTest {

    private lateinit var directory: File
    private lateinit var reporter: CrashReporter
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @BeforeTest
    fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        directory = File.createTempFile("neon-crash", "").apply {
            delete()
            mkdirs()
        }
        reporter = CrashReporter(directory)
    }

    @AfterTest
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        directory.deleteRecursively()
    }

    @Test
    fun `ohne Absturz gibt es nichts zu berichten`() {
        assertNull(reporter.lastCrash())
    }

    @Test
    fun `haelt einen abgefangenen Startfehler fest`() {
        reporter.recordHandled(
            "NeonContainer",
            IllegalStateException("Datenbank ließ sich nicht öffnen"),
            "0.1.0",
        )

        val report = assertNotNull(reporter.lastCrash())
        assertTrue(report.contains("NeonContainer"))
        assertTrue(report.contains("Datenbank ließ sich nicht öffnen"))
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("0.1.0"), "die Version muss im Bericht stehen")
    }

    @Test
    fun `faengt eine unbehandelte Ausnahme ab und reicht sie weiter`() {
        var delegated = false
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> delegated = true }

        reporter.install("0.1.0")
        val handler = assertNotNull(Thread.getDefaultUncaughtExceptionHandler())
        handler.uncaughtException(Thread.currentThread(), RuntimeException("kaputt"))

        val report = assertNotNull(reporter.lastCrash())
        assertTrue(report.contains("kaputt"))
        // Der vorherige Handler muss weiterlaufen, sonst beendet Android die App nicht
        // regulär, sondern sie bleibt hängen.
        assertTrue(delegated, "der vorherige Handler wurde nicht aufgerufen")
    }

    @Test
    fun `enthaelt die Aufrufliste und nicht nur die Meldung`() {
        reporter.recordHandled("Test", IllegalArgumentException("x"), "0.1.0")
        val report = assertNotNull(reporter.lastCrash())
        assertTrue(report.contains("CrashReporterTest"), "Aufrufliste fehlt:\n$report")
    }

    @Test
    fun `laesst sich verwerfen`() {
        reporter.recordHandled("Test", RuntimeException("x"), "0.1.0")
        reporter.clear()
        assertNull(reporter.lastCrash())
    }

    @Test
    fun `ein zweiter Absturz ersetzt den ersten`() {
        reporter.recordHandled("Erst", RuntimeException("alt"), "0.1.0")
        reporter.recordHandled("Dann", RuntimeException("neu"), "0.1.0")

        val report = assertNotNull(reporter.lastCrash())
        assertTrue(report.contains("neu"))
        assertTrue(!report.contains("alt"))
    }

    @Test
    fun `ein fehlendes Verzeichnis stoppt nichts`() {
        val nested = CrashReporter(File(directory, "tief/verschachtelt"))
        nested.recordHandled("Test", RuntimeException("x"), "0.1.0")
        assertNotNull(nested.lastCrash())
    }
}
