package de.neon.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import de.neon.audio.ListeningEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Der Dienst, der Neon am Leben hält.
 *
 * Läuft als Vordergrunddienst vom Typ `microphone`. Das ist unter Android 16 keine
 * Formalität, sondern die einzige Möglichkeit, dauerhaft am Mikrofon zu horchen — und der
 * Grund, warum es die dauerhafte Benachrichtigung gibt.
 *
 * Der Dienst wird immer aus einer sichtbaren Oberfläche heraus gestartet: über die App
 * selbst, die Schnelleinstellungs-Kachel oder das Antippen der Benachrichtigung nach einem
 * Neustart. Ein Start aus dem Hintergrund ist für Mikrofondienste nicht erlaubt.
 */
class NeonForegroundService : LifecycleService() {

    /** Hängt an der Anwendung, damit Modelle einen Dienstneustart überleben. */
    interface Dependencies {
        val orchestrator: ConversationOrchestrator
        fun listen(): kotlinx.coroutines.flow.Flow<ListeningEvent>

        /** Aufnahme ohne Weckwort starten, ausgelöst per Knopf oder Kachel. */
        fun triggerManually()

        fun release()
    }

    inner class LocalBinder : Binder() {
        val service: NeonForegroundService get() = this@NeonForegroundService
    }

    private val binder = LocalBinder()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    val state: StateFlow<NeonState>?
        get() = dependencies?.orchestrator?.state

    private var dependencies: Dependencies? = null

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                return START_NOT_STICKY
            }

            ACTION_INTERRUPT -> {
                dependencies?.orchestrator?.interrupt()
                return START_STICKY
            }

            ACTION_TRIGGER -> {
                if (!hasMicrophonePermission()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Ein Knopfdruck soll genügen: Läuft der Dienst noch nicht, wird er
                // gestartet und die Aufnahme beginnt sofort. Zwei getrennte Bedienschritte
                // dafür zu verlangen wäre für den häufigsten Fall — schnell etwas fragen —
                // einer zu viel.
                if (!_running.value) startListening()
                dependencies?.triggerManually()
                return START_NOT_STICKY
            }
        }

        if (!hasMicrophonePermission()) {
            Log.w(TAG, "ohne Mikrofonberechtigung kann Neon nicht lauschen")
            stopSelf()
            return START_NOT_STICKY
        }

        startListening()
        // NOT_STICKY ist Absicht: Nach einem Systemneustart darf sich ein Mikrofondienst
        // nicht selbst wiederbeleben. Die Benachrichtigung des BootReceivers ist der
        // vorgesehene Weg zurück.
        return START_NOT_STICKY
    }

    private fun startListening() {
        if (_running.value) return

        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.neon_listening)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )

        val deps = dependencyFactory?.invoke(this)
        if (deps == null) {
            Log.e(TAG, "keine Abhängigkeiten hinterlegt — der Dienst kann nicht starten")
            stopSelf()
            return
        }
        dependencies = deps
        _running.value = true
        deps.orchestrator.onIdle()

        lifecycleScope.launch {
            deps.listen().collect { event ->
                when (event) {
                    is ListeningEvent.WakeWordDetected -> deps.orchestrator.onWakeWord()

                    is ListeningEvent.SpeechCaptured ->
                        runCatching { deps.orchestrator.handleUtterance(event.samples) }
                            .onFailure { Log.e(TAG, "Durchgang fehlgeschlagen", it) }

                    ListeningEvent.CaptureTimedOut -> deps.orchestrator.onIdle()
                }
            }
        }
    }

    private fun stopListening() {
        _running.value = false
        dependencies?.orchestrator?.onStopped()
        dependencies?.release()
        dependencies = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.neon_channel_name),
            // Niedrige Wichtigkeit: Die Benachrichtigung muss da sein, soll aber nicht
            // klingeln oder aufblenden.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.neon_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, NeonForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.neon_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.neon_stop), stopIntent)
            .build()
    }

    companion object {
        private const val TAG = "NeonService"
        private const val CHANNEL_ID = "neon_listening"
        private const val NOTIFICATION_ID = 1

        const val ACTION_STOP = "de.neon.action.STOP"
        const val ACTION_INTERRUPT = "de.neon.action.INTERRUPT"
        const val ACTION_TRIGGER = "de.neon.action.TRIGGER"

        /**
         * Wird von der Anwendung beim Start gesetzt.
         *
         * Bewusst kein Dependency-Injection-Rahmenwerk: Der Dienst braucht genau ein
         * Objekt, und dieses eine Feld ist leichter nachzuvollziehen als eine
         * Bibliothek, die dasselbe mit Anmerkungen erledigt.
         */
        @Volatile
        var dependencyFactory: ((Context) -> Dependencies)? = null

        fun start(context: Context) {
            val intent = Intent(context, NeonForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, NeonForegroundService::class.java).setAction(ACTION_STOP)
            )
        }

        /**
         * Startet die Aufnahme sofort, ohne Weckwort.
         *
         * Darf nur aus einer sichtbaren Oberfläche heraus aufgerufen werden — der Dienst
         * benutzt das Mikrofon, und Android 16 erlaubt dessen Start nur dann.
         */
        fun trigger(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NeonForegroundService::class.java).setAction(ACTION_TRIGGER),
            )
        }
    }
}
