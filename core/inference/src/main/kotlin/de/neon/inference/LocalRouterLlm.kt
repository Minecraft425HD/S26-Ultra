package de.neon.inference

import de.neon.router.ModelSpec
import de.neon.router.RouteAnalysis
import de.neon.router.RouterLlm
import de.neon.router.RouterLlmProtocol
import de.neon.router.Utterance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Stufe 2 des Routers: das kleine Modell, das nur einsortiert.
 *
 * Zwei Eigenheiten machen das brauchbar, obwohl das Modell winzig ist:
 *
 *  - Die Ausgabe wird per Grammatik erzwungen. Ein 0.6B-Modell würde sonst regelmäßig
 *    erklären, warum es diese Kategorie gewählt hat, statt einfach JSON auszugeben.
 *  - Es läuft mit Temperatur null. Eine Klassifikation soll bei gleicher Frage immer
 *    dasselbe Ergebnis liefern; Kreativität ist hier ausschließlich schädlich.
 *
 * Wichtig für die Energiebilanz: Das Router-Modell hat einen **eigenen** Motor. Liefe es
 * über denselben wie die Antwortmodelle, müsste für jede Klassifikation das Alltagsmodell
 * entladen und danach wieder geladen werden — die Einordnung würde teurer als die Antwort.
 */
class LocalRouterLlm(
    private val engine: InferenceEngine,
    private val model: ModelSpec,
    private val modelFiles: ModelFileResolver,
) : RouterLlm {

    /**
     * Der Router ruft synchron auf, weil er selbst keine Coroutine ist.
     *
     * Das ist vertretbar, weil der Aufruf im Dienst ohnehin auf einem Hintergrund-Thread
     * läuft und einige hundert Millisekunden dauert — dieselbe Größenordnung wie die
     * Spracherkennung davor.
     */
    override fun analyze(utterance: Utterance): RouteAnalysis? = runBlocking {
        analyzeSuspending(utterance)
    }

    suspend fun analyzeSuspending(utterance: Utterance): RouteAnalysis? {
        if (engine.loadedModelId != model.id) {
            val file = modelFiles.fileFor(model) ?: return null
            if (!engine.load(model, file)) return null
        }

        val chunks = engine.generate(
            GenerationRequest(
                messages = listOf(
                    ChatMessage(Role.SYSTEM, RouterLlmProtocol.systemPrompt),
                    ChatMessage(Role.USER, RouterLlmProtocol.userPrompt(utterance)),
                ),
                maxTokens = MAX_TOKENS,
                temperature = 0f,
                grammar = RouterLlmProtocol.grammar,
            )
        ).toList()

        if (chunks.any { it is GenerationChunk.Failed }) return null

        val raw = chunks
            .filterIsInstance<GenerationChunk.Token>()
            .joinToString("") { it.text }

        return RouterLlmProtocol.parse(raw)
    }

    private companion object {
        /**
         * Das erwartete JSON ist kurz. Die Grenze verhindert, dass ein Modell, das die
         * Grammatik nicht einhält, unbegrenzt weiterredet und Strom verbrennt.
         */
        const val MAX_TOKENS = 64
    }
}
