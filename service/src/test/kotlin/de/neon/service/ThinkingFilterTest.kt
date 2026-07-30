package de.neon.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Selbstgespräche gehören nicht in die Antwort.
 *
 * **Der Anlass.** Zwei Fragen gestellt, eine beantwortet. Im Protokoll steht, dass
 * `llama-server` beide Aufgaben fertig gerechnet und sauber beendet hat — 10 Token bei der
 * ersten, 44 bei der zweiten. Trotzdem kam nur eine Antwort an.
 *
 * `--reasoning` stand auf `auto`, und Qwen3s Chat-Vorlage schaltet Denken damit **ein**.
 * Jedes Token eines `<think>`-Blocks landete ungefiltert in der Sprechblase und wurde
 * vorgelesen. Vierundvierzig Token reichen für einen Denkblock und keine Antwort — auf einem
 * Gerät mit anderthalb Token je Sekunde ist das kein Randfall, sondern der Normalfall.
 *
 * Abgeschaltet wird das am Server. Dieser Filter ist die zweite Linie, für Modelle, die sich
 * nicht daran halten.
 */
class ThinkingFilterTest {

    @Test
    fun `ein Denkblock verschwindet, die Antwort bleibt`() {
        val roh = "<think>Der Nutzer grüßt. Ich grüße zurück.</think>Hallo! Wie kann ich helfen?"

        assertEquals("Hallo! Wie kann ich helfen?", ThinkingFilter.strip(roh))
    }

    @Test
    fun `ohne Denkblock bleibt alles unveraendert`() {
        // Der häufigste Fall, wenn der Serverschalter greift. Er darf nichts kosten.
        val schlicht = "Der Kölner Dom ist 157 Meter hoch."

        assertEquals(schlicht, ThinkingFilter.strip(schlicht))
    }

    @Test
    fun `ein nie geschlossener Denkblock nimmt alles mit`() {
        // Genau der gemessene Fall: Das Token-Budget war mitten im Überlegen aufgebraucht.
        // Was dann folgt, ist kein Satz, sondern ein Fragment — und ein Fragment vorzulesen
        // ist schlechter, als nichts zu sagen.
        val abgebrochen = "<think>Also, die Frage ist eigentlich, ob"

        assertEquals("", ThinkingFilter.strip(abgebrochen))
        assertTrue(ThinkingFilter.istLeer(abgebrochen))
    }

    @Test
    fun `mehrere Bloecke werden alle entfernt`() {
        val roh = "<think>eins</think>Erstens.<think>zwei</think> Zweitens."

        assertEquals("Erstens. Zweitens.", ThinkingFilter.strip(roh))
    }

    @Test
    fun `auch die reasoning-Form wird erkannt`() {
        val roh = "<reasoning>überlege</reasoning>Die Antwort ist 42."

        assertEquals("Die Antwort ist 42.", ThinkingFilter.strip(roh))
    }

    @Test
    fun `Grosz- und Kleinschreibung spielt keine Rolle`() {
        assertEquals("Da.", ThinkingFilter.strip("<THINK>xyz</THINK>Da."))
    }

    @Test
    fun `eine echte Antwort gilt nicht als leer`() {
        assertFalse(ThinkingFilter.istLeer("<think>egal</think>Ja."))
        assertTrue(ThinkingFilter.istLeer("<think>egal</think>   "))
    }

    @Test
    fun `ein einzelnes spitzes Zeichen bricht nichts`() {
        // Antworten enthalten Vergleiche und Quelltext. Was wie ein Anfang aussieht, aber
        // keiner ist, darf nicht die halbe Antwort schlucken.
        val mathe = "Für a < b gilt: a ist kleiner."

        assertEquals(mathe, ThinkingFilter.strip(mathe))
    }
}
