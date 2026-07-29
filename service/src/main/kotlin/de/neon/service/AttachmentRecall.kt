package de.neon.service

/**
 * Eine Fundstelle aus den Anhängen, fertig für den Prompt.
 *
 * Die Quellenangabe ist der Teil, der aus einer Antwort eine überprüfbare macht: Ohne sie
 * müsste man Neon glauben, mit ihr kann man nachsehen.
 */
data class AttachmentExcerpt(
    /** Etwa `notizen/urlaub.txt:12-34`. */
    val source: String,
    val text: String,
) {
    fun asPromptBlock(): String = "[$source]\n$text"
}

/**
 * Sucht in den angehängten Dateien die Stellen, die zu einer Frage passen.
 *
 * Aus demselben Grund eine eigene Schnittstelle wie [MemoryRecall]: Der Gesprächsablauf
 * soll weder Room noch die Zerlegung kennen. In Tests tritt eine feste Liste an die Stelle
 * des Index.
 */
fun interface AttachmentRecall {
    suspend fun recall(query: String, limit: Int): List<AttachmentExcerpt>
}

/**
 * Wie weit das Laden eines Modells ist.
 *
 * Auf dem Bildschirm wird daraus eine mitlaufende Zahl. Der Unterschied zwischen „arbeitet
 * noch" und „hängt" lässt sich sonst nicht sehen — und genau daran scheitert die Geduld,
 * wenn ein Ladevorgang eine Minute dauert und die Frist fünfeinhalb.
 */
data class LoadingStatus(
    val elapsedMillis: Long,
    val budgetMillis: Long,
    /** Die letzte aussagekräftige Zeile des Servers, falls es eine gab. */
    val lastLine: String? = null,
)
