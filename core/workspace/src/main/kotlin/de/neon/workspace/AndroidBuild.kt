package de.neon.workspace

import java.io.File

/**
 * Wo die Werkzeuge der Bau-Kette liegen.
 *
 * Als Datenklasse mit Dateien statt als Sammlung von Pfaden: Ob etwas da ist, lässt sich dann
 * fragen, statt es beim Starten zu erfahren.
 */
data class BuildTools(
    /** Der Ressourcen-Compiler, aus `jniLibs` — ein Programm, das `libaapt2.so` heißt. */
    val aapt2: File,
    /** Vor-dexte Java-Werkzeuge, im Datenverzeichnis ausgepackt. */
    val d8: File,
    val kotlinc: File,
    val apksigner: File,
    /** Klassenpfade, ungedext: Der Kotlin-Compiler liest sie als Java-Bytecode. */
    val androidJar: File,
    val kotlinStdlib: File,
    val annotations: File,
    /** Der Schlüssel, mit dem gebaute Apps signiert werden. Keine Sicherheitsgrenze. */
    val keystore: File,
) {
    /** Was fehlt. Leer heißt: Es kann losgehen. */
    fun fehlend(): List<String> = buildList {
        listOf(
            "aapt2" to aapt2, "d8" to d8, "kotlinc" to kotlinc, "apksigner" to apksigner,
            "android.jar" to androidJar, "kotlin-stdlib" to kotlinStdlib,
            "annotations" to annotations, "Signierschlüssel" to keystore,
        ).forEach { (name, datei) -> if (!datei.isFile) add(name) }
    }
}

/** Wie ein Bauvorgang ausgegangen ist. */
data class BuildResult(
    val gelungen: Boolean,
    /** Die fertige APK, wenn es geklappt hat. */
    val apk: File?,
    /** Was zu berichten ist — bei einem Fehlschlag die Meldung des Werkzeugs. */
    val bericht: String,
    /** Welcher Schritt gescheitert ist, für das Protokoll. */
    val schritt: String? = null,
    val dauerMillis: Long = 0,
)

/**
 * Baut eine Android-App aus einem Projektverzeichnis — auf dem Telefon.
 *
 * **Die Kette, und warum sie genau so aussieht.** Eine APK entsteht in fünf Schritten, und
 * keiner lässt sich weglassen:
 *
 *  1. `aapt2 compile` übersetzt die Ressourcen in ein Zwischenformat.
 *  2. `aapt2 link` macht daraus die Ressourcentabelle, das **binäre** Manifest und `R.java`.
 *     Android liest kein Text-XML; ohne diesen Schritt gibt es keine installierbare Datei.
 *  3. Der Kotlin-Compiler übersetzt den Quelltext gegen `android.jar` zu Java-Bytecode.
 *  4. `d8` macht daraus Dex — Android führt keinen Java-Bytecode aus.
 *  5. `apksigner` signiert. Android weigert sich, eine unsignierte APK zu installieren.
 *
 * **Warum jeder Schritt ein eigener Prozess ist.** Dieselbe Überlegung wie bei llama-server
 * und Python: Der Kotlin-Compiler braucht viel Speicher und kann abstürzen, und wenn er das
 * tut, soll er allein sterben. In den App-Prozess geladen würde jeder `System.exit` eines
 * Werkzeugs Neon mitnehmen — und `d8` ruft ihn im Fehlerfall auf.
 *
 * Ohne Android: Die Pfade kommen herein, die Prozesse laufen über [CommandRunner], und die
 * Java-Werkzeuge über [JavaRunner]. Damit lässt sich prüfen, was hier eigentlich zu prüfen
 * ist — **dass die Befehlszeilen stimmen**. Der Rest ist die Arbeit fremder Programme.
 */
class AndroidBuild(
    private val tools: BuildTools,
    private val runner: CommandRunner,
    private val java: JavaRunner,
    /** Für Meldungen unterwegs — ein Bauvorgang dauert auf dem Telefon eine Minute. */
    private val log: (String) -> Unit = {},
) {

    /**
     * Baut das Projekt und gibt die signierte APK zurück.
     *
     * @param workspace das Projektverzeichnis. Erwartet wird der Aufbau, den
     *   [AndroidProjectTemplate] anlegt.
     */
    fun baue(
        workspace: Workspace,
        paketname: String,
        timeoutMillis: Long = TIMEOUT_MILLIS,
    ): BuildResult {
        val begonnen = System.currentTimeMillis()

        tools.fehlend().takeIf { it.isNotEmpty() }?.let { fehlt ->
            return BuildResult(
                gelungen = false,
                apk = null,
                bericht = "Die Bau-Werkzeuge sind nicht vollständig: ${fehlt.joinToString()}. " +
                    "Wahrscheinlich ist das Auspacken beim ersten Start noch nicht durch.",
                schritt = "Vorbereitung",
            )
        }

        val wurzel = workspace.wurzel
        val bau = File(wurzel, BAU_VERZEICHNIS).apply { deleteRecursively(); mkdirs() }
        val manifest = File(wurzel, "AndroidManifest.xml")
        if (!manifest.isFile) {
            return BuildResult(
                gelungen = false, apk = null,
                bericht = "Es gibt kein AndroidManifest.xml im Projekt. Ohne Manifest weiß " +
                    "Android nicht, wie die App heißt und womit sie startet.",
                schritt = "Vorbereitung",
            )
        }

        fun abbruch(schritt: String, ergebnis: CommandResult) = BuildResult(
            gelungen = false,
            apk = null,
            bericht = ergebnis.describe(),
            schritt = schritt,
            dauerMillis = System.currentTimeMillis() - begonnen,
        )

        // 1 und 2: Ressourcen.
        val res = File(wurzel, "res")
        val kompilierteRes = File(bau, "res.zip")
        if (res.isDirectory) {
            log("Ressourcen übersetzen")
            val schritt = runner.run(
                listOf(tools.aapt2.absolutePath, "compile", "--dir", res.absolutePath,
                    "-o", kompilierteRes.absolutePath),
                wurzel, timeoutMillis = timeoutMillis,
            )
            if (!schritt.gelungen) return abbruch("aapt2 compile", schritt)
        }

        log("Ressourcen verknüpfen")
        val basis = File(bau, "basis.apk")
        val gen = File(bau, "gen").apply { mkdirs() }
        val linkBefehl = buildList {
            add(tools.aapt2.absolutePath); add("link")
            add("-o"); add(basis.absolutePath)
            add("-I"); add(tools.androidJar.absolutePath)
            add("--manifest"); add(manifest.absolutePath)
            add("--java"); add(gen.absolutePath)
            add("--min-sdk-version"); add(MIN_SDK.toString())
            add("--target-sdk-version"); add(TARGET_SDK.toString())
            // Ohne das erzeugt aapt2 kein R.java für Projekte ohne eigene Ressourcen, und
            // der Kotlin-Compiler bricht über einen unbekannten Verweis ab.
            add("--auto-add-overlay")
            if (kompilierteRes.isFile) add(kompilierteRes.absolutePath)
        }
        val link = runner.run(linkBefehl, wurzel, timeoutMillis = timeoutMillis)
        if (!link.gelungen) return abbruch("aapt2 link", link)

        // 3: Kotlin.
        log("Quelltext übersetzen — das dauert auf dem Telefon am längsten")
        val src = File(wurzel, "src")
        if (!src.isDirectory) {
            return BuildResult(
                gelungen = false, apk = null,
                bericht = "Es gibt kein src-Verzeichnis im Projekt.",
                schritt = "Vorbereitung",
            )
        }
        val klassen = File(bau, "klassen").apply { mkdirs() }
        val klassenpfad = listOf(tools.androidJar, tools.kotlinStdlib, tools.annotations)
            .joinToString(File.pathSeparator) { it.absolutePath }

        val kotlin = java.run(
            dexJar = tools.kotlinc,
            mainClass = KOTLINC_MAIN,
            args = buildList {
                add(src.absolutePath)
                add(gen.absolutePath)
                add("-d"); add(klassen.absolutePath)
                add("-classpath"); add(klassenpfad)
                // Auf einem Telefon gibt es keine JDK. Ohne diesen Schalter sucht der
                // Compiler `rt.jar` und bricht mit einer Meldung ab, die von einer kaputten
                // Java-Installation spricht — was hier niemanden weiterbringt.
                add("-no-jdk")
                // Die Standardbibliothek steht schon im Klassenpfad. Zweimal wäre ein
                // Konflikt zwischen zwei Fassungen derselben Klassen.
                add("-no-reflect"); add("-no-stdlib")
                add("-jvm-target"); add(JVM_TARGET)
            },
            workingDir = wurzel,
            timeoutMillis = timeoutMillis,
        )
        if (!kotlin.gelungen) return abbruch("Kotlin-Compiler", kotlin)

        // 4: Dex.
        log("in Dex umwandeln")
        val dex = File(bau, "dex").apply { mkdirs() }
        val klassenDateien = klassen.walkTopDown().filter { it.extension == "class" }
            .map { it.absolutePath }.toList()
        if (klassenDateien.isEmpty()) {
            return BuildResult(
                gelungen = false, apk = null,
                bericht = "Der Compiler hat keine Klassen erzeugt. " + kotlin.describe(),
                schritt = "Kotlin-Compiler",
                dauerMillis = System.currentTimeMillis() - begonnen,
            )
        }

        val d8Ergebnis = java.run(
            dexJar = tools.d8,
            mainClass = D8_MAIN,
            args = buildList {
                add("--release")
                add("--min-api"); add(MIN_SDK.toString())
                add("--lib"); add(tools.androidJar.absolutePath)
                add("--output"); add(dex.absolutePath)
                addAll(klassenDateien)
                // Die Standardbibliothek gehört mit in die App: Auf dem Zielgerät gibt es
                // sie nicht, anders als auf einem Rechner mit installiertem Kotlin.
                add(tools.kotlinStdlib.absolutePath)
            },
            workingDir = wurzel,
            timeoutMillis = timeoutMillis,
        )
        if (!d8Ergebnis.gelungen) return abbruch("d8", d8Ergebnis)

        // Die Dex-Dateien in die APK legen.
        log("APK zusammensetzen")
        val unsigniert = File(bau, "unsigniert.apk")
        basis.copyTo(unsigniert, overwrite = true)
        val dexDateien = dex.listFiles { f -> f.extension == "dex" }?.sortedBy { it.name }.orEmpty()
        if (dexDateien.isEmpty()) {
            return BuildResult(
                gelungen = false, apk = null,
                bericht = "d8 hat keine Dex-Datei erzeugt. " + d8Ergebnis.describe(),
                schritt = "d8",
                dauerMillis = System.currentTimeMillis() - begonnen,
            )
        }
        ApkAssembler.fuegeEin(unsigniert, dexDateien)

        // 5: Signieren.
        log("signieren")
        val fertig = File(bau, "$paketname.apk")
        val signieren = java.run(
            dexJar = tools.apksigner,
            mainClass = APKSIGNER_MAIN,
            args = listOf(
                "sign",
                "--ks", tools.keystore.absolutePath,
                "--ks-pass", "pass:$KEYSTORE_PASS",
                "--key-pass", "pass:$KEYSTORE_PASS",
                "--ks-key-alias", KEYSTORE_ALIAS,
                "--min-sdk-version", MIN_SDK.toString(),
                "--out", fertig.absolutePath,
                unsigniert.absolutePath,
            ),
            workingDir = wurzel,
            timeoutMillis = timeoutMillis,
        )
        if (!signieren.gelungen) return abbruch("apksigner", signieren)

        val dauer = System.currentTimeMillis() - begonnen
        log("fertig nach ${dauer / 1000} s: ${fertig.name}, ${fertig.length() / 1024} KB")
        return BuildResult(
            gelungen = true,
            apk = fertig,
            bericht = "Die App ist gebaut: ${fertig.name}, ${fertig.length() / 1024} KB, " +
                "in ${dauer / 1000} Sekunden.",
            dauerMillis = dauer,
        )
    }

    companion object {
        const val BAU_VERZEICHNIS = "build"

        /**
         * Dieselben Grenzen wie bei Neon selbst.
         *
         * Eine hier gebaute App soll auf demselben Telefon laufen, auf dem sie entstanden
         * ist — mehr muss sie nicht können.
         */
        const val MIN_SDK = 33
        const val TARGET_SDK = 36
        const val JVM_TARGET = "17"

        const val KOTLINC_MAIN = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
        const val D8_MAIN = "com.android.tools.r8.D8"
        const val APKSIGNER_MAIN = "com.android.apksigner.ApkSignerTool"

        /**
         * Der Schlüssel liegt offen, und das ist Absicht.
         *
         * Android weigert sich, eine unsignierte APK zu installieren; irgendein Schlüssel
         * muss also her. Dieser beglaubigt nichts, er erfüllt eine Formvorschrift. Wer eine
         * hier gebaute App weitergeben will, signiert sie mit einem eigenen.
         */
        const val KEYSTORE_PASS = "neonneon"
        const val KEYSTORE_ALIAS = "neon-build"

        /**
         * Fünf Minuten je Schritt.
         *
         * Der Kotlin-Compiler ist der langsamste; auf einem Telefon sind ein bis zwei Minuten
         * für ein kleines Projekt zu erwarten. Fünf Minuten lassen Luft und schlagen trotzdem
         * zu, bevor der Akku es tut.
         */
        const val TIMEOUT_MILLIS = 300_000L
    }
}
