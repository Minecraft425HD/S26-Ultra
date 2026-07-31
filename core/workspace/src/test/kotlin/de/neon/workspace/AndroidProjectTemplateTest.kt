package de.neon.workspace

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Die Vorlage muss beim ersten Versuch bauen.
 *
 * **Warum es sie gibt.** Ein Android-Projekt hat vier Dateien, die exakt stimmen müssen, bevor
 * überhaupt etwas übersetzt wird. Ein 4-B-Modell schreibt davon drei richtig und eine falsch,
 * und der Fehler zeigt sich als aapt2-Meldung. Bei 15 Token je Sekunde kostet jeder Anlauf
 * Minuten — die Vorlage nimmt das ab.
 *
 * Geprüft wird deshalb genau das, was aapt2 und der Installer verlangen und was man leicht
 * vergisst.
 */
class AndroidProjectTemplateTest {

    private fun frisch(): Workspace = Workspace(
        File.createTempFile("neon-tpl", "").apply { delete(); mkdirs(); deleteOnExit() }
    )

    private val vorgabe = AndroidProjectTemplate.Vorgabe("de.neon.testapp", "Testapp")

    @Test
    fun `die Vorlage legt Manifest, Ressourcen und Activity an`() {
        val ws = frisch()

        val angelegt = AndroidProjectTemplate.anlegen(ws, vorgabe)

        assertEquals(
            listOf(
                "AndroidManifest.xml",
                "res/values/strings.xml",
                "src/de/neon/testapp/MainActivity.kt",
            ),
            angelegt,
        )
    }

    @Test
    fun `die Start-Activity ist exported`() {
        val ws = frisch()
        AndroidProjectTemplate.anlegen(ws, vorgabe)

        // Seit Android 12 Pflicht. Ohne das weist der Installer die App ab, mit einer
        // Meldung über einen fehlenden Wert, die nicht verrät, welcher gemeint ist.
        val manifest = ws.lies("AndroidManifest.xml")!!
        assertTrue(manifest.contains("android:exported=\"true\""), manifest)
        assertTrue(manifest.contains("android.intent.category.LAUNCHER"), manifest)
        assertTrue(manifest.contains("package=\"de.neon.testapp\""), manifest)
    }

    @Test
    fun `die Activity liegt im Verzeichnis ihres Pakets`() {
        val ws = frisch()
        AndroidProjectTemplate.anlegen(ws, AndroidProjectTemplate.Vorgabe("com.beispiel.tief.app", "X"))

        // Der Kotlin-Compiler verlangt das nicht, aapt2 und der Klassenlader schon: Der
        // Klassenname im Manifest muss zum Paket passen, sonst startet die App nicht.
        assertTrue(ws.lies("src/com/beispiel/tief/app/MainActivity.kt") != null)
    }

    @Test
    fun `die Activity benutzt nur Plattform und Standardbibliothek`() {
        val ws = frisch()
        AndroidProjectTemplate.anlegen(ws, vorgabe)
        val quelle = ws.lies("src/de/neon/testapp/MainActivity.kt")!!

        // Jede weitere Bibliothek wäre ein Download beim Bauen — auf einem Telefon ohne
        // Gradle gibt es den nicht. Geprüft wird an den Importen: nur android.* ist erlaubt.
        val importe = quelle.lines().filter { it.startsWith("import ") }
        assertTrue(importe.isNotEmpty())
        importe.forEach { zeile ->
            assertTrue(
                zeile.startsWith("import android.") || zeile.startsWith("import kotlin."),
                "unerlaubte Abhängigkeit: $zeile",
            )
        }
        assertTrue(!quelle.contains("androidx"), "androidx wäre nicht übersetzbar")
        assertTrue(!quelle.contains("Compose"), "Compose bräuchte ein Compiler-Plugin")
    }

    @Test
    fun `ein Ampersand im Namen zerlegt die Ressourcen nicht`() {
        val ws = frisch()
        AndroidProjectTemplate.anlegen(
            ws,
            AndroidProjectTemplate.Vorgabe("de.neon.app", "Tee & Kekse"),
        )

        // In XML sind fünf Zeichen besonders. Ein einzelnes & im App-Namen lässt aapt2 mit
        // einem Parserfehler abbrechen — an einer Datei, die niemand geschrieben hat.
        val strings = ws.lies("res/values/strings.xml")!!
        assertTrue(strings.contains("Tee &amp; Kekse"), strings)
        assertTrue(!strings.contains("Tee & Kekse"), strings)
    }

    @Test
    fun `ein Anfuehrungszeichen im Namen zerlegt den Quelltext nicht`() {
        val ws = frisch()
        AndroidProjectTemplate.anlegen(
            ws,
            AndroidProjectTemplate.Vorgabe("de.neon.app", "Der \"Zähler\""),
        )

        val quelle = ws.lies("src/de/neon/app/MainActivity.kt")!!
        assertTrue(quelle.contains("""Der \"Zähler\""""), quelle)
    }

    @Test
    fun `ein Dollarzeichen im Namen wird nicht als Vorlage gelesen`() {
        val ws = frisch()
        AndroidProjectTemplate.anlegen(
            ws,
            AndroidProjectTemplate.Vorgabe("de.neon.app", "100\$ App"),
        )

        // Kotlin liest $ in einer Zeichenkette als Vorlage. Ohne Maskierung sucht der
        // Compiler eine Variable namens `App` und bricht ab.
        val quelle = ws.lies("src/de/neon/app/MainActivity.kt")!!
        assertTrue(quelle.contains("100\\\$ App"), quelle)
    }

    @Test
    fun `ein ungueltiger Paketname wird sofort abgelehnt`() {
        // Ein ungültiger Name scheitert sonst erst beim Installieren — nach dem ganzen
        // Bauvorgang, also nach Minuten.
        listOf("keinpunkt", "Gross.Buchstaben", "1zahl.vorn", "de..leer", "de.neon-strich").forEach {
            assertFailsWith<IllegalArgumentException>("„$it\" wurde durchgelassen") {
                AndroidProjectTemplate.Vorgabe(it, "X")
            }
        }
    }

    @Test
    fun `gueltige Paketnamen gehen durch`() {
        listOf("de.neon", "de.neon.meine_app", "com.beispiel.a.b.c9").forEach {
            AndroidProjectTemplate.Vorgabe(it, "X")
        }
    }
}
