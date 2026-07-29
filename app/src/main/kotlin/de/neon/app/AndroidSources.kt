package de.neon.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import de.neon.attachments.AttachmentSource
import java.io.InputStream

/**
 * Eine Datei aus der Systemauswahl.
 *
 * Android reicht Dateien als `content://`-URI herein, nicht als Pfad — der eigentliche
 * Speicherort ist der App gar nicht bekannt und geht sie auch nichts an. Das ist der Grund,
 * warum [AttachmentSource] mit einem Strom arbeitet und nicht mit [java.io.File].
 */
class UriSource(
    private val context: Context,
    val uri: Uri,
    override val name: String,
    override val path: String,
    override val sizeBytes: Long,
    /** Was der Anbieter über den Typ sagt. Oft `null` oder geraten. */
    val mimeType: String? = null,
) : AttachmentSource {

    /**
     * Ob hier Text drinstecken könnte, den man sieht, aber nicht auslesen kann.
     *
     * Erst der gemeldete Typ, dann die Endung: Manche Anbieter melden gar nichts, und ein
     * Bildschirmfoto aus der Galerie kommt regelmäßig ohne Typangabe herein.
     */
    val istBild: Boolean
        get() = mimeType?.startsWith("image/") == true ||
            name.substringAfterLast('.', "").lowercase() in BILD_ENDUNGEN

    override fun open(): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: error("Datei ließ sich nicht öffnen: $name")

    companion object {
        val BILD_ENDUNGEN = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif")
    }
}

/**
 * Sammelt Anhänge aus dem, was die Systemauswahl liefert.
 *
 * Getrennt vom Rest, weil hier das ganze Android steckt: URIs, `DocumentFile`,
 * Berechtigungen. Alles danach — erkennen, auspacken, zerlegen — ist gewöhnliches Kotlin
 * und wird ohne Gerät geprüft.
 */
object AndroidSources {

    /**
     * Eine einzelne ausgewählte Datei.
     *
     * Der Anzeigename kommt aus den Metadaten des Anbieters; fehlt er, bleibt der letzte
     * Pfadteil des URI. Bei Dateien aus der Cloud ist dieser Teil regelmäßig eine
     * Zahlenkolonne, deshalb ist der Anzeigename der bessere erste Versuch.
     */
    fun fromDocument(context: Context, uri: Uri): AttachmentSource? {
        val dokument = DocumentFile.fromSingleUri(context, uri) ?: return null
        val name = dokument.name ?: uri.lastPathSegment ?: "unbenannt"
        return UriSource(
            context = context,
            uri = uri,
            name = name,
            path = name,
            sizeBytes = dokument.length(),
            mimeType = dokument.type ?: context.contentResolver.getType(uri),
        )
    }

    /**
     * Ein ganzer Ordner, rekursiv.
     *
     * Die Grenze bei [MAX_ENTRIES] ist kein Geiz, sondern Schutz: Wer versehentlich sein
     * Wurzelverzeichnis auswählt, würde sonst minutenlang auf eine Oberfläche starren, die
     * nichts tut. Die Aufnahme selbst hat noch eine eigene Grenze in Bytes.
     */
    fun fromTree(context: Context, treeUri: Uri, maxEntries: Int = MAX_ENTRIES): List<AttachmentSource> {
        val wurzel = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val wurzelName = wurzel.name ?: "ordner"

        val ergebnis = mutableListOf<AttachmentSource>()

        // Breitensuche statt Rekursion: Bei einem tief verschachtelten Baum ist der Stapel
        // begrenzt, und so wird zuerst das aufgenommen, was obenauf liegt — meistens das
        // Interessante.
        val warteschlange = ArrayDeque<Pair<DocumentFile, String>>()
        warteschlange += wurzel to wurzelName

        while (warteschlange.isNotEmpty() && ergebnis.size < maxEntries) {
            val (ordner, pfad) = warteschlange.removeFirst()
            val kinder = runCatching { ordner.listFiles() }.getOrDefault(emptyArray())

            kinder.forEach { kind ->
                if (ergebnis.size >= maxEntries) return@forEach
                val name = kind.name ?: return@forEach
                val kindPfad = "$pfad/$name"

                if (kind.isDirectory) {
                    warteschlange += kind to kindPfad
                } else {
                    ergebnis += UriSource(
                        context = context,
                        uri = kind.uri,
                        name = name,
                        path = kindPfad,
                        sizeBytes = kind.length(),
                        mimeType = kind.type,
                    )
                }
            }
        }

        return ergebnis
    }

    /** Ob ein URI auf einen Ordner zeigt. Die Auswahl liefert beides über denselben Weg. */
    fun isTree(context: Context, uri: Uri): Boolean = runCatching {
        DocumentsContract.isTreeUri(uri)
    }.getOrDefault(false)

    /**
     * So viele Dateien werden aus einem Ordner höchstens aufgenommen.
     *
     * Großzügig genug für jedes normale Projektverzeichnis und knapp genug, dass ein
     * versehentlich gewähltes Wurzelverzeichnis nach ein paar Sekunden endet.
     */
    const val MAX_ENTRIES = 500
}
