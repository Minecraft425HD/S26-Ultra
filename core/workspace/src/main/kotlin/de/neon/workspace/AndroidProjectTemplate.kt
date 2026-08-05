package de.neon.workspace

/**
 * Legt ein Android-Projekt an, das sich auf dem Telefon bauen lässt.
 *
 * **Warum eine Vorlage und nicht „das Modell schreibt alles".** Ein Android-Projekt hat vier
 * Dateien, die exakt stimmen müssen, bevor überhaupt etwas übersetzt wird: Manifest,
 * Ressourcen, eine Activity und ein Layout. Ein 4-B-Modell schreibt davon drei richtig und
 * eine falsch, und der Fehler zeigt sich als aapt2-Meldung, mit der niemand etwas anfängt.
 * Bei 15 Token je Sekunde kostet jeder Anlauf Minuten.
 *
 * Die Vorlage nimmt das ab. Was danach passiert — Code ändern, Ansichten hinzufügen — ist
 * genau das, wofür das Modell gut ist.
 *
 * **Keine XML-Layouts, keine Compose.** Die Oberfläche wird im Code gebaut. Layouts wären
 * möglich, Compose nicht: Dafür bräuchte es das Compose-Compiler-Plugin und ein Dutzend
 * Bibliotheken, die alle mit in die APK müssten. Eine App mit `LinearLayout` und `TextView`
 * im Code ist eine echte Android-App, und sie kommt mit dem aus, was mitgeliefert wird.
 *
 * Reine Zeichenkettenarbeit, ohne Android und ohne Dateisystem — bis auf das Schreiben.
 */
object AndroidProjectTemplate {

    /** Was eine neue App braucht. */
    data class Vorgabe(
        /** Etwa `de.neon.meineapp`. Landet im Manifest und als Verzeichnis. */
        val paketname: String,
        /** Was unter dem Symbol steht. */
        val appName: String,
    ) {
        init {
            require(GUELTIGES_PAKET.matches(paketname)) {
                "„$paketname\" ist kein gültiger Paketname. Erwartet werden mindestens zwei " +
                    "durch Punkte getrennte Teile aus Kleinbuchstaben, etwa de.neon.meineapp."
            }
        }
    }

    /**
     * Schreibt das Projekt in den Arbeitsbereich.
     *
     * @return die angelegten Pfade, projektrelativ.
     */
    fun anlegen(workspace: Workspace, vorgabe: Vorgabe): List<String> {
        val pfad = vorgabe.paketname.replace('.', '/')

        val dateien = mapOf(
            "AndroidManifest.xml" to manifest(vorgabe),
            "res/values/strings.xml" to strings(vorgabe),
            "src/$pfad/MainActivity.kt" to activity(vorgabe),
        )

        return dateien.mapNotNull { (ziel, inhalt) -> workspace.schreib(ziel, inhalt) }
    }

    /**
     * Das Manifest.
     *
     * `exported="true"` ist bei einer Start-Activity Pflicht, seit Android 12 — ohne das
     * weist der Installer die App ab, mit einer Meldung über einen fehlenden Wert, die nicht
     * verrät, welcher gemeint ist.
     */
    private fun manifest(vorgabe: Vorgabe) = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<manifest xmlns:android="http://schemas.android.com/apk/res/android"
        |    package="${vorgabe.paketname}">
        |
        |    <application
        |        android:label="@string/app_name"
        |        android:theme="@android:style/Theme.Material.Light">
        |        <activity
        |            android:name=".MainActivity"
        |            android:exported="true">
        |            <intent-filter>
        |                <action android:name="android.intent.action.MAIN" />
        |                <category android:name="android.intent.category.LAUNCHER" />
        |            </intent-filter>
        |        </activity>
        |    </application>
        |</manifest>
        |
    """.trimMargin()

    private fun strings(vorgabe: Vorgabe) = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<resources>
        |    <string name="app_name">${vorgabe.appName.xmlSicher()}</string>
        |</resources>
        |
    """.trimMargin()

    /**
     * Die Start-Activity, mit einer Oberfläche aus Code.
     *
     * Bewusst so klein wie möglich und ohne eine einzige Abhängigkeit außer der Plattform:
     * Was hier steht, muss mit `android.jar` und `kotlin-stdlib` übersetzbar sein, und beide
     * liegen in der APK. Jede weitere Bibliothek wäre ein Download beim Bauen — auf einem
     * Telefon ohne Gradle gibt es den nicht.
     *
     * **Und bewusst inhaltsleer.** Hier stand ein Zähler: ein Knopf „Zähl mich" und eine
     * Anzeige „3 Mal gedrückt". Der Nutzer bat um eine QR-App, bekam ein Gerüst, das eine
     * fertige Zähler-App war, und musste glauben, Neon habe ihn missverstanden — dabei war
     * die Vorlage einfach nie ausgetauscht worden.
     *
     * Ein Gerüst, das schon nach etwas aussieht, wird für das Ergebnis gehalten. Deshalb
     * steht jetzt auf dem Bildschirm, dass es eines ist. Der Knopf bleibt: Er ist der
     * Nachweis, dass Ereignisbehandlung übersetzt und läuft — und dafür braucht er keinen
     * Zähler.
     */
    private fun activity(vorgabe: Vorgabe) = """
        |package ${vorgabe.paketname}
        |
        |import android.app.Activity
        |import android.graphics.Color
        |import android.os.Bundle
        |import android.view.Gravity
        |import android.widget.Button
        |import android.widget.LinearLayout
        |import android.widget.TextView
        |
        |class MainActivity : Activity() {
        |
        |    override fun onCreate(savedInstanceState: Bundle?) {
        |        super.onCreate(savedInstanceState)
        |
        |        val anzeige = TextView(this).apply {
        |            text = "${vorgabe.appName.kotlinSicher()}\n\nGerüst — hier fehlt noch die App."
        |            textSize = 20f
        |            gravity = Gravity.CENTER
        |        }
        |
        |        val knopf = Button(this).apply {
        |            text = "Knopf"
        |            setOnClickListener { anzeige.text = "Es funktioniert." }
        |        }
        |
        |        setContentView(
        |            LinearLayout(this).apply {
        |                orientation = LinearLayout.VERTICAL
        |                gravity = Gravity.CENTER
        |                setBackgroundColor(Color.WHITE)
        |                setPadding(48, 48, 48, 48)
        |                addView(anzeige)
        |                addView(knopf)
        |            }
        |        )
        |    }
        |}
        |
    """.trimMargin()

    /**
     * Ein Name kann alles enthalten, was ein Nutzer eintippt.
     *
     * In XML sind fünf Zeichen besonders, und ein einzelnes `&` in einem App-Namen lässt
     * `aapt2` mit einem Parserfehler abbrechen — an einer Datei, die niemand geschrieben hat.
     */
    private fun String.xmlSicher(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** Dasselbe für eine Kotlin-Zeichenkette: Anführungszeichen und Dollar. */
    private fun String.kotlinSicher(): String = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("$", "\\$")

    /**
     * Mindestens zwei Teile, Kleinbuchstaben, keine Ziffer am Anfang eines Teils.
     *
     * Android verlangt einen Punkt im Paketnamen, und ein Verzeichnisname wird daraus auch.
     * Ein ungültiger Name scheitert erst beim Installieren — nach dem ganzen Bauvorgang.
     */
    private val GUELTIGES_PAKET = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
}
