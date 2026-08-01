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
    /**
     * Welche Modelldateien liegen tatsächlich auf der Platte?
     *
     * `null` heißt ausdrücklich **unbekannt** und nicht „keine": Dann wird nicht gefiltert.
     * Der Unterschied ist wichtig, weil ein Aufrufer, der diesen Wert nicht setzt, sonst
     * schlagartig gar kein Modell mehr fände.
     *
     * Ohne dieses Feld wählte die Auswahl-Policy aus der gesamten Startaufstellung, auch
     * aus Modellen, die nie heruntergeladen wurden. Der Fehler fiel erst eine Ebene später
     * auf, und Neon antwortete „das Modell ist noch nicht heruntergeladen", obwohl ein
     * brauchbares bereitlag.
     */
    val availableModelIds: Set<String>? = null,
    /**
     * Wie groß die vorhandenen Modelldateien **wirklich** sind, nach Modellkennung.
     *
     * [ModelSpec.sizeBytes] ist eine Angabe im Quelltext und damit eine Behauptung über eine
     * Datei, die jemand später importiert. Auf dem Gerät wich sie um den Faktor zwölf ab: Der
     * Eintrag `qwen3-coder-7b` nennt 4,5 GB, die importierte Datei war 378 MB — und alles,
     * was an der Größe hängt, rechnete mit der Behauptung.
     *
     * Die Folgen gingen in beide Richtungen. Zu groß geschätzt heißt: Neon lehnt ein Modell
     * wegen Speichermangels ab, das mühelos hineinpasst. Zu klein geschätzt heißt: Neon lädt
     * eines, für das der Platz nicht reicht — und Android erschlägt den Serverprozess.
     *
     * Ein leerer Eintrag heißt „nicht gemessen", nicht „null Bytes"; dann gilt weiter die
     * Angabe aus der Registry. Siehe [groesse].
     */
    val gemesseneGroessen: Map<String, Long> = emptyMap(),
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

    /**
     * Wie viel Platz dieses Modell braucht — gemessen, wo die Datei da ist.
     *
     * Die Untergrenze des Speicherbedarfs ist die Dateigröße, und die kennt man, sobald die
     * Datei auf der Platte liegt. Nur solange sie fehlt, bleibt die Registry-Angabe die
     * einzige Auskunft, und dann ist eine Schätzung besser als keine Zahl.
     */
    fun groesse(model: ModelSpec): Long = gemesseneGroessen[model.id] ?: model.sizeBytes

    fun fitsInMemory(model: ModelSpec): Boolean =
        isLoaded(model) || groesse(model) <= availableMemoryBytes

    /** Bei unbekanntem Bestand gilt jedes Modell als vorhanden — siehe [availableModelIds]. */
    fun isAvailable(model: ModelSpec): Boolean =
        availableModelIds?.contains(model.id) ?: true

    /**
     * Schränkt eine Kandidatenliste auf das Vorhandene ein.
     *
     * Bleibt dabei nichts übrig, kommt die Liste unverändert zurück. Dann ist wirklich kein
     * Modell da, und die richtige Antwort ist die ehrliche Ansage aus dem Gesprächsablauf —
     * nicht eine leere Auswahl, an der der Router scheitern würde.
     */
    fun <T> restrictToAvailable(models: List<T>, id: (T) -> ModelSpec): List<T> =
        models.filter { isAvailable(id(it)) }.ifEmpty { models }

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
