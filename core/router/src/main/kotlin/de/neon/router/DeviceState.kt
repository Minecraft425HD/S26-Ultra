package de.neon.router

/** Entspricht `PowerManager.getCurrentThermalStatus()`. */
enum class ThermalStatus {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL;

    /** Ab MODERATE drosselt das System bereits spürbar. */
    val isThrottling: Boolean get() = ordinal >= MODERATE.ordinal
}

enum class NetworkState {
    OFFLINE,
    MOBILE,
    WIFI;

    val isOnline: Boolean get() = this != OFFLINE
}

/**
 * Der Gerätezustand, den die Auswahl-Policy berücksichtigt.
 *
 * Ohne diese Werte würde der Router immer das qualitativ beste Modell wählen und den Akku
 * leerziehen. Mit ihnen wird aus der Modellauswahl eine Kosten-Nutzen-Entscheidung.
 */
data class DeviceState(
    val batteryPercent: Int,
    val isCharging: Boolean,
    val thermalStatus: ThermalStatus,
    val network: NetworkState,
    /** Welche Modelle liegen bereits im Speicher? Grundlage der Hysterese. */
    val loadedModelIds: Set<String>,
    /** Wie viel Speicher darf ein neu zu ladendes Modell noch belegen? */
    val availableMemoryBytes: Long,
) {
    /**
     * Sparmodus: Der Akku ist knapp und das Gerät hängt nicht am Ladegerät, oder es
     * drosselt bereits wegen Hitze. Dann sind große Modelle tabu.
     */
    val isConstrained: Boolean
        get() = (batteryPercent <= LOW_BATTERY_PERCENT && !isCharging) || thermalStatus.isThrottling

    /** Beim Laden darf Neon aus dem Vollen schöpfen. */
    val isUnconstrained: Boolean
        get() = isCharging && !thermalStatus.isThrottling

    fun isLoaded(model: ModelSpec): Boolean = model.id in loadedModelIds

    fun fitsInMemory(model: ModelSpec): Boolean =
        isLoaded(model) || model.sizeBytes <= availableMemoryBytes

    companion object {
        const val LOW_BATTERY_PERCENT = 20

        /** Ein neutraler Zustand für Tests und für den ersten Start. */
        fun unknown(): DeviceState = DeviceState(
            batteryPercent = 100,
            isCharging = false,
            thermalStatus = ThermalStatus.NONE,
            network = NetworkState.WIFI,
            loadedModelIds = emptySet(),
            availableMemoryBytes = 5L * 1024 * 1024 * 1024,
        )
    }
}
