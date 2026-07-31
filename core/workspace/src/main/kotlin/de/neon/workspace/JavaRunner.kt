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
     * Wie viel Speicher die Laufzeit höchstens nehmen darf.
     *
     * Der Kotlin-Compiler ist gierig; ohne Grenze nimmt er sich, was da ist, und das Gerät
     * beendet dann irgendetwas. Ein Gigabyte reicht für ein kleines Projekt und lässt Neon
     * und dem Sprachmodell ihren Platz.
     */
    private val heapMegabyte: Int = 1024,
) : JavaRunner {

    override fun run(
        dexJar: File,
        mainClass: String,
        args: List<String>,
        workingDir: File,
        timeoutMillis: Long,
    ): CommandResult {
        val dalvik = DALVIKVM.firstOrNull { File(it).canExecute() }
            ?: return CommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "dalvikvm ist auf diesem Gerät nicht ausführbar. Ohne sie lassen " +
                    "sich die Java-Werkzeuge der Bau-Kette nicht starten.",
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

    private companion object {
        /**
         * Wo `dalvikvm` liegt.
         *
         * Zwei Pfade, weil 64-Bit-Geräte beide anbieten und nicht jedes Abbild denselben
         * Namen benutzt. Geprüft wird, was ausführbar ist — nicht, was existieren sollte.
         */
        val DALVIKVM = listOf("/system/bin/dalvikvm64", "/system/bin/dalvikvm")
    }
}
