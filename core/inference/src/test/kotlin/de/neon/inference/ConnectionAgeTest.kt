package de.neon.inference

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Eine Verbindung, die der Server längst zugemacht hat.
 *
 * **Der Anlass.** Gemeldet wurde `unexpected end of stream on http://127.0.0.1:18080/`. Der
 * Serverprozess lebte, 7,1 von 14,8 GB waren frei, und `llama-server` hatte die Antwort
 * vollständig gerechnet — 46 Token in 6,6 Sekunden. Neon sah **null**.
 *
 * Nachgesehen im mitgelieferten Quelltext von llama.cpp bei `e9fa078`:
 *
 * ```c
 * #define CPPHTTPLIB_KEEPALIVE_TIMEOUT_SECOND 5
 * ```
 *
 * `llama-server` benutzt cpp-httplib und ruft `set_keep_alive_timeout` nirgends auf. Eine
 * untätige Verbindung wird also nach **fünf Sekunden** geschlossen. OkHttp behält sie
 * dagegen fünf Minuten im Pool.
 *
 * Das Muster auf dem Gerät passt dazu:
 *
 * | Pause vor der Frage | Ergebnis |
 * |---|---|
 * | erste Frage, frische Verbindung | Antwort |
 * | 17 s | Antwort |
 * | 34 s | Abbruch |
 * | 51 s | Abbruch |
 *
 * Deshalb zwei Änderungen: Der Pool gibt eine Verbindung auf, **bevor** der Server sie
 * zumacht — und ein Fehlschlag auf einer gepoolten Verbindung darf einen zweiten Versuch
 * kosten. Dass die Pause im Protokoll landet, ist der dritte Teil: Ein Muster aus vier
 * Beobachtungen ist noch kein Beweis, und die nächste Zeile entscheidet die Frage.
 */
class ConnectionAgeTest {

    @Test
    fun `der Pool gibt eine Verbindung vor dem Server auf`() {
        val pool = LlamaServerClient.defaultClient().connectionPool

        // OkHttp veröffentlicht die Frist nicht, also über die Reflexion: `ConnectionPool`
        // ist eine Hülle um `RealConnectionPool`, dort liegt `keepAliveDurationNs`. Der Wert
        // *ist* die Korrektur — ihn ungeprüft zu lassen hieße, sie nicht zu prüfen. Bricht
        // eine künftige OkHttp-Fassung diesen Weg, schlägt der Test fehl und sagt es, statt
        // stillschweigend nichts mehr zu prüfen.
        val delegate = pool.javaClass.getDeclaredField("delegate")
            .apply { isAccessible = true }
            .get(pool)!!
        val nanos = delegate.javaClass.getDeclaredField("keepAliveDurationNs")
            .apply { isAccessible = true }
            .get(delegate) as Long
        val sekunden = TimeUnit.NANOSECONDS.toSeconds(nanos)

        assertTrue(
            sekunden in 1..4,
            "Frist $sekunden s — sie muss unter den fünf Sekunden von cpp-httplib liegen " +
                "und über null, sonst wird für jede Frage neu verbunden",
        )
    }

    @Test
    fun `ein Fehlschlag auf einer alten Verbindung darf einen zweiten Versuch kosten`() {
        // Hier stand `false` mit der Begründung, localhost antworte entweder sofort oder gar
        // nicht. Für den Verbindungsaufbau stimmt das; für eine gepoolte Verbindung heißt ein
        // Fehlschlag nicht „der Server ist weg", sondern „diese Verbindung war alt".
        assertTrue(LlamaServerClient.defaultClient().retryOnConnectionFailure)
    }

    @Test
    fun `ohne vorherige Antwort gibt es keine Pause zu melden`() {
        // Eine „0 s" wäre eine erfundene Messung. Und die Unterscheidung trägt die
        // Erklärung: Der erste Aufruf auf einer frischen Verbindung gelang auf dem Gerät
        // jedes Mal.
        assertNull(LlamaServerEngine.pause(-1))
        assertEquals(-1, LlamaServerClient("http://127.0.0.1:1").pauseVorAnfrageMillis)
    }

    @Test
    fun `eine gemessene Pause steht in Sekunden da`() {
        assertEquals("Pause vor der Anfrage: 34.0 s", LlamaServerEngine.pause(34_000))
        assertEquals("Pause vor der Anfrage: 0.25 s", LlamaServerEngine.pause(250))
    }
}
