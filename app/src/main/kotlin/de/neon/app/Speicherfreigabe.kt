package de.neon.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Zugriff auf den Gerätespeicher — und warum es ausgerechnet dieser Weg ist.
 *
 * **Der übliche Weg funktioniert hier nicht.** Android sieht für Zugriffe außerhalb der App
 * das Storage Access Framework vor: Der Nutzer wählt einen Ordner, die App bekommt dauerhaft
 * Rechte darauf, und alles läuft über `content://`-Verweise. Das ist der richtige Weg für
 * eine App, die Dateien selbst liest.
 *
 * Neon liest sie nicht selbst. Die Entwicklungsumgebung startet **fremde Programme** —
 * `aapt2`, den Kotlin-Compiler, `d8`, den Python-Starter —, und keines davon kennt
 * `content://`. Sie erwarten einen Pfad im Dateisystem. Einen Deskriptor durchzureichen hilft
 * nicht: `python /proc/self/fd/42` kann kein Skript importieren, das daneben liegt, und der
 * Kotlin-Compiler braucht ein Verzeichnis, kein Handle.
 *
 * Deshalb `MANAGE_EXTERNAL_STORAGE`. Damit gelten für Neon — und für die Prozesse, die es
 * startet, denn die laufen unter derselben Kennung — gewöhnliche Dateipfade.
 *
 * **Was das bedeutet, und warum es trotzdem vertretbar ist.** Die Berechtigung ist weit: Sie
 * umfasst den gesamten gemeinsamen Speicher, nicht einen ausgewählten Ordner. Sie lässt sich
 * nicht beim ersten Zugriff erfragen, sondern nur in den Systemeinstellungen erteilen, und
 * Google Play würde eine App damit ablehnen. Neon geht in keinen Store und wird privat
 * installiert; die Abwägung trifft derselbe Mensch, dem die Dateien gehören.
 *
 * Was hier **nicht** passiert: Sie wird nicht beim Start erbeten und nicht vorausgesetzt.
 * Ohne sie arbeitet Neon im Projektordner weiter, und die Werkzeuge sagen ausdrücklich, dass
 * die Freigabe fehlt. Wer sie nicht will, verliert nichts als den Zugriff auf eigene Ordner.
 */
object Speicherfreigabe {

    /** Ob Neon auf den gemeinsamen Speicher zugreifen darf. */
    fun erteilt(): Boolean = Environment.isExternalStorageManager()

    /**
     * Öffnet die Systemeinstellung, in der die Freigabe erteilt wird.
     *
     * Mit der Paketkennung im Verweis, damit die Einstellung dieser App erscheint und nicht
     * die Liste aller Apps. Fällt auf die Liste zurück, falls ein Hersteller die genauere
     * Ansicht nicht anbietet — besser als ein Knopf, der nichts tut.
     */
    fun anfordern(context: Context) {
        val genau = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val ziel = if (genau.resolveActivity(context.packageManager) != null) genau
        else Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { context.startActivity(ziel) }
    }

    /**
     * Die Orte, an denen Neon außerhalb des Projekts arbeiten darf.
     *
     * **Nicht die Wurzel des gemeinsamen Speichers, sondern benannte Ordner.** Technisch
     * erlaubt die Berechtigung mehr; hier steht weniger. Der Unterschied ist der zwischen
     * „Neon darf, was der Nutzer freigegeben hat" und „Neon darf alles, was Android nicht
     * verbietet". Downloads und Dokumente sind die Orte, an denen Menschen Dateien ablegen,
     * die sie einem Assistenten zeigen wollen — `Android/data` und die Mediendatenbank
     * gehören nicht dazu.
     *
     * Leer, solange die Freigabe fehlt. Dann bleibt das Projekt der einzige Ort, und die
     * Fehlermeldung der Werkzeuge sagt genau das.
     */
    fun wurzeln(): List<File> {
        if (!erteilt()) return emptyList()
        return ORDNER.mapNotNull { name ->
            runCatching { Environment.getExternalStoragePublicDirectory(name) }
                .getOrNull()
                ?.takeIf { it.isDirectory }
        }
    }

    /** Ein Satz für die Einstellungen — was gilt und was fehlt. */
    fun beschreibung(): String = if (erteilt()) {
        "Neon darf auf Downloads, Dokumente und Bilder zugreifen."
    } else {
        "Neon arbeitet nur im eigenen Projektordner. Ohne Freigabe kommt es an Dateien in " +
            "Downloads oder Dokumenten nicht heran — auch nicht, um sie nur zu lesen."
    }

    private val ORDNER = listOf(
        Environment.DIRECTORY_DOWNLOADS,
        Environment.DIRECTORY_DOCUMENTS,
        Environment.DIRECTORY_PICTURES,
    )
}
