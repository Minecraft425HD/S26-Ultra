package de.neon.attachments

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Nimmt Anhänge auf: erkennt, packt aus, zerlegt.
 *
 * Der ganze Vorgang ist bewusst frei von Android. Was hereinkommt, ist ein Name und ein
 * Strom — ob der aus einer Dateiauswahl, einem Ordnerbaum, einem Archiv oder aus dem
 * Speicher stammt, spielt hier keine Rolle. Das ist der Grund, warum sich Auspacken und
 * Zerlegen ohne Gerät prüfen lassen.
 */
class AttachmentIngest(
    private val chunker: TextChunker = TextChunker(),
    /** Obergrenze je Datei. Darüber wird vermerkt statt gelesen. */
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    /** Obergrenze über alles, damit ein versehentlich gewähltes Wurzelverzeichnis nicht alles frisst. */
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    /** Wie tief in verschachtelte Archive hinein. */
    private val maxArchiveDepth: Int = DEFAULT_MAX_ARCHIVE_DEPTH,
) {

    fun ingest(sources: List<AttachmentSource>): IngestResult {
        val dateien = mutableListOf<AttachmentFile>()
        val abschnitte = mutableListOf<AttachmentChunk>()
        var verbraucht = 0L

        fun aufnehmen(source: AttachmentSource, tiefe: Int) {
            if (verbraucht >= maxTotalBytes) {
                dateien += uebersprungen(
                    source,
                    "Die Gesamtgrenze von ${megabyte(maxTotalBytes)} ist erreicht.",
                )
                return
            }

            if (source.sizeBytes in 1..Long.MAX_VALUE && source.sizeBytes > maxFileBytes) {
                dateien += uebersprungen(
                    source,
                    "Größer als ${megabyte(maxFileBytes)} — nicht gelesen.",
                )
                return
            }

            if (istArchiv(source.name)) {
                if (tiefe >= maxArchiveDepth) {
                    dateien += uebersprungen(source, "Archiv zu tief verschachtelt.")
                    return
                }
                val eintraege = runCatching { entpacken(source) }.getOrElse {
                    dateien += uebersprungen(source, "Archiv nicht lesbar: ${it.message}")
                    return
                }
                if (eintraege.isEmpty()) {
                    // ZipInputStream wirft bei einer Datei, die gar kein Archiv ist, keinen
                    // Fehler — es liefert schlicht keinen Eintrag. Ohne diesen Zweig stünde
                    // dort "Archiv, 0 Einträge", und niemand käme darauf, dass die Datei
                    // beschädigt ist oder nie ein Archiv war.
                    dateien += uebersprungen(source, "Kein lesbares Archiv oder leer.")
                    return
                }
                dateien += AttachmentFile(
                    name = source.name,
                    path = source.path,
                    sizeBytes = source.sizeBytes,
                    kind = AttachmentKind.ARCHIV,
                    note = "${eintraege.size} Einträge",
                )
                eintraege.forEach { aufnehmen(it, tiefe + 1) }
                return
            }

            val inhalt = runCatching { lesen(source) }.getOrElse {
                dateien += uebersprungen(source, "Nicht lesbar: ${it.message}")
                return
            }
            verbraucht += inhalt.size

            if (!TextDetection.isText(inhalt.copyOf(minOf(inhalt.size, TextDetection.PROBE_BYTES)))) {
                // Ausdrücklich vermerkt statt verschwiegen: Neon soll sagen können, dass die
                // Datei da ist, er sie aber nicht lesen kann.
                dateien += AttachmentFile(
                    name = source.name,
                    path = source.path,
                    sizeBytes = inhalt.size.toLong(),
                    kind = AttachmentKind.BINAER,
                    note = "Binärdatei — Inhalt nicht durchsuchbar.",
                )
                return
            }

            val neue = chunker.chunk(source.name, source.path, inhalt.decodeToString())
            abschnitte += neue
            dateien += AttachmentFile(
                name = source.name,
                path = source.path,
                sizeBytes = inhalt.size.toLong(),
                kind = AttachmentKind.TEXT,
                chunkCount = neue.size,
            )
        }

        sources.forEach { aufnehmen(it, tiefe = 0) }
        return IngestResult(dateien, abschnitte)
    }

    private fun lesen(source: AttachmentSource): ByteArray =
        source.open().use { it.readAtMost(maxFileBytes) }

    /**
     * Packt ein Archiv in den Speicher aus.
     *
     * Einträge mit `..` im Pfad werden verworfen. Hier wird zwar nichts auf die Platte
     * geschrieben, sodass ein Ausbruch aus dem Verzeichnis gar nicht möglich wäre — aber ein
     * solcher Pfad ist ohnehin nichts, was man in einen Prompt schreiben möchte.
     */
    private fun entpacken(source: AttachmentSource): List<AttachmentSource> {
        val ergebnis = mutableListOf<AttachmentSource>()
        ZipInputStream(source.open().buffered()).use { zip ->
            while (true) {
                val eintrag = zip.nextEntry ?: break
                if (eintrag.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val pfad = eintrag.name
                if (pfad.contains("..") || pfad.startsWith("/")) {
                    zip.closeEntry()
                    continue
                }

                val bytes = zip.readAtMost(maxFileBytes)
                zip.closeEntry()

                ergebnis += BytesSource(
                    name = pfad.substringAfterLast('/'),
                    path = "${source.path}!/$pfad",
                    bytes = bytes,
                )
            }
        }
        return ergebnis
    }

    private fun uebersprungen(source: AttachmentSource, grund: String) = AttachmentFile(
        name = source.name,
        path = source.path,
        sizeBytes = source.sizeBytes,
        kind = AttachmentKind.UEBERSPRUNGEN,
        note = grund,
    )

    /**
     * Liest höchstens [limit] Bytes.
     *
     * Nicht `readBytes()`: Ein ZIP-Archiv kann beim Auspacken um ein Vielfaches wachsen, und
     * ein Strom aus einer Dateiauswahl meldet seine Länge nicht immer ehrlich. Die Grenze
     * muss beim Lesen greifen, nicht davor.
     */
    private fun InputStream.readAtMost(limit: Long): ByteArray {
        val aus = ByteArrayOutputStream()
        val puffer = ByteArray(64 * 1024)
        var gesamt = 0L
        while (gesamt < limit) {
            val gelesen = read(puffer, 0, minOf(puffer.size.toLong(), limit - gesamt).toInt())
            if (gelesen < 0) break
            aus.write(puffer, 0, gelesen)
            gesamt += gelesen
        }
        return aus.toByteArray()
    }

    private fun istArchiv(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in ARCHIVE_ENDUNGEN

    companion object {
        /** Nur ZIP. JAR und APK sind ZIP, deshalb stehen sie mit dabei. */
        val ARCHIVE_ENDUNGEN = setOf("zip", "jar", "apk", "aar")

        const val DEFAULT_MAX_FILE_BYTES = 8L * 1024 * 1024
        const val DEFAULT_MAX_TOTAL_BYTES = 64L * 1024 * 1024
        const val DEFAULT_MAX_ARCHIVE_DEPTH = 2

        private fun megabyte(bytes: Long): String = "${bytes / 1024 / 1024} MB"
    }
}
