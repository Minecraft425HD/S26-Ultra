package de.neon.app

import android.app.Application
import de.neon.platform.CrashReporter
import de.neon.platform.NeonLog
import java.io.File

class NeonApplication : Application() {

    /**
     * Kann fehlen, wenn der Aufbau gescheitert ist.
     *
     * Früher war das ein `lateinit var` und der Aufbau ungeschützt — ein Fehler in einem
     * von zwanzig Bestandteilen beendete damit den Prozess, bevor irgendetwas sichtbar
     * wurde. Die App soll stattdessen starten und berichten, woran es lag.
     */
    var container: NeonContainer? = null
        private set

    /** Warum der Aufbau gescheitert ist, falls er das tat. */
    var startupFailure: Throwable? = null
        private set

    lateinit var crashReporter: CrashReporter
        private set

    override fun onCreate() {
        super.onCreate()

        val logDirectory = File(filesDir, "logs")

        // Allererste Anweisung. Alles, was danach schiefgeht, ist damit beim nächsten Start
        // ablesbar — ohne adb, ohne Entwicklungsumgebung.
        crashReporter = CrashReporter(logDirectory)
        crashReporter.install(BuildConfig.VERSION_NAME)

        NeonLog.install(logDirectory)
        NeonLog.i(TAG, "Neon startet — Version ${BuildConfig.VERSION_NAME}")

        // Der Aufbau darf scheitern, ohne die App mitzunehmen. Ein Assistent, der nicht
        // startet, weil ein Bestandteil hakt, kann nicht einmal sagen, welcher.
        runCatching { NeonContainer(this) }
            .onSuccess { built ->
                container = built
                NeonLog.i(
                    TAG,
                    "llama-server ${if (built.inferenceAvailable) "vorhanden" else "FEHLT"}, " +
                        "Weckwort ${if (built.wakeWordAvailable) "vorhanden" else "fehlt"}",
                )
            }
            .onFailure { error ->
                startupFailure = error
                NeonLog.e(TAG, "Der Objektgraph ließ sich nicht aufbauen", error)
                crashReporter.recordHandled("NeonContainer", error, BuildConfig.VERSION_NAME)
            }

        de.neon.service.NeonForegroundService.dependencyFactory = factory@{
            container?.serviceDependencies()
                ?: error("Neon konnte nicht vollständig starten — siehe Fehlerbericht.")
        }
    }

    private companion object {
        const val TAG = "NeonApp"
    }
}
