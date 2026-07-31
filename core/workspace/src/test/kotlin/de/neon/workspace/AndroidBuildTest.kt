package de.neon.workspace

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Die Bau-Kette, geprüft an ihren Befehlszeilen.
 *
 * **Was hier zu prüfen ist und was nicht.** Ob `kotlinc` richtig übersetzt, ist die Sache von
 * JetBrains, und ob `aapt2` gültige Ressourcen erzeugt, die von Google. Was **dieses** Projekt
 * falsch machen kann, sind die Aufrufe: ein fehlendes `-no-jdk`, eine Standardbibliothek
 * zweimal im Klassenpfad, eine vergessene `--min-api`. Jeder dieser Fehler zeigt sich erst auf
 * dem Telefon, nach einer Minute Bauzeit, als Meldung eines fremden Programms.
 *
 * Die Attrappen schreiben mit, was aufgerufen wurde. Damit ist die Kette hier prüfbar — und
 * zwar vollständig, einschließlich der Fälle, in denen ein Werkzeug scheitert.
 */
class AndroidBuildTest {

    /** Schreibt jeden Aufruf mit und antwortet nach Drehbuch. */
    private class Mitschrift(
        private val fehlerBei: String? = null,
        private val fehlermeldung: String = "so nicht",
    ) : CommandRunner, JavaRunner {
        val befehle = mutableListOf<List<String>>()

        override fun run(
            command: List<String>,
            workingDir: File,
            env: Map<String, String>,
            timeoutMillis: Long,
        ): CommandResult {
            befehle += command
            return antwort(command.firstOrNull().orEmpty(), workingDir)
        }

        override fun run(
            dexJar: File,
            mainClass: String,
            args: List<String>,
            workingDir: File,
            timeoutMillis: Long,
        ): CommandResult {
            befehle += listOf(mainClass) + args
            return antwort(mainClass, workingDir)
        }

        private fun antwort(wer: String, workingDir: File): CommandResult {
            if (fehlerBei != null && wer.contains(fehlerBei)) {
                return CommandResult(1, "", fehlermeldung, 10)
            }

            // Was das echte Werkzeug hinterlassen würde, damit der nächste Schritt etwas
            // vorfindet. Ohne das prüfte der Test nur die erste Zeile der Kette.
            val bau = File(workingDir, AndroidBuild.BAU_VERZEICHNIS)
            when {
                wer.contains("aapt2") && befehle.last().contains("link") -> {
                    File(bau, "gen").mkdirs()
                    leeresZip(File(bau, "basis.apk"))
                }
                wer.contains("K2JVMCompiler") -> {
                    File(bau, "klassen/de/test").mkdirs()
                    File(bau, "klassen/de/test/Main.class").writeText("x")
                }
                wer.contains("D8") -> {
                    File(bau, "dex").mkdirs()
                    File(bau, "dex/classes.dex").writeText("dex")
                }
                wer.contains("ApkSignerTool") -> {
                    val aus = befehle.last().let { it[it.indexOf("--out") + 1] }
                    File(aus).writeText("signierte apk")
                }
            }
            return CommandResult(0, "", "", 10)
        }

        private fun leeresZip(ziel: File) {
            ziel.parentFile?.mkdirs()
            ZipOutputStream(ziel.outputStream()).use {
                it.putNextEntry(ZipEntry("AndroidManifest.xml"))
                it.write("binaer".toByteArray())
                it.closeEntry()
            }
        }

        /** Der Aufruf, dessen erstes Element [marke] enthält. */
        fun aufruf(marke: String): List<String>? = befehle.firstOrNull { it.first().contains(marke) }
    }

    private fun aufbau(): Triple<Workspace, BuildTools, File> {
        val wurzel = File.createTempFile("neon-build", "").apply {
            delete(); mkdirs(); deleteOnExit()
        }
        val werkzeuge = File(wurzel, "werkzeuge").apply { mkdirs() }
        fun anlegen(name: String) = File(werkzeuge, name).apply { writeText("x") }

        val projekt = Workspace(File(wurzel, "projekt"))
        val tools = BuildTools(
            aapt2 = anlegen("libaapt2.so"),
            d8 = anlegen("d8.dex.jar"),
            kotlinc = anlegen("kotlinc.dex.jar"),
            apksigner = anlegen("apksigner.dex.jar"),
            androidJar = anlegen("android.jar"),
            kotlinStdlib = anlegen("kotlin-stdlib.jar"),
            annotations = anlegen("annotations.jar"),
            keystore = anlegen("debug.keystore"),
        )
        AndroidProjectTemplate.anlegen(
            projekt,
            AndroidProjectTemplate.Vorgabe("de.test.app", "Testapp"),
        )
        return Triple(projekt, tools, werkzeuge)
    }

    @Test
    fun `die ganze Kette laeuft durch und liefert eine signierte APK`() {
        val (projekt, tools, _) = aufbau()
        val mit = Mitschrift()

        val ergebnis = AndroidBuild(tools, mit, mit).baue(projekt, "de.test.app")

        assertTrue(ergebnis.gelungen, ergebnis.bericht)
        assertNotNull(ergebnis.apk)
        assertTrue(ergebnis.apk!!.isFile)

        // Alle fünf Schritte, in dieser Reihenfolge. Keiner lässt sich weglassen.
        val reihe = mit.befehle.map { it.first().substringAfterLast('/') }
        assertTrue(reihe.any { it.contains("libaapt2") }, reihe.toString())
        assertTrue(reihe.contains(AndroidBuild.KOTLINC_MAIN), reihe.toString())
        assertTrue(reihe.contains(AndroidBuild.D8_MAIN), reihe.toString())
        assertTrue(reihe.contains(AndroidBuild.APKSIGNER_MAIN), reihe.toString())
    }

    @Test
    fun `der Kotlin-Compiler bekommt no-jdk`() {
        val (projekt, tools, _) = aufbau()
        val mit = Mitschrift()
        AndroidBuild(tools, mit, mit).baue(projekt, "de.test.app")

        val kotlin = assertNotNull(mit.aufruf("K2JVMCompiler"))

        // Auf einem Telefon gibt es keine JDK. Ohne den Schalter sucht der Compiler rt.jar
        // und bricht mit einer Meldung über eine kaputte Java-Installation ab — was hier
        // niemanden weiterbringt.
        assertTrue(kotlin.contains("-no-jdk"), kotlin.toString())
    }

    @Test
    fun `die Standardbibliothek steht genau einmal im Klassenpfad`() {
        val (projekt, tools, _) = aufbau()
        val mit = Mitschrift()
        AndroidBuild(tools, mit, mit).baue(projekt, "de.test.app")

        val kotlin = assertNotNull(mit.aufruf("K2JVMCompiler"))
        val klassenpfad = kotlin[kotlin.indexOf("-classpath") + 1]

        assertTrue(klassenpfad.contains("kotlin-stdlib.jar"), klassenpfad)
        assertTrue(klassenpfad.contains("android.jar"), klassenpfad)
        // Und **nicht** zusätzlich über -include-runtime oder als Vorgabe: Zwei Fassungen
        // derselben Klassen im Klassenpfad sind ein Konflikt, den der Compiler erst spät
        // meldet.
        assertTrue(kotlin.contains("-no-stdlib"), kotlin.toString())
    }

    @Test
    fun `die Standardbibliothek landet trotzdem in der App`() {
        val (projekt, tools, _) = aufbau()
        val mit = Mitschrift()
        AndroidBuild(tools, mit, mit).baue(projekt, "de.test.app")

        val d8 = assertNotNull(mit.aufruf(AndroidBuild.D8_MAIN))

        // Der Gegenpol zum Test darüber: Beim Übersetzen ist sie nur Bezug, beim Dexen muss
        // sie mit — auf dem Zielgerät gibt es kein installiertes Kotlin.
        assertTrue(d8.any { it.endsWith("kotlin-stdlib.jar") }, d8.toString())
        assertTrue(d8.contains("--min-api"), d8.toString())
        assertEquals(AndroidBuild.MIN_SDK.toString(), d8[d8.indexOf("--min-api") + 1])
    }

    @Test
    fun `aapt2 bekommt Manifest, Plattform und die SDK-Grenzen`() {
        val (projekt, tools, _) = aufbau()
        val mit = Mitschrift()
        AndroidBuild(tools, mit, mit).baue(projekt, "de.test.app")

        val link = assertNotNull(mit.befehle.firstOrNull { it.contains("link") })

        assertTrue(link.contains("--manifest"), link.toString())
        assertTrue(link.contains("-I"), link.toString())
        assertTrue(link.any { it.endsWith("android.jar") }, link.toString())
        assertEquals(
            AndroidBuild.MIN_SDK.toString(),
            link[link.indexOf("--min-sdk-version") + 1],
        )
    }

    @Test
    fun `ein Fehler des Compilers bricht die Kette ab und nennt den Schritt`() {
        val (projekt, tools, _) = aufbau()
        val mit = Mitschrift(
            fehlerBei = "K2JVMCompiler",
            fehlermeldung = "MainActivity.kt:12:5: error: unresolved reference: Buton",
        )

        val ergebnis = AndroidBuild(tools, mit, mit).baue(projekt, "de.test.app")

        assertFalse(ergebnis.gelungen)
        assertEquals("Kotlin-Compiler", ergebnis.schritt)
        // Die Fehlermeldung des Compilers ist das Wertvollste am ganzen Fehlschlag: Sie nennt
        // Datei und Zeile, und damit kann das Modell den Code berichtigen.
        assertTrue(ergebnis.bericht.contains("unresolved reference"), ergebnis.bericht)
        assertTrue(ergebnis.bericht.contains("12:5"), ergebnis.bericht)

        // Und danach wird nichts mehr versucht: d8 auf nicht vorhandene Klassen loszulassen
        // erzeugt nur eine zweite, verwirrendere Meldung.
        assertTrue(mit.aufruf(AndroidBuild.D8_MAIN) == null, mit.befehle.toString())
    }

    @Test
    fun `fehlende Werkzeuge werden vorher gemeldet, nicht unterwegs`() {
        val (projekt, tools, werkzeuge) = aufbau()
        File(werkzeuge, "kotlinc.dex.jar").delete()
        val mit = Mitschrift()

        val ergebnis = AndroidBuild(tools, mit, mit).baue(projekt, "de.test.app")

        assertFalse(ergebnis.gelungen)
        assertEquals("Vorbereitung", ergebnis.schritt)
        assertTrue(ergebnis.bericht.contains("kotlinc"), ergebnis.bericht)
        // Nichts angefasst: Ein halber Bauvorgang hinterlässt Zwischenstände, die beim
        // nächsten Anlauf verwirren.
        assertTrue(mit.befehle.isEmpty())
    }

    @Test
    fun `ohne Manifest gibt es eine Erklaerung statt einer aapt2-Meldung`() {
        val (projekt, tools, _) = aufbau()
        projekt.datei("AndroidManifest.xml")!!.delete()
        val mit = Mitschrift()

        val ergebnis = AndroidBuild(tools, mit, mit).baue(projekt, "de.test.app")

        assertFalse(ergebnis.gelungen)
        assertTrue(ergebnis.bericht.contains("AndroidManifest"), ergebnis.bericht)
    }

    /**
     * Die Dex-Dateien müssen lückenlos durchnummeriert im Archiv liegen.
     *
     * Der Android-Klassenlader sucht `classes.dex`, `classes2.dex`, `classes3.dex` und hört
     * bei der ersten Lücke auf. Fehlt `classes2.dex`, fehlt die Hälfte der App — ohne jede
     * Meldung, bis eine Klasse gebraucht wird.
     */
    @Test
    fun `der Zusammenbau nummeriert die Dex-Dateien lueckenlos`() {
        val verzeichnis = File.createTempFile("neon-apk", "").apply {
            delete(); mkdirs(); deleteOnExit()
        }
        val apk = File(verzeichnis, "a.apk")
        ZipOutputStream(apk.outputStream()).use {
            it.putNextEntry(ZipEntry("AndroidManifest.xml")); it.write(byteArrayOf(1)); it.closeEntry()
            it.putNextEntry(ZipEntry("resources.arsc")); it.write(byteArrayOf(2)); it.closeEntry()
        }
        val dex = (1..3).map { n ->
            File(verzeichnis, "classes$n.dex").apply { writeText("dex$n") }
        }

        ApkAssembler.fuegeEin(apk, dex)

        val namen = ZipFile(apk).use { z -> z.entries().asSequence().map { it.name }.toList() }
        assertTrue(namen.contains("AndroidManifest.xml"), namen.toString())
        assertTrue(namen.contains("resources.arsc"), namen.toString())
        assertEquals(listOf("classes.dex", "classes2.dex", "classes3.dex"), namen.filter { it.endsWith(".dex") })
    }

    @Test
    fun `ein zweiter Bauvorgang laesst keine alten Dex-Dateien liegen`() {
        val verzeichnis = File.createTempFile("neon-apk2", "").apply {
            delete(); mkdirs(); deleteOnExit()
        }
        val apk = File(verzeichnis, "a.apk")
        ZipOutputStream(apk.outputStream()).use {
            it.putNextEntry(ZipEntry("AndroidManifest.xml")); it.write(byteArrayOf(1)); it.closeEntry()
        }
        val alt = File(verzeichnis, "classes.dex").apply { writeText("alt") }
        ApkAssembler.fuegeEin(apk, listOf(alt))

        val neu = File(verzeichnis, "classes.dex").apply { writeText("neu") }
        ApkAssembler.fuegeEin(apk, listOf(neu))

        // Zwei Fassungen desselben Codes im Archiv, und welche gewinnt, entscheidet die
        // Reihenfolge — ein Fehler, der sich als „meine Änderung wirkt nicht" äußert.
        val eintraege = ZipFile(apk).use { z ->
            z.entries().asSequence().map { it.name to z.getInputStream(it).readBytes().decodeToString() }.toList()
        }
        assertEquals(1, eintraege.count { it.first.endsWith(".dex") }, eintraege.toString())
        assertEquals("neu", eintraege.first { it.first.endsWith(".dex") }.second)
    }
}
