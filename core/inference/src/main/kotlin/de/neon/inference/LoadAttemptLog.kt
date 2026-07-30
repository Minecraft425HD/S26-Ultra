package de.neon.inference

import de.neon.platform.MemoryReading
import java.io.File

/**
 * Was über einen Ladeversuch aufgeschrieben wird, **bevor** er beginnt.
 *
 * **Warum vorher.** Wird ein Prozess von Android wegen Speichermangels beendet, geschieht
 * das mit SIGKILL. Kein Abwickeln, kein Handler, keine Zeile im Protokoll — der Prozess ist
 * einfach weg. Auf dem Gerät sah das so aus: sechs Anläufe, sechsmal „Neon startet", und
 * zwischen den Anläufen nichts, was verraten hätte, wohin der vorige verschwunden ist.
 *
 * Ein Abschuss kann sich nicht selbst melden. Also wird vorher aufgeschrieben, was im
 * Zweifel die Frage beantwortet: wann, wie groß, und wie viel Luft war noch.
 */
data class LoadAttempt(
    val startedAtMillis: Long,
    val modelName: String,
    val modelBytes: Long,
    val contextSize: Int,
    /** Was der Schlüssel-Wert-Speicher bei dieser Kontextgröße kostet. */
    val kvBytes: Long,
    val memory: MemoryReading,
) {
    /** Eine Zeile, die sich schreiben und wieder einlesen lässt. */
    fun serialize(): String = listOf(
        startedAtMillis.toString(),
        modelName.replace('\t', ' '),
        modelBytes.toString(),
        contextSize.toString(),
        kvBytes.toString(),
        memory.totalBytes.toString(),
        memory.availableBytes.toString(),
    ).joinToString("\t")

    /**
     * Der Satz, der im Protokoll steht, wenn dieser Versuch nicht zurückgekommen ist.
     *
     * Nennt ausdrücklich die Zahlen und nicht bloß „ist abgestürzt": Ob es Enge war, steht
     * hier oder nirgends.
     */
    fun describeAsLost(): String = buildString {
        append("der letzte Ladeversuch wurde vom System beendet — ")
        append("$modelName, ${mb(modelBytes)} MB, Kontext $contextSize")
        append(" (${mb(kvBytes)} MB Schlüssel-Wert-Speicher), ")
        append(if (memory.known) memory.describe() else "RAM war nicht lesbar")
        append(" beim Start des Versuchs")
    }

    private fun mb(bytes: Long) = bytes / 1024 / 1024

    companion object {
        fun parse(zeile: String): LoadAttempt? {
            val teile = zeile.trim().split('\t')
            if (teile.size < 7) return null
            return LoadAttempt(
                startedAtMillis = teile[0].toLongOrNull() ?: return null,
                modelName = teile[1],
                modelBytes = teile[2].toLongOrNull() ?: return null,
                contextSize = teile[3].toIntOrNull() ?: return null,
                kvBytes = teile[4].toLongOrNull() ?: return null,
                memory = MemoryReading(
                    totalBytes = teile[5].toLongOrNull() ?: 0,
                    availableBytes = teile[6].toLongOrNull() ?: 0,
                ),
            )
        }
    }
}

/**
 * Hält den laufenden Ladeversuch in einer Datei fest.
 *
 * Der Ablauf ist bewusst schlicht: [beginnen] schreibt, [gelungen] löscht. Liegt die Datei
 * beim nächsten Start noch da, ist der Prozess dazwischen gestorben — und zwar so, dass er
 * es nicht mehr sagen konnte.
 *
 * Ohne Android-Bezug, damit es sich mit einem echten Verzeichnis prüfen lässt.
 */
class LoadAttemptLog(private val datei: File) {

    /** Merkt den beginnenden Versuch. Fehler beim Schreiben sind nicht schlimm genug zum Abbruch. */
    fun beginnen(attempt: LoadAttempt) {
        runCatching {
            datei.parentFile?.mkdirs()
            datei.writeText(attempt.serialize())
        }
    }

    /** Der Versuch ist zurückgekommen — die Merkdatei hat ihren Zweck erfüllt. */
    fun gelungen() {
        runCatching { datei.delete() }
    }

    /**
     * Der Versuch, der nie zurückkam — oder `null`, wenn alles seinen Gang ging.
     *
     * Liest **und löscht**: Dieselbe Meldung soll nicht bei jedem Start wieder erscheinen.
     * Ein zweimal berichteter Fehler sieht aus wie zwei Fehler.
     */
    fun verlorenerVersuch(): LoadAttempt? {
        if (!datei.isFile) return null
        val zeile = runCatching { datei.readText() }.getOrNull()
        runCatching { datei.delete() }
        return zeile?.let { LoadAttempt.parse(it) }
    }
}
