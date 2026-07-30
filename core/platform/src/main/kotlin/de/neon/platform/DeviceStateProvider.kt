package de.neon.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.getSystemService
import de.neon.router.DeviceState
import de.neon.router.NetworkState
import de.neon.router.ThermalStatus

/**
 * Übersetzt den Zustand des Telefons in das, was der Router versteht.
 *
 * Ohne diese Brücke wüsste die Auswahl-Policy nichts von Akkustand und Hitze und würde
 * immer das beste Modell wählen — genau das, was Neon vermeiden soll.
 */
class DeviceStateProvider(
    private val context: Context,
    /** Welche Modelle gerade warm sind; kommt vom ModelLifecycleManager. */
    private val warmModelIds: () -> Set<String> = { emptySet() },
    /**
     * Welche Modelldateien auf der Platte liegen; kommt vom ModelStore.
     *
     * `null` heißt „unbekannt" und schaltet die Einschränkung ab — die Voreinstellung,
     * damit ein Aufrufer, der das nicht setzt, sich wie bisher verhält.
     */
    private val availableModelIds: (() -> Set<String>)? = null,
    /**
     * Rückfallwert, falls sich der freie Speicher nicht messen lässt.
     *
     * Nicht mehr das Budget selbst: Das wird gemessen. Siehe [availableForModels].
     */
    private val memoryBudgetBytes: Long = FALLBACK_MODEL_BUDGET,
) {

    private val batteryManager = context.getSystemService<BatteryManager>()
    private val powerManager = context.getSystemService<PowerManager>()
    private val connectivityManager = context.getSystemService<ConnectivityManager>()

    fun current(): DeviceState = DeviceState(
        batteryPercent = batteryPercent(),
        isCharging = batteryManager?.isCharging ?: false,
        thermalStatus = thermalStatus(),
        network = networkState(),
        loadedModelIds = warmModelIds(),
        availableMemoryBytes = availableForModels(),
        availableModelIds = availableModelIds?.invoke(),
    )

    private fun batteryPercent(): Int =
        batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
            ?: 100

    private fun thermalStatus(): ThermalStatus =
        when (powerManager?.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN,
            -> ThermalStatus.CRITICAL

            else -> ThermalStatus.NONE
        }

    private fun networkState(): NetworkState {
        val network = connectivityManager?.activeNetwork ?: return NetworkState.OFFLINE
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return NetworkState.OFFLINE
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkState.OFFLINE
        }
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkState.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkState.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkState.MOBILE
            else -> NetworkState.OFFLINE
        }
    }

    /**
     * Wie viel Speicher ein neu zu ladendes Modell noch belegen darf.
     *
     * **Hier stand eine Konstante: fünf Gigabyte, mit dem Kommentar „von sechzehn".** Das
     * Gerät hat 5,3 GB *insgesamt* und meldete 1,6 GB frei. Der Router hielt also fünf
     * Gigabyte für verfügbar, ließ das 4-B-Modell zu — und Android erschlug den Prozess
     * beim Laden, sechsmal hintereinander. Eine Zahl, die das Gerät kennt, hat in einer
     * Konstante nichts zu suchen.
     *
     * Gemessen wird `MemAvailable` und nicht `MemFree`: Letzteres ist auf Linux fast immer
     * klein, weil der Seitencache allen ungenutzten Speicher belegt. `MemAvailable` ist die
     * Schätzung des Kernels, wie viel sich ohne Auslagern vergeben lässt — genau die Frage,
     * die hier zu beantworten ist.
     *
     * Lässt sich nichts messen, gilt der übergebene Wert. Eine fehlende Auskunft soll den
     * Router nicht lahmlegen; sie soll ihn nur nicht belügen.
     */
    private fun availableForModels(): Long {
        val gemessen = DeviceMemory.read()
        return if (gemessen.known) gemessen.availableBytes else memoryBudgetBytes
    }

    companion object {
        /**
         * Der Rückfallwert, falls sich `/proc/meminfo` nicht lesen lässt.
         *
         * Bewusst klein: Wer nicht weiß, wie viel Platz da ist, sollte nicht das größte
         * Modell zulassen. Zwei Gigabyte lassen das Alltagsmodell zu und die großen nicht.
         */
        const val FALLBACK_MODEL_BUDGET = 2L * 1024 * 1024 * 1024
    }
}
