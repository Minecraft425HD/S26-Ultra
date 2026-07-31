package de.neon.app

import android.app.Application
import de.neon.platform.CrashReporter
import de.neon.platform.DeviceMemory
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

    /**
     * Welche Fassung genau läuft: Version, Commit und Bauzeitpunkt.
     *
     * `versionName` allein genügte nicht — er stand seit dem ersten Tag unverändert da, und
     * damit schrieb jede der zwölf APKs eines Tages dieselbe Startzeile. Bei einem
     * gemeldeten Fehler war dann nicht einmal zu erkennen, ob die neue überhaupt
     * installiert war.
     */
    val bauStand: String
        get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_COMMIT}, ${BuildConfig.BUILD_TIME})"

    override fun onCreate() {
        super.onCreate()

        val logDirectory = File(filesDir, "logs")

        // Allererste Anweisung. Alles, was danach schiefgeht, ist damit beim nächsten Start
        // ablesbar — ohne adb, ohne Entwicklungsumgebung.
        crashReporter = CrashReporter(logDirectory)
        crashReporter.install(bauStand)

        NeonLog.install(logDirectory)
        NeonLog.i(TAG, "Neon startet — $bauStand")

        // Was das Gerät kann und wie viel Platz es hat, gleich zu Beginn.
        //
        // Bisher stand das nur beim Ladeversuch im Protokoll — ein Protokoll ohne gestellte
        // Frage enthielt es deshalb nicht. Genau diese drei Zahlen wurden in diesem Projekt
        // dreimal falsch angenommen: Seitengröße, Befehlssatz, Arbeitsspeicher. Sie kosten
        // zwei Dateizugriffe und gehören in jedes Protokoll.
        NeonLog.i(
            TAG,
            "${de.neon.inference.CpuFeatures.describe()} · ${DeviceMemory.read().describe()}",
        )

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

                // Die Python-Standardbibliothek auspacken — dreitausend Dateien, beim ersten
                // Start ein paar Sekunden. Deshalb neben dem Startpfad und nicht in ihm: Eine
                // App, die deswegen fünf Sekunden schwarz bleibt, sieht aus wie eine
                // abgestürzte, und der Chat funktioniert derweil ohnehin.
                //
                // Der Baustand dient als Fassungskennung: Er ändert sich genau dann, wenn
                // sich das mitgelieferte ZIP geändert haben kann.
                Thread({
                    built.richtePythonEin(bauStand)
                    // Und die Bau-Werkzeuge, rund 48 MB. Nacheinander im selben Faden: Zwei
                    // gleichzeitige Auspackvorgänge auf denselben Flash-Speicher sind nicht
                    // schneller, nur unübersichtlicher im Protokoll.
                    built.richteBauKetteEin(bauStand)
                }, "neon-werkzeuge-setup").apply {
                    isDaemon = true
                    start()
                }
            }
            .onFailure { error ->
                startupFailure = error
                NeonLog.e(TAG, "Der Objektgraph ließ sich nicht aufbauen", error)
                crashReporter.recordHandled("NeonContainer", error, bauStand)
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
