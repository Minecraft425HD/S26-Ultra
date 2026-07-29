package de.neon.router

/** Warum eine Anfrage nachgezogen werden soll. */
enum class EscalationSignal {
    /** Kein Anlass. */
    KEINE,

    /** Das Modell hat selbst signalisiert, dass es sich nicht sicher ist. */
    MODELL_UNSICHER,

    /** Der Nutzer hat ausdrücklich um eine gründlichere Antwort gebeten. */
    NUTZER_VERLANGT,

    /** Ein Werkzeugaufruf ist gescheitert — meist ein zu schwaches Modell. */
    WERKZEUG_GESCHEITERT,
}

/**
 * Stufe 4: erst klein antworten, nur im Zweifel groß nachlegen.
 *
 * Das ist die Umkehrung der naheliegenden Reihenfolge und genau deshalb sparsam: Der
 * teure Denker läuft nur für die Fragen, bei denen das Alltagsmodell tatsächlich an seine
 * Grenze stößt — nicht vorsorglich für alle.
 */
class EscalationPolicy(
    private val registry: ModelRegistry,
    private val policy: SelectionPolicy,
) {

    /**
     * Liefert die nachgezogene Auswahl oder `null`, wenn nicht eskaliert werden soll.
     *
     * Es wird nie zweimal eskaliert: Ist die vorherige Auswahl bereits das Ergebnis einer
     * Eskalation, bleibt es dabei.
     */
    fun escalate(
        previous: ModelSelection,
        signal: EscalationSignal,
        state: DeviceState,
    ): ModelSelection? {
        if (signal == EscalationSignal.KEINE) return null

        // Der Nutzerwunsch sticht die Sparregeln — aber nur, solange das Gerät nicht
        // wegen Hitze drosselt. Ein heißes Gerät noch weiter zu belasten hilft niemandem.
        val userOverride = signal == EscalationSignal.NUTZER_VERLANGT &&
            !state.thermalStatus.isThrottling

        if (!previous.allowEscalation && !userOverride) return null

        val raised = previous.analysis.copy(
            complexity = (previous.analysis.complexity + 1)
                .coerceAtMost(RouteAnalysis.MAX_COMPLEXITY),
        )

        // Beim Nutzerwunsch wird der Sparmodus für diese eine Anfrage ausgeblendet.
        val effectiveState = if (userOverride && state.isConstrained) {
            state.copy(batteryPercent = 100, isCharging = true, thermalStatus = ThermalStatus.NONE)
        } else {
            state
        }

        val next = policy.select(raised, effectiveState)
        if (next.model.id == previous.model.id) return null

        // Ein kleineres Modell ist keine Eskalation.
        if (next.model.maxComplexity <= previous.model.maxComplexity) return null

        return next.copy(
            reason = "${next.reason} — nachgezogen (${signal.name.lowercase()})",
            allowEscalation = false,
        )
    }

    /**
     * Das stärkste Modell, das dieses Gerät derzeit tragen kann **und** das da ist.
     *
     * „Verfügbar" hieß hier lange nur „passt in den Speicher". Ein Modell, dessen Datei nie
     * heruntergeladen wurde, passt aber vorzüglich in den Speicher — und wurde deshalb als
     * stärkste Wahl gemeldet, obwohl es damit nicht antworten konnte.
     */
    fun strongestAvailable(state: DeviceState): ModelSpec {
        val vorhanden = state.restrictToAvailable(registry.generativeModels()) { it }
        return vorhanden
            .filter { state.fitsInMemory(it) }
            .maxByOrNull { it.maxComplexity }
            ?: vorhanden.minBy { it.sizeBytes }
    }
}
