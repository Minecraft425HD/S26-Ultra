package de.neon.tools

import de.neon.workspace.Projektbereich
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Was das Modell zu sehen bekommt, wenn es mit Projekten hantiert.
 *
 * **Die Antwortsätze sind hier der Prüfgegenstand, nicht das Dateisystem.** Das steht in
 * `ProjektbereichTest`. Hier geht es darum, ob das **Modell** aus der Antwort ableiten kann,
 * was es als Nächstes tun soll — bei zwölf Token je Sekunde kostet jeder Fehlversuch eine
 * halbe Minute, und ein „ging nicht" schickt es in denselben Fehler zurück.
 */
class ProjektWerkzeugeTest {

    private fun bereich(): Projektbereich = Projektbereich(
        File.createTempFile("neon-pw", "").apply { delete(); mkdirs(); deleteOnExit() }
    )

    private fun werkzeug(bereich: Projektbereich, name: String): Tool =
        WorkspaceToolset.alle(bereich, bereich.aktiverArbeitsbereich())
            .first { it.spec.name == name }

    @Test
    fun `die Liste sagt, welches Projekt aktiv ist und was darin liegt`() = runTest {
        val b = bereich()
        val zaehler = b.anlegen("zaehler")!!
        b.arbeitsbereich(zaehler)
            .schreib("AndroidManifest.xml", """<manifest package="de.neon.zaehler" />""")
        b.anlegen("notizen")
        b.waehle("zaehler")

        val ergebnis = werkzeug(b, "projekte-auflisten").execute(emptyMap())

        assertTrue(ergebnis is ToolResult.Ok)
        val text = (ergebnis as ToolResult.Ok).spoken
        assertTrue("zaehler" in text && "notizen" in text, text)
        assertTrue("(aktiv)" in text, text)
        assertTrue("de.neon.zaehler" in text, "der Paketname sagt, was hier gebaut würde: $text")
    }

    /**
     * **Mit der Liste der vorhandenen Namen.**
     *
     * Ohne sie rät das Modell den nächsten Namen, und jeder Fehlversuch kostet auf diesem
     * Gerät eine halbe Minute. Der Fehlschlag muss also mehr enthalten als die Auskunft, dass
     * es ein Fehlschlag war.
     */
    @Test
    fun `ein unbekanntes Projekt wird mit den vorhandenen Namen beantwortet`() = runTest {
        val b = bereich()
        b.anlegen("zaehler")
        b.anlegen("notizen")

        val ergebnis = werkzeug(b, "projekt-wechseln").execute(mapOf("name" to "zähler"))

        assertTrue(ergebnis is ToolResult.Failed)
        val text = (ergebnis as ToolResult.Failed).spoken
        assertTrue("zaehler" in text && "notizen" in text, text)
    }

    /**
     * **Kein Erraten beim Löschen.**
     *
     * Bei einem Werkzeug, das Arbeit wegräumt, ist Nachsicht gegenüber Tippfehlern genau die
     * falsche Freundlichkeit — sie räumt dann das falsche weg.
     */
    @Test
    fun `geloescht wird nur, was woertlich stimmt`() = runTest {
        val b = bereich()
        b.anlegen("zaehler")
        b.anlegen("notizen")

        val daneben = werkzeug(b, "projekt-loeschen").execute(mapOf("name" to "Zaehler"))
        assertTrue(daneben is ToolResult.Failed)
        assertEquals(2, b.projekte().size, "nichts angefasst")

        val treffer = werkzeug(b, "projekt-loeschen").execute(mapOf("name" to "zaehler"))
        assertTrue(treffer is ToolResult.Ok)
        assertEquals(listOf("notizen"), b.projekte().map { it.name })
    }

    @Test
    fun `nach dem Loeschen sagt die Antwort, wo weitergearbeitet wird`() = runTest {
        val b = bereich()
        b.anlegen("notizen")
        b.anlegen("zaehler")
        b.waehle("zaehler")

        val ergebnis = werkzeug(b, "projekt-loeschen").execute(mapOf("name" to "zaehler"))

        // Ohne diesen Satz arbeitet das Modell danach in einem Projekt, von dem es nichts
        // weiß — und schreibt seine nächste Datei woandershin, als es glaubt.
        assertTrue("notizen" in (ergebnis as ToolResult.Ok).spoken, ergebnis.spoken)
    }

    @Test
    fun `app-anlegen legt einen eigenen Ordner an, benannt nach der App`() = runTest {
        val b = bereich()
        val werkzeug = AppAnlegenImProjekt(b)

        val ergebnis = werkzeug.execute(
            mapOf("paketname" to "de.neon.zaehler", "name" to "Zähler")
        )

        assertTrue(ergebnis is ToolResult.Ok, (ergebnis as? ToolResult.Failed)?.spoken.orEmpty())
        assertEquals(listOf("zaehler"), b.projekte().map { it.name })
        assertTrue(b.projekt("zaehler")!!.istAndroidProjekt)
    }

    /**
     * **Der Fehler, über den der Nutzer gestolpert ist.**
     *
     * Die Vorlage schrieb ihre Dateien direkt in den Projektbereich. Damit gab es genau eine
     * App: Ein zweiter Aufruf überschrieb das Manifest der ersten und ließ deren Quelltext
     * verwaist zurück.
     */
    @Test
    fun `eine zweite App ueberschreibt die erste nicht`() = runTest {
        val b = bereich()
        val werkzeug = AppAnlegenImProjekt(b)

        werkzeug.execute(mapOf("paketname" to "de.neon.zaehler", "name" to "Zähler"))
        werkzeug.execute(mapOf("paketname" to "de.neon.notiz", "name" to "Notizen"))

        assertEquals(listOf("notizen", "zaehler"), b.projekte().map { it.name }.sorted())
        assertEquals("de.neon.zaehler", b.projekt("zaehler")!!.paketname())
        assertEquals("de.neon.notiz", b.projekt("notizen")!!.paketname())
    }

    @Test
    fun `ein unbrauchbarer Paketname sagt, was erwartet wird`() = runTest {
        val b = bereich()

        val ergebnis = AppAnlegenImProjekt(b)
            .execute(mapOf("paketname" to "Zähler App", "name" to "Zähler"))

        assertTrue(ergebnis is ToolResult.Failed)
        assertTrue(b.projekte().isEmpty(), "bei ungültigem Paket entsteht kein leerer Ordner")
    }

    @Test
    fun `projekt-anlegen wechselt gleich hinein`() = runTest {
        val b = bereich()
        b.anlegen("zaehler")

        val ergebnis = ProjektAnlegen(b).execute(mapOf("name" to "Meine Auswertung"))

        assertTrue(ergebnis is ToolResult.Ok)
        assertEquals("meine-auswertung", b.aktiv()?.name)
        assertFalse(b.aktiv()!!.istAndroidProjekt, "kein halbes Android-Gerüst für ein Skript")
    }

    /**
     * **Kein Beispielwert im Prompt, den ein Modell abschreiben kann.**
     *
     * Der Fall auf dem Gerät: Der Nutzer bat um eine QR-App und bekam ein Projekt namens
     * `zaehler-app` mit dem Paket `de.neon.zaehler`. In der Parameterbeschreibung stand
     * „etwa de.neon.zaehler" und „etwa Zähler" — das 7-B-Modell hat den Beispielwert
     * übernommen, statt zuzuhören.
     *
     * Für ein kleines Modell ist ein ausgeschriebenes Beispiel keine Erläuterung, sondern
     * ein Vorschlag. Beschrieben gehört die Form und woher der Inhalt kommt.
     */
    @Test
    fun `die Beschreibung von app-anlegen nennt keinen fertigen Wert`() {
        val spec = AppAnlegenImProjekt(bereich()).spec
        val text = spec.description + " " + spec.parameters.joinToString(" ") { it.description }

        assertFalse("zaehler" in text.lowercase(), text)
        assertFalse("meineapp" in text.lowercase(), text)
        // Und der Paketname darf nicht schon dreiteilig dastehen: Genau das schreibt ein
        // Modell dann ab.
        assertFalse(Regex("""de\.\w+\.\w+""").containsMatchIn(text), text)
    }

    /**
     * **Das Gerüst darf sich nicht für die App halten.**
     *
     * Die Vorlage schreibt eine übersetzbare Activity, aber nicht die gewünschte. Sagt das
     * Ergebnis nur „damit wird daraus eine APK", baut das Modell das Gerüst und meldet die
     * App als fertig — der Nutzer bekommt etwas Lauffähiges, das alles kann außer dem,
     * worum er gebeten hat.
     */
    @Test
    fun `nach app-anlegen sagt die Antwort, welche Datei jetzt zu schreiben ist`() = runTest {
        val b = bereich()

        val ergebnis = AppAnlegenImProjekt(b)
            .execute(mapOf("paketname" to "de.neon.qr", "name" to "QR"))

        val text = (ergebnis as ToolResult.Ok).spoken
        assertTrue("src/de/neon/qr/MainActivity.kt" in text, text)
        assertTrue("Gerüst" in text, text)
    }

    @Test
    fun `aus einem Namen ohne Buchstaben wird kein Projekt`() = runTest {
        val b = bereich()

        val ergebnis = ProjektAnlegen(b).execute(mapOf("name" to "..."))

        assertTrue(ergebnis is ToolResult.Failed)
        assertTrue(b.projekte().isEmpty())
    }
}
