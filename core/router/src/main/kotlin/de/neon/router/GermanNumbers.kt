package de.neon.router

/**
 * Erkennt deutsche Zahlwörter von 0 bis 99 sowie Ziffernfolgen.
 *
 * Reicht für alles, was per Sprache an Zahlen anfällt: Timer, Weckzeiten, Lautstärke.
 * Bewusst klein gehalten — hier soll keine allgemeine Zahlengrammatik entstehen.
 */
internal object GermanNumbers {

    private val units = mapOf(
        "null" to 0,
        "ein" to 1, "eine" to 1, "einen" to 1, "einem" to 1, "eins" to 1,
        "zwei" to 2, "zwo" to 2,
        "drei" to 3,
        "vier" to 4,
        "fünf" to 5, "fuenf" to 5,
        "sechs" to 6,
        "sieben" to 7,
        "acht" to 8,
        "neun" to 9,
    )

    private val teens = mapOf(
        "zehn" to 10,
        "elf" to 11,
        "zwölf" to 12, "zwoelf" to 12,
        "dreizehn" to 13,
        "vierzehn" to 14,
        "fünfzehn" to 15, "fuenfzehn" to 15,
        "sechzehn" to 16,
        "siebzehn" to 17,
        "achtzehn" to 18,
        "neunzehn" to 19,
    )

    private val tens = mapOf(
        "zwanzig" to 20,
        "dreißig" to 30, "dreissig" to 30,
        "vierzig" to 40,
        "fünfzig" to 50, "fuenfzig" to 50,
        "sechzig" to 60,
        "siebzig" to 70,
        "achtzig" to 80,
        "neunzig" to 90,
    )

    /** Ein einzelnes Wort oder eine Ziffernfolge in eine Zahl umwandeln. */
    fun parse(token: String): Int? {
        val word = token.trim().lowercase()
        if (word.isEmpty()) return null

        word.toIntOrNull()?.let { return it }

        units[word]?.let { return it }
        teens[word]?.let { return it }
        tens[word]?.let { return it }

        // Zusammengesetzt: "einundzwanzig", "fünfundvierzig".
        val undIndex = word.indexOf("und")
        if (undIndex > 0 && undIndex + 3 < word.length) {
            val unit = units[word.substring(0, undIndex)]
            val ten = tens[word.substring(undIndex + 3)]
            if (unit != null && ten != null) return ten + unit
        }
        return null
    }

    /**
     * Wörter, die im Deutschen gleichzeitig unbestimmter Artikel und Zahlwort sind.
     *
     * In "stell mir **einen** Timer auf fünfundzwanzig Minuten" ist "einen" ein Artikel und
     * nicht die Zahl 1. Ohne diese Unterscheidung würde jeder solche Timer auf eine Minute
     * gesetzt.
     */
    private val articleForms = setOf("ein", "eine", "einen", "einem")

    /** Die erste Zahl in einer Wortfolge finden — inklusive bloßer Artikel. */
    fun findFirst(tokens: List<String>): IndexedValue<Int>? = findAll(tokens).firstOrNull()

    /** Alle Zahlen einer Wortfolge mit ihrer Position. */
    fun findAll(tokens: List<String>): List<IndexedValue<Int>> =
        tokens.mapIndexedNotNull { index, token ->
            parse(token)?.let { IndexedValue(index, it) }
        }

    /**
     * Die Zahl finden, die tatsächlich eine Menge angibt.
     *
     * Zuerst zählt eine Zahl, auf die unmittelbar eine passende Einheit folgt ("fünf
     * *Minuten*"). Gibt es keine, gilt die erste Zahl, die kein bloßer Artikel ist. Damit
     * funktionieren sowohl "timer auf zehn" als auch "stell mir einen timer auf zehn minuten".
     */
    fun findQuantity(
        tokens: List<String>,
        isUnit: (String) -> Boolean,
    ): IndexedValue<Int>? {
        val candidates = findAll(tokens)
        candidates.firstOrNull { isUnit(tokens.getOrElse(it.index + 1) { "" }) }?.let { return it }
        return candidates.firstOrNull { tokens[it.index] !in articleForms }
    }
}
