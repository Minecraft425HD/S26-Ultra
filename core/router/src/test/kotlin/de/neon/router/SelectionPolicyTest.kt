package de.neon.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Die Auswahl-Policy entscheidet, wie viel Energie eine Anfrage kostet. Diese Tests halten
 * die Abwägungen fest, die dabei gelten sollen — sie sind die eigentliche Spezifikation.
 */
class SelectionPolicyTest {

    private val registry = ModelRegistry.defaultForS26Ultra()
    private val policy = SelectionPolicy(registry)

    private val kleinerAlltag = "qwen3-1.7b-instruct"
    private val alltag = "qwen3-4b-instruct"
    private val denker = "qwen3-8b-thinking"
    private val code = "qwen3-coder-7b"
    private val vision = "gemma-3-4b-it"

    private fun state(
        battery: Int = 80,
        charging: Boolean = false,
        thermal: ThermalStatus = ThermalStatus.NONE,
        loaded: Set<String> = emptySet(),
        memory: Long = 6L * 1024 * 1024 * 1024,
    ) = DeviceState(
        batteryPercent = battery,
        isCharging = charging,
        thermalStatus = thermal,
        network = NetworkState.WIFI,
        loadedModelIds = loaded,
        availableMemoryBytes = memory,
    )

    private fun analysis(
        category: TaskCategory,
        complexity: Int,
        vision: Boolean = false,
    ) = RouteAnalysis(
        category = category,
        complexity = complexity,
        needsVision = vision,
        confidence = 0.9,
        source = AnalysisSource.KNN,
    )

    @Test
    fun `Smalltalk landet beim kleinen Alltagsmodell`() {
        val selection = policy.select(analysis(TaskCategory.SMALLTALK, 1), state())

        // Seit es zwei Alltagsmodelle gibt, ist „das kleine" das 1.7B. Genau darum wurde es
        // aufgenommen: Auf einem Gerät mit sechs Gigabyte bleiben nur seine Gewichte im
        // Seitencache liegen, und beim 4-B-Modell wurden dadurch 0,22 Token je Sekunde
        // gemessen. Für eine Begrüßung das große zu wecken war schon vorher falsch — es fiel
        // nur nicht auf, weil es keine Alternative gab.
        assertEquals(kleinerAlltag, selection.model.id)
    }

    @Test
    fun `der Denker wird fuer eine Begruessung nicht geweckt`() {
        // Selbst wenn er schon geladen ist: Ein 8B-Modell für "guten Morgen" wäre die
        // teuerste Art, nichts zu gewinnen.
        val selection = policy.select(
            analysis(TaskCategory.SMALLTALK, 1),
            state(loaded = setOf(denker, alltag)),
        )
        assertEquals(alltag, selection.model.id)
    }

    @Test
    fun `hohe Komplexitaet erzwingt den Denker`() {
        val selection = policy.select(analysis(TaskCategory.LOGIK_MATHE, 5), state())
        assertEquals(denker, selection.model.id)
    }

    @Test
    fun `Hysterese - ein geladenes Modell schlaegt ein knapp besseres ungeladenes`() {
        // Bei einer Wissensfrage mittlerer Komplexität sind beide Modelle stark. Das
        // bereits geladene gewinnt, weil ein Modellwechsel mehrere Sekunden und spürbar
        // Energie kostet — der Qualitätsvorsprung wiegt das nicht auf.
        val selection = policy.select(
            analysis(TaskCategory.WISSENSFRAGE, 3),
            state(loaded = setOf(alltag)),
        )
        assertEquals(alltag, selection.model.id)
        assertTrue(selection.reason.contains("bereits geladen"))
    }

    @Test
    fun `ein klarer Qualitaetsvorsprung schlaegt die Hysterese`() {
        // Bei einer Code-Frage ist der Spezialist deutlich besser. Hier lohnt der
        // Modellwechsel trotz Ladezeit — die Hysterese darf nicht alles blockieren.
        val selection = policy.select(
            analysis(TaskCategory.CODE, 3),
            state(loaded = setOf(alltag)),
        )
        assertEquals(code, selection.model.id)
    }

    @Test
    fun `Bildfragen gehen zwingend an das multimodale Modell`() {
        val selection = policy.select(
            analysis(TaskCategory.BILD, 2, vision = true),
            state(loaded = setOf(alltag, denker)),
        )
        assertEquals(vision, selection.model.id)
    }

    @Test
    fun `im Sparmodus bleiben grosse Modelle ungeladen`() {
        val selection = policy.select(
            analysis(TaskCategory.WISSENSFRAGE, 3),
            state(battery = 15, charging = false),
        )
        assertEquals(alltag, selection.model.id)
        assertFalse(selection.constraintsRelaxed)
    }

    @Test
    fun `Hitze zaehlt wie leerer Akku`() {
        val selection = policy.select(
            analysis(TaskCategory.WISSENSFRAGE, 3),
            state(battery = 90, thermal = ThermalStatus.SEVERE),
        )
        assertEquals(alltag, selection.model.id)
    }

    @Test
    fun `im Sparmodus wird gelockert wenn sonst gar nichts passt`() {
        // Komplexität 5 kann das Alltagsmodell nicht. Lieber die Sparregel lockern als
        // dem Nutzer eine sicher schlechte Antwort geben — aber sichtbar gekennzeichnet.
        val selection = policy.select(
            analysis(TaskCategory.LOGIK_MATHE, 5),
            state(battery = 15),
        )
        assertEquals(denker, selection.model.id)
        assertTrue(selection.constraintsRelaxed)
    }

    @Test
    fun `zu wenig Speicher schliesst grosse Modelle aus`() {
        val selection = policy.select(
            analysis(TaskCategory.LOGIK_MATHE, 5),
            state(memory = 3L * 1024 * 1024 * 1024),
        )
        // Nichts Großes passt in den Speicher — dann lieber das kleinste Modell als
        // ein Absturz beim Laden.
        assertEquals(alltag, selection.model.id)
        assertTrue(selection.constraintsRelaxed)
    }

    @Test
    fun `im Sparmodus wird nicht eskaliert`() {
        val selection = policy.select(analysis(TaskCategory.WISSENSFRAGE, 2), state(battery = 10))
        assertFalse(selection.allowEscalation)
    }

    @Test
    fun `derselbe Auftrag faellt je nach Energielage anders aus`() {
        // Das ist der Kern der Akku-Strategie: identische Frage, unterschiedliche Antwort
        // auf die Frage "was darf das kosten".
        val request = analysis(TaskCategory.LOGIK_MATHE, 3)

        val amKabel = policy.select(request, state(charging = true))
        assertEquals(denker, amKabel.model.id, "am Ladegerät darf es der Denker sein")

        val imSparmodus = policy.select(request, state(battery = 15))
        assertEquals(alltag, imSparmodus.model.id, "bei 15 % Akku bleibt es beim Alltagsmodell")
    }

    @Test
    fun `liefert immer eine Begruendung und alle Kandidaten`() {
        val selection = policy.select(analysis(TaskCategory.WISSENSFRAGE, 2), state())
        assertTrue(selection.reason.isNotBlank())
        assertTrue(selection.candidates.isNotEmpty())
        // Absteigend sortiert, damit der Diagnose-Screen sie direkt anzeigen kann.
        val scores = selection.candidates.map { it.score }
        assertEquals(scores.sortedDescending(), scores)
    }

    @Test
    fun `die Punktebewertung ist nachvollziehbar aufgeschluesselt`() {
        val selection = policy.select(
            analysis(TaskCategory.CODE, 3),
            state(loaded = setOf(code)),
        )
        val winner = selection.candidates.first { it.model.id == code }
        assertEquals(0.0, winner.breakdown["ladekosten"])
        assertTrue((winner.breakdown["stärke"] ?: 0.0) > 0.0)
    }
}
