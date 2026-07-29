package de.neon.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Der Router darf nur unter dem wählen, was wirklich auf dem Telefon liegt.
 *
 * Diese Tests beschreiben den Zustand, in dem Neon tatsächlich in Betrieb geht: Von den
 * sechs Modellen der Startaufstellung ist zunächst genau eines importiert. Vorher gewann
 * bei jeder schweren Frage der Denker, dessen Datei es gar nicht gibt — und Neon antwortete
 * „das Modell ist noch nicht heruntergeladen", obwohl das Alltagsmodell bereitlag und die
 * Frage ordentlich beantwortet hätte.
 */
class AvailableModelsTest {

    private val registry = ModelRegistry.defaultForS26Ultra()
    private val policy = SelectionPolicy(registry)
    private val escalation = EscalationPolicy(registry, policy)

    private val alltag = "qwen3-4b-instruct"
    private val denker = "qwen3-8b-thinking"
    private val code = "qwen3-coder-7b"

    /** Der Alltag nach dem ersten Import: ein einziges Modell auf der Platte. */
    private fun nurAlltagsmodell(
        loaded: Set<String> = emptySet(),
    ) = DeviceState.unknown().copy(
        loadedModelIds = loaded,
        availableModelIds = setOf(alltag),
    )

    private fun analysis(category: TaskCategory, complexity: Int) = RouteAnalysis(
        category = category,
        complexity = complexity,
        confidence = 0.9,
        source = AnalysisSource.KNN,
    )

    @Test
    fun `eine schwere Frage geht ans vorhandene Modell statt an ein fehlendes`() {
        val wahl = policy.select(
            analysis(TaskCategory.LOGIK_MATHE, complexity = 5),
            nurAlltagsmodell(),
        )

        assertEquals(alltag, wahl.model.id, "gewählt wurde stattdessen ${wahl.model.id}")

        // Der Grund steht im Diagnose-Screen. Ohne diesen Zusatz sähe es dort so aus, als
        // hielte der Router eine schwere Frage für einen Fall fürs kleine Modell.
        assertTrue(
            wahl.reason.contains("nur importierte Modelle"),
            "der Grund verschweigt die Einschränkung: ${wahl.reason}",
        )
    }

    @Test
    fun `eine Programmierfrage ebenso`() {
        val wahl = policy.select(
            analysis(TaskCategory.CODE, complexity = 4),
            nurAlltagsmodell(),
        )

        assertEquals(alltag, wahl.model.id)
    }

    @Test
    fun `ein fehlendes Modell taucht auch nicht unter den Kandidaten auf`() {
        val wahl = policy.select(
            analysis(TaskCategory.WISSENSFRAGE, complexity = 5),
            nurAlltagsmodell(),
        )

        val ids = wahl.candidates.map { it.model.id }
        assertFalse(denker in ids, "der Denker steht noch zur Wahl: $ids")
        assertFalse(code in ids, "das Codemodell steht noch zur Wahl: $ids")
    }

    @Test
    fun `es wird nicht nachgezogen, wenn es nichts Staerkeres gibt`() {
        // Sonst kündigt Neon eine Eskalation an, die zwangsläufig ins Leere läuft.
        val wahl = policy.select(
            analysis(TaskCategory.LOGIK_MATHE, complexity = 3),
            nurAlltagsmodell(),
        )

        assertFalse(wahl.allowEscalation, "nachziehen worauf? es gibt nur ein Modell")
    }

    @Test
    fun `mit dem Denker auf der Platte wird wieder nachgezogen`() {
        val state = DeviceState.unknown().copy(availableModelIds = setOf(alltag, denker))

        // Dieselbe Ausgangslage wie in EscalationPolicyTest: eine mittelschwere
        // Wissensfrage landet beim Alltagsmodell. Bei einer Logikfrage gewinnt der Denker
        // von sich aus, und dann gäbe es nichts mehr nachzuziehen.
        val wahl = policy.select(analysis(TaskCategory.WISSENSFRAGE, complexity = 3), state)
        assertEquals(alltag, wahl.model.id, "Voraussetzung des Tests: erst das kleine Modell")
        assertTrue(wahl.allowEscalation, "der Denker liegt bereit, also muss es gehen")

        val nachgezogen = escalation.escalate(
            previous = wahl,
            signal = EscalationSignal.NUTZER_VERLANGT,
            state = state,
        )
        assertEquals(denker, nachgezogen?.model?.id)
    }

    @Test
    fun `das staerkste Modell ist eines, das es gibt`() {
        assertEquals(alltag, escalation.strongestAvailable(nurAlltagsmodell()).id)
    }

    @Test
    fun `ohne Angabe bleibt alles wie bisher`() {
        // Der Standardwert ist null und heißt „unbekannt", nicht „keins". Ein Aufrufer, der
        // die Verfügbarkeit nicht kennt, soll nicht plötzlich ohne Modell dastehen.
        val ohneAngabe = DeviceState.unknown()
        assertEquals(null, ohneAngabe.availableModelIds)

        val wahl = policy.select(analysis(TaskCategory.LOGIK_MATHE, complexity = 5), ohneAngabe)
        assertEquals(denker, wahl.model.id, "ohne Angabe muss der Denker gewinnen wie zuvor")
    }

    @Test
    fun `ist gar nichts da, wird trotzdem etwas gewaehlt`() {
        // Damit der Gesprächsablauf seine ehrliche Ansage machen kann („noch nicht
        // heruntergeladen") — eine leere Auswahl wäre ein Absturz statt einer Antwort.
        val leer = DeviceState.unknown().copy(availableModelIds = emptySet())

        val wahl = policy.select(analysis(TaskCategory.SMALLTALK, complexity = 1), leer)
        assertTrue(wahl.model.id.isNotBlank())
    }

    @Test
    fun `ein bereits geladenes Modell gilt weiterhin als vorhanden`() {
        // Hysterese und Verfügbarkeit dürfen sich nicht widersprechen: Was läuft, ist da.
        val state = nurAlltagsmodell(loaded = setOf(alltag))

        val wahl = policy.select(analysis(TaskCategory.SMALLTALK, complexity = 1), state)
        assertEquals(alltag, wahl.model.id)
        assertTrue(wahl.reason.contains("bereits geladen"), "Grund: ${wahl.reason}")
    }
}
