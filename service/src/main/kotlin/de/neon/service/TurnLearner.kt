package de.neon.service

import de.neon.router.EmbeddingProvider
import de.neon.router.FeedbackLearner
import de.neon.router.KnnClassifier
import de.neon.router.LabeledExample
import de.neon.router.RouteOutcome
import de.neon.router.SignalInference
import de.neon.router.UserSignal

/**
 * Macht aus dem Gesprächsverlauf Trainingsbeispiele für Stufe 1 des Routers.
 *
 * Der Kniff: Ob eine Antwort gut war, weiß man erst, wenn die nächste Frage kommt. Wer
 * dieselbe Sache kurz darauf noch einmal fragt, war unzufrieden — wer etwas anderes fragt
 * oder schweigt, offenbar nicht. Deshalb wird jeder Durchgang zurückgehalten und erst beim
 * *nächsten* bewertet.
 *
 * Niemand bewertet freiwillig die Antworten eines Assistenten. Diese stille Auswertung ist
 * die einzige Rückmeldung, die im Alltag tatsächlich anfällt.
 */
class TurnLearner(
    private val embeddings: EmbeddingProvider,
    private val learner: FeedbackLearner = FeedbackLearner(),
    /** Wohin ein neu gelerntes Beispiel geht — im Betrieb in den Router und in die Datenbank. */
    private val onLearned: (LabeledExample) -> Unit,
) {

    private class Pending(val outcome: RouteOutcome, val embedding: FloatArray)

    private var pending: Pending? = null

    /** Wie viele Beispiele diese Sitzung beigesteuert hat. Für den Diagnose-Screen. */
    var learnedCount: Int = 0
        private set

    /**
     * Eine neue Äußerung ist da — damit lässt sich der vorherige Durchgang bewerten.
     *
     * Muss vor dem Routen aufgerufen werden, damit ein daraus gelerntes Beispiel schon der
     * aktuellen Anfrage zugutekommt.
     */
    fun onNewUtterance(text: String, timestampMillis: Long) {
        val previous = pending ?: return
        pending = null

        val embedding = embeddings.embed(text)
        val similarity = KnnClassifier.cosineSimilarity(embedding, previous.embedding)
        val signal = SignalInference.infer(
            previous = previous.outcome,
            nextText = text,
            nextTimestampMillis = timestampMillis,
            similarityToPrevious = similarity,
        )

        emit(previous, signal)
    }

    /** Ein Durchgang ist fertig. Er wird zurückgehalten, bis die nächste Äußerung ihn bewertet. */
    fun onTurnCompleted(outcome: RouteOutcome) {
        pending = Pending(outcome, embeddings.embed(outcome.utteranceText))
    }

    /**
     * Kommt keine weitere Frage — etwa weil Neon beendet wird — gilt der letzte Durchgang
     * als in Ordnung. Kein Widerspruch ist die häufigste Form von Zustimmung.
     */
    fun flush() {
        val previous = pending ?: return
        pending = null
        emit(previous, UserSignal.ZUFRIEDEN)
    }

    private fun emit(previous: Pending, signal: UserSignal) {
        val example = learner.exampleFrom(
            outcome = previous.outcome.copy(signal = signal),
            embedding = previous.embedding,
        ) ?: return

        learnedCount++
        onLearned(example)
    }
}
