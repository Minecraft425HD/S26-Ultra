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
    /** Wie viel Speicher Modelle noch belegen dürfen. */
    private val memoryBudgetBytes: Long = DEFAULT_MODEL_BUDGET,
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
     * Nicht der freie Systemspeicher: Android meldet auch Speicher als frei, den es beim
     * Zugriff erst durch Abräumen anderer Apps beschaffen müsste. Ein festes Budget ist
     * die ehrlichere Grenze.
     */
    private fun availableForModels(): Long = memoryBudgetBytes

    companion object {
        /** Fünf Gigabyte von sechzehn — der Rest gehört One UI, Audio und der App selbst. */
        const val DEFAULT_MODEL_BUDGET = 5L * 1024 * 1024 * 1024
    }
}
