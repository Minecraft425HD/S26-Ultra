package de.neon.app

import android.app.Application
import android.content.res.XmlResourceParser
import androidx.test.core.app.ApplicationProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Neon muss mit seinem eigenen Server sprechen dürfen.
 *
 * **Der Fehler, den dieser Test verhindert.** `llama-server` läuft als Kindprozess dieser
 * App und hört auf `http://127.0.0.1:18080`. Seit Android 9 ist unverschlüsseltes HTTP für
 * Apps ab API 28 verboten, sofern nichts anderes konfiguriert ist — und **127.0.0.1 ist
 * davon nicht ausgenommen.** Ohne die Ausnahme wirft OkHttp vor jedem Verbindungsversuch
 * eine `UnknownServiceException`, ohne überhaupt eine Verbindung aufzubauen.
 *
 * Auf dem Gerät sah das so aus: Der Server lud durch, meldete `model loaded` und
 * `listening on http://127.0.0.1:18080` — und Neon erschlug ihn anderthalb Minuten später,
 * weil die Gesundheitsprüfung nie durchkam. Fünf Versuche über zwei Tage, jedes Mal gleich.
 *
 * **Was dieser Test kann und was nicht.** Er prüft, was ausgeliefert wird: das
 * zusammengeführte Manifest und die kompilierte Ressource. Er kann *nicht* prüfen, ob
 * Android die Regel dann auch anwendet — Robolectric hat keine echte Netzwerkrichtlinie,
 * und genau darum ist der Fehler durch alle bisherigen Tests gerutscht. Dieselbe Lücke wie
 * bei `(?U)`: Was nur auf dem Gerät gilt, findet man nur auf dem Gerät. Was hier bleibt,
 * ist die Zusicherung, dass die Erklärung überhaupt vorhanden und richtig ist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NetworkPolicyTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    /**
     * Die Ressourcen-Nummer, auf die `android:networkSecurityConfig` zeigt — `0`, wenn das
     * Manifest gar nicht darauf verweist.
     *
     * Über Reflexion, weil `ApplicationInfo.networkSecurityConfigRes` in Android als
     * `@hide` markiert ist und im SDK deshalb nicht auftaucht. In einem Test ist das
     * unbedenklich: Es wird nur gelesen, nichts aufgerufen, und der Code läuft nie auf
     * einem Gerät. Der Gewinn ist, dass hier das **zusammengeführte** Manifest geprüft
     * wird — also das, was tatsächlich in der APK landet, und nicht die Quelldatei.
     */
    private val konfigurationsRessource: Int
        get() = android.content.pm.ApplicationInfo::class.java
            .getField("networkSecurityConfigRes")
            .getInt(context.applicationInfo)

    @Test
    fun `das Manifest verweist auf eine Netzwerkkonfiguration`() {
        assertTrue(
            konfigurationsRessource != 0,
            "Im Manifest fehlt android:networkSecurityConfig. Damit verbietet Android ab " +
                "API 28 jeden Klartextzugriff — auch den auf den eigenen llama-server.",
        )
    }

    @Test
    fun `Klartext ist fuer 127_0_0_1 erlaubt`() {
        val konfiguration = lesen()

        assertTrue(
            konfiguration.erlaubtKlartextFuer("127.0.0.1"),
            "Kein <domain-config cleartextTrafficPermitted=\"true\"> für 127.0.0.1. " +
                "Ohne das erreicht Neon seinen eigenen Server nicht.",
        )
    }

    @Test
    fun `Klartext bleibt sonst verboten`() {
        val konfiguration = lesen()

        // Die Ausnahme gilt dem eigenen Prozess, nicht dem Internet. Wäre stattdessen
        // android:usesCleartextTraffic="true" gesetzt, ginge auch der spätere Cloud-Pfad
        // ungeschützt über die Leitung.
        assertTrue(
            !konfiguration.basisErlaubtKlartext,
            "Die Basis-Konfiguration erlaubt Klartext für alle Ziele. Die Ausnahme soll " +
                "nur für localhost gelten.",
        )
    }

    /** Was in der kompilierten Ressource steht. */
    private class Konfiguration(
        val basisErlaubtKlartext: Boolean,
        private val klartextDomains: Set<String>,
    ) {
        fun erlaubtKlartextFuer(host: String) = host in klartextDomains
    }

    /**
     * Liest die Ressource, auf die das Manifest zeigt.
     *
     * Bewusst über `resources.getXml` statt über die Quelldatei: Geprüft werden soll das
     * Kompilat, das in der APK landet, nicht der Text im Arbeitsverzeichnis.
     */
    private fun lesen(): Konfiguration {
        val parser: XmlResourceParser = context.resources.getXml(konfigurationsRessource)

        var basisErlaubt = false
        val domains = mutableSetOf<String>()

        // Ob der gerade offene <domain-config>-Block Klartext erlaubt. Die <domain>-Kinder
        // erben diese Angabe vom Elternelement.
        var offenerBlockErlaubt = false
        var inDomain = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "base-config" -> basisErlaubt = klartextAttribut(parser)
                    "domain-config" -> offenerBlockErlaubt = klartextAttribut(parser)
                    "domain" -> inDomain = true
                }

                XmlPullParser.TEXT -> if (inDomain && offenerBlockErlaubt) {
                    parser.text?.trim()?.takeIf { it.isNotEmpty() }?.let { domains += it }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "domain" -> inDomain = false
                    "domain-config" -> offenerBlockErlaubt = false
                }
            }
            event = parser.next()
        }
        parser.close()

        return Konfiguration(basisErlaubt, domains)
    }

    /**
     * `cleartextTrafficPermitted` steht **ohne** Namensraum.
     *
     * Anders als in Manifest und Layouts trägt das Format der Netzwerkkonfiguration kein
     * `android:` vor seinen Attributen. Die erste Fassung dieses Tests suchte im
     * Android-Namensraum, fand nichts und schlug fehl — und die Prüfung auf „Klartext
     * bleibt sonst verboten" wurde dadurch aus dem falschen Grund grün. Ein Test, der
     * nichts findet, sieht einem zufriedenen Test zum Verwechseln ähnlich.
     */
    private fun klartextAttribut(parser: XmlResourceParser): Boolean =
        parser.getAttributeBooleanValue(null, "cleartextTrafficPermitted", false)
}
