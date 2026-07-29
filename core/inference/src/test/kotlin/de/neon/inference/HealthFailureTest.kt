package de.neon.inference

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ein Fehlschlag muss sagen, warum.
 *
 * **Der Anlass.** `isHealthy()` war einmal
 * `runCatching { … }.getOrDefault(false)` — jede Ausnahme wurde zu einem schlichten
 * `false`. Auf dem Gerät sperrte Androids Netzwerkrichtlinie den Zugriff auf 127.0.0.1 und
 * OkHttp warf bei jedem einzelnen Aufruf eine `UnknownServiceException`. Für Neon sah das
 * exakt aus wie „der Server ist noch nicht so weit": Es wartete die volle Frist ab und gab
 * dann ohne Angabe von Gründen auf.
 *
 * Das kostete drei Runden und zwei falsche Diagnosen — bei einem Fehler, dessen Ursache
 * von Anfang an in der verschluckten Ausnahme stand.
 *
 * Der Test hält deshalb nicht den konkreten Fall fest, sondern die Regel: **Wenn die
 * Prüfung scheitert, steht der Grund fest.** Welche Ausnahme es ist, hängt vom System ab;
 * dass überhaupt eine Auskunft kommt, darf es nicht.
 */
class HealthFailureTest {

    /** Ein Port, auf dem sicher niemand hört: geöffnet, die Nummer gemerkt, geschlossen. */
    private fun geschlossenerPort(): Int =
        ServerSocket(0).use { it.localPort }

    @Test
    fun `ein unerreichbarer Server hinterlaesst einen Grund`() {
        val client = LlamaServerClient("http://127.0.0.1:${geschlossenerPort()}")

        assertFalse(client.isHealthy(), "auf einem geschlossenen Port darf nichts antworten")

        val grund = client.lastHealthFailure
        assertNotNull(grund, "die Ausnahme wurde verschluckt — genau das darf nie wieder passieren")
        assertTrue(grund.isNotBlank(), "der Grund war leer: '$grund'")

        // Der Klassenname gehört dazu. „Connection refused" heißt beim Laden „noch nicht so
        // weit", „CLEARTEXT … not permitted" heißt „kommt nie" — ohne den Namen sähen beide
        // Fälle in der Meldung gleich aus.
        assertTrue(
            grund.first().isUpperCase(),
            "der Grund beginnt nicht mit dem Namen der Ausnahme: '$grund'",
        )

        client.close()
    }

    @Test
    fun `eine abschlaegige Antwort nennt den Statuscode`() {
        // llama-server antwortet auf /health mit 503, solange er noch lädt. Das ist kein
        // Fehler, aber es ist eine andere Lage als „gar keine Verbindung" — und beim
        // Suchen ist genau dieser Unterschied das Entscheidende.
        val server = ServerSocket(0)
        val thread = Thread {
            runCatching {
                server.accept().use { socket ->
                    socket.getInputStream().bufferedReader().readLine()
                    socket.getOutputStream().write(
                        "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n"
                            .toByteArray()
                    )
                    socket.getOutputStream().flush()
                }
            }
        }
        thread.isDaemon = true
        thread.start()

        val client = LlamaServerClient("http://127.0.0.1:${server.localPort}")
        assertFalse(client.isHealthy())
        assertEquals("HTTP 503", client.lastHealthFailure)

        client.close()
        server.close()
    }

    @Test
    fun `nach einer geglueckten Pruefung ist kein Grund mehr vermerkt`() {
        val server = ServerSocket(0)
        val thread = Thread {
            runCatching {
                server.accept().use { socket ->
                    socket.getInputStream().bufferedReader().readLine()
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray()
                    )
                    socket.getOutputStream().flush()
                }
            }
        }
        thread.isDaemon = true
        thread.start()

        val client = LlamaServerClient("http://127.0.0.1:${server.localPort}")
        assertTrue(client.isHealthy())

        // Sonst bliebe ein alter Grund stehen und würde später als aktuelle Ursache
        // gemeldet — eine Fehlersuche, die auf eine längst behobene Sache zeigt.
        assertEquals(null, client.lastHealthFailure)

        client.close()
        server.close()
    }
}
