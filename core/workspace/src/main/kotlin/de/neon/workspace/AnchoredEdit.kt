package de.neon.workspace

/**
 * Eine Änderung, die nicht danebengreifen kann.
 *
 * **Warum nicht einfach die Datei neu schreiben lassen.** Der naheliegende Weg wäre, das
 * Modell die geänderte Datei vollständig ausgeben zu lassen. Das ist aus drei Gründen falsch:
 *
 *  - **Es kostet die Datei.** Auf diesem Gerät entstehen 15 bis 17 Token je Sekunde. Eine
 *    Datei mit 400 Zeilen sind gut 4000 Token, also über vier Minuten — für eine Änderung von
 *    zwei Zeilen. Und wird das Token-Budget vorher aufgebraucht, ist die Datei **abgeschnitten**.
 *  - **Es macht stille Änderungen.** Ein Modell, das eine Datei nachschreibt, ändert dabei
 *    Kleinigkeiten mit, an die niemand gedacht hat. Was man nicht sieht, prüft man nicht.
 *  - **Es lässt sich nicht prüfen.** Eine ganze Datei anzunehmen heißt, dem Modell zu
 *    glauben. Ein verankerter Austausch dagegen sagt selbst, ob er gepasst hat.
 *
 * **Die Verankerung.** Angegeben wird der alte Text, der ersetzt werden soll. Kommt er
 * **genau einmal** vor, wird ersetzt. Kommt er nicht vor oder mehrfach, passiert nichts und
 * der Grund wird benannt. Das ist der ganze Trick: Die Bedingung ist prüfbar, bevor
 * geschrieben wird — und ein Modell, das sich den Dateiinhalt falsch merkt, scheitert laut
 * statt an der falschen Stelle zu schreiben.
 *
 * Reine Funktionen ohne Android und ohne Dateisystem, damit jeder Fall ohne Gerät
 * festzuhalten ist.
 */
object AnchoredEdit {

    /** Wie eine Änderung ausgegangen ist. */
    sealed interface Result {
        /** Ersetzt. [text] ist der neue Inhalt, [zeile] die 1-basierte Fundzeile. */
        data class Geaendert(val text: String, val zeile: Int) : Result

        /**
         * Der Anker steht nicht in der Datei.
         *
         * Fast immer heißt das: Das Modell hat den Inhalt aus dem Gedächtnis zitiert statt aus
         * der Datei. [aehnlichsteZeile] hilft beim nächsten Versuch.
         */
        data class NichtGefunden(val aehnlichsteZeile: Int?) : Result

        /**
         * Der Anker steht mehrfach da — welche Stelle gemeint ist, ist nicht entschieden.
         *
         * Zu raten wäre hier das Schlimmste: Eine Änderung an der falschen von vier gleichen
         * Stellen fällt erst beim Übersetzen auf, wenn überhaupt.
         */
        data class Mehrdeutig(val treffer: List<Int>) : Result
    }

    /**
     * Ersetzt [alt] durch [neu], wenn [alt] genau einmal in [inhalt] vorkommt.
     *
     * @param alt der Anker. Leer ist unzulässig — das würde an einer beliebigen Stelle
     *   einfügen, und „beliebig" ist bei einer Quelldatei keine Angabe.
     */
    fun ersetze(inhalt: String, alt: String, neu: String): Result {
        require(alt.isNotEmpty()) { "Der zu ersetzende Text darf nicht leer sein" }

        val treffer = fundstellen(inhalt, alt)
        return when (treffer.size) {
            1 -> Result.Geaendert(
                text = inhalt.replaceRange(treffer.single(), treffer.single() + alt.length, neu),
                zeile = zeileVon(inhalt, treffer.single()),
            )

            0 -> Result.NichtGefunden(aehnlichsteZeile(inhalt, alt))
            else -> Result.Mehrdeutig(treffer.map { zeileVon(inhalt, it) })
        }
    }

    /**
     * Alle Anfangsstellen von [muster], ohne Überlappung.
     *
     * Ohne Überlappung, weil sich überlappende Treffer nicht getrennt ersetzen lassen — und
     * eine Trefferzahl, die höher ist als die Zahl der ersetzbaren Stellen, wäre eine
     * irreführende Auskunft.
     */
    private fun fundstellen(inhalt: String, muster: String): List<Int> {
        val stellen = mutableListOf<Int>()
        var ab = 0
        while (true) {
            val stelle = inhalt.indexOf(muster, ab)
            if (stelle < 0) return stellen
            stellen += stelle
            ab = stelle + muster.length
        }
    }

    private fun zeileVon(inhalt: String, stelle: Int): Int =
        inhalt.take(stelle).count { it == '\n' } + 1

    /**
     * Die Zeile, die dem Anker am nächsten kommt — als Hinweis, nicht als Behauptung.
     *
     * Verglichen wird die **erste** Zeile des Ankers, denn dort weicht ein aus dem Gedächtnis
     * zitierter Text am seltensten ab. Maßstab ist die Länge des gemeinsamen Anfangs; das ist
     * grob, aber es zeigt in fast allen Fällen auf die richtige Gegend, und mehr soll ein
     * Hinweis nicht.
     */
    private fun aehnlichsteZeile(inhalt: String, alt: String): Int? {
        val gesucht = alt.lineSequence().first().trim()
        if (gesucht.isEmpty()) return null

        var beste: Int? = null
        var bestesMass = 0

        inhalt.lineSequence().forEachIndexed { index, zeile ->
            val mass = gemeinsamerAnfang(zeile.trim(), gesucht)
            if (mass > bestesMass) {
                bestesMass = mass
                beste = index + 1
            }
        }

        // Unter vier Zeichen ist es keine Ähnlichkeit, sondern Zufall — jede Datei hat
        // irgendwo ein gemeinsames Leerzeichen oder eine Klammer.
        return if (bestesMass >= 4) beste else null
    }

    private fun gemeinsamerAnfang(a: String, b: String): Int {
        var laenge = 0
        while (laenge < a.length && laenge < b.length && a[laenge] == b[laenge]) laenge++
        return laenge
    }
}
