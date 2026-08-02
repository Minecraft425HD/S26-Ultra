package de.neon.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Was Neon selbst tun kann, muss auch als CODE erkannt werden.
 *
 * **Der Fehler, den das festhält, war der bisher ärgerlichste.** Auf „mach mir eine Zähler-App
 * für Android und erstelle das Projekt, danach direkt kompilieren" antwortete Neon mit einer
 * Anleitung: *„Erstelle ein neues Projekt in Eclipse oder Android Studio … Klick auf Build →
 * Build Project."* Eine App, die selbst eine Bau-Kette mitbringt, erklärte dem Nutzer, wie er
 * es auf einem Rechner machen soll, den er nicht hat.
 *
 * Die Ursache lag nicht bei den Werkzeugen — die waren inzwischen alle angeschlossen — sondern
 * eine Stufe davor. Der Prosa-Text beweist es: Bei der Kategorie `CODE` erzwingt die Grammatik
 * einen Werkzeugaufruf, Prosa ist dort unmöglich. Also war die Kategorie nicht `CODE`.
 *
 * **Und Stufe 1 konnte sie gar nicht liefern.** Alle neun Code-Beispiele handelten vom
 * Schreiben eines Schnipsels — Skript, Funktion, Schleife, Regex. Die Wörter, mit denen man
 * eine App bauen lässt, kamen in keinem vor: App, Projekt, anlegen, bauen, kompilieren,
 * installieren. Gemessen lag „bau die App" unter der Mindestähnlichkeit von 0,30 zu **jedem**
 * Beispiel — kein schwacher Treffer, gar keiner.
 *
 * Diese Tests prüfen dieselben Sätze wie damals. Sie sind billig und hätten den Fehler vor der
 * Auslieferung gefunden.
 */
class IdeEinordnungTest {

    private val embeddings = HashingEmbeddingProvider()

    private val knn = KnnClassifier(
        examples = SeedExamples.materialize(embeddings),
        // Dieselben Werte wie im Container. Ein Test mit großzügigeren Schwellen würde eine
        // Treffgenauigkeit nachweisen, die es auf dem Gerät nicht gibt.
        k = 5,
        minSimilarity = 0.30,
        minMargin = 0.10,
    )

    private fun einordnung(satz: String) = knn.classify(embeddings.embed(satz))

    /**
     * Sätze, mit denen jemand Neons eigene Entwicklungsumgebung anspricht.
     *
     * Bewusst in der Sprache, in der man wirklich spricht — nicht in der der Werkzeugnamen.
     * Niemand sagt „app-anlegen".
     */
    private val ideSaetze = listOf(
        "mach mir eine Zähler app für Android und erstelle das Projekt, danach direkt kompilieren",
        "mach mir eine Zähler app für Android",
        "erstelle ein Android Projekt",
        "leg mir eine App namens Zähler an",
        "bau die App",
        "kompilier das Projekt",
        "bau mir daraus eine APK",
        "schreib den Code in eine Datei",
        "lies die Datei MainActivity.kt",
        "welche Dateien gibt es im Projekt",
        "führ das Skript aus",
    )

    @Test
    fun `jeder Auftrag an die Entwicklungsumgebung wird als CODE erkannt`() {
        val danebengegriffen = ideSaetze.mapNotNull { satz ->
            val ergebnis = einordnung(satz)
            when {
                ergebnis == null -> "„$satz\" → gar kein Treffer"
                ergebnis.category != TaskCategory.CODE ->
                    "„$satz\" → ${ergebnis.category} (%.2f)".format(ergebnis.confidence)
                else -> null
            }
        }

        assertTrue(
            danebengegriffen.isEmpty(),
            "Stufe 1 erkennt diese Aufträge nicht als CODE:\n" +
                danebengegriffen.joinToString("\n"),
        )
    }

    @Test
    fun `der Satz aus dem Fehlerbericht wird ohne Stufe 2 erkannt`() {
        // Genau der Satz, auf den Neon mit einer Android-Studio-Anleitung antwortete. Er soll
        // nicht nur richtig eingeordnet werden, sondern sicher genug, dass Stufe 2 gar nicht
        // erst befragt wird — das spart auf dem Gerät ein bis vier Sekunden je Durchgang.
        val ergebnis = einordnung(
            "mach mir eine Zähler app für Android und erstelle das Projekt, " +
                "danach direkt kompilieren"
        )

        assertNotNull(ergebnis)
        assertEquals(TaskCategory.CODE, ergebnis.category)
        assertTrue(
            ergebnis.confidence >= ROUTER_KNN_SCHWELLE,
            "Zuversicht %.2f liegt unter der Schwelle des Routers — dann entscheidet Stufe 2, "
                .format(ergebnis.confidence) +
                "und genau die hat hier WISSENSFRAGE geraten",
        )
    }

    @Test
    fun `bau die App ist eindeutig`() {
        // Der kürzeste denkbare Bauauftrag. Wenn *der* nicht sicher erkannt wird, ist die
        // Kette „anlegen, dann bauen" über zwei Durchgänge hinweg nicht benutzbar.
        val ergebnis = einordnung("bau die App")

        assertNotNull(ergebnis)
        assertEquals(TaskCategory.CODE, ergebnis.category)
        assertTrue(ergebnis.confidence >= ROUTER_KNN_SCHWELLE, "%.2f".format(ergebnis.confidence))
    }

    @Test
    fun `die Beschreibung fuer Stufe 2 nennt, was Neon selbst tun kann`() {
        // Der zweite Teil derselben Ursache: Stufe 2 bekam „Programmieren, Shell,
        // Konfiguration, Fehlersuche" zu lesen. Das beschreibt jemanden, der über Code redet
        // — nicht jemanden, der eine App bauen lässt. Das Modell konnte nicht wissen, dass
        // Neon Projekte anlegt und APKs baut.
        val prompt = RouterLlmProtocol.systemPrompt.lowercase()

        listOf("app", "projekt", "kompilieren", "apk", "python").forEach { wort ->
            assertTrue(
                wort in prompt,
                "„$wort\" fehlt in der Beschreibung für das Router-Modell:\n" +
                    RouterLlmProtocol.systemPrompt,
            )
        }
    }

    @Test
    fun `eine Wissensfrage bleibt eine Wissensfrage`() {
        // Die Gegenprobe. Zwölf neue Code-Beispiele könnten die Grenze verschieben, und ein
        // Router, der alles für Programmierung hält, wäre schlimmer als der vorige Zustand:
        // Auf „warum ist der Himmel blau" bekäme man dann einen Werkzeugaufruf.
        listOf(
            "warum ist der himmel blau",
            "was ist die hauptstadt von peru",
            "erklär mir wie ein kühlschrank funktioniert",
            "wie spät ist es",
            "schalte das licht im wohnzimmer an",
        ).forEach { satz ->
            val ergebnis = einordnung(satz)
            assertTrue(
                ergebnis == null || ergebnis.category != TaskCategory.CODE,
                "„$satz\" wurde als CODE eingeordnet — dann wählt Neon dafür ein Werkzeug",
            )
        }
    }

    private companion object {
        /** Ab hier übernimmt Stufe 1 allein; siehe `Router.knnConfidenceThreshold`. */
        const val ROUTER_KNN_SCHWELLE = 0.6
    }
}
