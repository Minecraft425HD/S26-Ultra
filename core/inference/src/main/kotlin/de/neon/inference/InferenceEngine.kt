package de.neon.inference

import de.neon.router.ModelSpec
import kotlinx.coroutines.flow.Flow
import java.io.File

enum class Role { SYSTEM, USER, ASSISTANT }

/**
 * Eine Nachricht im Gespräch.
 *
 * @param images Bilder als reine Bytes, die zusammen mit dem Text gehen. Nur ein Modell mit
 * Projektordatei kann etwas damit anfangen; alle anderen bekommen sie gar nicht erst
 * geschickt, weil llama-server sie sonst mit einem Fehler abweist.
 */
data class ChatMessage(
    val role: Role,
    val content: String,
    val images: List<ImageAttachment> = emptyList(),
)

/** Ein Bild, wie es an ein Bildmodell geht. */
data class ImageAttachment(
    val bytes: ByteArray,
    /** Etwa `image/jpeg`. Landet unverändert in der Daten-URI. */
    val mimeType: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ImageAttachment && other.mimeType == mimeType && other.bytes.contentEquals(bytes))

    override fun hashCode(): Int = 31 * mimeType.hashCode() + bytes.contentHashCode()

    /**
     * Das Bild als Daten-URI, so wie llama-server es erwartet.
     *
     * Base64 bläht die Daten um ein Drittel auf. Bei einem Bildschirmfoto sind das ein paar
     * hundert Kilobyte über eine Verbindung nach 127.0.0.1 — der Umweg über eine Datei wäre
     * mehr Aufwand als Gewinn.
     */
    fun asDataUri(): String =
        "data:" + mimeType + ";base64," + java.util.Base64.getEncoder().encodeToString(bytes)
}

data class GenerationRequest(
    val messages: List<ChatMessage>,
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    /**
     * GBNF-Grammatik, die die Ausgabe erzwingt.
     *
     * Damit gibt auch ein 0.6B-Modell garantiert gültiges JSON zurück — die Grundlage
     * dafür, dass Stufe 2 des Routers und die Werkzeugaufrufe verlässlich funktionieren.
     */
    val grammar: String? = null,
    val stopSequences: List<String> = emptyList(),
)

/** Ein Stück der Antwort, während sie entsteht. */
sealed interface GenerationChunk {
    data class Token(val text: String) : GenerationChunk
    data class Done(val tokenCount: Int, val tokensPerSecond: Double) : GenerationChunk

    /**
     * Die Antwort kam nicht zustande.
     *
     * @param reason der Fall in einem Satz. Das steht in der Sprechblase und wird gesprochen.
     * @param detail die Rohmeldung und die Zahlen dahinter — fürs Protokoll, nicht für die
     *   Sprechblase.
     *
     *   Zwei Felder statt einem, weil dieselbe Auskunft zwei Adressaten hat.
     *   `unexpected end of stream on http://127.0.0.1:18080/` ist genau die Meldung, mit
     *   der dieser Fehler gemeldet wurde: Sie sagt einem Menschen nichts, taugt aber als
     *   einzige Spur. Sie vorzulesen hilft niemandem, sie wegzuwerfen auch nicht.
     */
    data class Failed(val reason: String, val detail: String? = null) : GenerationChunk
}

/**
 * Führt ein lokales Sprachmodell aus.
 *
 * Genau ein Modell ist gleichzeitig geladen. Das Umschalten übernimmt der
 * [ModelLifecycleManager] — die Engine selbst weiß nichts von Speicherbudgets.
 */
interface InferenceEngine {

    val loadedModelId: String?

    suspend fun load(model: ModelSpec, file: File, projector: File? = null): Boolean

    suspend fun unload()

    /**
     * Erzeugt die Antwort als Strom.
     *
     * Bewusst ein Strom und keine fertige Zeichenkette: Neon fängt an zu sprechen, sobald
     * der erste Satz steht. Bei rund zwanzig Token je Sekunde wäre das Warten auf die
     * vollständige Antwort der Unterschied zwischen Gespräch und Ladebalken.
     */
    fun generate(request: GenerationRequest): Flow<GenerationChunk>
}
