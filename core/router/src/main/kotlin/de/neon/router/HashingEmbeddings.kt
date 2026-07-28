package de.neon.router

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Ein Einbettungsverfahren ohne Modelldatei: gehashte Zeichen-n-Gramme und Wörter.
 *
 * **Was das ist und was nicht.** Das hier misst Ähnlichkeit auf der Ebene der Buchstaben,
 * nicht der Bedeutung. "Wie hoch ist der Eiffelturm" und "Wie hoch ist der Kölner Dom"
 * landen dicht beieinander — "Hauptstadt von Norwegen" und "Wo liegt Oslo" dagegen nicht.
 * Ein echtes Einbettungsmodell wie EmbeddingGemma kann das, dieses Verfahren nicht.
 *
 * **Warum es trotzdem hier steht.** Es braucht keine Modelldatei, keinen Tokenizer und
 * keine native Bibliothek, läuft in Mikrosekunden und ist vollständig testbar. Damit
 * funktioniert Stufe 1 des Routers vom ersten Start an, statt jede Frage an den Rückfall zu
 * schicken. Für die Aufgabe — eine deutsche Äußerung in eine von acht Kategorien
 * einsortieren — trägt die Wortebene erstaunlich weit, zumal der Router aus jeder
 * Rückmeldung dazulernt.
 *
 * Die Zeichen-n-Gramme sind für das Deutsche wichtiger als für viele andere Sprachen:
 * "Programmieren", "programmiert" und "Programm" teilen sich dadurch Merkmale, obwohl es
 * drei verschiedene Wörter sind.
 *
 * Sobald ein neuronaler Einbetter verfügbar ist, tritt er über dieselbe Schnittstelle
 * [EmbeddingProvider] an diese Stelle.
 */
class HashingEmbeddingProvider(
    private val dimensions: Int = DEFAULT_DIMENSIONS,
    private val minCharGram: Int = 3,
    private val maxCharGram: Int = 5,
) : EmbeddingProvider {

    init {
        require(dimensions > 0) { "dimensions muss positiv sein" }
        require(minCharGram in 1..maxCharGram) { "ungültiger n-Gramm-Bereich" }
    }

    override fun embed(text: String): FloatArray {
        val vector = FloatArray(dimensions)
        val normalized = normalize(text)
        if (normalized.isBlank()) return vector

        val words = normalized.split(' ').filter { it.isNotBlank() }

        for (word in words) {
            // Häufige Füllwörter tragen nichts zur Kategorie bei und würden alle
            // Äußerungen künstlich ähnlich machen.
            if (word in STOP_WORDS) continue

            add(vector, "w:$word", WORD_WEIGHT)

            // Wortgrenzen mitkodieren, damit Präfixe und Suffixe erkennbar bleiben.
            val padded = "^$word$"
            for (n in minCharGram..maxCharGram) {
                if (padded.length < n) break
                for (i in 0..padded.length - n) {
                    add(vector, "c:${padded.substring(i, i + n)}", CHAR_GRAM_WEIGHT)
                }
            }
        }

        // Wortpaare fangen einen Teil der Wortstellung ein: "licht aus" gegen "aus licht".
        for (i in 0 until words.size - 1) {
            add(vector, "b:${words[i]}_${words[i + 1]}", BIGRAM_WEIGHT)
        }

        return l2Normalize(vector)
    }

    private fun add(vector: FloatArray, feature: String, weight: Float) {
        val hash = feature.hashCode()
        val index = ((hash % dimensions) + dimensions) % dimensions
        // Das Vorzeichen aus einem zweiten Bit ziehen: So heben sich Hash-Kollisionen im
        // Mittel auf, statt sich zu addieren.
        val sign = if ((hash ushr 31) and 1 == 1) -1f else 1f
        vector[index] += sign * weight
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (value in vector) sum += value.toDouble() * value
        if (sum == 0.0) return vector
        val norm = sqrt(sum).toFloat()
        for (i in vector.indices) vector[i] /= norm
        return vector
    }

    private fun normalize(text: String): String = buildString {
        for (char in text.lowercase()) {
            when {
                char.isLetterOrDigit() -> append(char)
                char.isWhitespace() -> append(' ')
                // Satzzeichen fallen weg statt Wörter zu verkleben.
                else -> append(' ')
            }
        }
    }.trim().replace(MULTI_SPACE, " ")

    companion object {
        const val DEFAULT_DIMENSIONS = 512

        private val MULTI_SPACE = Regex("\\s+")

        private const val WORD_WEIGHT = 1.0f
        private const val CHAR_GRAM_WEIGHT = 0.35f
        private const val BIGRAM_WEIGHT = 0.6f

        /**
         * Deutsche Funktionswörter, die in nahezu jeder Äußerung vorkommen.
         *
         * Ohne diese Liste wären "wie hoch ist der Eiffelturm" und "wie schreibe ich das in
         * Python" allein wegen "wie" und "ist" ähnlich — und genau diese Verwechslung soll
         * der Router ja vermeiden.
         */
        private val STOP_WORDS = setOf(
            "der", "die", "das", "den", "dem", "des",
            "ein", "eine", "einen", "einem", "einer", "eines",
            "und", "oder", "aber", "auch", "noch", "schon", "mal",
            "ist", "sind", "war", "waren", "bin", "bist", "sein",
            "hat", "habe", "haben", "hast", "hatte",
            "ich", "du", "er", "sie", "es", "wir", "ihr",
            "mir", "mich", "dir", "dich", "uns",
            "mein", "meine", "meinen", "dein", "deine",
            "zu", "in", "im", "an", "am", "auf", "für", "von", "mit", "bei",
            "als", "wenn", "dass", "sich", "so", "nicht", "nur",
            "bitte", "danke", "neon", "hey", "okay", "ok",
        )
    }
}
