package de.neon.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Das Eingabefeld rutschte doppelt so hoch wie die Tastatur.
 *
 * **Die zwei Ursachen.** Sie addierten sich, und jede allein wäre schon falsch gewesen:
 *
 *  1. `enableEdgeToEdge()` fehlte. Ohne den Aufruf steht `decorFitsSystemWindows` auf `true`,
 *     und dann verkleinert Android das Fenster **selbst** um die Tastaturhöhe.
 *     `Modifier.imePadding()` legte dieselbe Höhe ein zweites Mal obendrauf.
 *  2. `.imePadding().navigationBarsPadding()` an derselben Spalte. Der Tastaturbereich reicht
 *     bis zum unteren Bildschirmrand und schließt die Navigationsleiste ein; deren Höhe wurde
 *     also noch ein drittes Mal aufgeschlagen.
 *
 * **Warum dieser Test nachsieht statt auszuprobieren.** Dieselbe Bauart wie
 * `PortableRegexTest`: Was auf einem Telefon mit sichtbarer Tastatur passiert, lässt sich
 * unter Robolectric nicht messen — es gibt dort keine Tastatur und keinen Fensterverwalter,
 * der etwas verkleinert. Ein Test, der es trotzdem behauptet, wäre grün und wertlos. Also
 * wird die Regel geprüft, die zur Verdopplung führt.
 */
class WindowInsetsTest {

    private val quellen: List<File> =
        File(wurzel(), "app/src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun `keine Oberflaeche addiert Tastatur und Navigationsleiste`() {
        val schuldige = quellen.filter { datei ->
            val text = ohneKommentare(datei.readText())
            text.contains("imePadding()") && text.contains("navigationBarsPadding()")
        }

        assertTrue(
            schuldige.isEmpty(),
            "imePadding und navigationBarsPadding in derselben Datei — der Tastaturbereich " +
                "schließt die Navigationsleiste schon ein, ihre Höhe kommt sonst zweimal: " +
                schuldige.map { it.name },
        )
    }

    /**
     * Der Chat benutzt den vereinigten Abstand.
     *
     * `safeDrawing` ist Systemleisten ∪ Aussparung ∪ Tastatur — **eine** Größe, die sich
     * nicht mit sich selbst addieren kann. Genau das ist der Grund, sie zu nehmen, statt
     * einzelne Abstände zu stapeln und auf ihre Disjunktheit zu hoffen.
     */
    @Test
    fun `der Chat nimmt den vereinigten Abstand`() {
        val chat = ohneKommentare(quellen.single { it.name == "ChatScreen.kt" }.readText())

        assertTrue(chat.contains("WindowInsets.safeDrawing"), "ChatScreen ohne safeDrawing")
        assertFalse(chat.contains("imePadding()"), "ChatScreen stapelt wieder Einzelabstände")
    }

    /**
     * Der Quelltext ohne seine Kommentare.
     *
     * **Warum es das braucht.** Der erste Anlauf dieses Wächters schlug an — auf meinen
     * eigenen Kommentar, in dem erklärt steht, welche Kombination falsch war. Wer einen
     * Fehler dokumentiert, schreibt ihn nun einmal hin; ein Wächter, der Prosa liest, verbietet
     * damit die Erklärung. Genau dieselbe Stolperstelle gab es schon bei `(?U)` in einem
     * Doc-Kommentar.
     *
     * Bewusst grob: Ein `//` in einer Zeichenkette würde zu viel entfernen. Das kann hier nur
     * dazu führen, dass ein Fund entgeht, der in einer Zeichenkette steht — und dort ist er
     * ohnehin keiner.
     */
    private fun ohneKommentare(quelle: String): String = quelle
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lineSequence()
        .map { it.substringBefore("//") }
        .joinToString("\n")

    /**
     * Randlos zeichnen wird ausgesprochen, nicht geerbt.
     *
     * Ab Android 15 ist es vorgeschrieben, davor nicht — und genau diese Verschiebung war die
     * erste Ursache. Eine Vorgabe, die sich zwischen Fassungen ändert, darf über das Aussehen
     * nicht entscheiden.
     */
    @Test
    fun `die Hauptansicht schaltet randloses Zeichnen ein`() {
        val activity = quellen.single { it.name == "MainActivity.kt" }.readText()

        assertTrue(activity.contains("enableEdgeToEdge()"), "enableEdgeToEdge fehlt")
    }

    /**
     * Und das Fenster sagt, was es mit der Tastatur will.
     *
     * `adjustResize` ist die dokumentierte Ergänzung zum randlosen Zeichnen: Android meldet
     * den Tastaturbereich, verkleinert das Fenster aber nicht mehr selbst. Ohne die Angabe
     * hängt das Verhalten an einer Vorgabe — und Vorgaben waren hier schon zweimal das
     * Problem.
     */
    @Test
    fun `das Manifest nennt adjustResize`() {
        val manifest = File(wurzel(), "app/src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android:windowSoftInputMode=\"adjustResize\""), manifest)
        // Nur an der Hauptansicht, und nur einmal — zwei Angaben widersprächen sich.
        assertEquals(1, Regex("windowSoftInputMode").findAll(manifest).count())
    }

    private fun wurzel(): File {
        var verzeichnis = File("").absoluteFile
        while (!File(verzeichnis, "settings.gradle.kts").exists()) {
            verzeichnis = verzeichnis.parentFile ?: error("Projektwurzel nicht gefunden")
        }
        return verzeichnis
    }
}
