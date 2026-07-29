package de.neon.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Die Merkmalszeile eines arm64-Telefons richtig lesen.
 *
 * **Wozu das gebraucht wird.** llama-server wird für einen festen Befehlssatz übersetzt.
 * Zu niedrig gewählt kostet es Faktor drei bis acht an Geschwindigkeit; zu hoch gewählt
 * beendet der Kern das Programm beim ersten unbekannten Befehl. Zwischen diesen beiden
 * Fehlern entscheidet allein, was in `/proc/cpuinfo` steht — und das lässt sich nur lesen,
 * nicht raten.
 *
 * Die Vorlagen unten sind echte Ausgaben, keine erfundenen.
 */
class CpuFeaturesTest {

    /** Ein arm64-Telefon neuerer Bauart: Skalarprodukt und Matrixbefehle vorhanden. */
    private val modernesTelefon = """
        processor	: 0
        BogoMIPS	: 38.40
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg dcpodp flagm2 frint i8mm bf16 bti ecv afp
        CPU implementer	: 0x51
        CPU architecture: 8
    """.trimIndent()

    /** Ein älteres Gerät: Skalarprodukt ja, Matrixbefehle nein. */
    private val aelteresTelefon = """
        processor	: 0
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
        CPU implementer	: 0x41
    """.trimIndent()

    @Test
    fun `erkennt die Merkmale eines neueren Geraets`() {
        val merkmale = CpuFeatures.parse(modernesTelefon)

        assertTrue("asimddp" in merkmale, "Skalarprodukt nicht erkannt")
        assertTrue("i8mm" in merkmale, "Matrixbefehle nicht erkannt")
        assertTrue("bf16" in merkmale)
        assertTrue("asimd" in merkmale)
    }

    @Test
    fun `unterscheidet ein Geraet ohne Matrixbefehle`() {
        val merkmale = CpuFeatures.parse(aelteresTelefon)

        // Genau diese Unterscheidung trägt die Bauentscheidung: dotprod ja, i8mm nein.
        assertTrue("asimddp" in merkmale)
        assertFalse("i8mm" in merkmale, "i8mm gefunden, obwohl es nicht in der Zeile steht")
    }

    @Test
    fun `nimmt nur die Merkmalszeile, nicht die uebrigen Felder`() {
        val merkmale = CpuFeatures.parse(modernesTelefon)

        // "CPU implementer" und "processor" dürfen nicht als Merkmale durchgehen —
        // sonst stünde im Protokoll Unsinn, und Unsinn im Protokoll führt in die Irre.
        assertFalse("0x51" in merkmale)
        assertFalse("0" in merkmale)
        assertFalse("38.40" in merkmale)
    }

    @Test
    fun `ein unlesbarer Prozessor ergibt keine Merkmale`() {
        assertEquals(emptySet(), CpuFeatures.parse(""))
        assertEquals(emptySet(), CpuFeatures.parse("processor\t: 0\nBogoMIPS\t: 38.40"))
    }

    @Test
    fun `die Beschreibung nennt die drei Merkmale, auf die es ankommt`() {
        val text = CpuFeatures.describe(CpuFeatures.parse(modernesTelefon))

        assertTrue(text.contains("asimddp ja"), text)
        assertTrue(text.contains("i8mm ja"), text)
        assertTrue(text.contains("bf16 ja"), text)
    }

    @Test
    fun `die Beschreibung sagt es, wenn nichts zu lesen war`() {
        val text = CpuFeatures.describe(emptySet())

        // Nicht schweigen. Eine fehlende Auskunft ist selbst eine Auskunft — das war die
        // Lehre aus der verschluckten Ausnahme in isHealthy().
        assertTrue(text.contains("nicht lesbar"), text)
    }

    @Test
    fun `auf dieser Maschine laesst sich etwas lesen oder es wird gesagt`() {
        // Kein Anspruch an den Inhalt: Der Testlauf kann auf x86 stattfinden, wo `flags`
        // statt `Features` steht, oder in einem Container ohne Zugriff. Geprüft wird nur,
        // dass beide Fälle eine brauchbare Zeile ergeben und nichts wirft.
        val text = CpuFeatures.describe()
        assertTrue(text.isNotBlank())
        println(text)
    }
}
