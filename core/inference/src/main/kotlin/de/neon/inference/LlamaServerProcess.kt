package de.neon.inference

import android.content.Context
import de.neon.platform.NeonLog
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Stellt sicher, dass ein Server läuft, der ein bestimmtes Modell bedient.
 *
 * Als Schnittstelle, damit Tests gegen einen von Hand gestarteten Server laufen können —
 * ohne Android, ohne Prozessverwaltung.
 */
interface ServerSupervisor {
    /**
     * @param projector die Projektordatei eines Bildmodells, sonst `null`.
     * @return ein Client für das Modell, oder `null`, wenn kein Server bereitsteht.
     */
    suspend fun clientFor(modelId: String, file: File, projector: File? = null): LlamaServerClient?

    suspend fun shutdown()

    /**
     * Wird während des Ladens regelmäßig gerufen.
     *
     * Der Grund ist ein Fehler, der hier gemacht wurde: Die Startfrist wurde von 60 auf 329
     * Sekunden angehoben, weil 60 zu knapp waren — ohne dafür zu sorgen, dass in dieser Zeit
     * etwas zu sehen ist. Damit dauerte es fünfeinhalb Minuten, bis ein Fehlschlag überhaupt
     * sichtbar wurde. Wer nach zwei Minuten aufgibt, sieht in der Zwischenzeit nichts, was
     * ihm sagt, ob überhaupt etwas passiert.
     */
    var onLoadingProgress: ((LoadingProgress) -> Unit)?
        get() = null
        set(_) = Unit
}

/** Wie weit das Laden ist. */
data class LoadingProgress(
    val elapsedMillis: Long,
    val budgetMillis: Long,
    /** Die letzte aussagekräftige Zeile des Servers, falls es eine gab. */
    val lastLine: String? = null,
)

/**
 * Startet und überwacht den mitgelieferten `llama-server` auf dem Telefon.
 *
 * **Warum ein eigenes Programm statt einer Bibliothek.** Android entpackt beim Installieren
 * alle Dateien aus `jniLibs`, deren Name auf `lib*.so` passt, in ein Verzeichnis, aus dem
 * ausgeführt werden darf — anders als das beschreibbare Datenverzeichnis der App, wo die
 * W^X-Regel das Ausführen verbietet. Deshalb heißt das Programm `libllama-server.so`,
 * obwohl es kein Bibliothek ist, und deshalb steht `extractNativeLibs="true"` im Manifest.
 *
 * **Warum ein Modell je Serverlauf.** llama-server kann mehrere Modelle über einen
 * Router-Modus verwalten, schaltet den aber auf Android bewusst ab: Er bräuchte dafür
 * Kindprozesse, die dort als nicht unterstützt gelten. Neon startet den Server deshalb mit
 * genau einem Modell und startet ihn beim Wechsel neu. Die Hysterese in der Auswahl-Policy
 * vermeidet solche Wechsel ohnehin, wo es geht — und weil die Gewichte per `mmap` aus dem
 * Seitencache kommen, kostet ein Neustart weit weniger als ein Kaltstart.
 */
class ProcessServerSupervisor(
    private val context: Context,
    private val port: Int = DEFAULT_PORT,
    contextSize: Int = DEFAULT_CONTEXT_SIZE,
    private val threads: Int = defaultThreads(),
) : ServerSupervisor {

    /**
     * Die Größe des Kontextfensters.
     *
     * Änderbar, weil der richtige Wert vom Gerät und vom Gebrauch abhängt: Wer Anhänge
     * benutzt, braucht mehr; wer nur kurz etwas fragt, verschenkt damit Arbeitsspeicher und
     * Zeit. Eine Änderung wirkt beim nächsten Serverstart — der laufende Server wird dafür
     * beendet, denn die Größe steht beim Start fest und lässt sich nicht nachjustieren.
     */
    @Volatile
    var contextSize: Int = contextSize.coerceIn(MIN_CONTEXT_SIZE, MAX_CONTEXT_SIZE)
        set(value) {
            val neu = value.coerceIn(MIN_CONTEXT_SIZE, MAX_CONTEXT_SIZE)
            if (neu == field) return
            field = neu
            // Nicht sofort neu starten: Der nächste Aufruf tut das ohnehin, und ein
            // Neustart mitten in einer laufenden Antwort wäre das Gegenteil von hilfreich.
            servingModelId = null
        }

    private var process: Process? = null
    @Volatile
    private var servingModelId: String? = null
    private var client: LlamaServerClient? = null
    private val stopping = AtomicBoolean(false)

    /** Wann der Server zuletzt etwas ausgegeben hat. Grundlage der Startfrist. */
    private val lastOutputAt = AtomicLong(0)

    /** Die zuletzt gesehene aussagekräftige Zeile — für die Fortschrittsmeldung. */
    @Volatile
    private var lastMeaningfulLine: String? = null

    override var onLoadingProgress: ((LoadingProgress) -> Unit)? = null

    private val baseUrl: String = "http://127.0.0.1:$port"

    /** Wo das Programm nach der Installation liegt. */
    private val binary: File
        get() = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    val isAvailable: Boolean get() = binary.canExecute()

    val isRunning: Boolean get() = process?.isAlive == true

    val currentModelId: String? get() = servingModelId

    override suspend fun clientFor(
        modelId: String,
        file: File,
        projector: File?,
    ): LlamaServerClient? {
        if (servingModelId == modelId && isRunning) {
            client?.let { if (it.isHealthy()) return it }
        }

        stopProcess()
        if (!isAvailable) {
            NeonLog.e(TAG, "llama-server fehlt unter ${binary.absolutePath}")
            return null
        }
        if (!file.isFile) {
            NeonLog.e(TAG, "Modelldatei fehlt: ${file.absolutePath}")
            return null
        }

        stopping.set(false)

        // Einmal je Serverstart: was der Prozessor kann. Zusammen mit der `system_info`-
        // Zeile, die llama-server gleich danach schreibt, ergibt das den Vergleich, auf den
        // es ankommt — was das Gerät könnte gegen das, was die Binärdatei benutzt.
        NeonLog.i(TAG, CpuFeatures.describe())

        val started = runCatching { launch(file, projector) }.getOrElse {
            NeonLog.e(TAG, "llama-server ließ sich nicht starten", it)
            return null
        }
        process = started

        val newClient = LlamaServerClient(baseUrl)
        // Die Dateigröße statt der Angabe aus der Registry: Sie beschreibt, was tatsächlich
        // gelesen werden muss, und stimmt auch bei einer anderen Quantisierung.
        if (!waitForHealth(newClient, file.length() + (projector?.length() ?: 0L))) {
            stopProcess()
            return null
        }

        servingModelId = modelId
        client = newClient
        return newClient
    }

    private fun launch(model: File, projector: File?): Process {
        val command = buildList {
            add(binary.absolutePath)
            add("--model"); add(model.absolutePath)
            add("--alias"); add(model.nameWithoutExtension)
            add("--host"); add("127.0.0.1")
            add("--port"); add(port.toString())
            add("--ctx-size"); add(contextSize.toString())
            add("--threads"); add(threads.toString())

            // Ein Telefon, ein Nutzer. llama-server legt sonst vier Bearbeitungsplätze an,
            // die hier nie gleichzeitig gebraucht werden.
            add("-np"); add("1")

            // Der Schlüssel-Wert-Speicher ist der Teil, der mit dem Kontextfenster wächst
            // — bei diesem Modell 144 KB je Token. Auf acht Bit komprimiert halbiert sich
            // das, und ein Fenster von 16384 kostet damit gut ein Gigabyte statt zwei
            // ein Viertel. Gegen einen echten Server geprüft, einschließlich einer
            // vollständigen Antwort.
            add("--cache-type-k"); add(KV_CACHE_TYPE)
            add("--cache-type-v"); add(KV_CACHE_TYPE)

            // Ohne den Projektor kann ein Bildmodell keine Bilder ansehen. Er ist die
            // zweite Hälfte des Modells und wird getrennt importiert.
            if (projector != null) {
                add("--mmproj"); add(projector.absolutePath)
            }

            // Die Chat-Vorlage aus der GGUF-Datei benutzen, statt eine zu raten. Ohne das
            // sieht ein Modell mit ungewöhnlicher Vorlage einen falsch formatierten Prompt.
            add("--jinja")
            // Die eingebaute Weboberfläche wird nie benutzt und kostet nur Speicher.
            add("--no-webui")
        }

        val started = ProcessBuilder(command)
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .start()
        drainOutput(started)
        return started
    }

    /**
     * Die Ausgabe des Servers muss gelesen werden.
     *
     * Läuft der Ausgabepuffer voll, blockiert der Server beim Schreiben und bleibt stehen —
     * ein Fehler, der sich als "hängt gelegentlich" äußert und schwer zu finden ist.
     */
    private fun drainOutput(process: Process) {
        thread(name = "neon-llama-log", isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { zeile ->
                        // Jede Zeile ist ein Lebenszeichen. Daran hängt die Startfrist:
                        // Ein Server, der noch redet, arbeitet noch. Deshalb zählt hier
                        // wirklich jede Zeile, auch die belanglosen.
                        lastOutputAt.set(System.currentTimeMillis())

                        if (istAussagekraeftig(zeile)) {
                            lastMeaningfulLine = zeile.trim()
                            // Nur das Wesentliche in die Protokolldatei. llama-server
                            // schreibt in der Voreinstellung hunderte Zeilen beim Laden;
                            // ungefiltert ertrinkt darin alles, was man später lesen will.
                            NeonLog.i(TAG, zeile)
                        } else {
                            NeonLog.d(TAG, zeile)
                        }
                    }
                }
            }
        }
    }

    /**
     * Wartet, bis der Server antwortet.
     *
     * **Warum nicht einfach eine feste Frist.** Hier stand einmal eine Minute. Auf dem Gerät
     * brauchte das Alltagsmodell 59,548 Sekunden — Neon gab 450 Millisekunden später auf und
     * erschlug einen Server, der gerade fertig geworden war. Der nächste Versuch begann von
     * vorn und scheiterte genauso: kein Wackeln, sondern ein dauerhafter Ausfall.
     *
     * Eine größere feste Zahl wäre nur eine bessere Vermutung. Die Ladezeit hängt an der
     * Modellgröße (2,5 GB gegen 5 GB), am Seitencache (kalt gegen warm) und an Androids
     * Dateiverschlüsselung, die beim ersten Lesen jede Seite entschlüsselt — gemessene
     * 42 MB/s statt der Geschwindigkeit des Flash-Speichers.
     *
     * Deshalb zwei Bedingungen statt einer:
     *
     *  - **Stille.** Solange der Server Ausgabezeilen schreibt, arbeitet er. Erst wenn er
     *    [SILENCE_TIMEOUT_MILLIS] lang schweigt *und* nicht antwortet, gilt er als hängend.
     *  - **Obergrenze nach Größe.** Als zweite Sicherung für den Fall, dass er stumm hängt:
     *    eine Frist, die mit der Modellgröße wächst statt für alle Modelle gleich zu raten.
     */
    private fun waitForHealth(client: LlamaServerClient, modelBytes: Long): Boolean {
        val budget = startupBudgetMillis(modelBytes)
        val begonnen = System.currentTimeMillis()
        lastOutputAt.set(begonnen)

        // Der zuletzt gemeldete Grund. Nur bei einer Änderung protokolliert — bei alle
        // 250 ms füllte "Connection refused" sonst die ganze Datei.
        var gemeldeterGrund: String? = null

        NeonLog.i(TAG, "warte auf llama-server, Frist ${budget / 1000} s für ${modelBytes / MB} MB")

        while (System.currentTimeMillis() - begonnen < budget) {
            if (stopping.get()) return false
            if (process?.isAlive != true) {
                NeonLog.e(TAG, "llama-server hat sich beim Start beendet")
                return false
            }
            if (client.isHealthy()) {
                NeonLog.i(TAG, "llama-server bereit nach ${System.currentTimeMillis() - begonnen} ms")
                return true
            }

            // Warum es nicht klappte. Beim Laden steht hier „Connection refused", und das
            // ist in Ordnung. Steht dort etwas anderes, ist genau das die Antwort auf die
            // Frage, warum Neon nicht loslegt — und sie darf nicht wieder verloren gehen.
            val grund = client.lastHealthFailure
            if (grund != null && grund != gemeldeterGrund) {
                gemeldeterGrund = grund
                NeonLog.i(TAG, "llama-server antwortet noch nicht: $grund")
            }

            val vergangen = System.currentTimeMillis() - begonnen
            onLoadingProgress?.invoke(
                LoadingProgress(
                    elapsedMillis = vergangen,
                    budgetMillis = budget,
                    // Ohne eine Serverzeile ist der Grund das Einzige, was zu sagen ist —
                    // und besser als eine Zahl, die stumm hochläuft.
                    lastLine = lastMeaningfulLine ?: grund,
                )
            )

            val stille = System.currentTimeMillis() - lastOutputAt.get()
            if (stille > SILENCE_TIMEOUT_MILLIS) {
                NeonLog.e(TAG, "llama-server schweigt seit $stille ms und antwortet nicht${weil(grund)}")
                return false
            }

            Thread.sleep(HEALTH_POLL_MILLIS)
        }

        // Der letzte Blick, bevor eine Minute Arbeit weggeworfen wird. Genau dieser eine
        // Aufruf hätte den Ausfall auf dem Gerät verhindert.
        if (client.isHealthy()) {
            NeonLog.i(TAG, "llama-server war knapp doch noch rechtzeitig fertig")
            return true
        }

        NeonLog.e(TAG, "llama-server war nach $budget ms nicht bereit${weil(client.lastHealthFailure)}")
        return false
    }

    /** Hängt den Grund an eine Fehlermeldung, wenn es einen gibt. */
    private fun weil(grund: String?): String = grund?.let { " — $it" }.orEmpty()

    override suspend fun shutdown() = stopProcess()

    private fun stopProcess() {
        stopping.set(true)
        client?.close()
        client = null
        servingModelId = null
        process?.let { running ->
            running.destroy()
            if (!running.waitFor(SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                running.destroyForcibly()
            }
        }
        process = null
    }

    companion object {
        private const val TAG = "NeonLlamaServer"

        /**
         * Das Programm heißt `lib*.so`, weil der Installer nur solche Dateien in ein
         * ausführbares Verzeichnis entpackt. Es ist trotzdem ein normales Programm.
         */
        const val BINARY_NAME = "libllama-server.so"

        /** Hoher Port, nur an localhost gebunden. */
        const val DEFAULT_PORT = 18_080

        /**
         * Voreinstellung: 16384 Token.
         *
         * Rund 12.000 Wörter — genug für einen Gesprächsverlauf samt mehrerer Fundstellen
         * aus Anhängen. Mit komprimiertem Cache kostet das etwa 1,1 GB, zusammen mit dem
         * Alltagsmodell knapp 3,5 GB und damit deutlich weniger, als das Gerät hergibt.
         */
        const val DEFAULT_CONTEXT_SIZE = 16_384

        /** Darunter passt kaum eine Seite Text — dann lohnt der ganze Aufwand nicht. */
        const val MIN_CONTEXT_SIZE = 4_096

        /**
         * Obergrenze. Darüber wird es auf 16 GB eng, sobald nebenher etwas anderes läuft,
         * und jeder Prompt braucht spürbar länger, weil mehr Text durchgerechnet wird.
         */
        const val MAX_CONTEXT_SIZE = 32_768

        /**
         * Wie der Schlüssel-Wert-Speicher abgelegt wird.
         *
         * `q8_0` halbiert ihn gegenüber `f16` bei einem Qualitätsverlust, den man in einer
         * Antwort nicht bemerkt — anders als bei `q4_0`, wo längere Zusammenhänge sichtbar
         * leiden.
         */
        const val KV_CACHE_TYPE = "q8_0"

        /**
         * Wie viel Arbeitsspeicher ein Token im Schlüssel-Wert-Speicher belegt.
         *
         * Gerechnet aus der Modellkonfiguration von Qwen3 4B: 36 Schichten, 8 KV-Köpfe,
         * head_dim 128, Schlüssel und Wert je ein Byte je Element bei `q8_0` — macht
         * 2 * 36 * 8 * 128 = 73728 Byte. Bei `f16` das Doppelte.
         *
         * Gilt für das Alltagsmodell. Ein anderes Modell hat andere Zahlen; für die
         * Anzeige neben dem Regler ist das die richtige Größenordnung.
         */
        const val KV_BYTES_PER_TOKEN = 73_728L

        /**
         * Wie viele Rechenfäden llama-server bekommt.
         *
         * Hier stand fest verdrahtet `8` — eine Zahl, die aus dem Datenblatt eines
         * bestimmten Prozessors stammte und nie geprüft wurde. Mehr Fäden als verfügbare
         * Kerne machen es messbar langsamer statt schneller, weil die Fäden einander vom
         * Kern verdrängen; und wie viele Kerne Android der App zugesteht, weiß nur das
         * Gerät.
         *
         * [Runtime.availableProcessors] meldet unter Android die Kerne, die dem Prozess
         * gerade zustehen. Nach oben begrenzt, weil llama.cpp über acht Fäden kaum noch
         * Durchsatz gewinnt und vor allem heizt — und Wärme kostet auf einem Telefon
         * innerhalb einer Minute mehr, als die Fäden einbringen.
         */
        fun defaultThreads(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(MIN_THREADS, MAX_THREADS)

        const val MIN_THREADS = 4
        const val MAX_THREADS = 8

        private const val MB = 1024L * 1024
        private const val GB = 1024L * MB

        /**
         * Grundzeit für den Start, unabhängig von der Modellgröße.
         *
         * Mindestens so lang wie [SILENCE_TIMEOUT_MILLIS]. Sonst schlüge bei einem kleinen
         * Modell immer die Uhr zu, bevor das Schweigen überhaupt auffallen könnte — und die
         * Uhr ist genau das Kriterium, das sich als falsch erwiesen hat.
         */
        const val STARTUP_BASE_MILLIS = 120_000L

        /**
         * Zuschlag je Gigabyte Modelldatei.
         *
         * Gemessen wurden auf dem S26 Ultra rund 42 MB/s beim ersten Laden, also etwa
         * 25 Sekunden je Gigabyte. Mit 90 Sekunden liegt die Frist mehr als dreifach
         * darüber — reichlich, aber das ist der Sinn: Zu früh aufzugeben kostet die ganze
         * Antwort, zu spät nur Geduld in einem Fall, der ohnehin schiefgeht.
         */
        const val STARTUP_PER_GIGABYTE_MILLIS = 90_000L

        /**
         * So lange darf der Server schweigen, bevor er als hängend gilt.
         *
         * Beim Laden schreibt llama.cpp regelmäßig Zeilen. Anderthalb Minuten ohne ein
         * einziges Wort heißen: Er kommt nicht mehr voran.
         *
         * Kürzer als [STARTUP_BASE_MILLIS], damit dieses Kriterium in jedem Fall zuerst
         * greift. Ein hängender Server soll am Schweigen auffallen und nicht an der Uhr —
         * die Uhr ist genau das, was sich als falsch erwiesen hat.
         */
        const val SILENCE_TIMEOUT_MILLIS = 90_000L

        /**
         * Ob eine Ausgabezeile in die Protokolldatei gehört.
         *
         * `llama-server` läuft mit Verbosität 3 und schreibt beim Laden hunderte Zeilen.
         * Ungefiltert füllen sie die Datei, verdrängen das Interessante und sprengen jeden
         * Weg, sie weiterzugeben. Behalten wird, was eine Frage beantwortet: Was wird
         * geladen, ist es fertig, hört es zu, ging etwas schief.
         */
        fun istAussagekraeftig(zeile: String): Boolean {
            val klein = zeile.lowercase()
            return MELDENSWERT.any { klein.contains(it) }
        }

        private val MELDENSWERT = listOf(
            "load_model",
            "model loaded",
            "listening",
            "error",
            "failed",
            "warning: ",
            "out of memory",
            "oom",
            "n_ctx",
            "kv cache",
            "kv self",

            // Ab hier die Zeilen, die über die Geschwindigkeit Auskunft geben. Sie fehlten,
            // als sich herausstellte, dass Neon mit 0,71 Token je Sekunde antwortete: Die
            // Zahlen standen zwar in der Ausgabe des Servers, aber nicht in der Datei, die
            // man weitergeben kann.
            //
            // `system_info` nennt die Rechenbefehle, die die Binärdatei benutzt — genau
            // die Auskunft, an der sich die Ursache ablesen ließ.
            "system_info",
            // `print_timing` bringt Token je Sekunde für jede einzelne Antwort mit.
            "print_timing",
            "n_threads",
        )

        /** Die Frist für ein Modell dieser Größe. */
        fun startupBudgetMillis(modelBytes: Long): Long =
            STARTUP_BASE_MILLIS + STARTUP_PER_GIGABYTE_MILLIS * modelBytes / GB
        private const val HEALTH_POLL_MILLIS = 250L
        private const val SHUTDOWN_TIMEOUT_MILLIS = 5_000L
    }
}
