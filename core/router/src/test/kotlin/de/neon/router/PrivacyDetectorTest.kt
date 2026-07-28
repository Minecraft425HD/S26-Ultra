package de.neon.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivacyDetectorTest {

    @Test
    fun `erkennt Finanzen Gesundheit Kontakte und Zugangsdaten`() {
        listOf(
            "wie hoch ist mein kontostand",
            "wann war ich zuletzt beim arzt",
            "such die telefonnummer von thomas",
            "wie lautet mein passwort für den router",
            "was steht in der letzten nachricht von anna",
            "welche medikamente nehme ich morgens",
        ).forEach { assertTrue(PrivacyDetector.isSensitive(it), "nicht erkannt: $it") }
    }

    @Test
    fun `erkennt Mehrwortbegriffe`() {
        assertTrue(PrivacyDetector.isSensitive("weißt du wo ich wohne"))
    }

    @Test
    fun `laesst harmlose Fragen in Ruhe`() {
        listOf(
            "wie hoch ist der eiffelturm",
            "schreib mir ein python skript",
            "wie wird das wetter morgen",
            "erzähl mir einen witz",
            "was ist die hauptstadt von norwegen",
        ).forEach { assertFalse(PrivacyDetector.isSensitive(it), "falsch erkannt: $it") }
    }

    @Test
    fun `ist unabhaengig von Gross- und Kleinschreibung und Satzzeichen`() {
        assertTrue(PrivacyDetector.isSensitive("Wie ist mein KONTOSTAND?"))
    }

    @Test
    fun `prueft auf ganzen Woertern`() {
        // "Bankdrücken" ist keine Bankangelegenheit — Teilwörter dürfen nicht auslösen.
        assertFalse(PrivacyDetector.isSensitive("wie viel sollte ich beim bankdrücken schaffen"))
    }
}

class SeedExamplesTest {

    @Test
    fun `enthaelt keine doppelten Texte`() {
        val texts = SeedExamples.all.map { it.text }
        assertTrue(
            texts.size == texts.toSet().size,
            "doppelt: " + texts.groupBy { it }.filter { it.value.size > 1 }.keys,
        )
    }

    @Test
    fun `deckt jede vergebbare Kategorie ab`() {
        val covered = SeedExamples.all.map { it.category }.toSet()
        TaskCategory.entries
            .filter { it != TaskCategory.UNBEKANNT }
            .forEach { assertTrue(it in covered, "keine Startbeispiele für $it") }
    }

    @Test
    fun `haelt die Komplexitaet im gueltigen Bereich`() {
        SeedExamples.all.forEach {
            assertTrue(
                it.complexity in RouteAnalysis.MIN_COMPLEXITY..RouteAnalysis.MAX_COMPLEXITY,
                "ungültige Komplexität bei: ${it.text}",
            )
        }
    }

    @Test
    fun `wird mit dem niedrigen Startgewicht eingebettet`() {
        val examples = SeedExamples.materialize { floatArrayOf(1f, 0f) }
        assertEquals(SeedExamples.all.size, examples.size)
        assertTrue(examples.all { it.weight == 1.0 })
    }
}
