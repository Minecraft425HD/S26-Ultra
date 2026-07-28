package de.neon.router

/** Wie der Nutzer auf eine Antwort reagiert hat. */
enum class UserSignal {
    /** Kein Widerspruch, kein Nachhaken — die Route war offenbar richtig. */
    ZUFRIEDEN,

    /** Kurz darauf fast dasselbe noch einmal gefragt. Starkes Zeichen für eine Fehlroute. */
    UMFORMULIERT,

    /** Mitten in der Antwort abgebrochen. */
    ABGEBROCHEN,

    /** Ausdrücklich um eine gründlichere Antwort gebeten. */
    ESKALATION_VERLANGT,

    UNBEKANNT,
}

/** Ein abgeschlossener Durchgang, wie er lokal protokolliert wird. */
data class RouteOutcome(
    val utteranceText: String,
    val analysis: RouteAnalysis,
    /** `null` bei einem Stufe-0-Treffer — dort lief kein Modell. */
    val modelId: String?,
    val latencyMs: Long,
    val tokensGenerated: Int,
    val signal: UserSignal,
    val timestampMillis: Long,
)

/**
 * Das Protokoll der Durchgänge.
 *
 * Auf dem Gerät liegt dahinter eine Room-Tabelle. Die Schnittstelle bleibt hier, damit der
 * Router ohne Android testbar ist.
 */
interface RouteOutcomeStore {
    fun record(outcome: RouteOutcome)
    fun recent(limit: Int = 100): List<RouteOutcome>
}

/** Speichert im Arbeitsspeicher — für Tests und für den Betrieb ohne Datenbank. */
class InMemoryRouteOutcomeStore(private val capacity: Int = 500) : RouteOutcomeStore {

    private val outcomes = ArrayDeque<RouteOutcome>()

    override fun record(outcome: RouteOutcome) {
        outcomes.addLast(outcome)
        while (outcomes.size > capacity) outcomes.removeFirst()
    }

    override fun recent(limit: Int): List<RouteOutcome> =
        outcomes.toList().takeLast(limit).asReversed()
}

/**
 * Macht aus Rückmeldungen neue Trainingsbeispiele für den kNN-Klassifikator.
 *
 * Bewusst zurückhaltend: Nur aus eindeutig positiven Signalen wird gelernt. Bei einer
 * Umformulierung weiß Neon zwar, dass die Route falsch war, aber nicht, welche richtig
 * gewesen wäre — daraus ein Beispiel zu erzeugen würde den Klassifikator verschlechtern.
 */
class FeedbackLearner(
    /** Beispiele aus echter Nutzung wiegen schwerer als die mitgelieferte Startmenge. */
    private val learnedWeight: Double = 2.0,
) {

    fun exampleFrom(outcome: RouteOutcome, embedding: FloatArray): LabeledExample? = when (outcome.signal) {
        UserSignal.ZUFRIEDEN -> {
            // Aus Regeltreffern lernt der kNN nichts: Stufe 0 fängt sie ohnehin vorher ab.
            if (outcome.analysis.source == AnalysisSource.REGELN) {
                null
            } else {
                LabeledExample(
                    text = outcome.utteranceText,
                    embedding = embedding,
                    category = outcome.analysis.category,
                    complexity = outcome.analysis.complexity,
                    weight = learnedWeight,
                )
            }
        }

        // Der Nutzer wollte mehr Tiefe: dieselbe Kategorie, aber künftig eine Stufe höher
        // einsortieren, damit gleich das stärkere Modell drankommt.
        UserSignal.ESKALATION_VERLANGT -> LabeledExample(
            text = outcome.utteranceText,
            embedding = embedding,
            category = outcome.analysis.category,
            complexity = (outcome.analysis.complexity + 1)
                .coerceAtMost(RouteAnalysis.MAX_COMPLEXITY),
            weight = learnedWeight,
        )

        UserSignal.UMFORMULIERT, UserSignal.ABGEBROCHEN, UserSignal.UNBEKANNT -> null
    }
}

/**
 * Leitet das Nutzersignal aus dem zeitlichen Verlauf ab.
 *
 * Niemand bewertet freiwillig die Antworten eines Assistenten. Die Rückmeldung muss also aus
 * dem Verhalten kommen: Wer dieselbe Frage kurz darauf noch einmal stellt, war unzufrieden.
 */
object SignalInference {

    /** Innerhalb dieser Spanne gilt eine ähnliche Frage als Umformulierung. */
    const val REPHRASE_WINDOW_MILLIS = 20_000L

    /** Ab dieser Ähnlichkeit gilt eine Äußerung als dieselbe Frage. */
    const val REPHRASE_SIMILARITY = 0.75

    /**
     * Unterhalb dieser Ähnlichkeit ist die nächste Frage klar ein anderes Thema.
     *
     * Dazwischen liegt ein Graubereich, in dem bewusst **nichts** gelernt wird. Der Grund
     * ist die Schieflage der beiden Fehler: Eine übersehene Umformulierung führt dazu, dass
     * eine nachweislich schlechte Route als gutes Beispiel abgespeichert wird — der
     * Klassifikator wird also aktiv schlechter. Ein übersehenes Lob kostet dagegen nur ein
     * Beispiel, das man ohnehin nicht gebraucht hätte.
     *
     * Gemessen an echten Formulierungen: Umformulierungen liegen bei 0,47 bis 0,85,
     * Themenwechsel unter 0,05. Die Grenze liegt in der Lücke dazwischen.
     */
    const val AMBIGUOUS_SIMILARITY = 0.30

    private val deeperRequest = Regex(
        "\\b(denk nochmal|denke nochmal|genauer|gründlicher|gruendlicher|" +
            "ausführlicher|ausfuehrlicher|streng dich an|nochmal richtig)\\b"
    )

    fun infer(
        previous: RouteOutcome?,
        nextText: String,
        nextTimestampMillis: Long,
        similarityToPrevious: Double,
    ): UserSignal {
        if (deeperRequest.containsMatchIn(nextText.lowercase())) {
            return UserSignal.ESKALATION_VERLANGT
        }
        if (previous == null) return UserSignal.UNBEKANNT

        val elapsed = nextTimestampMillis - previous.timestampMillis
        val withinWindow = elapsed in 0..REPHRASE_WINDOW_MILLIS
        if (!withinWindow) return UserSignal.ZUFRIEDEN

        return when {
            similarityToPrevious >= REPHRASE_SIMILARITY -> UserSignal.UMFORMULIERT
            // Graubereich: verwandt genug, um Zweifel zu wecken, zu unähnlich für Gewissheit.
            similarityToPrevious >= AMBIGUOUS_SIMILARITY -> UserSignal.UNBEKANNT
            else -> UserSignal.ZUFRIEDEN
        }
    }
}

/** Kennzahlen für den Diagnose-Screen. */
data class RouterStats(
    val total: Int,
    /** Anteil der Anfragen, die ganz ohne Sprachmodell beantwortet wurden. */
    val directActionShare: Double,
    val medianLatencyMs: Long,
    val perModel: Map<String, ModelStats>,
) {
    data class ModelStats(val count: Int, val medianLatencyMs: Long, val totalTokens: Int)

    companion object {
        fun from(outcomes: List<RouteOutcome>): RouterStats {
            if (outcomes.isEmpty()) {
                return RouterStats(0, 0.0, 0, emptyMap())
            }
            val direct = outcomes.count { it.modelId == null }
            val perModel = outcomes
                .filter { it.modelId != null }
                .groupBy { it.modelId!! }
                .mapValues { (_, group) ->
                    ModelStats(
                        count = group.size,
                        medianLatencyMs = median(group.map { it.latencyMs }),
                        totalTokens = group.sumOf { it.tokensGenerated },
                    )
                }
            return RouterStats(
                total = outcomes.size,
                directActionShare = direct.toDouble() / outcomes.size,
                medianLatencyMs = median(outcomes.map { it.latencyMs }),
                perModel = perModel,
            )
        }

        private fun median(values: List<Long>): Long {
            if (values.isEmpty()) return 0
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}
