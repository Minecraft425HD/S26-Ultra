package de.neon.service

/**
 * Entscheidet ohne Sprachmodell, ob ein Programmierauftrag erst eine Rückfrage braucht.
 *
 * **Warum das nicht das Modell tun darf.** Es gibt das Werkzeug `rueckfrage` seit Tagen. Es
 * stand zuletzt an erster Stelle in der Werkzeugliste, seine Beschreibung nannte die Gabelung
 * beim Namen — „Android-App oder Python-Skript?" —, und dieselbe Regel stand im Systemprompt.
 * Auf „programmiere eine QR-Generierungs-App" hat Neon trotzdem ungefragt ein Python-Skript
 * geschrieben, ohne Projekt.
 *
 * Daraus ist zu lernen, dass ein 1.7-B-Modell diese Wahl nicht trifft. Man kann ihm anbieten,
 * zu fragen; verlassen kann man sich nicht darauf. Also entscheidet nicht mehr es, sondern
 * eine Regel.
 *
 * **Warum hier Stichwörter erlaubt sind, wo sie sonst verboten waren.** Bei der Werkzeugauswahl
 * habe ich Stichwortsuche ausdrücklich abgelehnt: Wer am Wortlaut rät, welches Werkzeug gemeint
 * ist, liegt bei „mach das fertig" daneben und tut dann etwas Falsches. Hier ist die
 * Schadensbilanz umgekehrt. Der Fehlgriff dieser Regel ist eine **Frage**, keine Handlung: Sie
 * kostet zehn Sekunden und einen Satz. Der Fehlgriff der Alternative ist eine App in der
 * falschen Sprache, und die kostet Minuten und muss danach weggeräumt werden.
 *
 * **Und warum „App" nicht als Antwort zählt.** Naheliegend wäre, das Wort als „Android" zu
 * lesen — Neon läuft schließlich auf einem Telefon. Der Nutzer sieht das anders, und er hat
 * recht: Ein Python-Skript, das QR-Codes erzeugt, ist auch eine Anwendung. Nur ausdrückliche
 * Angaben zählen.
 */
object Zielklaerung {

    /** Was Neon fragt, wenn die Sprache offen ist. Ein fester Satz, keine Erzeugung. */
    const val FRAGE_SPRACHE = "Android oder Python?"

    /**
     * Dieselbe Frage noch einmal, deutlicher.
     *
     * **Weil die erste Antwort keine war.** Auf dem Gerät kam auf „Android oder Python?" der
     * ursprüngliche Auftrag ein zweites Mal zurück — und Neon hat das kommentarlos als
     * Antwort genommen und ohne Sprache weitergearbeitet. Eine Frage, die man auch dadurch
     * beantworten kann, dass man sie übergeht, ist keine.
     */
    const val FRAGE_NOCHMAL =
        "Das habe ich nicht als Antwort verstanden. Antworte bitte mit einem Wort: " +
            "Android für eine App zum Installieren, Python für ein Skript."

    /**
     * Wie oft nach derselben Sache gefragt wird, bevor Neon es trotzdem versucht.
     *
     * Zweimal. Ein Assistent, der dieselbe Frage dreimal stellt, ist kaputt; einer, der nach
     * der ersten unbeantworteten Frage aufgibt, ebenso.
     */
    const val MAX_RUECKFRAGEN = 2

    /**
     * Wörter, die einen Bauauftrag erkennbar machen.
     *
     * Ohne sie träfe die Regel auch „was ist ein QR-Code" — eine Wissensfrage, bei der eine
     * Rückfrage nach der Programmiersprache unsinnig wäre. Die Einordnung als CODE allein
     * reicht dafür nicht: Sie umfasst auch „erklär mir diese Funktion".
     */
    private val AUFTRAG = listOf(
        "programmier", "schreib", "bau", "erstell", "mach mir", "mach eine", "mach ein",
        "entwickl", "leg an", "anlegen", "implementier", "generier",
    )

    /** Angaben, die die Frage schon beantworten. */
    /**
     * Satzanfänge, die eine Frage einleiten und keinen Auftrag.
     *
     * **Ein Fehlgriff, den ein Test gefangen hat.** „Wie schreibe ich eine Schleife" enthält
     * „schreib" und wäre damit als Bauauftrag durchgegangen — eine Rückfrage nach der
     * Programmiersprache auf eine Verständnisfrage. Geprüft wird der **Anfang**: „Kannst du
     * mir eine App bauen?" ist trotz Fragezeichen ein Auftrag, „wie …" ist keiner.
     */
    private val FRAGEWORT = listOf(
        "wie ", "was ", "warum ", "wieso ", "weshalb ", "wozu ", "wo ", "wann ", "wer ",
        "welche", "gibt es ", "kann man ", "erklär", "erklaer",
    )

    private val ANDROID = listOf(
        "android", "apk", "kotlin", " java", "activity", "manifest", "installier",
    )

    private val PYTHON = listOf("python", "skript", "script", ".py", "kommandozeile")

    /**
     * Ob der Auftrag offen lässt, was gebaut werden soll.
     *
     * @param text die Äußerung des Nutzers.
     * @param projektIstAndroid ob im aktiven Projekt schon ein Manifest liegt. Dann ist die
     *   Frage beantwortet: Wer in einem Android-Projekt „schreib mir das noch dazu" sagt,
     *   meint dieses Projekt und will nicht gefragt werden.
     */
    fun brauchtSprachfrage(text: String, projektIstAndroid: Boolean): Boolean {
        if (projektIstAndroid) return false

        val klein = text.lowercase().trimStart()
        if (FRAGEWORT.any { klein.startsWith(it) }) return false
        if (AUFTRAG.none { it in klein }) return false
        if (ANDROID.any { it in klein }) return false
        if (PYTHON.any { it in klein }) return false

        return true
    }

    /**
     * Fügt Frage und Antwort wieder zum ursprünglichen Auftrag zusammen.
     *
     * **Ohne das ist die Rückfrage schlimmer als nutzlos.** Auf „Android" allein folgt eine
     * neue Einordnung, und „Android" ist für sich genommen keine Programmieraufgabe — die
     * Kette liefe gar nicht erst an, und der ursprüngliche Auftrag wäre verloren. Derselbe
     * Fehler steckte im Werkzeug `rueckfrage`: Es beendete die Kette und überließ den Rest
     * dem Verlauf.
     *
     * Zusammengesetzt wird zu **einem** Satz, damit die Einordnung dieselbe ist wie ohne die
     * Zwischenfrage.
     */
    fun zusammengefuegt(urspruenglich: String, antwort: String): String {
        val sauber = antwort.trim()
        // **Nicht verdoppeln.** Auf dem Gerät kam als „Antwort" der ursprüngliche Auftrag
        // noch einmal, und heraus kam „programmiere eine qr generierungsapp (programmiere
        // eine qr generierungsapp)". Ein doppelter Satz ist kein zusätzlicher Hinweis — er
        // verändert nur die Einordnung, und zwar unvorhersagbar.
        if (sauber.equals(urspruenglich.trim(), ignoreCase = true)) return urspruenglich
        if (sauber.length > urspruenglich.length &&
            urspruenglich.trim().lowercase() in sauber.lowercase()
        ) {
            return sauber
        }
        return "$urspruenglich ($sauber)"
    }
}
