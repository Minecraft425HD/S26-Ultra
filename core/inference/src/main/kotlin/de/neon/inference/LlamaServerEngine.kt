package de.neon.inference

import de.neon.router.ModelSpec
import kotlinx.coroutines.CoroutineDispatcher
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
 * Lokale Inferenz über einen laufenden `llama-server`.
 *
 * Ersetzt die frühere JNI-Brücke. Der Gewinn ist nicht nur, dass hier kein eigenes C++ mehr
 * gepflegt werden muss: Der Server bringt die GBNF-Grammatik mit, die Stufe 2 des Routers
 * und die Werkzeugaufrufe brauchen, und er läuft als eigener Prozess — ein Modell, das den
 * Speicher sprengt, reißt die Hörschleife nicht mit.
 *
 * Wo der Server herkommt, weiß der [ServerSupervisor]. Auf dem Telefon startet er ihn
 * selbst; im Test zeigt er auf einen von Hand gestarteten. Genau deshalb ist diese Klasse
 * ohne Gerät prüfbar.
 */
class LlamaServerEngine(
    private val supervisor: ServerSupervisor,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : InferenceEngine {

    @Volatile
    private var currentModelId: String? = null

    @Volatile
    private var currentClient: LlamaServerClient? = null

    override val loadedModelId: String? get() = currentModelId

    override suspend fun load(model: ModelSpec, file: File): Boolean = withContext(dispatcher) {
        val client = supervisor.clientFor(model.id, file) ?: return@withContext false
        currentClient = client
        currentModelId = model.id
        true
    }

    override suspend fun unload() {
        withContext(dispatcher) { supervisor.shutdown() }
        currentClient = null
        currentModelId = null
    }

    override fun generate(request: GenerationRequest): Flow<GenerationChunk> = callbackFlow {
        val client = currentClient
        val modelId = currentModelId
        if (client == null || modelId == null) {
            trySend(GenerationChunk.Failed("Es ist kein Modell geladen."))
            close()
            return@callbackFlow
        }

        val cancelled = AtomicBoolean(false)
        val startedAt = System.nanoTime()

        val worker = thread(name = "neon-llama-http") {
            val result = client.streamCompletion(
                modelId = modelId,
                messages = request.messages,
                maxTokens = request.maxTokens,
                temperature = request.temperature,
                topP = request.topP,
                grammar = request.grammar,
                stopSequences = request.stopSequences,
                onToken = { token ->
                    trySend(GenerationChunk.Token(token))
                    !cancelled.get()
                },
            )

            result
                .onSuccess { tokens ->
                    val seconds = (System.nanoTime() - startedAt) / 1e9
                    trySend(
                        GenerationChunk.Done(
                            tokenCount = tokens,
                            tokensPerSecond = if (seconds > 0) tokens / seconds else 0.0,
                        )
                    )
                }
                .onFailure { error ->
                    // Ein vom Nutzer abgebrochener Strom ist kein Fehler, den er hören muss.
                    if (!cancelled.get()) {
                        trySend(
                            GenerationChunk.Failed(
                                error.message ?: "Die Verbindung zum Modell ist abgerissen."
                            )
                        )
                    }
                }

            close()
        }

        awaitClose {
            cancelled.set(true)
            worker.join(CANCEL_TIMEOUT_MILLIS)
        }
    }.flowOn(dispatcher)

    private companion object {
        const val CANCEL_TIMEOUT_MILLIS = 2_000L
    }
}

/**
 * Zeigt auf einen bereits laufenden Server.
 *
 * Der Weg, die gesamte Inferenzschicht ohne Telefon zu prüfen: Der Test startet einen
 * echten `llama-server` mit einem kleinen Modell und lässt Neon dagegen arbeiten.
 */
class RunningServerSupervisor(baseUrl: String) : ServerSupervisor {

    private val client = LlamaServerClient(baseUrl)

    override suspend fun clientFor(modelId: String, file: File): LlamaServerClient? =
        if (client.isHealthy()) client else null

    override suspend fun shutdown() = Unit
}
