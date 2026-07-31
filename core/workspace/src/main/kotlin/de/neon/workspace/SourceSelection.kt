package de.neon.workspace

/**
 * Ein markierter Abschnitt einer Quelldatei, aufbereitet für eine Frage an das Modell.
 *
 * **Warum nicht einfach die markierten Zeichen in den Prompt.** Weil die Antwort dann falsch
 * wird. Wer drei Zeilen mitten aus einer Funktion markiert und fragt „was macht das", bekommt
 * eine Antwort über drei Zeilen ohne Zusammenhang — das Modell weiß nicht, in welcher Funktion
 * sie stehen, welche Variablen von außen kommen, in welcher Datei es überhaupt ist.
 *
 * Deshalb enthält der Prompt drei Dinge: **wo** es steht (Datei und Zeilennummern), **was**
 * markiert ist, und **etwas darum herum**. Die Zeilennummern sind nicht Zierde: Ohne sie kann
 * eine Antwort nicht auf eine Stelle zeigen, und Neon kann eine Änderung nicht anbringen.
 *
 * Reine Datenaufbereitung ohne Android und ohne Dateizugriff.
 */
data class SourceSelection(
    /** Projektrelativer Pfad, etwa `app/src/main/kotlin/Main.kt`. */
    val pfad: String,
    /** Der vollständige Dateiinhalt, aus dem der Abschnitt geschnitten wird. */
    val inhalt: String,
    /** Erste markierte Zeile, 1-basiert und einschließlich. */
    val vonZeile: Int,
    /** Letzte markierte Zeile, 1-basiert und einschließlich. */
    val bisZeile: Int,
) {

    private val zeilen: List<String> = inhalt.lines()

    /** Die Markierung, auf gültige Zeilennummern zurechtgebogen. */
    val bereich: IntRange = run {
        val von = vonZeile.coerceIn(1, maxOf(1, zeilen.size))
        val bis = bisZeile.coerceIn(von, maxOf(1, zeilen.size))
        von..bis
    }

    /** Nur der markierte Text. Das ist der Anker für eine anschließende Änderung. */
    val markiert: String
        get() = zeilen.subList(bereich.first - 1, bereich.last).joinToString("\n")

    /**
     * Der markierte Abschnitt mit Zeilennummern und etwas Umgebung, für den Prompt.
     *
     * Die markierten Zeilen sind mit `>` gekennzeichnet, die Umgebung mit einem Leerzeichen.
     * Das ist die knappste Form, die beides mitteilt: was gemeint ist und was drumherum steht.
     * Eine Erklärung in Prosa („die Zeilen 40 bis 42 sind markiert") kostet mehr Token und
     * wird von kleinen Modellen häufiger überlesen als ein Zeichen am Zeilenanfang.
     *
     * @param umgebung wie viele Zeilen vor und nach der Markierung mitgehen.
     */
    fun alsPromptBlock(umgebung: Int = UMGEBUNG): String {
        val von = (bereich.first - umgebung).coerceAtLeast(1)
        val bis = (bereich.last + umgebung).coerceAtMost(zeilen.size)

        val breite = bis.toString().length
        val text = (von..bis).joinToString("\n") { nummer ->
            val zeichen = if (nummer in bereich) '>' else ' '
            val nr = nummer.toString().padStart(breite)
            "$zeichen $nr  ${zeilen[nummer - 1]}"
        }

        return buildString {
            append("Datei ").append(pfad)
            append(", markiert sind die Zeilen ").append(bereich.first)
            if (bereich.last != bereich.first) append(" bis ").append(bereich.last)
            appendLine(" (mit > gekennzeichnet):")
            append(text)
        }
    }

    companion object {
        /**
         * Baut eine Markierung aus dem, was ein Texteingabefeld liefert.
         *
         * **Warum diese Umrechnung hier steht und nicht in der Oberfläche.** Compose kennt nur
         * Zeichenpositionen: `selection.start` und `selection.end` sind Abstände vom
         * Dateianfang. Der Prompt braucht Zeilennummern, denn nur die kann eine Antwort
         * nennen und ein Mensch wiederfinden. Die Umrechnung ist reine Textarbeit — hier ist
         * sie ohne Gerät prüfbar, in einer Composable-Funktion wäre sie es nicht.
         *
         * **Der Randfall, der zählt.** Eine Markierung, die am Zeilenanfang endet, gehört
         * nicht zu dieser Zeile. Wer drei Zeilen mit dem Finger überstreicht, markiert
         * meistens bis zum Anfang der vierten — und bekäme sonst eine Zeile mehr, als er
         * sieht. Deshalb wird das Ende um eins zurückgenommen, solange die Markierung nicht
         * dadurch leer wird.
         *
         * @param start Zeichenposition, ab der markiert ist.
         * @param ende Zeichenposition, bis zu der markiert ist (ausschließlich).
         */
        fun ausZeichenbereich(
            pfad: String,
            inhalt: String,
            start: Int,
            ende: Int,
        ): SourceSelection {
            // Erst ordnen, dann begrenzen. Wer von unten nach oben markiert, meint denselben
            // Bereich — manche Oberflächen reichen dann start > ende durch. Nur zu begrenzen
            // ließe die Markierung auf einen Punkt am falschen Ende zusammenfallen, und die
            // Frage bezöge sich dann auf eine Zeile, die niemand markiert hat.
            val von = minOf(start, ende).coerceIn(0, inhalt.length)
            val bis = maxOf(start, ende).coerceIn(von, inhalt.length)

            // Bei einer leeren Markierung — nur der Cursor — gilt die Zeile, in der er steht.
            val letztesZeichen = if (bis > von) bis - 1 else von

            return SourceSelection(
                pfad = pfad,
                inhalt = inhalt,
                vonZeile = zeileBei(inhalt, von),
                bisZeile = zeileBei(inhalt, letztesZeichen),
            )
        }

        /** Die 1-basierte Zeile, in der ein Zeichen steht. */
        private fun zeileBei(inhalt: String, stelle: Int): Int =
            inhalt.take(stelle.coerceIn(0, inhalt.length)).count { it == '\n' } + 1

        /**
         * Fünf Zeilen vor und nach der Markierung.
         *
         * Genug, um die umgebende Funktion und ihre Signatur meist mitzunehmen, und wenig
         * genug, dass es bei einem Kontextfenster von 16384 Token nicht ins Gewicht fällt.
         * Wer mehr braucht, fragt nach der ganzen Datei — dafür gibt es das Werkzeug zum
         * Lesen.
         */
        const val UMGEBUNG = 5
    }
}
