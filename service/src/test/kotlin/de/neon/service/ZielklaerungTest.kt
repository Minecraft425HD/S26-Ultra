package de.neon.service

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wann Neon nach der Sprache fragt — und wann nicht.
 *
 * **Warum diese Entscheidung nicht beim Modell liegt.** Das Werkzeug `rueckfrage` gab es seit
 * Tagen. Es stand an erster Stelle in der Werkzeugliste, seine Beschreibung nannte die
 * Gabelung beim Namen, und dieselbe Regel stand im Systemprompt. Auf „programmiere eine
 * QR-Generierungs-App" hat Neon trotzdem ungefragt ein Python-Skript geschrieben, ohne
 * Projekt. Ein 1.7-B-Modell trifft diese Wahl nicht — man kann ihm anbieten zu fragen, sich
 * darauf verlassen kann man nicht.
 *
 * **Und warum hier Stichwörter erlaubt sind, wo sie sonst verboten waren.** Bei der
 * Werkzeugauswahl habe ich sie abgelehnt: Wer am Wortlaut rät, welches Werkzeug gemeint ist,
 * tut bei einem Fehlgriff etwas Falsches. Hier ist der Fehlgriff eine **Frage** — zehn
 * Sekunden und ein Satz. Die Alternative kostet eine App in der falschen Sprache.
 */
class ZielklaerungTest {

    private fun fragt(text: String, android: Boolean = false) =
        Zielklaerung.brauchtSprachfrage(text, projektIstAndroid = android)

    // ---- Der Fall vom Gerät -------------------------------------------------------------

    @Test
    fun `der Satz, an dem es gescheitert ist, loest die Frage aus`() {
        assertTrue(fragt("programmiere eine qr generierungsapp"))
        assertTrue(fragt("Programmiere eine QR-Generierungs-App"))
    }

    /**
     * **„App" allein beantwortet die Frage nicht.**
     *
     * Naheliegend wäre, das Wort als „Android" zu lesen — Neon läuft schließlich auf einem
     * Telefon. Der Nutzer sieht das anders, und er hat recht: Ein Python-Skript, das
     * QR-Codes erzeugt, ist auch eine Anwendung.
     */
    @Test
    fun `App allein zaehlt nicht als Android`() {
        assertTrue(fragt("mach mir eine Zähler-App"))
        assertTrue(fragt("bau mir eine App zum Notizen machen"))
    }

    // ---- Wann nicht gefragt wird --------------------------------------------------------

    @Test
    fun `eine ausdrueckliche Angabe beendet die Frage`() {
        assertFalse(fragt("programmiere eine QR-App für Android"))
        assertFalse(fragt("schreib mir ein Python-Skript für QR-Codes"))
        assertFalse(fragt("bau mir eine apk"))
        assertFalse(fragt("schreib das in Kotlin"))
        assertFalse(fragt("mach mir ein script das Dateien umbenennt"))
    }

    /**
     * Wer schon in einem Android-Projekt steht, wird nicht gefragt.
     *
     * „Schreib mir das noch dazu" meint dann dieses Projekt. Eine Rückfrage wäre hier
     * Bürokratie — und genau die Sorte Nachfrage, die einen Assistenten unbrauchbar macht.
     */
    @Test
    fun `im laufenden Android-Projekt wird nicht gefragt`() {
        assertTrue(fragt("bau mir noch einen Knopf ein", android = false))
        assertFalse(fragt("bau mir noch einen Knopf ein", android = true))
    }

    /**
     * **Nur bei Bauaufträgen.**
     *
     * Die Einordnung als CODE umfasst auch „erklär mir diese Funktion". Eine Rückfrage nach
     * der Programmiersprache wäre dort unsinnig.
     */
    @Test
    fun `eine Frage ist kein Bauauftrag`() {
        assertFalse(fragt("was ist ein QR-Code"))
        assertFalse(fragt("wie funktioniert Fehlerkorrektur bei QR-Codes"))
        assertFalse(fragt("erklär mir diese Funktion"))
        assertFalse(fragt("wie spät ist es"))
    }

    /**
     * **Ein Fehlgriff, den ein vorhandener Test gefangen hat.**
     *
     * „Wie schreibe ich eine Schleife" enthält „schreib" und wäre als Bauauftrag
     * durchgegangen — eine Rückfrage nach der Programmiersprache auf eine Verständnisfrage.
     * Geprüft wird deshalb der Satzanfang.
     */
    @Test
    fun `ein Auftragswort in einer Frage macht daraus keinen Auftrag`() {
        assertFalse(fragt("wie schreibe ich eine schleife"))
        assertFalse(fragt("wie baue ich einen QR-Code selbst"))
        assertFalse(fragt("was macht man, wenn man eine App programmieren will"))
    }

    /**
     * Ein Fragezeichen allein macht aus einem Auftrag keine Frage.
     *
     * „Kannst du mir eine App bauen?" ist ein Auftrag in Frageform — und genau der Fall, in
     * dem gefragt werden muss.
     */
    @Test
    fun `ein Auftrag in Frageform bleibt ein Auftrag`() {
        assertTrue(fragt("kannst du mir eine QR-App bauen?"))
    }

    // ---- Die Frage und das Zusammensetzen -----------------------------------------------

    /**
     * Die Frage nennt beide Möglichkeiten und sagt, wie man antwortet.
     *
     * Eine offene Frage („was soll es werden?") bekäme offene Antworten, und die müsste
     * wieder ein Modell deuten. Zwei Wörter zur Auswahl kann man auch sprechen.
     */
    @Test
    fun `die Frage nennt beide Moeglichkeiten`() {
        assertContains(Zielklaerung.FRAGE_SPRACHE, "Android")
        assertContains(Zielklaerung.FRAGE_SPRACHE, "Python")
    }

    /**
     * **Die Antwort muss zurück zum Auftrag finden.**
     *
     * Ohne das ist die Rückfrage schlimmer als nutzlos: Auf „Android" allein folgt eine neue
     * Einordnung, und „Android" ist für sich genommen keine Programmieraufgabe — die
     * Werkzeugkette liefe gar nicht erst an.
     */
    @Test
    fun `Auftrag und Antwort ergeben wieder einen Auftrag`() {
        val zusammen = Zielklaerung.zusammengefuegt("programmiere eine qr generierungsapp", "Android")

        assertContains(zusammen, "qr generierungsapp")
        assertContains(zusammen, "Android")
        // Und das Ergebnis löst die Frage nicht erneut aus — sonst fragte Neon endlos.
        assertFalse(fragt(zusammen), zusammen)
    }

    @Test
    fun `auch mit Python als Antwort wird nicht noch einmal gefragt`() {
        val zusammen = Zielklaerung.zusammengefuegt("bau mir eine Zähler-App", "python bitte")

        assertFalse(fragt(zusammen), zusammen)
    }

    // ---- Was das Geraet gezeigt hat -----------------------------------------------------

    /**
     * **Der Auftrag wird nicht verdoppelt.**
     *
     * Auf „Android oder Python?" kam der ursprüngliche Auftrag ein zweites Mal zurück. Heraus
     * kam „programmiere eine qr generierungsapp (programmiere eine qr generierungsapp)" — ein
     * doppelter Satz ist kein zusätzlicher Hinweis, er verändert nur die Einordnung, und zwar
     * unvorhersagbar.
     */
    @Test
    fun `dieselbe Antwort wie die Frage verdoppelt den Auftrag nicht`() {
        val auftrag = "programmiere eine qr generierungsapp"

        assertEquals(auftrag, Zielklaerung.zusammengefuegt(auftrag, auftrag))
        assertEquals(auftrag, Zielklaerung.zusammengefuegt(auftrag, "  $auftrag  "))
    }

    @Test
    fun `eine Antwort, die den Auftrag enthaelt, ersetzt ihn`() {
        val zusammen = Zielklaerung.zusammengefuegt(
            "programmiere eine qr app",
            "programmiere eine qr app für Android",
        )

        assertEquals("programmiere eine qr app für Android", zusammen)
        assertFalse(fragt(zusammen), zusammen)
    }

    /**
     * **Eine Nicht-Antwort bleibt eine Nicht-Antwort.**
     *
     * Der zusammengesetzte Auftrag muss weiterhin die Frage auslösen — sonst arbeitet Neon
     * ohne Sprache weiter, und genau das ist auf dem Gerät passiert.
     */
    @Test
    fun `nach einer Nicht-Antwort ist die Frage weiter offen`() {
        val auftrag = "programmiere eine qr generierungsapp"

        assertTrue(fragt(Zielklaerung.zusammengefuegt(auftrag, auftrag)))
        assertTrue(fragt(Zielklaerung.zusammengefuegt(auftrag, "ja mach mal")))
    }

    @Test
    fun `die zweite Frage sagt, was als Antwort erwartet wird`() {
        assertContains(Zielklaerung.FRAGE_NOCHMAL, "Android")
        assertContains(Zielklaerung.FRAGE_NOCHMAL, "Python")
        assertContains(Zielklaerung.FRAGE_NOCHMAL, "einem Wort")
    }
}
