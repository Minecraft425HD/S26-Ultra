package de.neon.inference

import de.neon.router.ModelSpec
import kotlinx.coroutines.flow.Flow
import java.io.File

enum class Role { SYSTEM, USER, ASSISTANT }

data class ChatMessage(val role: Role, val content: String)

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
    data class Failed(val reason: String) : GenerationChunk
}

/**
 * Führt ein lokales Sprachmodell aus.
 *
 * Genau ein Modell ist gleichzeitig geladen. Das Umschalten übernimmt der
 * [ModelLifecycleManager] — die Engine selbst weiß nichts von Speicherbudgets.
 */
interface InferenceEngine {

    val loadedModelId: String?

    suspend fun load(model: ModelSpec, file: File): Boolean

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
