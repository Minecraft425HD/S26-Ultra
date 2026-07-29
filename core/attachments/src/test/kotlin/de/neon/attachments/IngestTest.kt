package de.neon.attachments

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Erkennen, Auspacken und Zerlegen — die Stellen mit echter Logik, ohne Android. */
class IngestTest {

    private fun quelle(name: String, inhalt: String, pfad: String = name) =
        BytesSource(name, pfad, inhalt.toByteArray())

    // ---- Erkennung -------------------------------------------------------------------

    @Test
    fun `Quelltext gilt als Text`() {
        assertTrue(TextDetection.isText("fun main() { println(\"hallo\") }".toByteArray()))
    }

    @Test
    fun `Umlaute und Emoji gelten als Text`() {
        assertTrue(TextDetection.isText("Größe: 3 m² — fertig 🎉".toByteArray()))
    }

    @Test
    fun `ein Nullbyte macht eine Binaerdatei`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0, 0, 13)
        assertTrue(!TextDetection.isText(png))
    }

    @Test
    fun `eine leere Datei ist kein Problem`() {
        assertTrue(TextDetection.isText(ByteArray(0)))
    }

    @Test
    fun `eine abgeschnittene Mehrbytefolge am Ende zaehlt nicht als kaputt`() {
        // Die Probe endet mitten in der Datei — das ist der Normalfall, kein Fehler.
        val text = "Straße".toByteArray()
        val abgeschnitten = text.copyOf(text.size - 1)
        assertTrue(TextDetection.isText(abgeschnitten))
    }

    @Test
    fun `eine Logdatei mit einem einzelnen kaputten Byte bleibt lesbar`() {
        // Genau die Datei, die man anhängt, wenn etwas schiefgegangen ist.
        val zeilen = (1..50).joinToString("\n") { "2026-07-29 08:0$it INFO alles in Ordnung" }
        val bytes = zeilen.toByteArray().toMutableList()
        bytes[100] = 0xFF.toByte()
        assertTrue(TextDetection.isText(bytes.toByteArray()))
    }

    @Test
    fun `eine Datei mit BOM gilt als Text und der Marker verschwindet`() {
        val mitBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hallo".toByteArray()
        assertTrue(TextDetection.isText(mitBom))

        val abschnitte = TextChunker().chunk("a.txt", "a.txt", mitBom.decodeToString())
        assertEquals("hallo", abschnitte.single().text)
    }

    // ---- Zerlegen --------------------------------------------------------------------

    @Test
    fun `kurzer Text ergibt genau einen Abschnitt`() {
        val abschnitte = TextChunker().chunk("notiz.txt", "notiz.txt", "eine kurze notiz")
        assertEquals(1, abschnitte.size)
        assertEquals(1, abschnitte.single().firstLine)
    }

    @Test
    fun `langer Text wird zerlegt und die Zeilennummern stimmen`() {
        val zeilen = (1..300).joinToString("\n") { "zeile $it mit ein paar woertern darin" }
        val abschnitte = TextChunker(targetWords = 100, overlapWords = 20)
            .chunk("gross.txt", "pfad/gross.txt", zeilen)

        assertTrue(abschnitte.size > 1, "nicht zerlegt: ${abschnitte.size}")
        assertEquals(1, abschnitte.first().firstLine)
        assertEquals(300, abschnitte.last().lastLine)

        // Jeder Abschnitt muss die Zeilen enthalten, die er zu enthalten behauptet.
        abschnitte.forEach {
            assertTrue(
                it.text.contains("zeile ${it.firstLine} "),
                "Abschnitt ${it.quelle} beginnt nicht bei Zeile ${it.firstLine}",
            )
        }
    }

    @Test
    fun `die Abschnitte ueberlappen sich`() {
        val zeilen = (1..200).joinToString("\n") { "zeile $it wort wort wort wort" }
        val abschnitte = TextChunker(targetWords = 50, overlapWords = 20)
            .chunk("a.txt", "a.txt", zeilen)

        val erster = abschnitte[0]
        val zweiter = abschnitte[1]
        assertTrue(
            zweiter.firstLine <= erster.lastLine,
            "keine Überlappung: ${erster.quelle} dann ${zweiter.quelle}",
        )
    }

    @Test
    fun `eine einzelne riesige Zeile bringt den Zerleger nicht in eine Endlosschleife`() {
        // Minifiziertes JavaScript, eine Zeile, hunderttausend Zeichen. Ohne Sicherung im
        // Vorrücken liefe das ewig.
        val eineZeile = (1..20_000).joinToString(" ") { "wort$it" }
        val abschnitte = TextChunker(targetWords = 50, overlapWords = 20)
            .chunk("min.js", "min.js", eineZeile)

        assertEquals(1, abschnitte.size)
        assertEquals(1, abschnitte.single().firstLine)
    }

    @Test
    fun `eine Datei nur aus Leerzeilen ergibt nichts`() {
        assertTrue(TextChunker().chunk("leer.txt", "leer.txt", "\n\n   \n\n").isEmpty())
    }

    @Test
    fun `die Quellenangabe nennt Pfad und Zeilenbereich`() {
        val abschnitt = AttachmentChunk("a.kt", "src/a.kt", 10, 42, "x")
        assertEquals("src/a.kt:10-42", abschnitt.quelle)
        assertEquals("src/a.kt:7", abschnitt.copy(firstLine = 7, lastLine = 7).quelle)
    }

    // ---- Aufnahme --------------------------------------------------------------------

    @Test
    fun `Textdateien werden zerlegt, Binaerdateien vermerkt`() {
        val ergebnis = AttachmentIngest().ingest(
            listOf(
                quelle("a.kt", "fun a() = 1"),
                BytesSource("bild.png", "bild.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 1, 2)),
            )
        )

        assertEquals(1, ergebnis.textFileCount)
        assertEquals(1, ergebnis.chunks.size)

        val binaer = ergebnis.files.single { it.kind == AttachmentKind.BINAER }
        assertEquals("bild.png", binaer.name)
        // Nicht verschweigen: Neon soll sagen können, dass die Datei da ist.
        assertNotNull(binaer.note)
    }

    @Test
    fun `ein ZIP wird ausgepackt und die Herkunft bleibt erkennbar`() {
        val zip = zipMit(
            "src/haupt.kt" to "fun haupt() = println(\"hallo\")",
            "docs/liesmich.md" to "# Anleitung\n\nSo geht das.",
        )

        val ergebnis = AttachmentIngest().ingest(listOf(BytesSource("projekt.zip", "projekt.zip", zip)))

        assertEquals(1, ergebnis.files.count { it.kind == AttachmentKind.ARCHIV })
        assertEquals(2, ergebnis.textFileCount)

        val pfade = ergebnis.chunks.map { it.filePath }
        assertTrue(pfade.any { it == "projekt.zip!/src/haupt.kt" }, "Pfade: $pfade")
        assertTrue(pfade.any { it == "projekt.zip!/docs/liesmich.md" }, "Pfade: $pfade")
    }

    @Test
    fun `Verzeichniseintraege im ZIP erzeugen keine leeren Dateien`() {
        val aus = ByteArrayOutputStream()
        ZipOutputStream(aus).use { zip ->
            zip.putNextEntry(ZipEntry("ordner/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("ordner/datei.txt"))
            zip.write("inhalt".toByteArray())
            zip.closeEntry()
        }

        val ergebnis = AttachmentIngest().ingest(
            listOf(BytesSource("a.zip", "a.zip", aus.toByteArray()))
        )
        assertEquals(1, ergebnis.textFileCount)
    }

    @Test
    fun `ein Eintrag mit Pfadausbruch wird verworfen`() {
        val zip = zipMit("../../etc/passwd" to "root:x:0:0", "brav.txt" to "harmlos")
        val ergebnis = AttachmentIngest().ingest(listOf(BytesSource("b.zip", "b.zip", zip)))

        assertEquals(1, ergebnis.textFileCount)
        assertTrue(ergebnis.chunks.none { it.filePath.contains("passwd") })
    }

    @Test
    fun `ein verschachteltes Archiv wird eine Ebene tief geoeffnet`() {
        val innen = zipMit("tief.txt" to "ganz unten")
        val aussen = zipMitBytes("innen.zip" to innen)

        val ergebnis = AttachmentIngest().ingest(
            listOf(BytesSource("aussen.zip", "aussen.zip", aussen))
        )
        assertTrue(
            ergebnis.chunks.any { it.text.contains("ganz unten") },
            "Pfade: ${ergebnis.chunks.map { it.filePath }}",
        )
    }

    @Test
    fun `eine zu grosse Datei wird vermerkt statt gelesen`() {
        val gross = BytesSource("riesig.log", "riesig.log", ByteArray(3 * 1024 * 1024) { 'a'.code.toByte() })
        val ergebnis = AttachmentIngest(maxFileBytes = 1024 * 1024).ingest(listOf(gross))

        val datei = ergebnis.files.single()
        assertEquals(AttachmentKind.UEBERSPRUNGEN, datei.kind)
        assertTrue(datei.note!!.contains("MB"), "Grund: ${datei.note}")
        assertTrue(ergebnis.chunks.isEmpty())
    }

    @Test
    fun `die Gesamtgrenze schuetzt vor einem versehentlich gewaehlten Wurzelverzeichnis`() {
        val viele = (1..20).map { quelle("d$it.txt", "x ".repeat(30_000)) }
        val ergebnis = AttachmentIngest(maxTotalBytes = 100_000).ingest(viele)

        assertTrue(ergebnis.skippedCount > 0, "nichts übersprungen — die Grenze griff nicht")
        assertTrue(ergebnis.textFileCount < 20)
    }

    @Test
    fun `ein kaputtes Archiv nimmt den Rest nicht mit`() {
        val ergebnis = AttachmentIngest().ingest(
            listOf(
                BytesSource("kaputt.zip", "kaputt.zip", "das ist kein zip".toByteArray()),
                quelle("gut.txt", "hier steht etwas"),
            )
        )
        assertEquals(1, ergebnis.textFileCount)
        assertEquals(1, ergebnis.skippedCount)
    }

    private fun zipMit(vararg eintraege: Pair<String, String>): ByteArray =
        zipMitBytes(*eintraege.map { it.first to it.second.toByteArray() }.toTypedArray())

    private fun zipMitBytes(vararg eintraege: Pair<String, ByteArray>): ByteArray {
        val aus = ByteArrayOutputStream()
        ZipOutputStream(aus).use { zip ->
            eintraege.forEach { (pfad, bytes) ->
                zip.putNextEntry(ZipEntry(pfad))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return aus.toByteArray()
    }
}
