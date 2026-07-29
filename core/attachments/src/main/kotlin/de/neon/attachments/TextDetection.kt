package de.neon.attachments

/**
 * Entscheidet, ob eine Datei als Text durchgeht.
 *
 * **Warum nicht an der Endung.** Eine Endungsliste ist immer unvollständig: `.kt`, `.kts`,
 * `.gradle`, `.toml`, `.yml`, `.conf`, `.env`, `.log`, `Dockerfile`, `Makefile`, `.gitignore`
 * — und morgen kommt etwas dazu, das noch niemand kennt. Umgekehrt heißt `.dat` mal Text und
 * mal nicht. Der Inhalt weiß es besser als der Name.
 *
 * **Woran es erkannt wird.** Zwei Merkmale, die zusammen sehr zuverlässig sind und beide auf
 * den ersten Kilobytes arbeiten, also auch bei einer 100-MB-Datei nichts kosten:
 *
 *  - **Nullbytes.** In Text kommen sie praktisch nie vor, in fast jedem Binärformat sofort.
 *  - **Gültiges UTF-8.** Wer die Probe dekodiert, ohne auf Ersatzzeichen zu laufen, hat Text.
 *
 * Dazu ein Zugeständnis an die Wirklichkeit: Eine Byte-Reihenfolge-Marke am Anfang wird
 * erkannt und übersprungen, und UTF-16 wird als Text angenommen, wenn die Marke es sagt.
 * Windows-Werkzeuge erzeugen beides regelmäßig.
 */
object TextDetection {

    /** So viel wird angesehen. Mehr bringt keine bessere Entscheidung. */
    const val PROBE_BYTES = 8192

    /**
     * Ab welchem Anteil Ersatzzeichen die Probe als Binärdatei gilt.
     *
     * Nicht null: Eine ansonsten saubere Logdatei mit einem einzelnen kaputten Byte ist
     * immer noch besser lesbar als gar nicht — und genau solche Dateien hängt man an, wenn
     * etwas schiefgegangen ist.
     */
    const val MAX_REPLACEMENT_SHARE = 0.02

    private val UTF16_LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16_BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun isText(probe: ByteArray): Boolean {
        if (probe.isEmpty()) return true

        if (probe.beginntMit(UTF16_LE) || probe.beginntMit(UTF16_BE)) return true

        val nutzdaten = if (probe.beginntMit(UTF8_BOM)) probe.copyOfRange(3, probe.size) else probe
        if (nutzdaten.isEmpty()) return true

        // Ein Nullbyte genügt. Kein verbreitetes Textformat enthält eines.
        if (nutzdaten.any { it == 0.toByte() }) return false

        val text = nutzdaten.decodeToString()

        // Das letzte Zeichen kann eine abgeschnittene Mehrbyte-Folge sein — die Probe endet
        // ja mitten in der Datei. Das darf nicht gegen sie zählen.
        val zuPruefen = if (text.isNotEmpty() && text.last() == ERSATZZEICHEN) text.dropLast(1) else text
        if (zuPruefen.isEmpty()) return true

        val ersatz = zuPruefen.count { it == ERSATZZEICHEN }
        return ersatz.toDouble() / zuPruefen.length <= MAX_REPLACEMENT_SHARE
    }

    /** Liest die Probe und entscheidet in einem Zug. */
    fun isText(source: AttachmentSource): Boolean = runCatching {
        source.open().use { strom ->
            val puffer = ByteArray(PROBE_BYTES)
            val gelesen = strom.readNBytes(puffer, 0, PROBE_BYTES)
            isText(puffer.copyOf(gelesen))
        }
    }.getOrDefault(false)

    /** Entfernt eine führende Byte-Reihenfolge-Marke, die sonst als Zeichen im Text landet. */
    fun ohneBom(text: String): String = text.removePrefix("﻿")

    private const val ERSATZZEICHEN = '�'

    private fun ByteArray.beginntMit(praefix: ByteArray): Boolean =
        size >= praefix.size && praefix.indices.all { this[it] == praefix[it] }
}
