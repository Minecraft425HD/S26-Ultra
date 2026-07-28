package de.neon.inference

import android.util.Log
import de.neon.router.ModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Lokale Inferenz über llama.cpp.
 *
 * Die native Bibliothek wird nicht mit eingecheckt und ist beim normalen Bauen nicht dabei
 * (siehe `scripts/fetch-native-deps.sh` und die Gradle-Eigenschaft `neon.buildNative`).
 * Fehlt sie, meldet diese Klasse das sauber, statt die App beim Start abstürzen zu lassen —
 * Neon bleibt dann bedienbar und kann erklären, was fehlt.
 */
class LlamaCppEngine(
    private val threadCount: Int = DEFAULT_THREADS,
    /**
     * GPU-Schichten. Auf dem Adreno des Snapdragon 8 Elite läuft llama.cpp über OpenCL;
     * wie viel sich auslagern lässt, wird in M1 gemessen.
     */
    private val gpuLayers: Int = 0,
) : InferenceEngine {

    /** Zeiger auf den nativen Kontext. 0 bedeutet "nichts geladen". */
    private var handle: Long = 0
    private var currentModelId: String? = null
    private val cancelRequested = AtomicBoolean(false)

    override val loadedModelId: String? get() = currentModelId

    override suspend fun load(model: ModelSpec, file: File): Boolean = withContext(Dispatchers.IO) {
        if (!nativeAvailable) {
            Log.w(TAG, "native Bibliothek nicht verfügbar — ${model.id} wird nicht geladen")
            return@withContext false
        }
        if (!file.exists()) return@withContext false

        unloadInternal()
        handle = nativeLoad(file.absolutePath, threadCount, gpuLayers)
        if (handle == 0L) {
            Log.e(TAG, "llama.cpp konnte ${file.name} nicht laden")
            return@withContext false
        }
        currentModelId = model.id
        true
    }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        unloadInternal()
    }

    private fun unloadInternal() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0
        }
        currentModelId = null
    }

    override fun generate(request: GenerationRequest): Flow<GenerationChunk> = callbackFlow {
        if (!nativeAvailable) {
            trySend(GenerationChunk.Failed(NATIVE_MISSING_HINT))
            close()
            return@callbackFlow
        }
        if (handle == 0L) {
            trySend(GenerationChunk.Failed("Es ist kein Modell geladen."))
            close()
            return@callbackFlow
        }

        cancelRequested.set(false)
        val startedAt = System.nanoTime()
        var tokenCount = 0

        val worker = thread(name = "neon-llama") {
            try {
                nativeGenerate(
                    handle,
                    buildPrompt(request.messages),
                    request.maxTokens,
                    request.temperature,
                    request.topP,
                    request.grammar,
                    request.stopSequences.toTypedArray(),
                ) { token ->
                    tokenCount++
                    trySend(GenerationChunk.Token(token))
                    // Rückgabewert an den nativen Code: false beendet die Erzeugung.
                    !cancelRequested.get()
                }
                val seconds = (System.nanoTime() - startedAt) / 1e9
                trySend(
                    GenerationChunk.Done(
                        tokenCount = tokenCount,
                        tokensPerSecond = if (seconds > 0) tokenCount / seconds else 0.0,
                    )
                )
            } catch (t: Throwable) {
                trySend(GenerationChunk.Failed(t.message ?: "Die Inferenz ist fehlgeschlagen."))
            } finally {
                close()
            }
        }

        awaitClose {
            cancelRequested.set(true)
            worker.join(CANCEL_TIMEOUT_MILLIS)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Baut den Prompt im ChatML-Format.
     *
     * Qwen3 und die meisten offenen Modelle verstehen es; weicht ein Modell davon ab,
     * gehört die Vorlage in die Modellbeschreibung und nicht hierher.
     */
    private fun buildPrompt(messages: List<ChatMessage>): String = buildString {
        for (message in messages) {
            val role = when (message.role) {
                Role.SYSTEM -> "system"
                Role.USER -> "user"
                Role.ASSISTANT -> "assistant"
            }
            append("<|im_start|>").append(role).append('\n')
            append(message.content).append("<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }

    // --- Native Schnittstelle ----------------------------------------------------------

    private external fun nativeLoad(path: String, threads: Int, gpuLayers: Int): Long
    private external fun nativeFree(handle: Long)
    private external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        grammar: String?,
        stopSequences: Array<String>,
        onToken: (String) -> Boolean,
    )

    companion object {
        private const val TAG = "NeonLlama"
        private const val CANCEL_TIMEOUT_MILLIS = 2_000L

        /**
         * Acht Threads: Der Snapdragon 8 Elite Gen 5 hat mehr Kerne, aber über den großen
         * Kernen bringt llama.cpp kaum noch Durchsatz und heizt nur. Der genaue Wert kommt
         * aus der Messung in M1.
         */
        private const val DEFAULT_THREADS = 8

        const val NATIVE_MISSING_HINT =
            "Die native Inferenz-Bibliothek fehlt. Führe scripts/fetch-native-deps.sh aus " +
                "und baue mit -Pneon.buildNative=true."

        /**
         * Einmalig beim Laden der Klasse geprüft.
         *
         * Ein fehlender `libneon_llama.so` ist der Normalfall bei einem frisch geklonten
         * Projekt und darf die App nicht daran hindern zu starten.
         */
        val nativeAvailable: Boolean = runCatching {
            System.loadLibrary("neon_llama")
        }.isSuccess
    }
}
