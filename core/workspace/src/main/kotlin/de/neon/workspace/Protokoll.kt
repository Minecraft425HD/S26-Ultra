package de.neon.workspace

/**
 * Was die Entwicklungsumgebung ins Protokoll schreibt.
 *
 * **Warum das überhaupt eine eigene Datei ist.** Die IDE hat auf dem Gerät geschwiegen. Ein
 * Bauvorgang aus fünf Programmaufrufen, eine Python-Umgebung und eine Projektvorlage — und im
 * Protokoll stand darüber keine einzige Zeile. Als die Projekterstellung nicht funktionierte,
 * war deshalb nicht zu sagen, ob das Werkzeug gar nicht aufgerufen wurde, ob es scheiterte
 * oder ob es gelang und danach etwas anderes schieflief. Das sind drei verschiedene Fehler
 * mit drei verschiedenen Behebungen, und keiner davon war auffindbar.
 *
 * Die Vorgänge liegen in `core/workspace` und damit bewusst ohne Android — `NeonLog` ist von
 * hier aus nicht erreichbar. Deshalb nehmen sie eine Protokollfunktion entgegen, und der
 * Container hängt sie an `NeonLog`. Nebeneffekt: Im Test lässt sich mitlesen, was gemeldet
 * wird, und damit prüfen, dass die Meldung die Frage beantwortet, um die es geht.
 */
fun interface Protokoll {
    fun schreib(meldung: String)

    companion object {
        /** Schreibt nichts. Für Aufrufer, denen das Protokoll gleichgültig ist — Tests. */
        val STUMM = Protokoll { }
    }
}

/**
 * Kürzt eine Zeichenkette für das Protokoll und sagt dazu, wie viel fehlt.
 *
 * Die Angabe der Restlänge ist der Punkt. Ein Text, der einfach aufhört, sieht aus wie einer,
 * der zu Ende ist — und genau daran ließ sich ein bei 128 Token abgeschnittener Werkzeugaufruf
 * nicht von einem vollständigen unterscheiden.
 */
fun String.gekuerzt(grenze: Int): String {
    val einzeilig = replace('\n', '⏎').replace('\r', ' ')
    if (einzeilig.length <= grenze) return einzeilig
    return einzeilig.take(grenze) + "… (+${einzeilig.length - grenze} Zeichen)"
}
