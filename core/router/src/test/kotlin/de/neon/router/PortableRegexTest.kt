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
                codezeilen(datei.readText()).mapIndexedNotNull { index, zeile ->
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

    /**
     * Das Abschneiden der Kommentare macht den Wächter nicht blind.
     *
     * Der wichtigste Test dieser Datei, seit der Wächter Kommentare überspringt: Eine
     * Abschwächung, die niemand nachprüft, ist eine Abschaltung mit Umweg. Geprüft wird
     * beides — dass Kommentare durchgehen **und** dass Code weiterhin auffällt.
     */
    @Test
    fun `Kommentare gehen durch, Code nicht`() {
        val quelle = """
            |/**
            | * Erklaert, warum (?U) hier verboten ist.
            | */
            |val harmlos = "abc"
            |val schuldig = Regex("(?U)\\blicht\\b")
            |val auchHarmlos = 1 // (?U) im Zeilenkommentar
        """.trimMargin()

        val zeilen = codezeilen(quelle)

        // Die Zeilennummern müssen erhalten bleiben, sonst zeigt die Meldung ins Leere.
        assertEquals(6, zeilen.size, zeilen.toString())

        val gemeldet = zeilen.mapIndexedNotNull { index, zeile ->
            PortableRegex.incompatibility(zeile)?.let { index + 1 }
        }

        // Nur Zeile 5 — der echte Fund. Nicht Zeile 2 (Blockkommentar), nicht Zeile 6
        // (Zeilenkommentar).
        assertEquals(listOf(5), gemeldet, "gemeldet: $gemeldet in $zeilen")
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

    /**
     * Die Zeilen einer Datei ohne ihre Kommentare, bei gleicher Zeilennummerierung.
     *
     * **Warum das nachgetragen wurde.** Dieser Wächter hat zweimal auf einen Doc-Kommentar
     * angeschlagen, der das verbotene Konstrukt **benannte**, um vor ihm zu warnen. Beim
     * ersten Mal wurde der Kommentar umformuliert — die bequeme Antwort, aber die falsche:
     * Ein Wächter, der Prosa liest, verbietet damit die Erklärung des Fehlers, den er
     * verhindern soll. Beim zweiten Mal war es der Kommentar eines anderen Wächters, der
     * genau dasselbe erklärte.
     *
     * Ein Konstrukt in einem Kommentar bringt Android nicht zum Absturz. Es zu melden ist ein
     * Fehlalarm, und Fehlalarme kosten am Ende die Glaubwürdigkeit des Alarms.
     *
     * Blockkommentare werden **leer geschrieben statt entfernt**: Ihre Zeilenumbrüche bleiben
     * stehen, sonst verschiebt sich jede Zeilennummer danach — und eine Meldung, die auf die
     * falsche Zeile zeigt, ist fast so schlecht wie keine.
     *
     * Bewusst grob: Eine Kommentareröffnung, die in einer Zeichenkette steht, schneidet zu
     * viel weg. Das kann nur dazu führen, dass ein Fund entgeht, der in einer Zeichenkette
     * **dahinter** steht — ein seltener Fall, und der Preis dafür, dass sich Fehler erklären
     * lassen.
     *
     * Und noch eine Fußangel, die beim Schreiben dieses Kommentars zugeschnappt ist: Kotlin
     * erlaubt **verschachtelte** Blockkommentare. Eine Eröffnungsfolge hier hineinzuschreiben
     * öffnet eine zweite Ebene, die das abschließende Zeichenpaar dann nur wieder zumacht —
     * der Rest der Datei verschwindet im Kommentar. Deshalb steht sie hier in Worten.
     */
    private fun codezeilen(inhalt: String): List<String> {
        val code = StringBuilder(inhalt.length)
        var stelle = 0
        var imBlock = false

        while (stelle < inhalt.length) {
            val zeichen = inhalt[stelle]
            val folgt = inhalt.getOrNull(stelle + 1)

            when {
                imBlock && zeichen == '*' && folgt == '/' -> {
                    imBlock = false
                    stelle += 2
                }

                imBlock -> {
                    if (zeichen == '\n') code.append('\n')
                    stelle++
                }

                zeichen == '/' && folgt == '*' -> {
                    imBlock = true
                    stelle += 2
                }

                else -> {
                    code.append(zeichen)
                    stelle++
                }
            }
        }

        return code.toString().lines().map { it.substringBefore("//") }
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
