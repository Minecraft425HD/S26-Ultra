package de.neon.inference

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Die Spur, die ein Abschuss selbst nicht legen kann.
 *
 * **Der Anlass.** Auf dem Gerät starb der App-Prozess sechsmal hintereinander beim Laden
 * des Modells. Zwischen den Anläufen stand im Protokoll nichts — nur die nächste
 * Startmeldung. Ein SIGKILL kennt keinen Handler: kein Abwickeln, keine Zeile, nichts.
 *
 * Die einzige Möglichkeit, das später zu erkennen, ist, vorher aufzuschreiben, was gerade
 * versucht wird, und die Notiz beim Gelingen wieder wegzunehmen. Bleibt sie liegen, ist
 * jemand nicht zurückgekommen.
 */
class LoadAttemptLogTest {

    private val ordner: File = Files.createTempDirectory("ladeversuch").toFile()
    private val datei = File(ordner, "unter/ladeversuch.txt")
    private val log = LoadAttemptLog(datei)

    private val versuch = LoadAttempt(
        startedAtMillis = 1_769_000_000_000,
        modelName = "qwen3-4b-instruct.gguf",
        modelBytes = 2_496_000_000,
        contextSize = 16_384,
        kvBytes = 16_384L * ProcessServerSupervisor.KV_BYTES_PER_TOKEN,
        memory = MemoryReading(totalBytes = 12_209_618_944, availableBytes = 4_082_810_880),
    )

    @AfterTest
    fun aufraeumen() {
        ordner.deleteRecursively()
    }

    @Test
    fun `ohne vorherigen Versuch ist nichts zu berichten`() {
        assertNull(log.verlorenerVersuch())
    }

    @Test
    fun `ein gelungener Versuch hinterlaesst nichts`() {
        log.beginnen(versuch)
        log.gelungen()

        assertNull(log.verlorenerVersuch(), "die Merkdatei blieb nach dem Gelingen liegen")
        assertFalse(datei.exists())
    }

    @Test
    fun `ein verlorener Versuch wird beim naechsten Start gemeldet`() {
        log.beginnen(versuch)
        // Kein `gelungen()` — das ist genau der Fall: Der Prozess kam nicht mehr dazu.

        val gefunden = assertNotNull(log.verlorenerVersuch())
        assertEquals(versuch, gefunden)
    }

    @Test
    fun `dieselbe Meldung kommt nicht zweimal`() {
        log.beginnen(versuch)

        assertNotNull(log.verlorenerVersuch())
        // Ein zweimal berichteter Fehler sieht aus wie zwei Fehler — und schickt die
        // Fehlersuche auf eine Spur, die es nicht gibt.
        assertNull(log.verlorenerVersuch(), "der verlorene Versuch wurde erneut gemeldet")
    }

    @Test
    fun `die Meldung nennt die Zahlen, auf die es ankommt`() {
        val text = versuch.describeAsLost()

        assertTrue(text.contains("vom System beendet"), text)
        assertTrue(text.contains("qwen3-4b-instruct.gguf"), text)
        assertTrue(text.contains("16384"), text)
        // Der freie Speicher ist die eigentliche Auskunft: Ob es Enge war, steht hier oder
        // nirgends.
        assertTrue(text.contains("3,8"), text)
        // Der Schlüssel-Wert-Speicher bei dieser Kontextgröße: 16384 * 73728 Byte.
        assertTrue(text.contains("1152"), text)
    }

    @Test
    fun `ein angelegter Ordner stoert nicht`() {
        // Beim ersten Start gibt es das Unterverzeichnis noch nicht.
        assertFalse(datei.parentFile.exists())
        log.beginnen(versuch)
        assertTrue(datei.isFile)
    }

    @Test
    fun `beschaedigte Notizen werden verworfen statt zu werfen`() {
        datei.parentFile.mkdirs()
        listOf("", "Unsinn", "1\t2", "keine\tzahlen\thier\tsind\tsieben\tfelder\tzwar") .forEach {
            datei.writeText(it)
            // Eine halb geschriebene Datei ist genau der Fall, den ein Abschuss mitten im
            // Schreiben hinterlässt. Daran darf der nächste Start nicht scheitern.
            assertNull(log.verlorenerVersuch(), "'$it' ergab einen Versuch")
        }
    }
}
