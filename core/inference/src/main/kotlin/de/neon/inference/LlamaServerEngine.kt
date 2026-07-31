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

    override suspend fun load(model: ModelSpec, file: File, projector: File?): Boolean = withContext(dispatcher) {
        val client = supervisor.clientFor(model, file, projector) ?: return@withContext false
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
                        // Erst jetzt nachsehen, wie es dem Server geht. Vorher wäre die
                        // Auskunft veraltet, und laufend zu fragen kostet einen
                        // /proc/meminfo-Zugriff je Antwort ohne Gegenwert.
                        val zustand = supervisor.zustand()
                        val roh = error.message?.takeIf { it.isNotBlank() }
                            ?: error.javaClass.simpleName
                        val deutung = deuteAbbruch(roh, zustand.lebt)
                        trySend(
                            GenerationChunk.Failed(
                                reason = deutung,
                                // Die Rohmeldung nur dann anhängen, wenn sie nicht schon die
                                // Deutung ist. Bei einer Serverablehnung stand sie sonst
                                // zweimal in derselben Protokollzeile — 250 Zeichen doppelt.
                                detail = if (deutung == roh) zustand.describe()
                                else "$roh · ${zustand.describe()}",
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

    companion object {
        private const val CANCEL_TIMEOUT_MILLIS = 2_000L

        /**
         * Was der Abbruch bedeutet, in einem Satz.
         *
         * **Der Anlass.** Gemeldet wurde `unexpected end of stream on
         * http://127.0.0.1:18080/`, und Neon gab das wörtlich weiter. Die Meldung sagt, dass
         * die Gegenseite mitten im Strom weg war — Port 18080 gehört `llama-server`, niemand
         * sonst kann diese Verbindung abbrechen. Über die Ursache sagt sie nichts, und genau
         * die entscheidet, was als nächstes zu tun ist.
         *
         * Eine reine Funktion, damit jeder der Fälle ohne Server festzuhalten ist.
         *
         * @param roh die Meldung der Ausnahme.
         * @param lebt ob der Serverprozess noch läuft; `null` wenn unbekannt.
         */
        fun deuteAbbruch(roh: String, lebt: Boolean?): String = when {
            // Wer mit einem Statuscode antwortet, lebt. Hier über einen weggefallenen
            // Prozess zu reden wäre falsch, und die Meldung des Servers steht schon in
            // verständlichem Deutsch da.
            roh.startsWith(LlamaServerClient.ABLEHNUNG_PRAEFIX) -> roh

            lebt == false ->
                "Der Server wurde mitten in der Antwort beendet. Das passiert auf diesem " +
                    "Gerät vor allem bei Speichermangel — ein kleineres Modell oder ein " +
                    "kleineres Kontextfenster hilft."

            lebt == true ->
                "Die Verbindung zum Server brach ab, obwohl er noch läuft."

            else -> "Die Verbindung zum Server brach ab."
        }
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

    override suspend fun clientFor(
        model: de.neon.router.ModelSpec,
        file: File,
        projector: File?,
    ): LlamaServerClient? = if (client.isHealthy()) client else null

    override suspend fun shutdown() = Unit
}
