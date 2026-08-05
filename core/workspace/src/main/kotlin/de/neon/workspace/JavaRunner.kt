package de.neon.workspace

import java.io.File

/**
 * Führt ein vor-dextes Java-Werkzeug aus.
 *
 * **Warum es diese Schnittstelle gibt.** `d8`, `kotlinc` und `apksigner` sind Java-Programme.
 * Auf einem Rechner startet man sie mit `java -jar`; auf Android gibt es keine JVM. Sie
 * kommen deshalb als Dex mit und werden anders gestartet — aber *wie*, ist eine Eigenschaft
 * des Geräts und nicht der Bau-Kette. Deshalb hier eine Schnittstelle und dort eine
 * Umsetzung.
 *
 * Der Nebengewinn ist der eigentliche: [AndroidBuild] wird damit prüfbar. Was dort zu prüfen
 * ist, sind die **Befehlszeilen** — ob `-no-jdk` mitgeht, ob der Klassenpfad stimmt, ob die
 * Standardbibliothek genau einmal vorkommt. Ob `kotlinc` danach richtig übersetzt, ist die
 * Sache von JetBrains.
 */
fun interface JavaRunner {
    fun run(
        dexJar: File,
        mainClass: String,
        args: List<String>,
        workingDir: File,
        timeoutMillis: Long,
    ): CommandResult
}

/**
 * Startet ein Dex-Werkzeug als eigenen Prozess über `dalvikvm`.
 *
 * **Warum ein Prozess und keine Einbettung.** Der bequeme Weg wäre ein `DexClassLoader` im
 * eigenen Prozess und ein Aufruf von `main` per Reflexion. Er hat zwei Fehler, und beide sind
 * unangenehm:
 *
 *  - **`System.exit`.** `d8` ruft ihn im Fehlerfall auf. Im eigenen Prozess nimmt das Neon
 *    mit — ein Tippfehler im Quelltext des Nutzers beendet den Assistenten.
 *  - **Speicher.** Der Kotlin-Compiler belegt für ein kleines Projekt mehrere hundert
 *    Megabyte. Im eigenen Prozess zählt das gegen Neons Budget, und der Low-Memory-Killer
 *    nimmt sich den dicksten Brocken — was in diesem Projekt schon sechsmal Neon war.
 *
 * `dalvikvm` gehört zur Plattform und führt eine Dex-Datei mit einer Hauptklasse aus. Fehlt
 * es, sagt diese Klasse das in einem Satz, statt es zu verschweigen.
 */
class DalvikRunner(
    /** Wohin die Laufzeit ihre Zwischenstände legen darf. */
    private val cacheDir: File,
    private val runner: CommandRunner = ProcessCommandRunner(),
    /**
     * Wo gesucht wird. Nur für Tests austauschbar — auf dem Gerät gilt [DALVIKVM].
     */
    private val kandidaten: List<String> = DALVIKVM,
    /**
     * Wie viel Speicher die Laufzeit höchstens nehmen darf.
     *
     * Der Kotlin-Compiler ist gierig; ohne Grenze nimmt er sich, was da ist, und das Gerät
     * beendet dann irgendetwas. Ein Gigabyte reicht für ein kleines Projekt und lässt Neon
     * und dem Sprachmodell ihren Platz.
     */
    private val heapMegabyte: Int = 1024,
) : JavaRunner {

    /**
     * Die gefundene Laufzeit, oder `null`.
     *
     * Damit beim Start im Protokoll steht, ob es überhaupt eine gibt — und nicht erst dann,
     * wenn nach einer Minute Bauzeit der dritte Schritt scheitert. Bei jedem Aufruf neu
     * nachgesehen: Ein gemerkter Pfad wäre eine zweite Wahrheit über das Dateisystem.
     */
    fun gefundeneLaufzeit(): String? = kandidaten.firstOrNull { File(it).canExecute() }

    /** Was an jedem geprüften Ort steht — für das Protokoll und für die Fehlermeldung. */
    fun befund(): String = kandidaten.joinToString("\n") { pfad ->
        val datei = File(pfad)
        val zustand = when {
            !datei.exists() -> "gibt es nicht"
            !datei.canExecute() -> "vorhanden, aber nicht ausführbar"
            else -> "vorhanden"
        }
        "  $pfad — $zustand"
    }

    override fun run(
        dexJar: File,
        mainClass: String,
        args: List<String>,
        workingDir: File,
        timeoutMillis: Long,
    ): CommandResult {
        val dalvik = gefundeneLaufzeit()
            ?: return CommandResult(
                exitCode = -1,
                stdout = "",
                // **Mit dem Befund je Pfad, und das ist der eigentliche Punkt.**
                //
                // Vorher stand hier ein Satz ohne eine einzige Tatsache: „dalvikvm ist auf
                // diesem Gerät nicht ausführbar." Damit ließ sich nicht unterscheiden, ob
                // die Datei fehlt, ob sie da ist und nicht ausgeführt werden darf, oder ob
                // an der falschen Stelle gesucht wurde — und genau das war der Fall. Zwei
                // Wochen lang war diese Meldung die einzige Auskunft, und sie hat gar
                // nichts gesagt.
                stderr = "Keine Laufzeit gefunden. Ohne sie lassen sich die Java-Werkzeuge " +
                    "der Bau-Kette nicht starten. Gesucht wurde:\n" + befund(),
                durationMillis = 0,
            )
        if (!dexJar.isFile) {
            return CommandResult(
                exitCode = -1, stdout = "",
                stderr = "${dexJar.name} fehlt — das Auspacken der Werkzeuge ist wohl noch " +
                    "nicht durch.",
                durationMillis = 0,
            )
        }

        val befehl = buildList {
            add(dalvik)
            add("-Xmx${heapMegabyte}m")
            add("-cp"); add(dexJar.absolutePath)
            add(mainClass)
            addAll(args)
        }

        return runner.run(
            command = befehl,
            workingDir = workingDir,
            env = mapOf(
                // Ohne ein beschreibbares Verzeichnis für den optimierten Code fällt die
                // Laufzeit auf einen Pfad zurück, in den eine App nicht schreiben darf.
                "ANDROID_DATA" to cacheDir.apply { mkdirs() }.absolutePath,
                "TMPDIR" to File(cacheDir, "tmp").apply { mkdirs() }.absolutePath,
            ),
            timeoutMillis = timeoutMillis,
        )
    }

    companion object {
        /**
         * Wo die Laufzeit liegt — und warum es dafür sechs Pfade braucht.
         *
         * **Hier stand `/system/bin/`, und dort liegt sie seit Android 10 nicht mehr.** ART
         * ist seither ein APEX-Modul: Die ausführbaren Dateien liegen unter
         * `/apex/com.android.art/bin/`, und `/system/bin/dalvikvm` gibt es auf vielen
         * Abbildern gar nicht mehr — auf dem S26 Ultra offenbar auch nicht. Zwei Wochen lang
         * scheiterte jeder Bauversuch an dieser Zeile, und die Meldung dazu lautete
         * „dalvikvm ist auf diesem Gerät nicht ausführbar": inhaltlich richtig, als Hinweis
         * wertlos.
         *
         * Die APEX-Pfade stehen vorn, weil sie auf allem gelten, was neuer als Android 9
         * ist. `com.android.runtime` war der Name in Android 10, bevor das Modul in
         * `com.android.art` umbenannt wurde. `/system/bin` bleibt als letzter Anlauf stehen:
         * Auf älteren Abbildern ist es der richtige Ort, und ein Pfad, den es nicht gibt,
         * kostet einen `access`-Aufruf.
         *
         * Innerhalb jedes Ortes zuerst die 64-Bit-Fassung. Ein 32-Bit-`dalvikvm` auf einem
         * 64-Bit-Gerät startet zwar, bekommt aber nur vier Gigabyte Adressraum — für den
         * Kotlin-Compiler unnötig knapp.
         *
         * Geprüft wird, was **ausführbar** ist, nicht was existieren sollte.
         */
        val DALVIKVM: List<String> = listOf(
            "/apex/com.android.art/bin/dalvikvm64",
            "/apex/com.android.art/bin/dalvikvm",
            "/apex/com.android.art/bin/dalvikvm32",
            "/apex/com.android.runtime/bin/dalvikvm64",
            "/apex/com.android.runtime/bin/dalvikvm",
            "/system/bin/dalvikvm64",
            "/system/bin/dalvikvm",
        )
    }
}
