package de.neon.workspace

import java.io.File

/**
 * Ein Projekt auf der Platte, in dem Neon arbeiten darf — und nur darin.
 *
 * **Die eigentliche Aufgabe dieser Klasse ist eine Grenze.** Die Pfade kommen aus einem
 * Sprachmodell. Ein Modell, das `../../../databases/neon.db` schreibt, tut das nicht aus
 * Bosheit, sondern weil es einen Pfad halluziniert hat — und der Schaden ist derselbe.
 * Deshalb geht jeder Zugriff durch [datei], und die gibt außerhalb der Wurzel `null` zurück,
 * statt zu öffnen.
 *
 * Geprüft wird der **aufgelöste** Pfad, nicht die Zeichenkette. Ein Vergleich auf `".."` ist
 * keine Sicherung: Symbolische Verknüpfungen, `.` in der Mitte und URL-kodierte Trenner gehen
 * daran vorbei. `canonicalFile` löst all das auf, und danach ist die Frage schlicht, ob der
 * Pfad unterhalb der Wurzel liegt.
 *
 * Ohne Android, damit sich die Grenze in einem Verzeichnis unter `/tmp` prüfen lässt.
 */
class Workspace(wurzel: File) {

    /** Das Projektverzeichnis, aufgelöst. Alles darunter ist erlaubt, alles daneben nicht. */
    val wurzel: File = wurzel.canonicalFile

    init {
        require(this.wurzel.isDirectory || this.wurzel.mkdirs()) {
            "Arbeitsverzeichnis ${this.wurzel} lässt sich nicht anlegen"
        }
    }

    /**
     * Die Datei zu einem projektrelativen Pfad — oder `null`, wenn er hinausführt.
     *
     * `null` und keine Ausnahme, weil das der häufige Fall ist, sobald ein Modell die Pfade
     * liefert. Ein Werkzeug soll darauf mit einem Satz antworten können, nicht mit einem
     * Absturz.
     */
    fun datei(pfad: String): File? {
        if (pfad.isBlank()) return null

        // Ein absoluter Pfad ist nie gemeint: Er würde die Wurzel schlicht übergehen.
        val relativ = File(pfad)
        if (relativ.isAbsolute) return null

        val ziel = File(wurzel, pfad).canonicalFile
        return if (ziel.liegtIn(wurzel)) ziel else null
    }

    /** Der Inhalt einer Datei, oder `null`, wenn es sie nicht gibt oder sie draußen liegt. */
    fun lies(pfad: String): String? = datei(pfad)?.takeIf { it.isFile }?.readText()

    /**
     * Schreibt eine Datei und legt die Verzeichnisse darüber an.
     *
     * @return der projektrelative Pfad, oder `null`, wenn er hinausführt.
     */
    fun schreib(pfad: String, inhalt: String): String? {
        val ziel = datei(pfad) ?: return null
        ziel.parentFile?.mkdirs()
        ziel.writeText(inhalt)
        return relativ(ziel)
    }

    /**
     * Ändert eine Datei an einer verankerten Stelle. Siehe [AnchoredEdit].
     *
     * Geschrieben wird **nur** bei [AnchoredEdit.Result.Geaendert]. Das ist der Grund, warum
     * die Prüfung vor dem Schreiben steht und nicht danach: Eine halb geänderte Quelldatei ist
     * schlimmer als eine unveränderte.
     */
    fun aendere(pfad: String, alt: String, neu: String): AnchoredEdit.Result? {
        val inhalt = lies(pfad) ?: return null
        val ergebnis = AnchoredEdit.ersetze(inhalt, alt, neu)
        if (ergebnis is AnchoredEdit.Result.Geaendert) schreib(pfad, ergebnis.text)
        return ergebnis
    }

    /**
     * Alle Dateien des Projekts, projektrelativ, alphabetisch.
     *
     * Ausgelassen wird, was nicht zum Quelltext gehört: Versionsverwaltung, Bauverzeichnisse,
     * Zwischenstände. Ein Modell, dem man 4000 Dateien aus `build/` vorlegt, findet die
     * eigentlichen zwölf nicht mehr — und jeder Eintrag kostet Kontext.
     */
    fun dateien(): List<String> = wurzel.walkTopDown()
        .onEnter { it.name !in UEBERGANGEN }
        .filter { it.isFile }
        .map { relativ(it) }
        .sorted()
        .toList()

    private fun relativ(datei: File): String =
        datei.relativeTo(wurzel).path.replace(File.separatorChar, '/')

    private fun File.liegtIn(oben: File): Boolean {
        var lauf: File? = this
        while (lauf != null) {
            if (lauf == oben) return true
            lauf = lauf.parentFile
        }
        return false
    }

    private companion object {
        /**
         * Verzeichnisse, die kein Quelltext sind.
         *
         * `.git` enthält Objektdateien, `build` und `.gradle` Zwischenstände, `__pycache__`
         * übersetzten Bytecode. Nichts davon beantwortet eine Frage über den Code, und alles
         * davon ist zahlreich.
         */
        val UEBERGANGEN = setOf(".git", "build", ".gradle", "__pycache__", ".idea", "node_modules")
    }
}
