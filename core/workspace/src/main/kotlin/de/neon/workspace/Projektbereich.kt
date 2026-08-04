package de.neon.workspace

import java.io.File

/**
 * Ein einzelnes Projekt: ein Ordner mit einem Namen.
 *
 * Mehr ist es nicht, und das ist Absicht. Es gibt keine Projektdatei, keine Metadaten, keinen
 * Zustand neben dem Dateisystem — wer den Ordner umbenennt, hat das Projekt umbenannt. Eine
 * zweite Wahrheit über den Ordner wäre eine, die irgendwann nicht mehr stimmt.
 */
data class Projekt(val name: String, val verzeichnis: File) {

    /** Ob hier eine Android-App liegt: erkennbar am Manifest, nicht an einer Merkdatei. */
    val istAndroidProjekt: Boolean get() = File(verzeichnis, MANIFEST).isFile

    /**
     * Der Paketname aus dem Manifest.
     *
     * Jedes Mal nachgesehen und nicht gemerkt: Wer das Manifest von Hand ändert, hat danach
     * recht.
     */
    fun paketname(): String? = File(verzeichnis, MANIFEST)
        .takeIf { it.isFile }
        ?.readText()
        ?.let { PAKET.find(it)?.groupValues?.get(1) }

    /** Wie viele Dateien darin liegen — für die Übersicht, nicht für eine Entscheidung. */
    fun dateizahl(): Int = verzeichnis.walkTopDown()
        .onEnter { it.name !in Workspace.UEBERGANGEN }
        .count { it.isFile }

    companion object {
        const val MANIFEST = "AndroidManifest.xml"
        private val PAKET = Regex("""package="([^"]+)"""")
    }
}

/**
 * Der Ort, an dem alle Projekte liegen — und der Grund, warum es ihn gibt.
 *
 * **Vorher war `files/projekt/` das Projekt.** Ein flacher Ordner, ein Manifest darin, fertig.
 * Damit gab es genau eine App: Ein zweites `app-anlegen` schrieb sein Manifest über das erste,
 * und die Quelldateien der alten blieben als Waisen daneben liegen. Löschen ging gar nicht,
 * Verschieben auch nicht. Wer zwei Dinge ausprobieren wollte, hatte danach ein Durcheinander,
 * das sich nicht mehr auflösen ließ.
 *
 * Jetzt ist `files/projekt/` ein **Behälter**, und jeder Unterordner darin ein Projekt.
 *
 * **Warum das der billigste Umbau ist.** [Workspace] bleibt unverändert das, was es war: die
 * Grenze *eines* Projekts. Es zeigt nur nicht mehr auf den Behälter, sondern auf einen Ordner
 * darin. Damit funktionieren Ankeränderungen, Pfadprüfung, die fünfstufige Bau-Kette und alle
 * Datei-Werkzeuge weiter, ohne dass eine Zeile davon angefasst wird — sie erfahren gar nicht,
 * dass es jetzt mehrere gibt.
 *
 * **Ein Projekt ist aktiv.** Die Alternative wäre, jedem Werkzeug einen Projektnamen
 * mitzugeben. Das wäre eindeutiger und in der Praxis schlechter: Bei zwölf Token je Sekunde
 * kostet jeder zusätzliche Parameter Zeit, und ein 4-B-Modell, das ihn bei jedem der sieben
 * Werkzeuge richtig setzen muss, setzt ihn irgendwann falsch. Stattdessen steht im Prompt, in
 * welchem Projekt gearbeitet wird, und die Pfade bleiben kurz.
 */
class Projektbereich(
    wurzel: File,
    /**
     * Wohin Gelöschtes wandert. Liegt **neben** dem Behälter, nicht darin.
     *
     * Läge er darin, wäre der Papierkorb selbst ein Projekt — und ein gelöschtes Projekt
     * stünde nach dem Löschen wieder in der Liste.
     */
    papierkorb: File = File(wurzel.parentFile ?: wurzel, "papierkorb"),
    /** Siehe [Workspace.erlaubteWurzeln]: die Freigabe für den Gerätespeicher. */
    private val weitereWurzeln: () -> List<File> = { emptyList() },
    /** Welches Projekt zuletzt aktiv war. Überdauert einen Neustart; kommt vom Container. */
    private val gemerkterName: () -> String? = { null },
    private val merkeName: (String?) -> Unit = {},
    private val uhr: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {

    val wurzel: File = wurzel.canonicalFile
    private val papierkorb: File = papierkorb.canonicalFile

    init {
        this.wurzel.mkdirs()
    }

    /** Alle Projekte, alphabetisch. Ordner, die keine sind, gibt es hier nicht. */
    fun projekte(): List<Projekt> = wurzel.listFiles()
        .orEmpty()
        .filter { it.isDirectory }
        .sortedBy { it.name.lowercase() }
        .map { Projekt(it.name, it) }

    fun projekt(name: String): Projekt? = projekte().firstOrNull { it.name == name }

    /**
     * Das Projekt, in dem gerade gearbeitet wird.
     *
     * Der gemerkte Name gewinnt, solange es ihn noch gibt. Sonst das einzige vorhandene, sonst
     * das zuletzt geänderte — und `null`, wenn es keines gibt. Diese Reihenfolge macht den
     * häufigen Fall stumm: Wer genau ein Projekt hat, muss nie eines auswählen.
     */
    fun aktiv(): Projekt? {
        val gemerkt = gemerkterName()?.let { projekt(it) }
        if (gemerkt != null) return gemerkt

        val alle = projekte()
        return alle.singleOrNull()
            ?: alle.maxByOrNull { it.verzeichnis.lastModified() }
    }

    /** Wechselt das aktive Projekt. `null`, wenn es keines dieses Namens gibt. */
    fun waehle(name: String): Projekt? = projekt(name)?.also {
        merkeName(it.name)
        log("Projekt gewechselt: ${it.name}")
    }

    /**
     * Legt ein Projekt an und macht es zum aktiven.
     *
     * @return das Projekt, oder `null`, wenn aus dem Wunschnamen kein gültiger wird.
     */
    fun anlegen(wunschname: String): Projekt? {
        val name = ordnername(wunschname) ?: return null
        val verzeichnis = File(wurzel, name)
        if (!verzeichnis.isDirectory && !verzeichnis.mkdirs()) return null

        merkeName(name)
        log("Projekt angelegt: $name")
        return Projekt(name, verzeichnis)
    }

    /**
     * Legt ein ganzes Projekt in den Papierkorb.
     *
     * @return der Ort im Papierkorb, oder `null`, wenn es das Projekt nicht gibt.
     */
    fun inDenPapierkorb(name: String): String? {
        val projekt = projekt(name) ?: return null
        val ablage = File(papierkorb, "${uhr()}-$name")
        ablage.parentFile?.mkdirs()

        if (!projekt.verzeichnis.renameTo(ablage)) {
            runCatching { projekt.verzeichnis.copyRecursively(ablage, overwrite = true) }
                .getOrElse { return null }
            projekt.verzeichnis.deleteRecursively()
        }

        if (gemerkterName() == name) merkeName(null)
        log("Projekt „$name\" in den Papierkorb: ${ablage.absolutePath}")
        return ablage.absolutePath
    }

    /** Der Arbeitsbereich eines Projekts — die Grenze, die alle Werkzeuge benutzen. */
    fun arbeitsbereich(projekt: Projekt): Workspace = Workspace(
        wurzel = projekt.verzeichnis,
        weitereWurzeln = weitereWurzeln,
        papierkorb = papierkorb,
    )

    /**
     * Der Arbeitsbereich des aktiven Projekts — und wenn es keines gibt, ein neues.
     *
     * **Warum hier nicht `null` herauskommt.** Ein Nutzer, der „schreib mir ein Python-Skript"
     * sagt, hat kein Projekt angelegt und will auch keines anlegen. Ihm zu antworten, es gebe
     * keines, wäre Bürokratie. Stattdessen entsteht eines, und zwar sichtbar unter einem
     * Namen, den man wiederfindet.
     */
    fun aktiverArbeitsbereich(): Workspace {
        val projekt = aktiv() ?: anlegen(STANDARDNAME)
            ?: return Workspace(wurzel, weitereWurzeln, papierkorb)
        return arbeitsbereich(projekt)
    }

    /**
     * Holt ein Projekt aus der alten, flachen Ablage in einen eigenen Ordner.
     *
     * **Ohne das verschwände vorhandene Arbeit aus der Ansicht.** Bis hierher lagen Manifest
     * und Quelltext direkt im Behälter; danach zählt nur noch, was in Unterordnern liegt. Wer
     * die neue Fassung installiert, sähe also ein leeres Projektverzeichnis und müsste
     * glauben, seine Dateien seien weg — sie wären nur unsichtbar.
     *
     * Läuft genau einmal: Danach liegt nichts mehr lose herum, und die Bedingung trifft nicht
     * mehr zu.
     *
     * @return der Name des umgezogenen Projekts, oder `null`, wenn nichts umzuziehen war.
     */
    fun holeAltesProjektHerein(): String? {
        val lose = wurzel.listFiles().orEmpty().filter { !it.isDirectory }
        val loseOrdner = wurzel.listFiles().orEmpty().filter {
            it.isDirectory && it.name in ALTE_ORDNER
        }
        if (lose.isEmpty() && loseOrdner.isEmpty()) return null

        // Der Name kommt aus dem Paketnamen, wenn es einen gibt — `de.neon.zaehler` wird zu
        // `zaehler`. Das ist der Name, unter dem der Nutzer sein Projekt kennt.
        val manifest = File(wurzel, Projekt.MANIFEST).takeIf { it.isFile }
        val ausPaket = manifest?.readText()
            ?.let { Regex("""package="([^"]+)"""").find(it)?.groupValues?.get(1) }
            ?.substringAfterLast('.')
        val name = ordnername(ausPaket ?: UMZUGSNAME) ?: UMZUGSNAME

        val ziel = File(wurzel, name)
        ziel.mkdirs()
        (lose + loseOrdner).forEach { quelle ->
            val neu = File(ziel, quelle.name)
            if (!quelle.renameTo(neu)) {
                runCatching { quelle.copyRecursively(neu, overwrite = true) }
                quelle.deleteRecursively()
            }
        }

        merkeName(name)
        log("Vorhandenes Projekt nach „$name\" umgezogen: ${lose.size + loseOrdner.size} Einträge")
        return name
    }

    companion object {
        /** Wenn jemand ohne Projekt loslegt. */
        const val STANDARDNAME = "projekt"

        /** Wohin die alte, flache Ablage wandert, falls sie keinen Paketnamen hergibt. */
        const val UMZUGSNAME = "erstes-projekt"

        /** Was in der alten Ablage lose herumlag und mit umziehen muss. */
        private val ALTE_ORDNER = setOf("src", "res", "build", "gen")

        private const val MAX_NAME = 40

        /**
         * Macht aus einem Wunsch einen Ordnernamen — oder `null`, wenn nichts übrig bleibt.
         *
         * **Der Name kommt aus einem Sprachmodell**, also aus derselben Quelle wie die Pfade,
         * gegen die [Workspace] sich absichert. Ein Projekt namens `../../models` wäre kein
         * Projekt, sondern ein Ausbruch. Erlaubt sind deshalb nur Kleinbuchstaben, Ziffern und
         * Bindestriche — keine Punkte, keine Trenner, kein Leerraum.
         *
         * Umlaute werden übersetzt statt verworfen: „Zähler" soll `zaehler` heißen und nicht
         * `z-hler`.
         */
        fun ordnername(wunsch: String): String? {
            val klein = wunsch.trim().lowercase()
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                .replace("ß", "ss")

            val bereinigt = klein
                .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
                .joinToString("")
                .replace(Regex("-{2,}"), "-")
                .trim('-')
                .take(MAX_NAME)
                .trim('-')

            return bereinigt.ifBlank { null }
        }
    }
}
