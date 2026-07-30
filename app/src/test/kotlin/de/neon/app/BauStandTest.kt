package de.neon.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Das Protokoll muss sagen, welche Fassung es geschrieben hat.
 *
 * **Der Anlass.** `NeonApplication` schrieb `"Neon startet — Version ${VERSION_NAME}"`, und
 * `versionName = "0.1.0-m1"` stand seit dem ersten Tag unverändert im Build. An einem Tag mit
 * zwölf APKs schrieb damit jede dieselbe Zeile — bei einem gemeldeten Fehler war nicht einmal
 * zu erkennen, ob die neue überhaupt installiert war.
 *
 * Direkt daneben stand im Build `buildConfig = true` mit dem Kommentar „Für die
 * Versionsnummer im Protokoll. Ohne sie ist bei einem gemeldeten Fehler nicht erkennbar,
 * welcher Stand überhaupt lief." Die Absicht war richtig aufgeschrieben und nie eingelöst.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BauStandTest {

    private val app: NeonApplication
        get() = ApplicationProvider.getApplicationContext<Application>() as NeonApplication

    @Test
    fun `der Baustand nennt Version, Commit und Zeitpunkt`() {
        val stand = app.bauStand

        assertTrue(stand.contains(BuildConfig.VERSION_NAME), "Version fehlt: $stand")
        assertTrue(stand.contains(BuildConfig.GIT_COMMIT), "Commit fehlt: $stand")
        assertTrue(stand.contains(BuildConfig.BUILD_TIME), "Bauzeitpunkt fehlt: $stand")
    }

    @Test
    fun `keines der Felder ist leer`() {
        // Ein leeres Feld wäre schlimmer als keins: Die Zeile sähe vollständig aus und
        // sagte nichts. Fehlt die Git-Auskunft, steht dort ausdrücklich "unbekannt".
        listOf(
            "VERSION_NAME" to BuildConfig.VERSION_NAME,
            "GIT_COMMIT" to BuildConfig.GIT_COMMIT,
            "BUILD_TIME" to BuildConfig.BUILD_TIME,
        ).forEach { (name, wert) ->
            assertTrue(wert.isNotBlank(), "$name ist leer")
        }
    }

    @Test
    fun `der Commit ist ein Hash oder ausdruecklich unbekannt`() {
        val commit = BuildConfig.GIT_COMMIT

        assertTrue(
            commit == "unbekannt" || commit.matches(Regex("[0-9a-f]{7,40}")),
            "weder Hash noch 'unbekannt': '$commit'",
        )
    }

    @Test
    fun `der Baustand passt in eine Protokollzeile`() {
        // Er steht am Anfang jeder Protokolldatei und in jedem Absturzbericht. Wird er zu
        // lang, verdrängt er das, was man eigentlich lesen will.
        assertTrue(app.bauStand.length <= 60, "zu lang (${app.bauStand.length}): ${app.bauStand}")
    }
}
