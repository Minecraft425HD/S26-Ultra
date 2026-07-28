package de.neon.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * Der einfachste und zugleich wichtigste Anspruch an diese App: **Sie muss starten können.**
 *
 * Genau diese Prüfung fehlte, und deshalb wurde eine APK ausgeliefert, die beim Antippen
 * sofort abstürzte. Der gesamte Objektgraph entsteht in `NeonApplication.onCreate`; ein
 * Fehler in einem einzigen Bestandteil beendet den Prozess, bevor irgendetwas sichtbar wird.
 *
 * Robolectric führt Android-Code auf der JVM aus. Room, `getSystemService` und die
 * Sprachausgabe laufen damit echt genug, um einen Initialisierungsfehler zu zeigen — ohne
 * Gerät und ohne Emulator.
 *
 * Der Test prüft ausdrücklich den Zustand **nach einer frischen Installation**: kein
 * Sprachmodell, kein Weckwortmodell, kein llama-server. Das ist der Zustand, in dem jeder
 * neue Nutzer die App zum ersten Mal öffnet, und der einzige, der wirklich funktionieren muss.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StartupTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `der Objektgraph laesst sich ohne Modelle aufbauen`() {
        val container = runCatching { NeonContainer(context) }.getOrElse { error ->
            fail(
                "NeonContainer ließ sich nicht erzeugen — genau daran stirbt die App beim " +
                    "Start:\n${error.stackTraceToString()}"
            )
        }

        assertNotNull(container.registry)
        assertNotNull(container.orchestrator)
        assertNotNull(container.router)
    }

    @Test
    fun `meldet fehlende Bestandteile statt daran zu scheitern`() {
        val container = NeonContainer(context)

        // Auf einem frisch installierten Gerät ist beides nicht da. Die App muss das
        // aushalten und darüber berichten, statt beim Start zu sterben.
        println("llama-server vorhanden: ${container.inferenceAvailable}")
        println("Weckwortmodell vorhanden: ${container.wakeWordAvailable}")
        println("Modelle vorhanden: ${container.registry.generativeModels().count { container.modelStore.isAvailable(it) }}")
    }

    @Test
    fun `die Anwendung startet wie beim Antippen des Symbols`() {
        // Was Android beim Start tatsächlich tut. Scheitert onCreate, sieht der Nutzer
        // nie ein Fenster — genau das gemeldete Verhalten.
        val application = context
        runCatching { (application as NeonApplication).onCreate() }.getOrElse { error ->
            fail("NeonApplication.onCreate ist gescheitert:\n${error.stackTraceToString()}")
        }
    }

    @Test
    fun `die Hauptansicht laesst sich erzeugen`() {
        // Auch eine Activity, die in onCreate scheitert, zeigt kein Fenster. Dieser Fall
        // sähe für den Nutzer identisch aus.
        runCatching {
            org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup().get()
        }.getOrElse { error ->
            fail("MainActivity ließ sich nicht starten:\n${error.stackTraceToString()}")
        }
    }

    @Test
    fun `der Router entscheidet auch ohne geladenes Modell`() {
        val container = NeonContainer(context)

        // Die Regelstufe muss ohne jedes Modell funktionieren — sie ist das Einzige, was
        // ein Nutzer direkt nach der Installation überhaupt benutzen kann.
        val decision = container.router.route(
            de.neon.router.Utterance("stell einen timer auf fünf minuten"),
            de.neon.router.DeviceState.unknown(),
        )
        assertNotNull(decision)
    }
}
