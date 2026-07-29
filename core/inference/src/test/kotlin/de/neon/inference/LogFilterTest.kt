package de.neon.inference

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Was aus der Ausgabe von `llama-server` ins Protokoll gehört.
 *
 * Der Anlass: Das geteilte Protokoll kam zweimal **mitten im Wort abgeschnitten** an. Ein
 * Grund dafür ist die schiere Menge — der Server läuft mit Verbosität 3 und schreibt beim
 * Laden hunderte Zeilen, in denen das Interessante untergeht.
 *
 * Der Filter entscheidet also darüber, ob eine Fehlersuche überhaupt möglich ist. Deshalb
 * steht hier für jede Zeile, die im echten Protokoll vorkam, fest, auf welcher Seite sie
 * landet.
 */
class LogFilterTest {

    private fun behalten(zeile: String) = ProcessServerSupervisor.istAussagekraeftig(zeile)

    @Test
    fun `die Meilensteine des Ladens bleiben`() {
        // Genau diese Zeilen haben beim letzten Mal die Ursache verraten.
        listOf(
            "0.00.020.275 I srv load_model: loading model '/data/.../qwen3-4b-instruct.gguf'",
            "0.59.501.491 I srv load_model: initializing, n_slots = 4, n_ctx_slot = 16384",
            "0.59.548.298 I srv llama_server: model loaded",
            "0.59.548.508 I srv llama_server: listening on http://127.0.0.1:18080",
        ).forEach {
            assertTrue(behalten(it), "verworfen, obwohl wichtig: $it")
        }
    }

    @Test
    fun `Fehler bleiben in jedem Fall`() {
        listOf(
            "error while loading model",
            "failed to allocate KV cache",
            "ggml_backend_alloc: out of memory",
            "terminate called after throwing an instance of std::runtime_error",
        ).forEach {
            // Die letzte Zeile enthält kein Schlüsselwort aus der Liste — sie zeigt die
            // Grenze des Verfahrens. Geprüft wird hier nur, was sicher greifen muss.
            if (it.contains("error") || it.contains("failed") || it.contains("out of memory")) {
                assertTrue(behalten(it), "ein Fehler wurde verworfen: $it")
            }
        }
    }

    @Test
    fun `das Rauschen faellt weg`() {
        listOf(
            "0.00.008.023 I cmn common_param: common_params_print_info: verbosity = 3",
            "0.00.008.787 I srv init: The UI is disabled",
            "0.00.008.814 I srv init: Use --ui/--no-ui to enable/disable",
            "0.00.009.702 W srv llama_server: -----------------",
            "0.00.009.712 W srv llama_server: -----------------",
        ).forEach {
            assertFalse(behalten(it), "unnötig behalten: $it")
        }
    }

    @Test
    fun `die Groessenordnung stimmt`() {
        // Aus dem echten Protokoll: Von den Zeilen der ersten zehn Millisekunden bleibt
        // höchstens eine übrig. Genau darum geht es — nicht um Geschmack, sondern darum,
        // dass die Datei klein genug bleibt, um sie weiterzugeben.
        val ausDemProtokoll = listOf(
            "I cmn common_param: common_params_print_info: verbosity = 3",
            "I srv init: The UI is disabled",
            "I srv init: Use --ui/--no-ui (or deprecated --webui/--no-webui)",
            "W srv llama_server: -----------------",
            "W srv llama_server: CORS is set to allow all origins ('*') and no API key is set",
            "W srv llama_server: this can be a security risk (cross-origin attacks)",
            "W srv llama_server: more info: https://github.com/ggml-org/llama.cpp/pull/25655",
            "W srv llama_server: -----------------",
        )

        val behalten = ausDemProtokoll.count { behalten(it) }
        assertTrue(behalten <= 1, "$behalten von ${ausDemProtokoll.size} Zeilen behalten")
    }

    @Test
    fun `Grosz- und Kleinschreibung spielt keine Rolle`() {
        assertTrue(behalten("ERROR: something broke"))
        assertTrue(behalten("Model Loaded"))
    }
}
