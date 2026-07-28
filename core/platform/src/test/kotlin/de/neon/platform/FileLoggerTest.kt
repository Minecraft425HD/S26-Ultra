package de.neon.platform

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileLoggerTest {

    private lateinit var directory: File

    @BeforeTest
    fun setUp() {
        directory = File.createTempFile("neon-log", "").apply {
            delete()
            mkdirs()
        }
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun logger(maxBytes: Long = FileLogger.DEFAULT_MAX_BYTES) =
        FileLogger(directory, maxBytes, clock = { 0L })

    @Test
    fun `schreibt eine Zeile mit Zeitstempel Stufe und Kennung`() {
        val logger = logger()
        logger.log(FileLogger.Level.ERROR, "NeonLlamaServer", "Modelldatei fehlt")

        val line = logger.recent().single()
        assertTrue(line.contains("NeonLlamaServer"))
        assertTrue(line.contains("Modelldatei fehlt"))
        assertTrue(line.contains(" E "), "Stufe fehlt: $line")
    }

    @Test
    fun `haengt die Ausnahme an`() {
        val logger = logger()
        logger.log(
            FileLogger.Level.ERROR,
            "Test",
            "Start gescheitert",
            IllegalStateException("Port belegt"),
        )

        val line = logger.recent().single()
        assertTrue(line.contains("IllegalStateException"))
        assertTrue(line.contains("Port belegt"))
    }

    @Test
    fun `behaelt die Reihenfolge`() {
        val logger = logger()
        repeat(5) { logger.log(FileLogger.Level.INFO, "T", "Zeile $it") }

        val lines = logger.recent()
        assertEquals(5, lines.size)
        assertTrue(lines.first().contains("Zeile 0"))
        assertTrue(lines.last().contains("Zeile 4"))
    }

    @Test
    fun `bricht um statt zu leeren`() {
        // Ein Fehler passiert oft kurz vor dem Umbruch. Würde einfach geleert, wäre genau
        // die interessante Stelle verschwunden.
        val logger = logger(maxBytes = 200)
        repeat(40) { logger.log(FileLogger.Level.INFO, "T", "Zeile $it") }

        val lines = logger.recent(1_000)
        assertTrue(lines.size > 5, "nach dem Umbruch blieb zu wenig übrig: ${lines.size}")
        assertTrue(lines.last().contains("Zeile 39"))
    }

    @Test
    fun `waechst nicht unbegrenzt`() {
        val logger = logger(maxBytes = 500)
        repeat(500) { logger.log(FileLogger.Level.INFO, "T", "eine ziemlich lange Zeile $it") }

        // Höchstens zwei Dateien à maxBytes, plus die Zeile, die den Umbruch auslöst.
        assertTrue(
            logger.sizeBytes() < 500 * 3,
            "Protokoll ist zu groß geworden: ${logger.sizeBytes()} B",
        )
    }

    @Test
    fun `gibt die letzten Zeilen begrenzt zurueck`() {
        val logger = logger()
        repeat(50) { logger.log(FileLogger.Level.INFO, "T", "Zeile $it") }

        val lines = logger.recent(10)
        assertEquals(10, lines.size)
        assertTrue(lines.last().contains("Zeile 49"))
    }

    @Test
    fun `laesst sich leeren`() {
        val logger = logger()
        logger.log(FileLogger.Level.INFO, "T", "etwas")
        logger.clear()

        assertTrue(logger.recent().isEmpty())
        assertEquals(0L, logger.sizeBytes())
    }

    @Test
    fun `kommt mit einem fehlenden Verzeichnis zurecht`() {
        val missing = File(directory, "tiefer/verschachtelt")
        val logger = FileLogger(missing, clock = { 0L })
        logger.log(FileLogger.Level.INFO, "T", "trotzdem")

        assertEquals(1, logger.recent().size)
    }

    @Test
    fun `liefert leere Liste statt zu werfen wenn nichts geschrieben wurde`() {
        assertTrue(logger().recent().isEmpty())
        assertEquals("", logger().fullText())
    }
}
