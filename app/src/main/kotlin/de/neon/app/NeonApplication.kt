package de.neon.app

import android.app.Application
import de.neon.platform.NeonLog
import de.neon.service.NeonForegroundService
import java.io.File

class NeonApplication : Application() {

    lateinit var container: NeonContainer
        private set

    override fun onCreate() {
        super.onCreate()

        // Als Allererstes: Ohne Protokolldatei wäre ein Fehler beim Start des
        // Sprachmodells auf einem Telefon ohne Entwicklungsumgebung nicht
        // nachvollziehbar. Der Nutzer hörte nur, dass etwas schiefging.
        NeonLog.install(File(filesDir, "logs"))
        NeonLog.i(TAG, "Neon startet — Version ${BuildConfig.VERSION_NAME}")

        container = NeonContainer(this)

        // Der Dienst holt sich seine Abhängigkeiten hier ab. Der Graph hängt an der
        // Anwendung und nicht am Dienst, damit ein geladenes Modell einen Dienstneustart
        // übersteht — es erneut von der Platte zu lesen würde Sekunden kosten.
        NeonForegroundService.dependencyFactory = { container.serviceDependencies() }

        NeonLog.i(
            TAG,
            "llama-server ${if (container.inferenceAvailable) "vorhanden" else "FEHLT"}, " +
                "Weckwort ${if (container.wakeWordAvailable) "vorhanden" else "fehlt"}",
        )
    }

    private companion object {
        const val TAG = "NeonApp"
    }
}
