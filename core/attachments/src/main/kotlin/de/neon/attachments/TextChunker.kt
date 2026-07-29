package de.neon.attachments

/**
 * Zerlegt eine Textdatei in Abschnitte, die einzeln auffindbar sind.
 *
 * **Warum überhaupt zerlegen.** Eine ganze Datei als Einheit taugt nicht als Fundstelle:
 * Bei einer 3000-Zeilen-Datei wäre der Treffer „irgendwo hier drin" und der Prompt sofort
 * voll. Umgekehrt verlieren zu kleine Stücke den Zusammenhang, in dem sie stehen.
 *
 * **Warum an Zeilengrenzen.** Für Quelltext, Logdateien und Konfiguration — also das
 * meiste, was man anhängt — ist die Zeile die natürliche Einheit. Mitten in einer Zeile zu
 * schneiden zerreißt Bezeichner und macht die Zeilenangabe wertlos.
 *
 * **Warum Überlappung.** Ohne sie landet eine Antwort, deren Frage genau an der Schnittkante
 * steht, in keinem der beiden Abschnitte vollständig. Die Überlappung kostet Platz und ist
 * genau dafür da.
 */
class TextChunker(
    /** Zielgröße in Wörtern. Rund 400 Wörter sind grob 550 Token. */
    private val targetWords: Int = DEFAULT_TARGET_WORDS,
    /** Wie viele Wörter der vorherige Abschnitt am Ende noch einmal mitgibt. */
    private val overlapWords: Int = DEFAULT_OVERLAP_WORDS,
) {

    init {
        require(targetWords > 0) { "targetWords muss positiv sein" }
        require(overlapWords in 0 until targetWords) {
            "overlapWords ($overlapWords) muss kleiner als targetWords ($targetWords) sein"
        }
    }

    fun chunk(fileName: String, filePath: String, content: String): List<AttachmentChunk> {
        val zeilen = TextDetection.ohneBom(content).lines()
        if (zeilen.all { it.isBlank() }) return emptyList()

        val abschnitte = mutableListOf<AttachmentChunk>()

        var startZeile = 0
        while (startZeile < zeilen.size) {
            val (endeZeile, woerter) = sammle(zeilen, startZeile)

            val text = zeilen.subList(startZeile, endeZeile + 1).joinToString("\n").trim()
            if (text.isNotBlank()) {
                abschnitte += AttachmentChunk(
                    fileName = fileName,
                    filePath = filePath,
                    // Zeilennummern zählen ab eins — so, wie jeder Editor sie anzeigt.
                    firstLine = startZeile + 1,
                    lastLine = endeZeile + 1,
                    text = text,
                )
            }

            if (endeZeile >= zeilen.lastIndex) break
            startZeile = naechsterStart(zeilen, startZeile, endeZeile, woerter)
        }

        return abschnitte
    }

    /** Nimmt Zeilen auf, bis das Wortziel erreicht ist. Mindestens eine, auch wenn sie riesig ist. */
    private fun sammle(zeilen: List<String>, von: Int): Pair<Int, Int> {
        var woerter = 0
        var index = von
        while (index < zeilen.size) {
            woerter += zeilen[index].woerter()
            if (woerter >= targetWords) return index to woerter
            index++
        }
        return zeilen.lastIndex to woerter
    }

    /**
     * Wo der nächste Abschnitt beginnt.
     *
     * Rückwärts so weit, dass etwa [overlapWords] noch einmal mitkommen — aber nie so weit,
     * dass der Anfang stehen bleibt. Ohne diese Sicherung liefe der Zerleger bei einer
     * einzelnen sehr langen Zeile endlos im Kreis.
     */
    private fun naechsterStart(zeilen: List<String>, von: Int, bis: Int, woerter: Int): Int {
        if (overlapWords == 0 || woerter <= overlapWords) return bis + 1

        var index = bis
        var gesammelt = 0
        while (index > von + 1 && gesammelt < overlapWords) {
            gesammelt += zeilen[index].woerter()
            index--
        }
        return maxOf(index + 1, von + 1)
    }

    private fun String.woerter(): Int =
        if (isBlank()) 0 else trim().split(WHITESPACE).size

    companion object {
        val WHITESPACE = Regex("\\s+")

        const val DEFAULT_TARGET_WORDS = 400
        const val DEFAULT_OVERLAP_WORDS = 80
    }
}
