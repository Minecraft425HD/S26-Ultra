package de.neon.inference

import android.content.Context
import de.neon.platform.NeonLog
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Stellt sicher, dass ein Server läuft, der ein bestimmtes Modell bedient.
 *
 * Als Schnittstelle, damit Tests gegen einen von Hand gestarteten Server laufen können —
 * ohne Android, ohne Prozessverwaltung.
 */
interface ServerSupervisor {
    /** @return ein Client für das Modell, oder `null`, wenn kein Server bereitsteht. */
    suspend fun clientFor(modelId: String, file: File): LlamaServerClient?

    suspend fun shutdown()
}

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
    private val threads: Int = DEFAULT_THREADS,
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

    private val baseUrl: String = "http://127.0.0.1:$port"

    /** Wo das Programm nach der Installation liegt. */
    private val binary: File
        get() = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    val isAvailable: Boolean get() = binary.canExecute()

    val isRunning: Boolean get() = process?.isAlive == true

    val currentModelId: String? get() = servingModelId

    override suspend fun clientFor(modelId: String, file: File): LlamaServerClient? {
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
        val started = runCatching { launch(file) }.getOrElse {
            NeonLog.e(TAG, "llama-server ließ sich nicht starten", it)
            return null
        }
        process = started

        val newClient = LlamaServerClient(baseUrl)
        if (!waitForHealth(newClient)) {
            stopProcess()
            return null
        }

        servingModelId = modelId
        client = newClient
        return newClient
    }

    private fun launch(model: File): Process {
        val command = listOf(
            binary.absolutePath,
            "--model", model.absolutePath,
            "--alias", model.nameWithoutExtension,
            "--host", "127.0.0.1",
            "--port", port.toString(),
            "--ctx-size", contextSize.toString(),
            "--threads", threads.toString(),
            // Der Schlüssel-Wert-Speicher ist der Teil, der mit dem Kontextfenster wächst
            // — bei diesem Modell 144 KB je Token. Auf acht Bit komprimiert halbiert sich
            // das, und ein Fenster von 16384 kostet damit gut ein Gigabyte statt zwei
            // ein Viertel. Gegen einen echten Server geprüft, einschließlich einer
            // vollständigen Antwort.
            "--cache-type-k", KV_CACHE_TYPE,
            "--cache-type-v", KV_CACHE_TYPE,
            // Die Chat-Vorlage aus der GGUF-Datei benutzen, statt eine zu raten. Ohne das
            // sieht ein Modell mit ungewöhnlicher Vorlage einen falsch formatierten Prompt.
            "--jinja",
            // Die eingebaute Weboberfläche wird nie benutzt und kostet nur Speicher.
            "--no-webui",
        )

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
                    lines.forEach { NeonLog.d(TAG, it) }
                }
            }
        }
    }

    private fun waitForHealth(client: LlamaServerClient): Boolean {
        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (stopping.get()) return false
            if (process?.isAlive != true) {
                NeonLog.e(TAG, "llama-server hat sich beim Start beendet")
                return false
            }
            if (client.isHealthy()) return true
            Thread.sleep(HEALTH_POLL_MILLIS)
        }
        NeonLog.e(TAG, "llama-server war nach $STARTUP_TIMEOUT_MILLIS ms nicht bereit")
        return false
    }

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
         * Acht Threads. Der Snapdragon 8 Elite Gen 5 hat mehr Kerne, aber über den großen
         * bringt llama.cpp kaum noch Durchsatz und heizt vor allem. Der endgültige Wert
         * kommt aus der Messung auf dem Gerät.
         */
        const val DEFAULT_THREADS = 8

        /** Ein 4B-Modell braucht auf einem Telefon einige Sekunden zum Laden. */
        private const val STARTUP_TIMEOUT_MILLIS = 60_000L
        private const val HEALTH_POLL_MILLIS = 250L
        private const val SHUTDOWN_TIMEOUT_MILLIS = 5_000L
    }
}
