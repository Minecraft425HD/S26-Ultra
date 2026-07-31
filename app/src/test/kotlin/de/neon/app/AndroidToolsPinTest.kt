package de.neon.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Jede fremde Binärdatei ist festgenagelt.
 *
 * **Der Anlass ist mein eigener erster Entwurf.** `scripts/fetch-android-tools.sh` holt aapt2
 * und dessen Abhängigkeiten aus dem Termux-Depot — Programme, die dieses Projekt nicht selbst
 * baut und deren Herkunft es nicht prüfen kann. Das Einzige, was daraus etwas Verlässliches
 * macht, sind zwei Dinge: eine feste Fassung und eine Prüfsumme.
 *
 * Im ersten Entwurf standen bei **acht von neun** Paketen leere Prüfsummen. Das Skript nahm
 * es hin, holte alles und meldete Erfolg. Wer später ein Paket hinzufügt, wird denselben
 * Weg des geringsten Widerstands nehmen — und dann ist die Festnagelung eine Behauptung.
 *
 * Deshalb wird hier nachgesehen und nicht ausprobiert: Ein Test, der die Dateien wirklich
 * holt, bräuchte Netz und würde bei jedem Lauf zehn Megabyte ziehen. Die Regel dagegen lässt
 * sich in einer Millisekunde prüfen. Dieselbe Bauart wie `PortableRegexTest`.
 */
class AndroidToolsPinTest {

    private val skript: String by lazy {
        File(wurzel(), "scripts/fetch-android-tools.sh")
            .also { assertTrue(it.isFile, "${it.path} fehlt") }
            .readText()
    }

    /** Die Zeilen der PAKETE-Liste, ohne Kommentare und Klammern. */
    private val pakete: List<String> by lazy {
        skript.substringAfter("PAKETE=(").substringBefore("\n)")
            .lines()
            .map { it.trim() }
            .filter { it.startsWith("\"") }
    }

    @Test
    fun `es gibt ueberhaupt Pakete zu pruefen`() {
        // Ohne diese Prüfung wäre der Test unten grün, wenn sich die Liste umbenennt — und
        // dann prüft er nichts mehr, ohne es zu sagen.
        assertTrue(pakete.size >= 5, "nur ${pakete.size} Pakete gefunden: $pakete")
    }

    @Test
    fun `jedes Paket hat eine vollstaendige SHA-256-Summe`() {
        val hex = Regex("^[0-9a-f]{64}$")

        pakete.forEach { zeile ->
            val felder = zeile.trim('"').split("|")
            assertEquals(3, felder.size, "Zeile hat nicht drei Felder: $zeile")

            val (name, pfad, summe) = felder
            assertTrue(name.isNotBlank(), "Paket ohne Namen: $zeile")
            assertTrue(pfad.endsWith(".deb"), "Pfad ist kein Paket: $zeile")
            assertTrue(
                hex.matches(summe),
                "„$name\" ohne gültige SHA-256-Summe. Genau das war der erste Entwurf: " +
                    "acht von neun Paketen ohne Prüfsumme, und das Skript nahm es hin.",
            )
        }
    }

    @Test
    fun `jede Fassung ist festgenagelt und nicht die neueste`() {
        pakete.forEach { zeile ->
            val pfad = zeile.trim('"').split("|")[1]
            // Im Dateinamen steht die Fassung. Ein Pfad ohne Fassungsnummer wäre ein
            // bewegliches Ziel — dieselbe Lehre wie bei `git pull --ff-only` in
            // build-llama-server.sh, wo nebenbei ein neuer Programmstand hereinkam.
            assertTrue(
                pfad.contains(Regex("_[0-9]")),
                "keine Fassungsnummer im Pfad: $pfad",
            )
            assertTrue(!pfad.contains("latest"), "bewegliches Ziel: $pfad")
        }
    }

    /**
     * Das Skript prüft, was es abgelegt hat.
     *
     * Die drei Prüfungen am Ende des Skripts sind der Grund, warum eine fremde Binärdatei
     * hier vertretbar ist: 16-KB-Seiten, keine versionierten Verweise, keine fehlende
     * Abhängigkeit. Fällt eine davon weg, ist es wieder ein Sprung ins Dunkle.
     */
    @Test
    fun `die Selbstpruefungen des Skripts sind noch da`() {
        listOf(
            "16384" to "die Prüfung auf 16-KB-Seiten",
            "print-needed" to "die Prüfung der Abhängigkeiten",
            "set-rpath" to "das Umhängen des RUNPATH",
            "sha256sum" to "der Prüfsummenvergleich",
        ).forEach { (marke, was) ->
            assertTrue(skript.contains(marke), "$was fehlt im Skript")
        }
    }

    /**
     * Das Skript fasst nur an, was es selbst mitgebracht hat.
     *
     * Auch das ein eigener Fehler: Die Patch-Schleife lief zuerst über `lib*.so` und damit
     * über `libllama-server.so` — eine eingecheckte Datei, die einem anderen Skript gehört.
     * Sie tauchte danach als geändert auf.
     */
    @Test
    fun `patchelf laeuft nicht ueber das ganze Verzeichnis`() {
        val patchTeil = skript.substringAfter("RUNPATH und Abhängigkeitsnamen anpassen")
            .substringBefore("unbenutzte Bibliotheken")

        assertTrue(
            patchTeil.contains("MITGEBRACHT"),
            "Die Patch-Schleife geht nicht über die eigene Liste",
        )
        assertTrue(
            !patchTeil.contains("\$TARGET_DIR\"/lib*.so"),
            "Die Patch-Schleife läuft wieder über das ganze Verzeichnis — dort liegt auch " +
                "libllama-server.so, das einem anderen Skript gehört.",
        )
    }

    private fun wurzel(): File {
        var verzeichnis = File("").absoluteFile
        while (!File(verzeichnis, "settings.gradle.kts").exists()) {
            verzeichnis = verzeichnis.parentFile ?: error("Projektwurzel nicht gefunden")
        }
        return verzeichnis
    }
}
