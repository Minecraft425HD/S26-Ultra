package de.neon.inference

import android.content.Context
import android.util.Log
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
    private val contextSize: Int = DEFAULT_CONTEXT_SIZE,
    private val threads: Int = DEFAULT_THREADS,
) : ServerSupervisor {

    private var process: Process? = null
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
            Log.e(TAG, "llama-server fehlt unter ${binary.absolutePath}")
            return null
        }
        if (!file.isFile) {
            Log.e(TAG, "Modelldatei fehlt: ${file.absolutePath}")
            return null
        }

        stopping.set(false)
        val started = runCatching { launch(file) }.getOrElse {
            Log.e(TAG, "llama-server ließ sich nicht starten", it)
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
                    lines.forEach { Log.d(TAG, it) }
                }
            }
        }
    }

    private fun waitForHealth(client: LlamaServerClient): Boolean {
        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (stopping.get()) return false
            if (process?.isAlive != true) {
                Log.e(TAG, "llama-server hat sich beim Start beendet")
                return false
            }
            if (client.isHealthy()) return true
            Thread.sleep(HEALTH_POLL_MILLIS)
        }
        Log.e(TAG, "llama-server war nach $STARTUP_TIMEOUT_MILLIS ms nicht bereit")
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

        const val DEFAULT_CONTEXT_SIZE = 4_096

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
