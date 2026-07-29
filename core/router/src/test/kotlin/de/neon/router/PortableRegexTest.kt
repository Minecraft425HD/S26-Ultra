package de.neon.router

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Der Test, den es beim ersten Startabsturz nicht gab.
 *
 * Neon starb auf dem Telefon in einem Klasseninitialisierer, weil die Muster der
 * Regelstufe mit `(?U)` gebaut wurden — einem Flag, das nur das OpenJDK kennt. Hier lief
 * dasselbe grün durch, auch unter Robolectric: Das läuft auf der JVM und benutzt deren
 * Regex-Maschine, nicht die von Android.
 *
 * Daraus folgt die Bauart dieses Tests. Ob ICU ein Muster annimmt, lässt sich auf der JVM
 * nicht ausprobieren; also wird nicht ausprobiert, sondern **nachgesehen**: Kein Quelltext
 * darf ein Konstrukt enthalten, das nur die eine Maschine kennt.
 */
class PortableRegexTest {

    @Test
    fun `die Wortgrenze greift vor einem Umlaut`() {
        // Der ursprüngliche Grund für das Flag. Ohne Unicode-fähige Wortgrenzen findet
        // "\böffne" nichts, und die halbe deutsche Regelstufe fällt still aus.
        val regex = PortableRegex.compile("\\b(öffne|über|ändere)\\b")

        assertTrue(regex.containsMatchIn("öffne die kamera"))
        assertTrue(regex.containsMatchIn("sag mir was über den dom"))
        assertTrue(regex.containsMatchIn("ändere das"))
    }

    @Test
    fun `die Wortgrenze greift nicht mitten im Wort`() {
        val regex = PortableRegex.compile("\\b(licht)\\b")

        assertTrue(regex.containsMatchIn("mach das licht an"))
        assertTrue(!regex.containsMatchIn("die lichtung"), "Wortmitte darf nicht treffen")
        assertTrue(!regex.containsMatchIn("das gaslicht"), "Wortende darf nicht treffen")
    }

    @Test
    fun `Umlaute zaehlen als Wortzeichen, nicht als Grenze`() {
        // Die eigentliche Falle: Zählt „ä" nicht als Wortzeichen, liegt mitten in
        // „lautstärke" eine Wortgrenze — und „\bstärke\b" träfe fälschlich.
        val regex = PortableRegex.compile("\\b(stärke)\\b")

        assertTrue(regex.containsMatchIn("stärke das signal"))
        assertTrue(
            !regex.containsMatchIn("stell die lautstärke ein"),
            "hinter „laut\" darf keine Wortgrenze liegen",
        )
    }

    @Test
    fun `Ziffern gelten als Wortzeichen`() {
        val regex = PortableRegex.compile("\\b(\\d{1,2})[:. ](\\d{2})\\b")

        assertEquals(listOf("7", "30"), regex.find("wecker um 7:30")?.groupValues?.drop(1))
        assertTrue(!regex.containsMatchIn("12:345"), "die längere Zahl darf nicht treffen")
    }

    @Test
    fun `ein Muster mit JVM-eigenem Flag wird abgewiesen`() {
        val fehler = assertFailsWith<IllegalArgumentException> {
            PortableRegex.compile("(?U)\\b(test)\\b")
        }
        assertTrue(fehler.message!!.contains("Android"))
    }

    @Test
    fun `harmlose Muster gelten als vertraeglich`() {
        assertNull(PortableRegex.incompatibility("\\b(licht|lampe)\\b"))
        assertNull(PortableRegex.incompatibility("[\\p{L}\\d ]*"))
        assertNotNull(PortableRegex.incompatibility("(?U)x"))
        assertNotNull(PortableRegex.incompatibility("\\p{IsAlphabetic}"))
    }

    @Test
    fun `die ausgeschriebene Wortgrenze verlaesst sich auf kein Flag`() {
        assertNull(PortableRegex.incompatibility(PortableRegex.WORD_BOUNDARY))
        assertNull(PortableRegex.incompatibility(PortableRegex.WORD_CHARACTER))
    }

    /**
     * Die eigentliche Absicherung.
     *
     * Sie prüft nicht Verhalten, sondern Quelltext — weil sich das fragliche Verhalten auf
     * der JVM grundsätzlich nicht herstellen lässt. Jede Datei des Projekts wird
     * durchgesehen; findet sich ein Konstrukt, das nur das OpenJDK kennt, scheitert der
     * Lauf hier statt später auf dem Gerät.
     */
    @Test
    fun `kein Quelltext benutzt ein Konstrukt, das nur die JVM kennt`() {
        val wurzel = projektWurzel()

        val treffer = wurzel.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.path.contains("/build/") }
            // Diese Datei benennt die Konstrukte, um vor ihnen zu warnen, und die
            // Definition selbst muss irgendwo stehen dürfen.
            .filterNot { it.name == "PortableRegex.kt" || it.name == "PortableRegexTest.kt" }
            .flatMap { datei ->
                datei.readLines().mapIndexedNotNull { index, zeile ->
                    PortableRegex.incompatibility(zeile)
                        ?.let { "${datei.relativeTo(wurzel)}:${index + 1}: $it" }
                }
            }
            .toList()

        assertTrue(
            treffer.isEmpty(),
            "Konstrukte, an denen Android beim Start scheitert:\n" + treffer.joinToString("\n"),
        )
    }

    @Test
    fun `der Quelltext-Test findet die Wurzel und sieht wirklich Dateien an`() {
        // Ohne diese Prüfung könnte der Test oben grün sein, weil er nichts gelesen hat.
        val wurzel = projektWurzel()
        val anzahl = wurzel.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.contains("/build/") }
            .count()

        assertTrue(anzahl > 50, "nur $anzahl Kotlin-Dateien gefunden — die Wurzel stimmt nicht")
    }

    private fun projektWurzel(): File {
        var verzeichnis: File? = File(System.getProperty("user.dir")).absoluteFile
        while (verzeichnis != null) {
            if (File(verzeichnis, "settings.gradle.kts").isFile) return verzeichnis
            verzeichnis = verzeichnis.parentFile
        }
        error("settings.gradle.kts nicht gefunden, ausgehend von ${System.getProperty("user.dir")}")
    }
}
