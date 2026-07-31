package de.neon.inference

import de.neon.router.RouteAnalysis
import de.neon.router.RouterLlm
import de.neon.router.RouterLlmProtocol
import de.neon.router.Utterance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Stufe 2 des Routers: einordnen statt antworten.
 *
 * Zwei Eigenheiten machen das mit einem kleinen Modell brauchbar:
 *
 *  - Die Ausgabe wird per Grammatik erzwungen. Ohne sie würde ein Modell regelmäßig
 *    erklären, warum es diese Kategorie gewählt hat, statt schlicht JSON auszugeben.
 *  - Temperatur null. Eine Klassifikation soll bei gleicher Frage immer gleich ausfallen;
 *    Kreativität ist hier ausschließlich schädlich.
 *
 * **Es wird ausdrücklich nie das Modell gewechselt.** llama-server bedient je Lauf genau ein
 * Modell; für eine Einordnung das geladene Antwortmodell zu entladen und danach wieder
 * einzulesen würde die Einordnung teurer machen als die Antwort. Läuft nichts, gibt diese
 * Stufe `null` zurück und der Router entscheidet ohne sie.
 *
 * Ein eigenes, dauerhaft geladenes 0.6B-Router-Modell lohnt erst, wenn mehrere
 * Antwortmodelle im Wechsel laufen — dann als zweiter Serverprozess auf eigenem Port.
 */
class LocalRouterLlm(
    private val engine: InferenceEngine,
    /**
     * Wohin ein Fehlschlag geht.
     *
     * **Warum das nötig wurde.** Hier stand nur `return null`, und der Router entschied
     * daraufhin ohne Stufe 2 — lautlos. Im Protokoll des Geräts stehen mehrere
     * Serveranfragen, zu denen es keine einzige Neon-Zeile gibt: Ob die Einordnung gelang
     * oder still scheiterte, ließ sich nicht entscheiden. Ein Rückfall auf die Regelstufe ist
     * kein Fehler, aber er ist eine Auskunft — und eine, die niemand bekam.
     *
     * Eine Funktion und nicht `NeonLog`, weil dieses Modul ohne Android prüfbar bleibt.
     */
    private val log: (String) -> Unit = {},
) : RouterLlm {

    /**
     * Der Router ruft synchron auf, weil er selbst keine Coroutine ist.
     *
     * Vertretbar, weil der Aufruf im Dienst ohnehin auf einem Hintergrund-Thread läuft und
     * einige hundert Millisekunden dauert — dieselbe Größenordnung wie die Spracherkennung
     * davor.
     */
    override fun analyze(utterance: Utterance): RouteAnalysis? = runBlocking {
        analyzeSuspending(utterance)
    }

    suspend fun analyzeSuspending(utterance: Utterance): RouteAnalysis? {
        if (engine.loadedModelId == null) return null

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

        val gescheitert = chunks.filterIsInstance<GenerationChunk.Failed>().firstOrNull()
        if (gescheitert != null) {
            log(
                "Einordnung gescheitert, entscheide nach Regeln — ${gescheitert.reason}" +
                    gescheitert.detail?.let { " · $it" }.orEmpty()
            )
            return null
        }

        val raw = chunks
            .filterIsInstance<GenerationChunk.Token>()
            .joinToString("") { it.text }

        return RouterLlmProtocol.parse(raw)
            // Auch das ist ein Fehlschlag, nur ein anderer: Der Server lieferte, die Ausgabe
            // ließ sich aber nicht auswerten. Ohne Zeile sieht das genauso aus wie „Stufe 2
            // war gar nicht dran".
            ?: run {
                log("Einordnung unlesbar, entscheide nach Regeln — \"${raw.take(120)}\"")
                null
            }
    }

    private companion object {
        /**
         * Das erwartete JSON ist kurz. Die Grenze verhindert, dass ein Modell, das die
         * Grammatik nicht einhält, unbegrenzt weiterredet und Strom verbrennt.
         */
        const val MAX_TOKENS = 64
    }
}
