package de.neon.attachments

import de.neon.router.EmbeddingProvider
import kotlin.math.sqrt

/** Ein Abschnitt mit seinem eingebetteten Vektor — so, wie er in der Ablage steht. */
data class IndexedChunk(
    val chunk: AttachmentChunk,
    val embedding: FloatArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is IndexedChunk && other.chunk == chunk)

    override fun hashCode(): Int = chunk.hashCode()
}

/** Eine Fundstelle mit ihrer Bewertung — aufgeschlüsselt, damit nachvollziehbar bleibt, warum. */
data class RankedChunk(
    val chunk: AttachmentChunk,
    val score: Double,
    val contentScore: Double,
    val nameScore: Double,
)

/**
 * Sucht zu einer Frage die passenden Stellen in den Anhängen.
 *
 * **Warum zwei Signale.** Der Inhalt allein reicht nicht. Fragt jemand „was steht in der
 * build.gradle", dann kommt der entscheidende Hinweis aus dem *Dateinamen* — im Inhalt
 * einer Gradle-Datei steht ihr eigener Name nirgends. Umgekehrt fragt man meistens nach
 * einer Sache und nicht nach einer Datei. Beides zusammen deckt beide Fälle ab.
 *
 * **Was das Verfahren nicht kann.** [de.neon.router.HashingEmbeddingProvider] misst
 * lexikalische Ähnlichkeit, keine Bedeutung: „Auto" und „Fahrzeug" liegen darin nicht
 * beieinander. Für Dateien ist das eher ein Vorteil — Bezeichner, Fehlermeldungen und Pfade
 * treffen dadurch genau —, aber es ist eine echte Grenze und der Grund, warum ein echter
 * Einbetter weiterhin auf der Liste steht.
 */
class ChunkRanker(
    private val embeddings: EmbeddingProvider,
    /** Wie stark ein Treffer im Datei- oder Ordnernamen zählt. */
    private val nameWeight: Double = DEFAULT_NAME_WEIGHT,
    /** Darunter gilt eine Stelle als unpassend und wird gar nicht erst angeboten. */
    private val minScore: Double = DEFAULT_MIN_SCORE,
) {

    fun rank(query: String, indexed: List<IndexedChunk>, limit: Int): List<RankedChunk> {
        if (indexed.isEmpty() || query.isBlank()) return emptyList()

        val frageVektor = embeddings.embed(query)
        val frageWoerter = woerter(query)

        return indexed
            .asSequence()
            .map { eintrag ->
                val inhalt = cosine(frageVektor, eintrag.embedding)
                val name = nameTreffer(frageWoerter, eintrag.chunk)
                RankedChunk(
                    chunk = eintrag.chunk,
                    score = inhalt + nameWeight * name,
                    contentScore = inhalt,
                    nameScore = name,
                )
            }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    /**
     * Wie stark der Pfad in der Frage vorkommt.
     *
     * Der Pfad wird an allem zerlegt, was kein Buchstabe oder keine Ziffer ist — „build.gradle.kts"
     * zerfällt so in „build", „gradle", „kts". Damit trifft auch, wer nur „gradle" sagt.
     */
    private fun nameTreffer(frageWoerter: Set<String>, chunk: AttachmentChunk): Double {
        if (frageWoerter.isEmpty()) return 0.0
        val pfadWoerter = woerter(chunk.filePath.replace('/', ' ').replace('.', ' '))
        if (pfadWoerter.isEmpty()) return 0.0

        val gemeinsam = pfadWoerter.count { pfadWort ->
            pfadWort.length >= MIN_NAME_TOKEN && frageWoerter.any { passt(pfadWort, it) }
        }
        if (gemeinsam == 0) return 0.0

        // Am Pfad gemessen, nicht an der Frage: Eine lange Frage soll den Treffer nicht
        // verwässern, und ein kurzer, genau getroffener Pfad soll voll zählen.
        return gemeinsam.toDouble() / pfadWoerter.size
    }

    /**
     * Ob ein Pfadwort und ein Frageworte dasselbe meinen.
     *
     * Gleichheit genügt im Deutschen nicht. „Einkaufsliste" und „einkaufen" sind für jeden
     * Leser dieselbe Sache, für einen Zeichenkettenvergleich aber zwei verschiedene — und
     * das ist kein Randfall, sondern die Regel: Zusammensetzungen und Beugung sind der
     * Normalzustand dieser Sprache. Ein gemeinsamer Anfang von mindestens vier Zeichen
     * fängt beides ab, ohne dass „datei" und „daten" schon als gleich durchgingen.
     */
    private fun passt(pfadWort: String, frageWort: String): Boolean {
        if (pfadWort == frageWort) return true
        val kurz = minOf(pfadWort.length, frageWort.length)
        if (kurz < MIN_STAMM) return false

        var gleich = 0
        while (gleich < kurz && pfadWort[gleich] == frageWort[gleich]) gleich++
        return gleich >= MIN_STAMM
    }

    private fun woerter(text: String): Set<String> =
        text.lowercase().split(TRENNER).filter { it.isNotBlank() }.toSet()

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            normA += x * x
            normB += y * y
        }
        if (normA == 0.0 || normB == 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }

    companion object {
        val TRENNER = Regex("[^\\p{L}\\p{N}]+")

        /**
         * Ein voller Namenstreffer wiegt so viel wie eine sehr gute inhaltliche Ähnlichkeit.
         * Wer eine Datei beim Namen nennt, meint sie auch.
         */
        const val DEFAULT_NAME_WEIGHT = 0.6

        const val DEFAULT_MIN_SCORE = 0.12

        /** Ein- und zweibuchstabige Pfadteile treffen zufällig und sagen nichts. */
        const val MIN_NAME_TOKEN = 3

        /** So viele Anfangszeichen müssen übereinstimmen, damit zwei Wörter als verwandt gelten. */
        const val MIN_STAMM = 4
    }
}
