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
     * Die tatsächlichen Dateigrößen; kommt ebenfalls vom ModelStore.
     *
     * Wie [availableModelIds] bei jedem Durchgang neu gelesen: Ein Import ändert die Antwort,
     * und zwar sofort.
     */
    private val gemesseneGroessen: (() -> Map<String, Long>)? = null,
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
        gemesseneGroessen = gemesseneGroessen?.invoke() ?: emptyMap(),
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

    /** Siehe [weightBudget]. */
    private fun availableForModels(): Long = weightBudget(DeviceMemory.read(), memoryBudgetBytes)

    companion object {
        /**
         * Wie groß die **Gewichte** eines Modells sein dürfen.
         *
         * Diese Zahl hat zwei Fassungen hinter sich, und beide waren falsch:
         *
         * 1. Eine Konstante von fünf Gigabyte, mit dem Kommentar „von sechzehn". Das Gerät
         *    hat 5,3 GB *insgesamt*. Der Router ließ das 4-B-Modell mit einem Kontextfenster
         *    von 16384 Token zu, und Android erschlug den Prozess sechsmal beim Laden.
         * 2. Danach `MemAvailable` — 1,5 GB. Damit fiel das 2,5 GB schwere Alltagsmodell
         *    durch, und Neon antwortete nach sechs Millisekunden „Dafür bräuchte ich ein
         *    Modell, das nicht in den Speicher passt". Kein Ladeversuch mehr, auf einem
         *    Gerät, das dasselbe Modell einen Tag zuvor geladen und beantwortet hatte.
         *
         * Der Denkfehler in (2): Die Gewichte werden per `mmap` eingebunden und liegen als
         * **Dateiseiten** im Seitencache. Der Kernel darf sie jederzeit verdrängen und aus
         * Flash nachlesen — sie müssen **nicht** in den freien Speicher passen. Was
         * hineinpassen muss, ist der anonyme Anteil: Schlüssel-Wert-Speicher und
         * Rechenpuffer. Darüber entscheidet `ProcessServerSupervisor.passendeKontextgroesse`
         * gegen `MemAvailable`, und das ist die richtige Stelle dafür.
         *
         * Für die Gewichte zählt daher der **Gesamtspeicher**: Sie brauchen genug
         * Seitencache, um voranzukommen. [WEIGHT_BUDGET_SHARE] lässt den Rest für One UI,
         * die App und den anonymen Anteil übrig. Auf 5,3 GB ergibt das 3,2 GB — genug für
         * das Alltagsmodell (2,5 GB), zu wenig für 8B (5,0 GB) und Coder 7B (4,5 GB).
         *
         * Als reine Funktion und nicht als private Methode, damit die Zahl geprüft werden
         * kann. Beide falschen Fassungen sind an grünen Tests vorbeigekommen, weil genau
         * diese Rechnung nirgends festgehalten war.
         */
        fun weightBudget(memory: MemoryReading, fallback: Long = FALLBACK_MODEL_BUDGET): Long =
            if (memory.known) (memory.totalBytes * WEIGHT_BUDGET_SHARE).toLong() else fallback

        /** Wie viel des Gesamtspeichers die Modellgewichte höchstens ausmachen dürfen. */
        const val WEIGHT_BUDGET_SHARE = 0.6

        /**
         * Der Rückfallwert, falls sich `/proc/meminfo` nicht lesen lässt.
         *
         * Drei Gigabyte: Wer nicht weiß, wie viel Platz da ist, soll das Alltagsmodell
         * zulassen und die großen nicht. Ein zu kleiner Rückfall führt zurück in die
         * Verweigerung, ein zu großer in den Abschuss.
         */
        const val FALLBACK_MODEL_BUDGET = 3L * 1024 * 1024 * 1024
    }
}
