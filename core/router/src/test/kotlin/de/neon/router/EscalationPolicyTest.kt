package de.neon.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Die Eskalation ist der Grund, warum Neon meistens mit dem kleinen Modell auskommt:
 * Erst wird günstig geantwortet, nachgelegt wird nur im Zweifel.
 */
class EscalationPolicyTest {

    private val registry = ModelRegistry.defaultForS26Ultra()
    private val policy = SelectionPolicy(registry)
    private val escalation = EscalationPolicy(registry, policy)

    private fun state(
        battery: Int = 80,
        charging: Boolean = false,
        thermal: ThermalStatus = ThermalStatus.NONE,
    ) = DeviceState.unknown().copy(
        batteryPercent = battery,
        isCharging = charging,
        thermalStatus = thermal,
        availableMemoryBytes = 6L * 1024 * 1024 * 1024,
    )

    private fun mittelschwereFrage() = RouteAnalysis(
        category = TaskCategory.WISSENSFRAGE,
        complexity = 3,
        confidence = 0.8,
        source = AnalysisSource.KNN,
    )

    @Test
    fun `ohne Anlass wird nicht eskaliert`() {
        val previous = policy.select(mittelschwereFrage(), state())
        assertNull(escalation.escalate(previous, EscalationSignal.KEINE, state()))
    }

    @Test
    fun `ein unsicheres Modell zieht das Denkmodell nach`() {
        val previous = policy.select(mittelschwereFrage(), state())
        assertEquals("qwen3-4b-instruct", previous.model.id)
        assertTrue(previous.allowEscalation)

        val escalated = escalation.escalate(previous, EscalationSignal.MODELL_UNSICHER, state())
        assertNotNull(escalated)
        assertEquals("qwen3-8b-thinking", escalated.model.id)
        assertEquals(4, escalated.analysis.complexity)
    }

    @Test
    fun `es wird nie zweimal nachgezogen`() {
        val previous = policy.select(mittelschwereFrage(), state())
        val escalated = escalation.escalate(previous, EscalationSignal.MODELL_UNSICHER, state())
        assertNotNull(escalated)
        // Die nachgezogene Auswahl erlaubt selbst keine weitere Eskalation mehr.
        assertEquals(false, escalated.allowEscalation)
        assertNull(escalation.escalate(escalated, EscalationSignal.MODELL_UNSICHER, state()))
    }

    @Test
    fun `im Sparmodus bleibt es beim kleinen Modell`() {
        val sparsam = state(battery = 12)
        val previous = policy.select(mittelschwereFrage(), sparsam)
        assertNull(escalation.escalate(previous, EscalationSignal.MODELL_UNSICHER, sparsam))
    }

    @Test
    fun `der ausdrueckliche Nutzerwunsch sticht den Sparmodus`() {
        // Wer "denk nochmal nach" sagt, hat sich für den Stromverbrauch entschieden.
        val sparsam = state(battery = 12)
        val previous = policy.select(mittelschwereFrage(), sparsam)

        val escalated = escalation.escalate(previous, EscalationSignal.NUTZER_VERLANGT, sparsam)
        assertNotNull(escalated)
        assertEquals("qwen3-8b-thinking", escalated.model.id)
    }

    @Test
    fun `bei Hitze hilft auch der Nutzerwunsch nicht`() {
        // Ein drosselndes Gerät weiter zu belasten macht die Antwort nicht besser,
        // sondern nur langsamer und heißer.
        val heiss = state(thermal = ThermalStatus.SEVERE)
        val previous = policy.select(mittelschwereFrage(), heiss)
        assertNull(escalation.escalate(previous, EscalationSignal.NUTZER_VERLANGT, heiss))
    }

    @Test
    fun `Bildanfragen werden nicht eskaliert`() {
        // Es gibt nur ein multimodales Modell — eine Eskalation liefe ins Leere.
        val bild = RouteAnalysis(
            category = TaskCategory.BILD,
            complexity = 2,
            needsVision = true,
            confidence = 0.9,
            source = AnalysisSource.KNN,
        )
        val previous = policy.select(bild, state())
        assertEquals(false, previous.allowEscalation)
        assertNull(escalation.escalate(previous, EscalationSignal.MODELL_UNSICHER, state()))
    }

    @Test
    fun `die Begruendung nennt den Anlass`() {
        val previous = policy.select(mittelschwereFrage(), state())
        val escalated = escalation.escalate(previous, EscalationSignal.WERKZEUG_GESCHEITERT, state())
        assertNotNull(escalated)
        assertTrue(escalated.reason.contains("nachgezogen"))
    }

    @Test
    fun `kennt das staerkste tragbare Modell`() {
        assertEquals("qwen3-8b-thinking", escalation.strongestAvailable(state()).id)

        // Passt der Denker nicht in den Speicher, wird ehrlich das nächstkleinere gemeldet.
        val mittel = state().copy(availableMemoryBytes = 3_500L * 1024 * 1024)
        assertEquals("gemma-3-4b-it", escalation.strongestAvailable(mittel).id)

        // Bei 3 GB fällt auch das Bildmodell heraus: Es zählt mit 3,11 GB, weil zu den
        // Gewichten die Projektordatei gehört. Ohne die kann es keine Bilder ansehen, also
        // wäre es unehrlich, nur die Gewichte zu rechnen.
        val eng = state().copy(availableMemoryBytes = 3L * 1024 * 1024 * 1024)
        assertEquals("qwen3-4b-instruct", escalation.strongestAvailable(eng).id)
    }
}
