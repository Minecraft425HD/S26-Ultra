package de.neon.router

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Misst, wie gut der Router tatsächlich einsortiert.
 *
 * Alle Prüfäußerungen sind **neu** — keine steht in [SeedExamples]. Das ist der
 * entscheidende Punkt: Ein Test gegen die Trainingsmenge würde nur beweisen, dass der
 * kNN sich selbst wiederfindet.
 *
 * Die Schwelle ist bewusst nicht bei hundert Prozent angesetzt. Das lexikalische
 * Einbettungsverfahren kann Bedeutung nicht erfassen, und einige Äußerungen sind auch für
 * einen Menschen mehrdeutig. Der Test hält die erreichte Güte fest, damit eine
 * Verschlechterung auffällt — er behauptet nicht, das Problem sei gelöst.
 */
class RouterQualityTest {

    private val embeddings = HashingEmbeddingProvider()

    private fun classifier() = KnnClassifier(
        examples = SeedExamples.materialize(embeddings),
        k = 5,
        minSimilarity = 0.30,
        minMargin = 0.10,
    )

    /** Äußerungen, die nicht in der Startmenge vorkommen. */
    private val heldOut: List<Pair<String, TaskCategory>> = listOf(
        // Wissensfragen
        "wie hoch ist der kölner dom" to TaskCategory.WISSENSFRAGE,
        "wer hat die glühbirne erfunden" to TaskCategory.WISSENSFRAGE,
        "was ist die hauptstadt von finnland" to TaskCategory.WISSENSFRAGE,
        "erklär mir wie eine waschmaschine funktioniert" to TaskCategory.WISSENSFRAGE,
        "warum ist das meer salzig" to TaskCategory.WISSENSFRAGE,

        // Code
        "schreib mir ein python skript das ordner sortiert" to TaskCategory.CODE,
        "wie sortiere ich eine liste in java" to TaskCategory.CODE,
        "schreib eine funktion die fibonacci berechnet" to TaskCategory.CODE,
        "warum kompiliert mein kotlin programm nicht" to TaskCategory.CODE,
        "wie schreibe ich eine while schleife in python" to TaskCategory.CODE,

        // Logik und Mathematik
        "was ist neunzehn mal siebenundzwanzig" to TaskCategory.LOGIK_MATHE,
        "löse die gleichung drei x minus vier gleich elf" to TaskCategory.LOGIK_MATHE,
        "wie viel prozent sind fünfzig von zweihundertfünfzig" to TaskCategory.LOGIK_MATHE,
        "plane mir eine viertägige reise nach wien mit budget" to TaskCategory.LOGIK_MATHE,

        // Aktuelles aus dem Netz
        "wie wird das wetter am wochenende" to TaskCategory.WEB_AKTUELL,
        "was gibt es heute für schlagzeilen" to TaskCategory.WEB_AKTUELL,
        "wie steht der dow jones gerade" to TaskCategory.WEB_AKTUELL,
        "gibt es stau auf der a7" to TaskCategory.WEB_AKTUELL,

        // Persönliches
        "merk dir dass ich keine oliven mag" to TaskCategory.PERSOENLICH,
        "was hatte ich dir über meinen umzug erzählt" to TaskCategory.PERSOENLICH,
        "wie heißt nochmal mein zahnarzt" to TaskCategory.PERSOENLICH,

        // Smalltalk
        "erzähl mir einen anderen witz" to TaskCategory.SMALLTALK,
        "guten abend" to TaskCategory.SMALLTALK,

        // Bild
        "was ist auf diesem foto zu sehen" to TaskCategory.BILD,
        "welcher vogel ist das" to TaskCategory.BILD,
    )

    @Test
    fun `ordnet unbekannte Aeusserungen ueberwiegend richtig ein`() {
        val classifier = classifier()

        var correct = 0
        var unsure = 0
        val mistakes = mutableListOf<String>()

        for ((text, expected) in heldOut) {
            val result = classifier.classify(embeddings.embed(text))
            when {
                result == null -> {
                    unsure++
                    mistakes += "unsicher: \"$text\" (erwartet $expected)"
                }

                result.category == expected -> correct++

                else -> mistakes += "falsch:   \"$text\" -> ${result.category}, erwartet $expected"
            }
        }

        val accuracy = correct.toDouble() / heldOut.size
        val report = buildString {
            appendLine()
            appendLine("Treffer: $correct von ${heldOut.size} (${(accuracy * 100).toInt()} %)")
            appendLine("davon unsicher (geht an Stufe 2): $unsure")
            mistakes.forEach { appendLine("  $it") }
        }

        // Unsichere Fälle sind kein Fehler: Sie werden an das Router-Modell weitergereicht.
        // Als Fehler zählt nur eine falsche, aber selbstbewusste Zuordnung.
        val wrong = heldOut.size - correct - unsure
        assertTrue(
            accuracy >= MIN_ACCURACY,
            "Trefferquote unter $MIN_ACCURACY gefallen$report",
        )
        assertTrue(
            wrong <= MAX_CONFIDENT_MISTAKES,
            "zu viele selbstbewusste Fehlgriffe$report",
        )
    }

    @Test
    fun `Geraetebefehle erreichen den kNN gar nicht erst`() {
        // Sie werden bereits von Stufe 0 abgefangen. Dieser Test sichert die Arbeitsteilung
        // ab: Käme ein solcher Befehl beim kNN an, wäre bereits Energie verschwendet.
        val matcher = RuleMatcher()
        listOf(
            "licht aus",
            "timer zehn minuten",
            "wie spät ist es",
            "taschenlampe an",
            "stell den wecker auf 6:30",
        ).forEach { text ->
            assertTrue(matcher.match(Utterance(text)) != null, "Stufe 0 verpasst: $text")
        }
    }

    private companion object {
        /**
         * Gemessen wurden 88 Prozent (22 von 25) mit einem selbstbewussten Fehlgriff.
         * Die Schwelle liegt mit Abstand darunter, damit der Test eine echte
         * Verschlechterung anzeigt und nicht bei jeder Formulierungsänderung rot wird.
         *
         * Der eine Fehlgriff — "wer hat die glühbirne erfunden" landet bei SMALLTALK —
         * zeigt genau die Grenze des Verfahrens: "erfunden" und "entwickelt" teilen sich
         * keine Buchstaben, obwohl sie dasselbe meinen. Ein neuronaler Einbetter löst das,
         * ein lexikalischer nicht. Der Fall bleibt bewusst im Test stehen.
         */
        const val MIN_ACCURACY = 0.80

        /** Falsche, aber zuversichtliche Zuordnungen sind das eigentliche Problem. */
        const val MAX_CONFIDENT_MISTAKES = 3
    }
}
