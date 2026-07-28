package de.neon.tools

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import androidx.core.content.getSystemService
import de.neon.router.DeviceAction

/**
 * Führt die Handlungen aus, die Stufe 0 des Routers ohne Sprachmodell erkannt hat.
 *
 * Getrennt vom Werkzeug-Framework: Diese Aufrufe brauchen kein Modell, keine Grammatik und
 * keine Argumentprüfung — sie kommen aus einer festen Grammatik und sind bereits eindeutig.
 */
class DeviceActionExecutor(private val context: Context) {

    private val cameraManager = context.getSystemService<CameraManager>()

    /** @return was Neon dazu sagen soll, oder `null`, wenn die Handlung nicht ging. */
    fun execute(action: DeviceAction): String? = when (action) {
        is DeviceAction.SetTimer -> setTimer(action.seconds)
        is DeviceAction.SetAlarm -> setAlarm(action.hour, action.minute)
        is DeviceAction.Flashlight -> flashlight(action.on)
        is DeviceAction.OpenApp -> openApp(action.appName)
        is DeviceAction.CallContact -> callContact(action.contact)

        // Zeit und Datum beantwortet der Dienst selbst aus der Systemuhr; Licht und
        // Lautstärke liegen bei Smart-Home- beziehungsweise Audio-Diensten.
        DeviceAction.TellTime,
        DeviceAction.TellDate,
        DeviceAction.Cancel,
        is DeviceAction.SwitchLight,
        is DeviceAction.ChangeVolume,
        is DeviceAction.SetVolume,
        -> null
    }

    private fun setTimer(seconds: Int): String? = runCatching {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Neon")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        "Timer läuft: ${describeDuration(seconds)}."
    }.getOrNull()

    private fun setAlarm(hour: Int, minute: Int): String? = runCatching {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        "Wecker gestellt auf %d:%02d Uhr.".format(hour, minute)
    }.getOrNull()

    private fun flashlight(on: Boolean): String? = runCatching {
        val manager = cameraManager ?: return null
        // Die erste Kamera mit Blitz ist auf jedem Telefon die Rückkamera.
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return null

        manager.setTorchMode(cameraId, on)
        if (on) "Taschenlampe an." else "Taschenlampe aus."
    }.getOrNull()

    private fun openApp(appName: String): String? = runCatching {
        val packageManager = context.packageManager
        val target = packageManager.getInstalledApplications(0)
            .firstOrNull {
                packageManager.getApplicationLabel(it).toString()
                    .equals(appName, ignoreCase = true)
            }
            ?: packageManager.getInstalledApplications(0).firstOrNull {
                packageManager.getApplicationLabel(it).toString()
                    .contains(appName, ignoreCase = true)
            }
            ?: return null

        val launch = packageManager.getLaunchIntentForPackage(target.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        "Öffne ${packageManager.getApplicationLabel(target)}."
    }.getOrNull()

    private fun callContact(contact: String): String? = runCatching {
        // Bewusst ACTION_DIAL und nicht ACTION_CALL: Der Anruf wird vorbereitet, ausgelöst
        // wird er vom Nutzer. Eine Fehlerkennung darf niemanden versehentlich anrufen.
        val intent = Intent(Intent.ACTION_DIAL).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        "Ich öffne die Telefon-App für $contact."
    }.getOrNull()

    private fun describeDuration(seconds: Int): String = when {
        seconds % 3600 == 0 && seconds >= 3600 -> {
            val hours = seconds / 3600
            if (hours == 1) "eine Stunde" else "$hours Stunden"
        }

        seconds % 60 == 0 -> {
            val minutes = seconds / 60
            if (minutes == 1) "eine Minute" else "$minutes Minuten"
        }

        else -> "$seconds Sekunden"
    }
}
