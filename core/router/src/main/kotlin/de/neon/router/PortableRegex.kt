package de.neon.router

/**
 * Reguläre Ausdrücke, die auf dem Telefon dasselbe tun wie im Test.
 *
 * **Warum es das gibt.** Android und die JVM benutzen zwei verschiedene Regex-Maschinen.
 * Auf der JVM steht hinter `java.util.regex` die Implementierung des OpenJDK, auf Android
 * seit Nougat die von ICU. Die beiden decken sich weitgehend — aber eben nicht ganz, und
 * die Abweichungen fallen erst zur Laufzeit auf.
 *
 * Genau daran ist Neon einmal beim Start gestorben: Die Muster der Regelstufe standen mit
 * dem eingebetteten Flag `(?U)` da, damit die JVM Umlaute als Wortzeichen zählt — ohne das
 * greift `\böffne` nicht, weil vor dem „ö" keine Wortgrenze erkannt wird. ICU kennt dieses
 * Flag nicht und wirft eine [java.util.regex.PatternSyntaxException]. Da die Muster in
 * einem Klasseninitialisierer entstehen, wurde daraus ein `ExceptionInInitializerError`
 * beim Aufbau des Routers, und die App war tot, bevor irgendetwas zu sehen war.
 *
 * **Die Lösung.** Die Wortgrenze wird ausgeschrieben statt einem Flag überlassen. `\p{L}`,
 * `\p{N}` und Vorausschau in beide Richtungen versteht jede der beiden Maschinen gleich,
 * und das Ergebnis hängt an keiner Voreinstellung mehr.
 *
 * **Was dieser Test-Rahmen nicht leisten kann.** Die Prüfläufe laufen auf der JVM; ob ICU
 * ein Muster annimmt, lässt sich hier grundsätzlich nicht feststellen. Deshalb ist der
 * Schutz ein anderer: [compile] weist die bekannten OpenJDK-Eigenheiten ausdrücklich ab,
 * und ein Test durchsucht die Quellen danach. Beides fängt den Fehler beim Bauen ab statt
 * beim Starten.
 */
object PortableRegex {

    /** Was als Wortzeichen gilt. Für deutschen Text bewusst großzügig. */
    const val WORD_CHARACTER = "[\\p{L}\\p{N}_]"

    /**
     * Eine Wortgrenze, ausgeschrieben.
     *
     * Wortzeichen auf genau einer Seite — die Definition von `\b`, nur ohne Verlass auf
     * die Voreinstellung der jeweiligen Maschine. Beide Zweige zusammen wirken an jeder
     * Stelle, an der sonst `\b` stünde; das Muster lässt sich deshalb einfach einsetzen.
     */
    const val WORD_BOUNDARY =
        "(?:(?<!$WORD_CHARACTER)(?=$WORD_CHARACTER)|(?<=$WORD_CHARACTER)(?!$WORD_CHARACTER))"

    /**
     * Konstrukte, die nur das OpenJDK kennt.
     *
     * Kein Anspruch auf Vollständigkeit — es ist die Liste dessen, was hier schon einmal
     * schiefging oder naheliegt. Wer etwas Neues findet, trägt es hier nach, und der Test
     * über die Quellen deckt es ab sofort mit ab.
     */
    private val JVM_ONLY = listOf(
        "(?U)" to "das Unicode-Flag; benutze stattdessen \\b über PortableRegex.compile",
        "(?u)" to "das Unicode-Flag; benutze stattdessen \\b über PortableRegex.compile",
        "\\h" to "waagerechter Leerraum; schreibe die Zeichenklasse aus",
        "\\v" to "senkrechter Leerraum; schreibe die Zeichenklasse aus",
        "\\R" to "Zeilenumbruch; schreibe die Zeichenklasse aus",
        "\\p{IsAlphabetic}" to "benutze \\p{L}",
        "\\p{IsDigit}" to "benutze \\p{N}",
    )

    /**
     * Meldet, was an einem Muster auf Android scheitern würde — oder `null`.
     *
     * Getrennt von [compile], damit derselbe Prüfblick auch auf Quelltext angewandt werden
     * kann, ohne ihn übersetzen zu müssen.
     */
    fun incompatibility(pattern: String): String? =
        JVM_ONLY.firstOrNull { (construct, _) -> pattern.contains(construct) }
            ?.let { (construct, hint) -> "$construct läuft auf Android nicht — $hint" }

    /**
     * Übersetzt ein Muster so, dass es auf beiden Maschinen dasselbe bedeutet.
     *
     * Jedes `\b` wird durch die ausgeschriebene Wortgrenze ersetzt. Innerhalb einer
     * Zeichenklasse stünde `\b` für das Rückschrittzeichen; das kommt hier nirgends vor
     * und wäre auch kein sinnvolles Muster für gesprochene Sprache.
     *
     * @throws IllegalArgumentException wenn das Muster ein Konstrukt enthält, das nur das
     *   OpenJDK kennt. Lieber hier laut als auf dem Telefon still.
     */
    fun compile(pattern: String, vararg options: RegexOption): Regex {
        incompatibility(pattern)?.let { throw IllegalArgumentException("$it: $pattern") }
        return Regex(pattern.replace("\\b", WORD_BOUNDARY), options.toSet())
    }
}
