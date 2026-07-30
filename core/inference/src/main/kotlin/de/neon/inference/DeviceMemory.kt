package de.neon.inference

import java.io.File

/**
 * Wie viel Arbeitsspeicher das Gerät hat und wie viel davon noch frei ist.
 *
 * **Warum das nachgesehen wird.** Die Annahme „16 GB" stammte aus einer
 * Gerätebezeichnung und wurde nie geprüft. In diesem Projekt haben ungeprüfte Annahmen
 * über das Gerät schon dreimal in die falsche Richtung gezeigt — bei der Seitengröße, beim
 * Befehlssatz und bei der Ladezeit.
 *
 * Vor allem aber: Wenn Android einen Prozess wegen Speichermangels erschlägt, hinterlässt
 * das keine Spur. Die einzige Möglichkeit, das später zu erkennen, ist, die Zahlen
 * **vorher** aufzuschreiben.
 */
data class MemoryReading(
    /** Gesamter Arbeitsspeicher in Byte, `0` wenn unbekannt. */
    val totalBytes: Long,
    /**
     * Was davon noch vergeben werden kann, ohne dass etwas ausgelagert werden muss.
     *
     * `MemAvailable` und nicht `MemFree`: Letzteres ist auf Linux fast immer klein, weil
     * der Seitencache allen ungenutzten Speicher belegt. Wer `MemFree` liest, hält jedes
     * gesunde System für erschöpft.
     */
    val availableBytes: Long,
) {
    val known: Boolean get() = totalBytes > 0

    /** Kurz und fürs Protokoll: `RAM: 3,2 von 11,7 GB frei`. */
    fun describe(): String {
        if (!known) return "RAM: nicht lesbar"
        return "RAM: ${gb(availableBytes)} von ${gb(totalBytes)} GB frei"
    }

    private fun gb(bytes: Long): String =
        String.format(java.util.Locale.GERMANY, "%.1f", bytes / 1024.0 / 1024.0 / 1024.0)
}

object DeviceMemory {

    fun read(): MemoryReading = runCatching {
        parse(File(MEMINFO).readText())
    }.getOrDefault(MemoryReading(0, 0))

    /**
     * Zerlegt `/proc/meminfo`.
     *
     * Getrennt vom Lesen, damit es sich ohne Gerät prüfen lässt — mit der echten Ausgabe
     * eines Telefons als Vorlage. Dieselbe Aufteilung wie bei [CpuFeatures].
     *
     * Die Werte stehen in Kibibyte: `MemTotal:  11720000 kB`.
     */
    fun parse(inhalt: String): MemoryReading {
        var total = 0L
        var available = 0L

        inhalt.lineSequence().forEach { zeile ->
            val name = zeile.substringBefore(':', "").trim()
            when (name) {
                "MemTotal" -> total = kibibyte(zeile)
                "MemAvailable" -> available = kibibyte(zeile)
            }
        }
        return MemoryReading(total, available)
    }

    private fun kibibyte(zeile: String): Long {
        val zahl = zeile.substringAfter(':', "").trim().takeWhile { it.isDigit() }
        return (zahl.toLongOrNull() ?: 0L) * 1024
    }

    private const val MEMINFO = "/proc/meminfo"
}
