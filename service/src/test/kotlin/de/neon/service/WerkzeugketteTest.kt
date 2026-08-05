package de.neon.service

import de.neon.inference.GenerationChunk
import de.neon.inference.GenerationRequest
import de.neon.inference.InferenceEngine
import de.neon.inference.ModelFileResolver
import de.neon.inference.ModelLifecycleManager
import de.neon.router.AnalysisSource
import de.neon.router.DeviceState
import de.neon.router.InMemoryRouteOutcomeStore
import de.neon.router.ModelRegistry
import de.neon.router.ModelSpec
import de.neon.router.RouteAnalysis
import de.neon.router.Router
import de.neon.router.RouterLlm
import de.neon.router.SelectionPolicy
import de.neon.router.TaskCategory
import de.neon.speech.AsrEngine
import de.neon.speech.Transcript
import de.neon.speech.TtsEngine
import de.neon.tools.Fertig
import de.neon.tools.ParameterType
import de.neon.tools.Rueckfrage
import de.neon.tools.Tool
import de.neon.tools.ToolParameter
import de.neon.tools.ToolRegistry
import de.neon.tools.ToolResult
import de.neon.tools.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mehrere Werkzeuge hintereinander — und wo die Kette aufhört.
 *
 * **Der Anlass ist eine Frage, die sich nicht beantworten ließ.** Auf „mach mir eine
 * Zähler-App für Android und erstelle das Projekt, danach direkt kompilieren" legte Neon das
 * Projekt an und hörte auf. Der zweite Halbsatz fiel stillschweigend unter den Tisch: Es gab
 * genau eine Werkzeugrunde je Durchgang.
 *
 * Für „trag mir einen Termin ein" ist das richtig. Für eine Entwicklungsumgebung ist es zu
 * wenig — „anlegen und bauen" sind zwei Handlungen, „lies die Datei, ändere sie, prüf das
 * Ergebnis" sind drei.
 *
 * **Und deshalb prüfen diese Tests vor allem die Enden.** Eine Kette, die läuft, ist der
 * einfache Teil; eine Kette, die nicht mehr aufhört, ist auf einem Telefon der teure Fehler.
 * Vier Runden zu je einer halben Minute sind zwei Minuten, in denen niemand weiß, was
 * passiert.
 */
class WerkzeugketteTest {

    private class FakeAsr(private val text: String) : AsrEngine {
        override suspend fun transcribe(samples: ShortArray, sampleRate: Int) =
            Transcript(text, 0.9f, "de-DE")
        override fun close() = Unit
    }

    private class FakeTts : TtsEngine {
        val spoken = mutableListOf<String>()
        override var isSpeaking = false
            private set
        override suspend fun speak(text: String) { spoken += text }
        override fun stop() = Unit
        override fun close() = Unit
    }

    /**
     * Gibt der Reihe nach aus, was ihr vorgelegt wurde; danach immer das Letzte.
     *
     * @param werkzeugeSichtbar wird vor jeder Erzeugung mit den angebotenen Werkzeugnamen
     *   gerufen. Damit lässt sich prüfen, **wann** ein Werkzeug zur Wahl stand — und nicht
     *   nur, ob es am Ende aufgerufen wurde.
     */
    private class SkriptEngine(
        private val ausgaben: List<String>,
        private val werkzeugeSichtbar: (String) -> Unit = {},
    ) : InferenceEngine {
        override var loadedModelId: String? = null
            private set
        var aufrufe = 0
        val prompts = mutableListOf<String>()

        override suspend fun load(model: ModelSpec, file: File, projector: File?): Boolean {
            loadedModelId = model.id
            return true
        }

        override suspend fun unload() { loadedModelId = null }

        override fun generate(request: GenerationRequest): Flow<GenerationChunk> = flow {
            val prompt = request.messages.joinToString("\n") { "${it.role}: ${it.content}" } +
                "\nGRAMMATIK: " + request.grammar.orEmpty()
            prompts += prompt
            werkzeugeSichtbar(prompt)
            val ausgabe = ausgaben.getOrElse(aufrufe) { ausgaben.last() }
            aufrufe++
            emit(GenerationChunk.Token(ausgabe))
            emit(GenerationChunk.Done(1, 20.0))
        }
    }

    private class NotierendesWerkzeug(name: String, private val antwort: String) : Tool {
        var aufrufe = 0

        /** Damit ein Werkzeug die Welt verändern kann, so wie `app-anlegen` es tut. */
        var beiAufruf: (() -> Unit)? = null

        override val spec = ToolSpec(
            name = name,
            description = "Attrappe $name",
            parameters = listOf(ToolParameter("wert", ParameterType.STRING, "irgendetwas")),
        )

        override suspend fun execute(arguments: Map<String, String>): ToolResult {
            aufrufe++
            beiAufruf?.invoke()
            return ToolResult.Ok(antwort)
        }
    }

    private val registry = ModelRegistry.defaultForS26Ultra()

    private fun ruf(name: String, wert: String) =
        """{"werkzeug":"$name","argumente":{"wert":"$wert"}}"""

    private fun aufbau(
        text: String,
        kategorie: TaskCategory,
        engine: InferenceEngine,
        tts: FakeTts,
        werkzeuge: List<Tool>,
        protokoll: MutableList<String>,
    ): ConversationOrchestrator {
        val lifecycle = ModelLifecycleManager(
            engine = engine,
            resolver = ModelFileResolver { File("/dev/null") },
            memoryBudgetBytes = { 16L * 1024 * 1024 * 1024 },
        )
        val werkzeugkiste = ToolRegistry(werkzeuge)
        return ConversationOrchestrator(
            router = Router(
                registry,
                SelectionPolicy(registry),
                routerLlm = RouterLlm {
                    RouteAnalysis(
                        category = kategorie,
                        complexity = 2,
                        confidence = 0.9,
                        source = AnalysisSource.ROUTER_LLM,
                    )
                },
            ),
            asr = FakeAsr(text),
            tts = tts,
            lifecycle = lifecycle,
            engine = engine,
            deviceState = { DeviceState.unknown() },
            actionExecutor = { null },
            outcomeStore = InMemoryRouteOutcomeStore(),
            clock = { 0L },
            tools = { werkzeugkiste },
            codeTools = { werkzeugkiste },
            log = { protokoll += it },
        )
    }

    private val samples = ShortArray(16_000)

    @Test
    fun `anlegen und bauen laufen in einem Durchgang`() = runTest {
        // Genau der Satz, an dem es aufgefallen ist.
        val anlegen = NotierendesWerkzeug("app-anlegen", "Projekt angelegt.")
        val bauen = NotierendesWerkzeug("app-bauen", "Die App ist gebaut.")
        val tts = FakeTts()
        val protokoll = mutableListOf<String>()

        val engine = SkriptEngine(
            listOf(
                ruf("app-anlegen", "de.neon.zaehler"),
                ruf("app-bauen", "los"),
                """{"werkzeug":"fertig","argumente":{"zusammenfassung":"Die Zähler-App liegt als APK bereit."}}""",
            )
        )

        aufbau(
            "mach mir eine zaehler app fuer android und erstelle das projekt, danach direkt kompilieren",
            TaskCategory.CODE, engine, tts,
            listOf(anlegen, bauen, Fertig()),
            protokoll,
        ).handleUtterance(samples)

        assertEquals(1, anlegen.aufrufe, "das Projekt wurde nicht angelegt")
        assertEquals(1, bauen.aufrufe, "der zweite Halbsatz fiel wieder unter den Tisch")
        // Jeder Zwischenschritt wird gesprochen: Zwei Minuten Schweigen nach „mach mir eine
        // App" sind von einem Absturz nicht zu unterscheiden.
        assertEquals(
            listOf(
                "Projekt angelegt.",
                "Die App ist gebaut.",
                "Die Zähler-App liegt als APK bereit.",
            ),
            tts.spoken,
        )
    }

    @Test
    fun `in der ersten Runde gibt es kein fertig`() = runTest {
        // **Der Fehler, den das Gerät vorgeführt hat.** Auf eine Programmieraufgabe der
        // Komplexität 5 rief das Modell in Runde eins `fertig` und erklärte die Arbeit für
        // erledigt, ohne eine Zeile geschrieben zu haben. Im Protokoll stand danach nur
        // „Werkzeugkette beendet nach 1 Runde(n)" und sonst nichts.
        //
        // Ein Modell wählt, was dasteht. Also darf in Runde eins nicht dastehen, was dort
        // nicht hingehört: Man kann nicht fertig sein, bevor etwas geschehen ist.
        val anlegen = NotierendesWerkzeug("app-anlegen", "Projekt angelegt.")
        val protokoll = mutableListOf<String>()
        val engine = SkriptEngine(listOf(ruf("app-anlegen", "de.neon.zaehler")))

        aufbau(
            "mach mir eine android app", TaskCategory.CODE, engine, FakeTts(),
            listOf(anlegen, Fertig()), protokoll,
        ).handleUtterance(samples)

        // Die erste Anfrage darf `fertig` weder in der Beschreibung noch in der Grammatik
        // enthalten — geprüft am Prompt, den die Engine tatsächlich bekommen hat.
        assertTrue(
            Fertig.NAME !in engine.prompts.first(),
            "„fertig\" stand schon in der ersten Runde im Prompt:\n${engine.prompts.first()}",
        )
        // Ab der zweiten schon: Ohne das könnte die Kette nur noch an der Grenze enden.
        assertTrue(engine.prompts.size >= 2, "es gab keine zweite Runde")
        assertTrue(
            Fertig.NAME in engine.prompts[1],
            "„fertig\" fehlt auch in der zweiten Runde:\n${engine.prompts[1]}",
        )
    }

    @Test
    fun `das Ergebnis einer Runde steht der naechsten zur Verfuegung`() = runTest {
        // Ohne das begänne jede Runde bei null — das Modell wüsste nicht, dass das Projekt
        // schon angelegt ist, und legte es noch einmal an.
        val anlegen = NotierendesWerkzeug("app-anlegen", "Projekt angelegt.")
        val protokoll = mutableListOf<String>()
        val engine = SkriptEngine(
            listOf(
                ruf("app-anlegen", "de.neon.zaehler"),
                """{"werkzeug":"fertig","argumente":{"zusammenfassung":"Angelegt."}}""",
            )
        )

        aufbau(
            "leg das android projekt an", TaskCategory.CODE, engine, FakeTts(),
            listOf(anlegen, Fertig()), protokoll,
        ).handleUtterance(samples)

        assertTrue(engine.prompts.size >= 2, "es gab keine zweite Runde")
        assertTrue(
            "Ergebnis von app-anlegen" in engine.prompts[1],
            "die zweite Runde kennt das Ergebnis der ersten nicht:\n${engine.prompts[1]}",
        )
    }

    @Test
    fun `eine Kette, die sich im Kreis dreht, wird abgebrochen`() = runTest {
        // Derselbe Aufruf mit denselben Angaben, immer wieder. Bei zwölf Token je Sekunde ist
        // jede Runde im Kreis eine halbe Minute, die niemand zurückbekommt.
        val werkzeug = NotierendesWerkzeug("datei-lesen", "Inhalt.")
        val protokoll = mutableListOf<String>()
        val engine = SkriptEngine(listOf(ruf("datei-lesen", "src/Main.kt")))

        aufbau(
            "lies die datei", TaskCategory.CODE, engine, FakeTts(),
            listOf(werkzeug, Fertig()), protokoll,
        ).handleUtterance(samples)

        assertEquals(1, werkzeug.aufrufe, "derselbe Aufruf wurde mehrfach ausgeführt")
        assertTrue(
            protokoll.any { "im Kreis" in it },
            "der Abbruch steht nicht im Protokoll: $protokoll",
        )
    }

    @Test
    fun `die Rundengrenze haelt eine endlose Kette an`() = runTest {
        // Verschiedene Argumente, also greift die Schleifenerkennung nicht. Dann muss die
        // harte Grenze zuschlagen — sonst liefe Neon, bis der Akku leer ist.
        val werkzeug = NotierendesWerkzeug("datei-schreiben", "geschrieben.")
        val protokoll = mutableListOf<String>()
        val engine = SkriptEngine(
            (1..20).map { ruf("datei-schreiben", "datei$it.txt") }
        )

        aufbau(
            "schreib was in kotlin", TaskCategory.CODE, engine, FakeTts(),
            listOf(werkzeug, Fertig()), protokoll,
        ).handleUtterance(samples)

        assertEquals(
            de.neon.tools.WorkspaceToolset.RUNDEN,
            werkzeug.aufrufe,
            "die Rundengrenze hat nicht gegriffen",
        )
        assertTrue(
            protokoll.any { "Rundengrenze" in it },
            "das Ende an der Grenze steht nicht im Protokoll: $protokoll",
        )
    }

    @Test
    fun `ein Werkzeug, das erst durch die Kette moeglich wird, steht danach zur Wahl`() = runTest {
        // **Der Fehler, den das Gerät vorgeführt hat.** Ein Werkzeug wird nur angeboten, wenn
        // es gerade gelingen kann — `app-bauen` also erst, wenn ein Manifest im Projekt liegt.
        // Geprüft wurde das aber einmal, vor der ersten Runde.
        //
        // Im Protokoll: Runde 1 scheiterte am Paketnamen, Runde 2 legte das Projekt an, und
        // Runde 3 rief `fertig` — ohne zu bauen. `app-bauen` stand nicht zur Wahl, weil beim
        // Zusammenstellen noch kein Manifest da war. Die Kette hatte die Voraussetzung für
        // ihren zweiten Schritt gerade selbst geschaffen und durfte ihn trotzdem nicht tun.
        val anlegen = NotierendesWerkzeug("app-anlegen", "Projekt angelegt.")
        val bauen = NotierendesWerkzeug("app-bauen", "Die App ist gebaut.")
        val protokoll = mutableListOf<String>()

        // Die Welt: `app-bauen` gibt es erst, nachdem `app-anlegen` gelaufen ist.
        var projektDa = false
        val werkzeugeJetzt = {
            ToolRegistry(
                buildList {
                    add(anlegen)
                    if (projektDa) add(bauen)
                    add(Fertig())
                }
            )
        }

        val engine = SkriptEngine(
            listOf(
                ruf("app-anlegen", "de.neon.zaehler"),
                ruf("app-bauen", "los"),
                """{"werkzeug":"fertig","argumente":{"zusammenfassung":"fertig"}}""",
            )
        )
        // Das Anlegen verändert die Welt — genau wie auf dem Gerät.
        anlegen.beiAufruf = { projektDa = true }

        val lifecycle = ModelLifecycleManager(
            engine = engine,
            resolver = ModelFileResolver { File("/dev/null") },
            memoryBudgetBytes = { 16L * 1024 * 1024 * 1024 },
        )
        ConversationOrchestrator(
            router = Router(
                registry,
                SelectionPolicy(registry),
                routerLlm = RouterLlm {
                    RouteAnalysis(
                        category = TaskCategory.CODE,
                        complexity = 2,
                        confidence = 0.9,
                        source = AnalysisSource.ROUTER_LLM,
                    )
                },
            ),
            asr = FakeAsr("leg das android projekt an und bau es"),
            tts = FakeTts(),
            lifecycle = lifecycle,
            engine = engine,
            deviceState = { DeviceState.unknown() },
            actionExecutor = { null },
            outcomeStore = InMemoryRouteOutcomeStore(),
            clock = { 0L },
            codeTools = werkzeugeJetzt,
            log = { protokoll += it },
        ).handleUtterance(samples)

        assertEquals(1, anlegen.aufrufe, "das Projekt wurde nicht angelegt")
        assertEquals(
            1,
            bauen.aufrufe,
            "app-bauen kam nie zum Zug — die Werkzeugliste ist wieder eingefroren:\n" +
                protokoll.joinToString("\n"),
        )
    }

    @Test
    fun `eine Rueckfrage beendet die Kette sofort`() = runTest {
        // Weiterzuarbeiten hieße, die eigene Frage zu übergehen.
        val anlegen = NotierendesWerkzeug("app-anlegen", "Projekt angelegt.")
        val tts = FakeTts()
        val protokoll = mutableListOf<String>()
        val engine = SkriptEngine(
            listOf(
                """{"werkzeug":"rueckfrage","argumente":{"frage":"Wie soll die App heißen?"}}""",
                ruf("app-anlegen", "de.neon.irgendwas"),
            )
        )

        aufbau(
            "mach mir eine android app", TaskCategory.CODE, engine, tts,
            listOf(anlegen, Rueckfrage(), Fertig()), protokoll,
        ).handleUtterance(samples)

        assertEquals(0, anlegen.aufrufe, "nach der Rückfrage wurde weitergearbeitet")
        assertEquals(listOf("Wie soll die App heißen?"), tts.spoken)
    }

    @Test
    fun `eine Geraetehandlung bekommt weiterhin genau eine Runde`() = runTest {
        // Dort werden am Ende echte Geräte geschaltet. Ein Modell, das aus eigenem Antrieb
        // nachlegt, ist dabei etwas anderes als eines, das eine zweite Datei schreibt.
        val wlan = NotierendesWerkzeug("wlan", "WLAN ist an.")
        val protokoll = mutableListOf<String>()
        val engine = SkriptEngine(listOf(ruf("wlan", "an"), ruf("wlan", "aus")))

        aufbau(
            "schalte das wlan an", TaskCategory.GERAETE_AKTION, engine, FakeTts(),
            listOf(wlan), protokoll,
        ).handleUtterance(samples)

        assertEquals(1, wlan.aufrufe)
        assertEquals(1, engine.aufrufe, "die Gerätewerkzeuge haben eine zweite Runde bekommen")
    }

    // ---- Die Rueckfrage vor dem Bauen ---------------------------------------------------

    /**
     * **Der Fall, an dem alles Bisherige gescheitert ist.**
     *
     * Auf „programmiere eine QR-Generierungs-App" hat Neon ungefragt ein Python-Skript
     * geschrieben, ohne Projekt — obwohl `rueckfrage` an erster Stelle in der Werkzeugliste
     * stand, die Gabelung in seiner Beschreibung genannt war und dieselbe Regel im
     * Systemprompt stand. Ein 1.7-B-Modell trifft diese Wahl nicht.
     *
     * Jetzt entscheidet eine Regel, und zwar **vor** dem Routen: kein Serverstart, keine
     * Erzeugung, kein Token. Der Test prueft beides — dass gefragt wird, und dass dabei
     * nichts erzeugt wurde.
     */
    @Test
    fun `ein Bauauftrag ohne Sprache wird zuerst zurueckgefragt`() = runTest {
        val anlegen = NotierendesWerkzeug("app-anlegen", "Projekt angelegt.")
        val tts = FakeTts()
        val engine = SkriptEngine(listOf(ruf("app-anlegen", "de.neon.qr")))

        val bericht = aufbau("egal", TaskCategory.CODE, engine, tts, listOf(anlegen), mutableListOf())
            .handleText("programmiere eine qr generierungsapp")!!

        assertEquals(Zielklaerung.FRAGE_SPRACHE, bericht.answer)
        assertEquals(0, anlegen.aufrufe, "vor der Antwort darf nichts angelegt werden")
        assertTrue(bericht.usedNoModel, "die Frage ist ein fester Satz, keine Erzeugung")
        assertTrue(engine.prompts.isEmpty(), "es wurde nichts erzeugt: " + engine.prompts)
    }

    /**
     * **Und die Antwort findet zurueck zum Auftrag.**
     *
     * Ohne das waere die Rueckfrage schlimmer als nutzlos: Auf „Android" allein folgt eine
     * neue Einordnung, und „Android" ist fuer sich genommen keine Programmieraufgabe — die
     * Kette liefe gar nicht erst an, und der urspruengliche Auftrag waere verloren.
     */
    @Test
    fun `nach der Antwort laeuft die Kette mit dem urspruenglichen Auftrag`() = runTest {
        val anlegen = NotierendesWerkzeug("app-anlegen", "Projekt angelegt.")
        val protokoll = mutableListOf<String>()
        val engine = SkriptEngine(
            listOf(
                ruf("app-anlegen", "de.neon.qr"),
                """{"werkzeug":"fertig","argumente":{"zusammenfassung":"Liegt bereit."}}""",
            )
        )

        val neon = aufbau(
            "egal", TaskCategory.CODE, engine, FakeTts(),
            listOf(anlegen, Fertig()), protokoll,
        )
        neon.handleText("programmiere eine qr generierungsapp")
        neon.handleText("Android")

        assertEquals(1, anlegen.aufrufe, "nach der Antwort muss die Kette laufen")
        // Der zusammengesetzte Auftrag steht im Prompt, nicht bloss das Wort „Android".
        assertTrue(
            engine.prompts.any { "qr generierungsapp" in it },
            "der urspruengliche Auftrag ist verloren gegangen: " + engine.prompts,
        )
        assertTrue(
            protokoll.any { "wieder zusammengesetzt" in it },
            protokoll.toString(),
        )
    }

    /** Zweimal fragen waere eine Schleife. Nach der Antwort wird gearbeitet. */
    @Test
    fun `nach der Antwort wird nicht noch einmal gefragt`() = runTest {
        val anlegen = NotierendesWerkzeug("app-anlegen", "Projekt angelegt.")
        val engine = SkriptEngine(listOf(ruf("app-anlegen", "de.neon.qr")))

        val neon = aufbau(
            "egal", TaskCategory.CODE, engine, FakeTts(),
            listOf(anlegen), mutableListOf(),
        )
        neon.handleText("mach mir eine zaehler app")
        val zweiter = neon.handleText("python")!!

        assertTrue(zweiter.answer != Zielklaerung.FRAGE_SPRACHE, zweiter.answer)
    }

    /**
     * **Wer die Frage uebergeht, wird noch einmal gefragt.**
     *
     * Auf dem Geraet kam auf „Android oder Python?" der urspruengliche Auftrag ein zweites
     * Mal zurueck. Neon hat das kommentarlos als Antwort genommen und ohne Sprache
     * weitergearbeitet — heraus kam eine Prosa-Antwort von 220 Token und kein Projekt. Eine
     * Frage, die man auch dadurch beantworten kann, dass man sie uebergeht, ist keine.
     */
    @Test
    fun `eine Nicht-Antwort fuehrt zu einer zweiten Frage`() = runTest {
        val anlegen = NotierendesWerkzeug("app-anlegen", "Projekt angelegt.")
        val engine = SkriptEngine(listOf(ruf("app-anlegen", "de.neon.qr")))

        val neon = aufbau(
            "egal", TaskCategory.CODE, engine, FakeTts(), listOf(anlegen), mutableListOf(),
        )
        neon.handleText("programmiere eine qr generierungsapp")
        val zweite = neon.handleText("programmiere eine qr generierungsapp")!!

        assertEquals(Zielklaerung.FRAGE_NOCHMAL, zweite.answer)
        assertEquals(0, anlegen.aufrufe, "ohne Sprache darf nichts angelegt werden")
    }

    /** Aber nicht endlos: Nach zwei Fragen wird gearbeitet. */
    @Test
    fun `nach zwei Fragen versucht Neon es trotzdem`() = runTest {
        val anlegen = NotierendesWerkzeug("app-anlegen", "Projekt angelegt.")
        val engine = SkriptEngine(listOf(ruf("app-anlegen", "de.neon.qr")))

        val neon = aufbau(
            "egal", TaskCategory.CODE, engine, FakeTts(), listOf(anlegen), mutableListOf(),
        )
        neon.handleText("programmiere eine qr generierungsapp")
        neon.handleText("weiss nicht")
        val dritte = neon.handleText("weiss nicht")!!

        assertTrue(dritte.answer != Zielklaerung.FRAGE_SPRACHE, dritte.answer)
        assertTrue(dritte.answer != Zielklaerung.FRAGE_NOCHMAL, dritte.answer)
        assertEquals(1, anlegen.aufrufe, "irgendwann muss es weitergehen")
    }

    /**
     * **Nach der Antwort steht die Kategorie fest.**
     *
     * „Android" allein ist keine Programmieraufgabe. Wird die Antwort fuer sich eingeordnet,
     * laeuft die Werkzeugkette gar nicht erst an — genau das steht im Geraeteprotokoll: eine
     * Prosa-Antwort statt eines Projekts.
     */
    @Test
    fun `die Antwort auf die Rueckfrage wird als Programmierauftrag eingeordnet`() = runTest {
        val anlegen = NotierendesWerkzeug("app-anlegen", "Projekt angelegt.")
        val engine = SkriptEngine(listOf(ruf("app-anlegen", "de.neon.qr")))

        // Der Router ordnet hier absichtlich als WISSENSFRAGE ein — so, wie er „Android"
        // fuer sich genommen einordnen wuerde. Die bekannte Kategorie muss das schlagen.
        val neon = aufbau(
            "egal", TaskCategory.WISSENSFRAGE, engine, FakeTts(),
            listOf(anlegen), mutableListOf(),
        )
        neon.handleText("programmiere eine qr generierungsapp")
        neon.handleText("Android")

        assertEquals(1, anlegen.aufrufe, "die Kette lief nicht an")
    }
}
