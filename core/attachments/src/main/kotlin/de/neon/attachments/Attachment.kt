package de.neon.attachments

import java.io.InputStream

/**
 * Etwas, das dem Gespräch beigelegt wurde.
 *
 * Als Schnittstelle und nicht als Datei: Auf Android kommen Anhänge als `content://`-URI
 * herein, aus einem ZIP-Archiv als Eintrag im Strom, und in den Tests als Zeichenkette.
 * Keiner dieser drei Fälle ist eine [java.io.File], und keiner soll es sein müssen.
 */
interface AttachmentSource {

    /** Wie die Datei heißt, ohne Pfad. */
    val name: String

    /**
     * Wo sie herkam, als lesbarer Pfad.
     *
     * Bei einem Ordner der Weg darin, bei einem Archiv der Eintragspfad. Steht später an
     * der Fundstelle, damit eine Antwort sagen kann, woher sie es hat.
     */
    val path: String

    /** `-1`, wenn unbekannt — bei einem Strom ohne Längenangabe. */
    val sizeBytes: Long

    fun open(): InputStream
}

/** Ein Anhang aus dem Speicher, für Tests und für Text aus der Bilderkennung. */
class BytesSource(
    override val name: String,
    override val path: String = name,
    private val bytes: ByteArray,
) : AttachmentSource {
    override val sizeBytes: Long get() = bytes.size.toLong()
    override fun open(): InputStream = bytes.inputStream()
}

/** Was aus einer Datei geworden ist. */
enum class AttachmentKind {
    /** Lesbarer Text — zerlegt und durchsuchbar. */
    TEXT,

    /**
     * Nicht lesbar, aber vorhanden.
     *
     * Wird ausdrücklich nicht verschwiegen: Neon soll „die Datei ist da, lesen kann ich sie
     * nicht" sagen können, statt so zu tun, als hätte man sie nie angehängt.
     */
    BINAER,

    /** Ein Archiv, dessen Inhalt einzeln aufgenommen wurde. */
    ARCHIV,

    /** Übersprungen, weil zu groß oder unlesbar. Der Grund steht in [AttachmentFile.note]. */
    UEBERSPRUNGEN,
}

/** Eine aufgenommene Datei mit ihrem Befund. */
data class AttachmentFile(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val kind: AttachmentKind,
    val chunkCount: Int = 0,
    /** Warum etwas nicht ging, in einem Satz für die Oberfläche. */
    val note: String? = null,
)

/**
 * Ein Abschnitt einer Textdatei samt Herkunft.
 *
 * Die Herkunft ist kein Beiwerk: Ohne sie könnte eine Antwort zwar die richtige Stelle
 * benutzen, aber nicht sagen, wo sie steht — und damit wäre sie nicht überprüfbar.
 */
data class AttachmentChunk(
    val fileName: String,
    val filePath: String,
    val firstLine: Int,
    val lastLine: Int,
    val text: String,
) {
    /** Die Kopfzeile, mit der der Abschnitt im Prompt steht. */
    val quelle: String
        get() = if (firstLine == lastLine) "$filePath:$firstLine"
        else "$filePath:$firstLine-$lastLine"
}

/** Das Ergebnis einer Aufnahme. */
data class IngestResult(
    val files: List<AttachmentFile>,
    val chunks: List<AttachmentChunk>,
) {
    val textFileCount: Int get() = files.count { it.kind == AttachmentKind.TEXT }
    val skippedCount: Int get() = files.count { it.kind == AttachmentKind.UEBERSPRUNGEN }
}
