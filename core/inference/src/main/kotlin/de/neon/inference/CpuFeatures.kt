package de.neon.inference

import java.io.File

/**
 * Was der Prozessor dieses Geräts kann.
 *
 * **Wozu.** `llama-server` wird für einen bestimmten Befehlssatz übersetzt. Wählt man ihn
 * zu niedrig, rechnet er quantisierte Matrizen in Einzelschritten aus — gemessen 0,71 Token
 * je Sekunde statt der erwarteten 15 bis 25. Wählt man ihn zu hoch, beendet der Kern das
 * Programm beim ersten unbekannten Befehl, und Neon ist nicht langsam, sondern weg.
 *
 * Zwischen diesen beiden Fehlern kann man nicht raten, sondern nur nachsehen. Der Kern
 * schreibt seine Merkmale nach `/proc/cpuinfo`; diese Zeile beantwortet die Frage, ob sich
 * ein weiterer Schritt lohnt — etwa `i8mm`, das noch einmal spürbar mehr brächte, aber
 * erst auf Kernen ab 2021 vorhanden ist.
 */
object CpuFeatures {

    /**
     * Die Merkmale, wie der Kern sie meldet — etwa `asimd`, `asimddp`, `i8mm`, `sve2`.
     *
     * Leer, wenn sich die Datei nicht lesen ließ. Manche Geräte beschränken den Zugriff;
     * das ist kein Fehler, aber es ist eine Auskunft weniger, und deshalb wird es vermerkt
     * statt verschluckt.
     */
    fun read(): Set<String> = runCatching {
        parse(File(CPUINFO).readText())
    }.getOrDefault(emptySet())

    /**
     * Zieht die Merkmale aus dem Inhalt von `/proc/cpuinfo`.
     *
     * Getrennt von [read], damit sich das Zerlegen ohne Gerät prüfen lässt — mit der
     * echten Ausgabe eines arm64-Telefons als Vorlage.
     */
    fun parse(inhalt: String): Set<String> = inhalt.lineSequence()
        .filter { it.substringBefore(':').trim().lowercase() in MERKMALSZEILEN }
        .flatMap { it.substringAfter(':', "").trim().split(' ') }
        .filter { it.isNotBlank() }
        .toSet()

    /**
     * Eine Zeile fürs Protokoll: was da ist und was davon zählt.
     *
     * Die drei genannten Merkmale sind die, an denen die Geschwindigkeit hängt:
     * `asimddp` ist das Skalarprodukt (`sdot`), mit dem gerade gebaut wird; `i8mm` und
     * `bf16` wären der nächste Schritt.
     */
    fun describe(merkmale: Set<String> = read()): String {
        if (merkmale.isEmpty()) {
            return "CPU-Merkmale nicht lesbar ($CPUINFO) — dann bleibt es beim sicheren Befehlssatz"
        }
        val wichtig = INTERESSANT.joinToString(", ") { name ->
            if (name in merkmale) "$name ja" else "$name nein"
        }
        return "CPU: $wichtig (${merkmale.size} Merkmale insgesamt)"
    }

    private const val CPUINFO = "/proc/cpuinfo"

    /** Wie die Merkmalszeile heißt — arm64 schreibt `Features`, x86 `flags`. */
    private val MERKMALSZEILEN = setOf("features", "flags")

    /** Die Merkmale, über die eine Bauentscheidung fällt. */
    private val INTERESSANT = listOf("asimddp", "i8mm", "bf16")
}
