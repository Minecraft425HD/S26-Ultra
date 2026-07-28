package de.neon.app

import android.app.Application
import de.neon.service.NeonForegroundService

class NeonApplication : Application() {

    lateinit var container: NeonContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = NeonContainer(this)

        // Der Dienst holt sich seine Abhängigkeiten hier ab. Der Graph hängt an der
        // Anwendung und nicht am Dienst, damit ein geladenes Modell einen Dienstneustart
        // übersteht — es erneut von der Platte zu lesen würde Sekunden kosten.
        NeonForegroundService.dependencyFactory = { container.serviceDependencies() }
    }
}
