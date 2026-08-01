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
class Workspace(
    wurzel: File,
    /**
     * Weitere Orte, an denen Neon arbeiten darf — Downloads, Dokumente, was der Nutzer
     * freigibt.
     *
     * **Eine Funktion und keine Liste.** Die Freigabe für den Gerätespeicher wird in den
     * Systemeinstellungen erteilt und kann jederzeit zurückgenommen werden. Eine beim Bauen
     * eingefrorene Liste hieße: Wer die Freigabe erteilt, muss Neon neu starten — und wer
     * sie entzieht, wird trotzdem weiter gelesen. Beides falsch.
     *
     * Nur das Projektverzeichnis ist immer dabei; es gehört der App und braucht niemandes
     * Erlaubnis.
     */
    private val weitereWurzeln: () -> List<File> = { emptyList() },
) {

    /** Das Projektverzeichnis, aufgelöst. Der Ort, auf den sich relative Pfade beziehen. */
    val wurzel: File = wurzel.canonicalFile

    init {
        require(this.wurzel.isDirectory || this.wurzel.mkdirs()) {
            "Arbeitsverzeichnis ${this.wurzel} lässt sich nicht anlegen"
        }
    }

    /**
     * Alle Orte, unter denen ein Zugriff erlaubt ist.
     *
     * Bei jedem Zugriff neu gefragt, siehe [weitereWurzeln]. Nicht vorhandene Verzeichnisse
     * fliegen raus: Ein Ort, den es nicht gibt, kann nichts erlauben, und `canonicalFile`
     * auf einen fehlenden Pfad liefert etwas, das zufällig irgendwo hinzeigt.
     */
    fun erlaubteWurzeln(): List<File> = buildList {
        add(wurzel)
        weitereWurzeln().forEach { ort ->
            runCatching { ort.canonicalFile }.getOrNull()
                ?.takeIf { it.isDirectory }
                ?.let { add(it) }
        }
    }

    /**
     * Die Datei zu einem Pfad — oder `null`, wenn er aus allen erlaubten Orten hinausführt.
     *
     * Relative Pfade beziehen sich auf das Projekt, absolute auf das Gerät. **Absolute Pfade
     * waren früher pauschal verboten**, mit der Begründung, sie würden die Wurzel übergehen.
     * Das stimmte, solange es genau eine Wurzel gab. Seit der Nutzer weitere Orte freigeben
     * kann, ist ein absoluter Pfad die einzige Art, eine Datei in seinen Downloads zu
     * benennen — und die Grenze liegt ohnehin nicht an der Schreibweise, sondern daran, wo
     * der aufgelöste Pfad landet.
     *
     * Geprüft wird weiterhin der **aufgelöste** Pfad. Ein Vergleich auf `".."` wäre keine
     * Sicherung: Symbolische Verknüpfungen, `.` in der Mitte und kodierte Trenner gehen daran
     * vorbei. `canonicalFile` löst all das auf, und danach ist die Frage schlicht, ob der
     * Pfad unter einem erlaubten Ort liegt.
     *
     * `null` und keine Ausnahme, weil das der häufige Fall ist, sobald ein Modell die Pfade
     * liefert. Ein Werkzeug soll darauf mit einem Satz antworten können, nicht mit einem
     * Absturz.
     */
    fun datei(pfad: String): File? {
        if (pfad.isBlank()) return null

        val roh = File(pfad)
        val ziel = runCatching {
            if (roh.isAbsolute) roh.canonicalFile else File(wurzel, pfad).canonicalFile
        }.getOrNull() ?: return null

        return if (erlaubteWurzeln().any { ziel.liegtIn(it) }) ziel else null
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

    /**
     * Auflistung eines Verzeichnisses — auch außerhalb des Projekts.
     *
     * **Warum nicht einfach [dateien] erweitern.** Das Projekt lässt sich vollständig
     * aufzählen; der Gerätespeicher nicht. Ein `walkTopDown` über `/storage/emulated/0`
     * liefert zehntausende Einträge, von denen keiner die Frage beantwortet — und jeder
     * kostet Kontext. Wer im Gerätespeicher etwas sucht, nennt ein Verzeichnis.
     *
     * Nicht rekursiv, aus demselben Grund. Verzeichnisse bekommen ein `/` angehängt, damit
     * das Modell sieht, wo es weitersuchen kann.
     *
     * @return `null`, wenn der Pfad hinausführt oder kein Verzeichnis ist.
     */
    fun ordner(pfad: String, grenze: Int = ORDNER_GRENZE): List<String>? {
        val ziel = datei(pfad)?.takeIf { it.isDirectory } ?: return null
        return ziel.listFiles().orEmpty()
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .take(grenze)
            .map { if (it.isDirectory) "${it.name}/" else "${it.name}  (${it.length()} B)" }
    }

    /**
     * Wie eine Datei benannt wird, wenn sie zur Sprache kommt.
     *
     * Innerhalb des Projekts der kurze relative Pfad, außerhalb der volle. `relativeTo`
     * lieferte für eine Datei in den Downloads sonst eine Kette aus `../..`, die weder zu
     * lesen noch wieder zu öffnen ist.
     */
    private fun relativ(datei: File): String =
        if (datei.liegtIn(wurzel)) datei.relativeTo(wurzel).path.replace(File.separatorChar, '/')
        else datei.absolutePath

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

        /**
         * Wie viele Einträge eine Ordnerauflistung höchstens nennt.
         *
         * Ein Download-Ordner mit dreihundert Dateien beantwortet keine Frage besser als
         * einer mit hundert — er kostet nur dreimal so viel Kontext und damit Wartezeit vor
         * dem ersten Wort.
         */
        const val ORDNER_GRENZE = 100
    }
}
