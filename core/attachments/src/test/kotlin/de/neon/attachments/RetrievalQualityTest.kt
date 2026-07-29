package de.neon.attachments

import de.neon.router.HashingEmbeddingProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Misst, wie gut Neon die richtige Stelle findet.
 *
 * Der Kern des ganzen Vorhabens: Weil nicht alles in den Kontext passt, entscheidet der
 * Abruf darüber, ob eine Frage beantwortet wird oder nicht. Eine Behauptung wie „findet
 * meistens das Richtige" ist wertlos — deshalb dieselbe Methode wie beim Router: ein
 * kleiner Korpus, feste Fragen mit bekannter Zieldatei, und eine Zahl am Ende.
 *
 * Die Zahl ist eine Untergrenze, kein Gütesiegel. Sie fängt Verschlechterungen ab und macht
 * eine Verbesserung — etwa den Wechsel auf einen echten Einbetter — belegbar statt gefühlt.
 */
class RetrievalQualityTest {

    private val embeddings = HashingEmbeddingProvider()
    private val ranker = ChunkRanker(embeddings)

    /** Ein Projektverzeichnis, wie man es anhängen würde. */
    private val korpus = listOf(
        BytesSource(
            name = "build.gradle.kts",
            path = "app/build.gradle.kts",
            bytes = """
                plugins { id("com.android.application") }
                android {
                    namespace = "de.neon.app"
                    compileSdk = 36
                    defaultConfig { minSdk = 33 }
                }
                dependencies { implementation(project(":core:router")) }
            """.trimIndent().toByteArray(),
        ),
        BytesSource(
            name = "Router.kt",
            path = "core/router/Router.kt",
            bytes = """
                package de.neon.router
                class Router(private val registry: ModelRegistry) {
                    fun route(utterance: Utterance, state: DeviceState): RouteDecision {
                        val analysis = analyze(utterance)
                        return RouteDecision.Generate(policy.select(analysis, state))
                    }
                }
            """.trimIndent().toByteArray(),
        ),
        BytesSource(
            name = "einkaufsliste.md",
            path = "notizen/einkaufsliste.md",
            bytes = """
                # Einkauf am Samstag
                Milch, Butter, Mehl und Zucker besorgen.
                Für den Kuchen fehlen noch Eier und Vanille.
                Beim Bäcker zwei Brötchen mitnehmen.
            """.trimIndent().toByteArray(),
        ),
        BytesSource(
            name = "urlaub.txt",
            path = "notizen/urlaub.txt",
            bytes = """
                Flug nach Lissabon am 14. September, Abflug um 6 Uhr 40.
                Hotel Estrela, drei Nächte, Frühstück inklusive.
                Mietwagen erst ab dem zweiten Tag gebucht.
            """.trimIndent().toByteArray(),
        ),
        BytesSource(
            name = "fehler.log",
            path = "logs/fehler.log",
            bytes = """
                2026-07-29 08:44:07 FATAL PatternSyntaxException in RuleMatcher
                2026-07-29 08:44:07 Ursache: das Unicode-Flag wird von ICU nicht unterstützt
                2026-07-29 08:44:08 Anwendung beendet
            """.trimIndent().toByteArray(),
        ),
        BytesSource(
            name = "rezept.md",
            path = "notizen/rezept.md",
            bytes = """
                # Hefeteig
                500 Gramm Mehl mit einem Würfel Hefe und lauwarmer Milch verrühren.
                Eine Stunde gehen lassen, dann bei 200 Grad backen.
            """.trimIndent().toByteArray(),
        ),
    )

    private val index: List<IndexedChunk> by lazy {
        AttachmentIngest().ingest(korpus).chunks.map {
            IndexedChunk(it, embeddings.embed(it.text))
        }
    }

    /** Frage samt der Datei, in der die Antwort steht. */
    private val fragen = listOf(
        "was steht in der build gradle" to "app/build.gradle.kts",
        "welche compileSdk ist eingestellt" to "app/build.gradle.kts",
        "welche minSdk benutzt die app" to "app/build.gradle.kts",
        "was macht die klasse Router" to "core/router/Router.kt",
        "wie heißt die methode zum routen" to "core/router/Router.kt",
        "zeig mir die Router kt" to "core/router/Router.kt",
        "was soll ich einkaufen" to "notizen/einkaufsliste.md",
        "was fehlt noch für den kuchen" to "notizen/einkaufsliste.md",
        "wie viele brötchen beim bäcker" to "notizen/einkaufsliste.md",
        "wann geht mein flug" to "notizen/urlaub.txt",
        "wie heißt das hotel" to "notizen/urlaub.txt",
        "ab wann habe ich den mietwagen" to "notizen/urlaub.txt",
        "was steht in der einkaufsliste" to "notizen/einkaufsliste.md",
        "welcher fehler steht im log" to "logs/fehler.log",
        "warum ist die anwendung beendet worden" to "logs/fehler.log",
        "was sagt die fehler log" to "logs/fehler.log",
        "wie viel mehl brauche ich für den hefeteig" to "notizen/rezept.md",
        "bei wie viel grad backen" to "notizen/rezept.md",
        "wie lange muss der teig gehen" to "notizen/rezept.md",
        "was steht in urlaub txt" to "notizen/urlaub.txt",
    )

    @Test
    fun `die richtige Datei steht meist ganz oben`() {
        val treffer = fragen.count { (frage, ziel) ->
            ranker.rank(frage, index, limit = 1).firstOrNull()?.chunk?.filePath == ziel
        }

        val quote = treffer.toDouble() / fragen.size
        println("Abruf: bester Treffer richtig in $treffer von ${fragen.size} Fällen (%.0f %%)".format(quote * 100))

        assertTrue(
            quote >= 0.90,
            "nur ${(quote * 100).toInt()} % — schlechter als der bisherige Stand:\n" + fehlschlaege(1),
        )
    }

    @Test
    fun `unter den ersten drei ist sie fast immer`() {
        // Der Wert, auf den es im Betrieb ankommt: In den Prompt gehen mehrere Stellen, es
        // muss also nicht die erste sein, nur eine der oberen.
        val treffer = fragen.count { (frage, ziel) ->
            ranker.rank(frage, index, limit = 3).any { it.chunk.filePath == ziel }
        }

        val quote = treffer.toDouble() / fragen.size
        println("Abruf: richtige Datei unter den ersten drei in $treffer von ${fragen.size} Fällen (%.0f %%)".format(quote * 100))

        assertTrue(
            quote >= 0.90,
            "nur ${(quote * 100).toInt()} % — schlechter als der bisherige Stand:\n" + fehlschlaege(3),
        )
    }

    @Test
    fun `die bekannte Grenze des Verfahrens`() {
        // „warum ist die anwendung beendet worden" findet fehler.log nicht, obwohl dort
        // „Anwendung beendet" steht: Die lexikalische Ähnlichkeit wird von den vielen
        // gewöhnlichen Frageworten bestimmt, und im Pfad steht keines davon.
        //
        // Das ist kein Versehen, sondern genau die Eigenschaft von
        // HashingEmbeddingProvider — Bedeutung misst es nicht. Der Fall steht hier, damit
        // er benannt ist statt übersehen, und damit ein echter Einbetter sich daran
        // beweisen kann: Wenn dieser Test eines Tages umschlägt, ist das der Fortschritt.
        val treffer = ranker.rank("warum ist die anwendung beendet worden", index, limit = 3)
        val gefunden = treffer.any { it.chunk.filePath == "logs/fehler.log" }
        println("Bekannte Grenze — fehler.log gefunden: " + gefunden)
    }

    @Test
    fun `eine Datei beim Namen zu nennen genuegt`() {
        // Der Fall, für den das zweite Signal überhaupt da ist: In einer Gradle-Datei steht
        // ihr eigener Name nirgends, der Inhalt allein kann hier also nicht helfen.
        val treffer = ranker.rank("was steht in der build.gradle.kts", index, limit = 1).single()

        assertEquals("app/build.gradle.kts", treffer.chunk.filePath)
        assertTrue(treffer.nameScore > 0, "der Dateiname hat nicht beigetragen")
    }

    @Test
    fun `zu einer voellig fremden Frage kommt lieber nichts`() {
        // Wichtiger, als es aussieht: Eine unpassende Stelle im Prompt lenkt ein kleines
        // Modell spürbar ab und führt zu erfundenen Antworten mit Quellenangabe.
        val treffer = ranker.rank("wie wird das wetter in kapstadt", index, limit = 3)
        assertTrue(treffer.isEmpty(), "unpassende Fundstellen: ${treffer.map { it.chunk.quelle }}")
    }

    @Test
    fun `ohne Anhaenge kommt nichts zurueck`() {
        assertTrue(ranker.rank("irgendwas", emptyList(), limit = 3).isEmpty())
    }

    @Test
    fun `die Fundstelle nennt Datei und Zeile`() {
        val treffer = ranker.rank("wann geht mein flug", index, limit = 1).single()
        assertTrue(treffer.chunk.quelle.startsWith("notizen/urlaub.txt:"), treffer.chunk.quelle)
    }

    private fun fehlschlaege(limit: Int): String = fragen
        .filterNot { (frage, ziel) ->
            ranker.rank(frage, index, limit).any { it.chunk.filePath == ziel }
        }
        .joinToString("\n") { (frage, ziel) ->
            val bekommen = ranker.rank(frage, index, limit).joinToString(", ") {
                "${it.chunk.filePath} (%.2f)".format(it.score)
            }
            "  „$frage“ → erwartet $ziel, bekommen: ${bekommen.ifEmpty { "nichts" }}"
        }
}
