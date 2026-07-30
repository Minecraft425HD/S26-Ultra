package de.neon.service

/**
 * Entfernt Selbstgespräche aus einer Antwort.
 *
 * **Der Anlass.** Qwen3 und andere neuere Modelle stellen ihrer Antwort einen
 * `<think>`-Block voran, in dem sie laut überlegen. `llama-server` liefert den mit aus, und
 * Neon hängte jedes Token davon in die Sprechblase und las es vor.
 *
 * Abgeschaltet wird das schon am Server (`--reasoning off`, siehe `ProcessServerSupervisor`).
 * Dieser Filter ist die zweite Linie: Nicht jedes Modell hält sich an den Schalter, und ein
 * Modell, das es doch tut, kostet hier nichts. Ein Assistent, der seine Überlegungen
 * vorliest, ist unbenutzbar — und schlimmer: Auf einem Gerät mit anderthalb Token je Sekunde
 * verbraucht ein Selbstgespräch die Zeit, in der die Antwort hätte kommen sollen.
 *
 * Reine Funktionen ohne Android-Bezug, damit sich jeder Fall ohne Gerät festhalten lässt.
 */
object ThinkingFilter {

    /**
     * Der Text ohne abgeschlossene Denkblöcke.
     *
     * Ein **nicht** abgeschlossener Block wird ebenfalls entfernt: Wenn das Token-Budget
     * mitten im Überlegen aufgebraucht war, ist der Rest kein Satz, sondern ein Fragment —
     * und ein Fragment vorzulesen ist schlechter, als nichts zu sagen. Dass dann nichts
     * übrig bleibt, ist die richtige Auskunft; [istLeer] macht sie sichtbar.
     */
    fun strip(text: String): String {
        var ergebnis = text
        MARKIERUNGEN.forEach { (auf, zu) ->
            ergebnis = entferne(ergebnis, auf, zu)
        }
        return ergebnis.trim()
    }

    /**
     * Ob nach dem Filtern nichts Sprechbares übrig ist.
     *
     * Genau der Fall, der auf dem Gerät wie „die zweite Frage wurde nicht beantwortet"
     * aussieht: Der Server rechnet, meldet Token, beendet sauber — und in der Blase steht
     * nichts, weil alles Überlegung war. Wer das unterscheiden will, braucht diese Frage.
     */
    fun istLeer(text: String): Boolean = strip(text).isBlank()

    private fun entferne(text: String, auf: String, zu: String): String {
        val ergebnis = StringBuilder()
        var rest = text

        while (true) {
            val start = rest.indexOf(auf, ignoreCase = true)
            if (start < 0) {
                ergebnis.append(rest)
                return ergebnis.toString()
            }
            ergebnis.append(rest, 0, start)

            val ende = rest.indexOf(zu, startIndex = start + auf.length, ignoreCase = true)
            if (ende < 0) {
                // Kein Abschluss: Alles ab hier war Überlegung, die nie fertig wurde.
                return ergebnis.toString()
            }
            rest = rest.substring(ende + zu.length)
        }
    }

    /**
     * Die Formen, in denen Denkblöcke vorkommen.
     *
     * `<think>` benutzen Qwen3 und DeepSeek-R1, `<reasoning>` einige Abwandlungen. Die Liste
     * ist bewusst kurz: Was hier fehlt, fällt beim ersten Gebrauch auf — was hier zu viel
     * steht, schneidet stillschweigend Antworten ab.
     */
    private val MARKIERUNGEN = listOf(
        "<think>" to "</think>",
        "<reasoning>" to "</reasoning>",
    )
}
