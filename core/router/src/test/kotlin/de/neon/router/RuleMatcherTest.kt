package de.neon.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stufe 0 ist der größte Akku-Hebel des ganzen Systems: Jeder Treffer hier vermeidet eine
 * Modellinferenz vollständig. Entsprechend genau wird sie geprüft — inklusive der Fälle,
 * in denen sie ausdrücklich *nicht* greifen darf.
 */
class RuleMatcherTest {

    private val matcher = RuleMatcher()

    private fun match(text: String) = matcher.match(Utterance(text))

    private fun action(text: String): DeviceAction? = match(text)?.action

    @Test
    fun `erkennt die Uhrzeitfrage in mehreren Formulierungen`() {
        listOf(
            "wie spät ist es?",
            "Neon, wie spät ist es",
            "hey neon wie viel uhr haben wir",
            "sag mir die uhrzeit",
        ).forEach { text ->
            assertEquals(DeviceAction.TellTime, action(text), "fehlgeschlagen für: $text")
        }
    }

    @Test
    fun `erkennt die Datumsfrage`() {
        assertEquals(DeviceAction.TellDate, action("welcher tag ist heute"))
        assertEquals(DeviceAction.TellDate, action("neon, der wievielte ist heute?"))
    }

    @Test
    fun `setzt Timer aus Ziffern und aus Zahlwoertern`() {
        assertEquals(DeviceAction.SetTimer(5 * 60), action("stell einen timer auf 5 minuten"))
        assertEquals(DeviceAction.SetTimer(5 * 60), action("timer fünf minuten"))
        assertEquals(DeviceAction.SetTimer(90), action("neon timer 90 sekunden"))
        assertEquals(DeviceAction.SetTimer(2 * 3600), action("timer auf zwei stunden"))
        assertEquals(
            DeviceAction.SetTimer(25 * 60),
            action("stell mir einen timer auf fünfundzwanzig minuten"),
        )
    }

    @Test
    fun `versteht Timer ohne Einheit als Minuten`() {
        assertEquals(DeviceAction.SetTimer(10 * 60), action("timer auf zehn"))
    }

    @Test
    fun `kennt die festen Wendungen fuer halbe und viertel Stunde`() {
        assertEquals(DeviceAction.SetTimer(30 * 60), action("stell einen timer auf eine halbe stunde"))
        assertEquals(DeviceAction.SetTimer(15 * 60), action("timer viertelstunde"))
    }

    @Test
    fun `setzt Wecker mit und ohne Minutenangabe`() {
        assertEquals(DeviceAction.SetAlarm(7, 30), action("stell den wecker auf 7:30"))
        assertEquals(DeviceAction.SetAlarm(6, 0), action("weck mich um sechs"))
        assertEquals(DeviceAction.SetAlarm(6, 45), action("weck mich um sechs uhr fünfundvierzig"))
    }

    @Test
    fun `verwechselt den Artikel nicht mit der Zahl eins`() {
        // "einen" ist hier Artikel, nicht die Zahl 1 — sonst würde jeder so formulierte
        // Timer auf eine Minute gesetzt.
        assertEquals(DeviceAction.SetTimer(20 * 60), action("stell mir einen timer auf zwanzig minuten"))
        assertEquals(DeviceAction.SetAlarm(7, 0), action("stell einen wecker auf sieben"))
    }

    @Test
    fun `schaltet Licht mit und ohne Raumangabe`() {
        assertEquals(DeviceAction.SwitchLight(on = false, room = null), action("licht aus"))
        assertEquals(DeviceAction.SwitchLight(on = true, room = null), action("mach das licht an"))
        assertEquals(
            DeviceAction.SwitchLight(on = true, room = "wohnzimmer"),
            action("mach das licht im wohnzimmer an"),
        )
        assertEquals(
            DeviceAction.SwitchLight(on = false, room = "küche"),
            action("schalte die lampe in der küche aus"),
        )
    }

    @Test
    fun `schaltet die Taschenlampe`() {
        assertEquals(DeviceAction.Flashlight(on = true), action("taschenlampe an"))
        assertEquals(DeviceAction.Flashlight(on = false), action("mach die taschenlampe aus"))
    }

    @Test
    fun `regelt die Lautstaerke relativ und absolut`() {
        assertEquals(DeviceAction.ChangeVolume(+1), action("lauter"))
        assertEquals(DeviceAction.ChangeVolume(-1), action("neon, mach leiser"))
        assertEquals(DeviceAction.SetVolume(30), action("lautstärke auf 30"))
    }

    @Test
    fun `oeffnet Apps und ruft Kontakte an`() {
        assertEquals(DeviceAction.OpenApp("spotify"), action("öffne spotify"))
        assertEquals(DeviceAction.CallContact("mama"), action("ruf mama an"))
    }

    @Test
    fun `erkennt den Abbruch`() {
        assertEquals(DeviceAction.Cancel, action("stopp"))
        assertEquals(DeviceAction.Cancel, action("neon vergiss es"))
    }

    @Test
    fun `entfernt die Anrede nur am Anfang`() {
        // "hallo" ist Anrede — aber nur führend. Sonst würde "sag hallo zu ihm" verstümmelt.
        val match = match("neon hey licht aus")
        assertEquals(DeviceAction.SwitchLight(on = false, room = null), match?.action)
    }

    @Test
    fun `greift nicht bei echten Fragen`() {
        // Diese Äußerungen müssen an die Modellstufen durchgereicht werden. Ein
        // Fehlalarm hier wäre schlimmer als eine verpasste Regel: Neon würde eine
        // Frage mit einer Gerätehandlung beantworten.
        listOf(
            "erklär mir wie eine glühlampe funktioniert",
            "warum ist das licht am ende des tunnels weiß",
            "wie hoch ist der eiffelturm",
            "schreib mir ein python skript",
            "was ist der unterschied zwischen led und halogen",
        ).forEach { text ->
            assertNull(action(text), "hätte nicht greifen dürfen: $text")
        }
    }

    @Test
    fun `verlangt bei Schaltbefehlen eine Richtung`() {
        // Ohne "an" oder "aus" ist unklar, was gemeint ist — dann übernimmt ein Modell.
        assertNull(action("das licht im wohnzimmer"))
    }

    @Test
    fun `laesst Bildanfragen immer durch`() {
        // Selbst wenn der Text nach einer Regel aussieht: Mit Bild kann Stufe 0 nichts anfangen.
        assertNull(matcher.match(Utterance("licht aus", hasImage = true)))
    }

    @Test
    fun `liefert eine Analyse mit voller Zuversicht`() {
        val match = match("licht aus")
        assertIs<RuleMatcher.Match>(match)
        assertEquals(AnalysisSource.REGELN, match.analysis.source)
        assertEquals(TaskCategory.GERAETE_AKTION, match.analysis.category)
        assertEquals(1.0, match.analysis.confidence)
        assertEquals(1, match.analysis.complexity)
    }

    @Test
    fun `ignoriert Satzzeichen und Grossschreibung`() {
        assertTrue(action("LICHT AUS!!!") is DeviceAction.SwitchLight)
    }
}
