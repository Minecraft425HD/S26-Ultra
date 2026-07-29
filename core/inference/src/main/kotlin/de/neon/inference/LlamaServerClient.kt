package de.neon.inference

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Spricht mit einem laufenden `llama-server` über HTTP.
 *
 * Der Server läuft im **Router-Modus**: gestartet ohne Modell, mit `--models-dir` auf Neons
 * Modellordner. Er findet die GGUF-Dateien selbst, lädt und entlädt sie auf Zuruf und
 * startet für jedes Modell einen eigenen Kindprozess. Das ist genau die Prozesstrennung, die
 * Neon braucht — ein Modell, das den Speicher sprengt, reißt nur seinen eigenen Kindprozess
 * mit, nicht die Hörschleife.
 *
 * Bewusst ein reiner HTTP-Client ohne Android-Bezug: So lässt er sich hier gegen einen echt
 * laufenden Server testen, statt erst auf dem Telefon.
 */
class LlamaServerClient(
    private val baseUrl: String,
    private val http: OkHttpClient = defaultClient(),
) : Closeable {

    /** Ein vom Server gemeldetes Modell. */
    @Serializable
    data class ModelInfo(
        val id: String,
        @SerialName("status") val rawStatus: JsonObject? = null,
    ) {
        /** "loaded", "unloaded", "downloading" … je nach Serverzustand. */
        val status: String?
            get() = (rawStatus?.get("value") as? JsonPrimitive)?.content
    }

    /**
     * Warum die letzte Gesundheitsprüfung scheiterte — `null`, wenn sie gelang.
     *
     * **Warum es dieses Feld gibt.** Hier stand einmal
     * `runCatching { … }.getOrDefault(false)`. Damit wurde jede Ausnahme zu einem
     * schlichten `false` verkürzt: nicht protokolliert, nicht gemeldet, spurlos weg.
     *
     * Auf dem Gerät sperrte Androids Netzwerkrichtlinie den Zugriff auf 127.0.0.1, und
     * OkHttp warf bei jedem Aufruf eine `UnknownServiceException`. Für Neon sah das exakt
     * aus wie „der Server ist noch nicht so weit" — an fünf Versuchen über zwei Tage, mit
     * einem Server, der jedes Mal einwandfrei lief. Die Ursache hätte in der ersten
     * Sekunde dagestanden, wenn irgendwer die Ausnahme angesehen hätte.
     *
     * Ein verschluckter Fehler ist teurer als ein lauter. Deshalb wird hier festgehalten,
     * was schiefging, auch wenn der Rückgabewert nur `false` sein kann.
     */
    @Volatile
    var lastHealthFailure: String? = null
        private set

    /** Antwortet der Server überhaupt? */
    fun isHealthy(): Boolean = runCatching {
        http.newCall(Request.Builder().url("$baseUrl/health").get().build()).execute().use {
            lastHealthFailure = if (it.isSuccessful) null else "HTTP ${it.code}"
            it.isSuccessful
        }
    }.getOrElse { fehler ->
        lastHealthFailure = describe(fehler)
        false
    }

    fun listModels(): List<ModelInfo> = runCatching {
        http.newCall(Request.Builder().url("$baseUrl/models").get().build()).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            val parsed = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
            val data = parsed["data"] ?: parsed["models"] ?: return emptyList()
            json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(ModelInfo.serializer()), data)
        }
    }.getOrDefault(emptyList())

    /**
     * Erzeugt eine Antwort und liefert sie Stück für Stück.
     *
     * Der Rückruf bestimmt über seinen Rückgabewert, ob weitergemacht wird — `false` bricht
     * ab, indem die Verbindung geschlossen wird. Der Server merkt das und beendet die
     * Erzeugung, statt weiter Strom zu verbrauchen.
     */
    fun streamCompletion(
        modelId: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        grammar: String?,
        stopSequences: List<String>,
        onToken: (String) -> Boolean,
    ): Result<Int> {
        val payload = buildJsonObject {
            put("model", modelId)
            put("stream", true)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            put("top_p", topP)
            if (grammar != null) put("grammar", grammar)
            if (stopSequences.isNotEmpty()) {
                put("stop", buildJsonArray { stopSequences.forEach { add(JsonPrimitive(it)) } })
            }
            put("messages", buildJsonArray {
                messages.forEach { message ->
                    add(buildJsonObject {
                        put("role", message.role.wireName)

                        // Ohne Bilder bleibt der Inhalt eine schlichte Zeichenkette. Das ist
                        // nicht nur kürzer, sondern nötig: Manche Chat-Vorlagen kommen mit
                        // der Listenform nicht zurecht, und dann ist der Prompt still kaputt.
                        if (message.images.isEmpty()) {
                            put("content", message.content)
                        } else {
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", message.content)
                                })
                                message.images.forEach { bild ->
                                    add(buildJsonObject {
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject {
                                            put("url", bild.asDataUri())
                                        })
                                    })
                                }
                            })
                        }
                    })
                }
            })
        }

        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Server antwortete mit ${response.code}: ${errorText(response)}")
                }
                readEventStream(response, onToken)
            }
        }
    }

    /**
     * Liest den Server-Sent-Events-Strom.
     *
     * Jede Nutzzeile beginnt mit `data: `; `[DONE]` beendet den Strom. Zeilen, die sich
     * nicht auswerten lassen, werden übersprungen statt zum Abbruch zu führen — ein
     * unerwartetes Feld soll keine laufende Antwort zerstören.
     */
    private fun readEventStream(response: Response, onToken: (String) -> Boolean): Int {
        val source = response.body?.source() ?: return 0
        var tokens = 0

        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith(DATA_PREFIX)) continue

            val payload = line.removePrefix(DATA_PREFIX).trim()
            if (payload == DONE_MARKER) break

            val text = extractDelta(payload) ?: continue
            if (text.isEmpty()) continue

            tokens++
            if (!onToken(text)) break
        }
        return tokens
    }

    private fun extractDelta(payload: String): String? = runCatching {
        val root = json.parseToJsonElement(payload) as? JsonObject ?: return null
        val choices = root["choices"] as? kotlinx.serialization.json.JsonArray ?: return null
        val first = choices.firstOrNull() as? JsonObject ?: return null
        val delta = first["delta"] as? JsonObject ?: return null

        // JsonNull ist selbst ein JsonPrimitive, dessen `content` die Zeichenkette "null"
        // ergibt. Ohne diese Prüfung beginnt jede Antwort mit einem gesprochenen "null" —
        // der erste Datensatz eines Stroms enthält nämlich nur die Rolle und ein leeres
        // Inhaltsfeld.
        val content = delta["content"]
        if (content == null || content is JsonNull) return null
        (content as? JsonPrimitive)?.content
    }.getOrNull()

    /** Erzeugt einen Vektor für [text]. Verlangt ein Modell, das mit Einbettungen läuft. */
    fun embed(modelId: String, text: String): FloatArray? = runCatching {
        val payload = buildJsonObject {
            put("model", modelId)
            put("input", text)
        }
        http.newCall(
            Request.Builder()
                .url("$baseUrl/v1/embeddings")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) return null
            val root = json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
                ?: return null
            val data = root["data"] as? kotlinx.serialization.json.JsonArray ?: return null
            val first = data.firstOrNull() as? JsonObject ?: return null
            val values = first["embedding"] as? kotlinx.serialization.json.JsonArray ?: return null
            FloatArray(values.size) { (values[it] as JsonPrimitive).content.toFloat() }
        }
    }.getOrNull()

    /**
     * Eine Ausnahme in einer Zeile, die auf einen Telefonbildschirm passt.
     *
     * Der Name der Klasse gehört dazu: `ConnectException` heißt „der Server ist noch nicht
     * so weit" und ist beim Laden völlig normal, `UnknownServiceException` dagegen heißt
     * „Android lässt uns gar nicht erst hin" — ein dauerhafter Ausfall. Ohne den Namen
     * sähen beide gleich aus.
     */
    private fun describe(fehler: Throwable): String {
        val name = fehler.javaClass.simpleName
        val text = fehler.message?.takeIf { it.isNotBlank() } ?: return name
        return "$name: ${text.take(200)}"
    }

    private fun errorText(response: Response): String =
        runCatching { response.body?.string().orEmpty().take(300) }.getOrDefault("")

    override fun close() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    companion object {
        private const val DATA_PREFIX = "data:"
        private const val DONE_MARKER = "[DONE]"
        private val JSON_MEDIA = "application/json".toMediaType()

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Kein Lesezeitlimit.
         *
         * Zwischen zwei Token eines langsam rechnenden Modells können auf einem Telefon
         * mehrere Sekunden liegen; ein Zeitlimit würde genau dann zuschlagen, wenn das
         * Modell arbeitet. Das Verbindungslimit bleibt kurz, weil localhost entweder
         * sofort antwortet oder gar nicht.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}

/** Der Rollenname, den die OpenAI-kompatible Schnittstelle erwartet. */
internal val Role.wireName: String
    get() = when (this) {
        Role.SYSTEM -> "system"
        Role.USER -> "user"
        Role.ASSISTANT -> "assistant"
    }
