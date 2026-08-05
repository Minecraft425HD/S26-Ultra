package de.neon.app

import android.content.Context
import de.neon.audio.CascadeStats
import de.neon.audio.EnergyGate
import de.neon.audio.ListeningEvent
import de.neon.audio.MicrophoneAudioSource
import de.neon.audio.OpenWakeWordDetector
import de.neon.audio.SileroVadOnnx
import de.neon.audio.SpeechSegmenter
import de.neon.audio.VoiceActivityDetector
import de.neon.audio.WakeWordDetector
import de.neon.audio.WakeWordPipeline
import de.neon.inference.ImageAttachment
import de.neon.inference.LlamaServerEngine
import de.neon.inference.LocalRouterLlm
import de.neon.inference.ModelLifecycleManager
import de.neon.inference.ModelStore
import de.neon.inference.ProcessServerSupervisor
import de.neon.attachments.AttachmentIngest
import de.neon.attachments.BytesSource
import de.neon.attachments.AttachmentKind
import de.neon.attachments.IngestResult
import de.neon.memory.AttachmentRepository
import de.neon.memory.ChatHistoryRepository
import de.neon.memory.MemoryRepository
import de.neon.memory.NeonDatabase
import de.neon.memory.RoutingExampleRepository
import de.neon.platform.DeviceStateProvider
import de.neon.platform.NeonLog
import de.neon.router.Capability
import de.neon.router.HashingEmbeddingProvider
import de.neon.router.InMemoryRouteOutcomeStore
import de.neon.router.KnnClassifier
import de.neon.router.ModelRegistry
import de.neon.router.ModelRole
import de.neon.router.Router
import de.neon.router.RouterStats
import de.neon.router.SeedExamples
import de.neon.router.SelectionPolicy
import de.neon.service.AttachmentExcerpt
import de.neon.service.ConversationOrchestrator
import de.neon.service.NeonForegroundService
import de.neon.service.TurnLearner
import de.neon.speech.AndroidOnDeviceAsr
import de.neon.speech.AndroidTts
import de.neon.tools.CalendarEventTool
import de.neon.tools.WorkspaceToolset
import de.neon.workspace.AndroidBuild
import de.neon.workspace.BuildTools
import de.neon.workspace.DalvikRunner
import de.neon.workspace.ProcessCommandRunner
import de.neon.workspace.Projektbereich
import de.neon.workspace.PythonRuntime
import de.neon.workspace.Workspace
import de.neon.tools.ComposeMessageTool
import de.neon.tools.DeviceActionExecutor
import de.neon.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Baut die Objekte zusammen, aus denen Neon besteht.
 *
 * Bewusst von Hand statt mit einem Injektions-Rahmenwerk: Es gibt genau einen Graphen, er
 * wird genau einmal gebaut, und wer verstehen will, wie Neon funktioniert, kann ihn hier in
 * einem Stück lesen.
 */
class NeonContainer(context: Context) {

    private val appContext = context.applicationContext

    private val einstellungen = appContext.getSharedPreferences("neon", Context.MODE_PRIVATE)

    val registry: ModelRegistry = ModelRegistry.defaultForS26Ultra()

    val modelStore = ModelStore(File(appContext.filesDir, "models")).also { store ->
        // **Sagen, welche Datei aussortiert wurde und warum.** Ein Modell, das kommentarlos
        // aus der Liste verschwindet, ist schlimmer als eines, das falsche Antworten gibt —
        // beim zweiten weiß man wenigstens, dass etwas nicht stimmt.
        registry.models.forEach { modell ->
            val datei = store.fileFor(modell) ?: return@forEach
            if (store.istBruchstueck(modell, datei.length())) {
                NeonLog.w(
                    TAG,
                    "${modell.id} wird nicht benutzt: die Datei hat " +
                        "${datei.length() / (1024 * 1024)} MB, der Eintrag nennt " +
                        "${modell.sizeBytes / (1024 * 1024)} MB. Das ist kein " +
                        "quantisiertes Modell mehr, sondern ein abgebrochener Download. " +
                        "Bitte in den Einstellungen löschen und neu importieren.",
                )
            }
        }
    }

    /**
     * Startet und überwacht den mitgelieferten `llama-server`.
     *
     * Er läuft als eigener Prozess: Ein Modell, das den Speicher sprengt, reißt damit nicht
     * die Hörschleife mit — Neon kann weiter zuhören und melden, dass es nicht geklappt hat.
     */
    private val serverSupervisor = ProcessServerSupervisor(
        context = appContext,
        contextSize = einstellungen.getInt(SCHLUESSEL_KONTEXT, ProcessServerSupervisor.DEFAULT_CONTEXT_SIZE),
    )

    /**
     * Die Größe des Kontextfensters, dauerhaft gemerkt.
     *
     * Anders als der Weckwort-Schwellwert überdauert dieser Wert einen Neustart der App:
     * Wer ihn einmal an sein Gerät angepasst hat, will das nicht bei jedem Start wiederholen
     * — und ein falscher Wert fällt erst auf, wenn der Speicher knapp wird.
     */
    var contextSize: Int
        get() = serverSupervisor.contextSize
        set(value) {
            serverSupervisor.contextSize = value
            einstellungen.edit().putInt(SCHLUESSEL_KONTEXT, serverSupervisor.contextSize).apply()
        }

    val inferenceAvailable: Boolean get() = serverSupervisor.isAvailable

    private val answerEngine = LlamaServerEngine(serverSupervisor)

    /**
     * Das Speicherbudget kommt aus der Messung, nicht aus einer Konstante.
     *
     * Bei jedem Ladeversuch neu gefragt: Was frei ist, hängt davon ab, was sonst läuft.
     * Vorher standen hier fünf Gigabyte mit der Begründung „von 16 GB" — auf einem Gerät mit
     * 5,3 GB insgesamt.
     */
    val lifecycle = ModelLifecycleManager(
        engine = answerEngine,
        resolver = modelStore,
        // Dieselbe Rechnung wie im DeviceStateProvider, und zwar buchstäblich dieselbe
        // Funktion. Zwei Stellen mit derselben Absicht und eigener Rechnung sind eine
        // Stelle zu viel: Die erste Fassung dieser Zahl war an beiden Orten falsch, die
        // zweite an beiden Orten anders falsch.
        memoryBudgetBytes = {
            de.neon.platform.DeviceStateProvider.weightBudget(de.neon.platform.DeviceMemory.read())
        },
    )

    private val deviceStateProvider = DeviceStateProvider(
        context = appContext,
        warmModelIds = { lifecycle.warmModelIds() },
        // Damit der Router nur unter dem wählt, was tatsächlich importiert wurde. Ohne das
        // gewinnt bei einer schweren Frage der Denker, dessen Datei niemand geladen hat,
        // und Neon sagt „nicht heruntergeladen", statt mit dem Alltagsmodell zu antworten.
        //
        // Bei jedem Durchgang neu gelesen und nicht gemerkt: Ein Import soll sofort wirken,
        // nicht erst nach einem Neustart.
        availableModelIds = {
            registry.models.filter { modelStore.isAvailable(it) }.map { it.id }.toSet()
        },
        // Und wie groß sie wirklich sind. Der Eintrag `qwen3-coder-7b` nennt 4,5 GB; die
        // Datei auf dem Gerät hatte 378 MB. Mit der Behauptung gerechnet hätte Neon dieses
        // Modell bei knappem Speicher abgelehnt, obwohl es zwölfmal kleiner ist als gedacht.
        gemesseneGroessen = {
            registry.models.mapNotNull { modell ->
                modelStore.gemesseneGroesse(modell)?.let { modell.id to it }
            }.toMap()
        },
    )

    /**
     * Stufe 1 arbeitet ohne Modelldatei.
     *
     * Das Verfahren misst lexikalische Ähnlichkeit, keine Bedeutung — dafür funktioniert es
     * vom ersten Start an, ohne Download und ohne Tokenizer. Auf ungesehenen deutschen
     * Äußerungen trifft es rund neun von zehn Kategorien; die Fehlgriffe landen in Stufe 2.
     */
    private val embeddings = HashingEmbeddingProvider()

    private val knn = KnnClassifier(
        examples = SeedExamples.materialize(embeddings),
        k = 5,
        minSimilarity = 0.30,
        minMargin = 0.10,
    )

    /**
     * Stufe 2 läuft auf demselben Modell, das gerade antwortet.
     *
     * Ein eigenes Router-Modell wäre schneller, bräuchte aber einen zweiten Serverprozess —
     * llama-server bedient je Lauf genau ein Modell. Solange nur das Alltagsmodell geladen
     * ist, wäre das ein zweiter Speicherblock für eine Einordnung, die auch so wenige
     * hundert Millisekunden dauert.
     */
    private val routerLlm = LocalRouterLlm(
        engine = answerEngine,
        log = { meldung -> NeonLog.i("NeonRouter", meldung) },
    )

    val router = Router(
        registry = registry,
        policy = SelectionPolicy(registry),
        knn = knn,
        embeddings = embeddings,
        routerLlm = routerLlm,
    )

    val outcomeStore = InMemoryRouteOutcomeStore()

    /**
     * Träge erzeugt — Room öffnet die Datei erst beim ersten Zugriff, und ein Fehler dabei
     * darf nicht den Start der ganzen App verhindern.
     */
    private val database by lazy { NeonDatabase.create(appContext) }

    private val routingExamples by lazy {
        RoutingExampleRepository(
            dao = database.routingExamples(),
            expectedDimensions = HashingEmbeddingProvider.DEFAULT_DIMENSIONS,
        )
    }

    private val memory by lazy { MemoryRepository(database.memoryFacts(), embeddings) }

    private val chatHistory by lazy { ChatHistoryRepository(database.chatEntries()) }

    private val attachments by lazy { AttachmentRepository(database.attachmentChunks(), embeddings) }

    /** Der Zustand der Anhänge für die Oberfläche. */
    val attachmentState = MutableStateFlow(AttachmentState())

    /**
     * Bilder, die mit der **nächsten** Frage mitgehen.
     *
     * Nur mit der nächsten, nicht mit allen folgenden: Ein Bild kostet je nach Größe
     * Tausende Token. Es stillschweigend bei jeder weiteren Frage mitzuschicken würde den
     * Kontext füllen und jede Antwort verlangsamen, ohne dass jemand den Grund sähe.
     *
     * Gilt nur, wenn ein Bildmodell mit Projektor vorliegt. Sonst bleibt es beim Text aus
     * der Bilderkennung, der ohnehin schon im Index steht.
     */
    private val wartendeBilder = mutableListOf<ImageAttachment>()

    val visionAvailable: Boolean
        get() = registry.models.any {
            it.supports(Capability.VISION) && modelStore.isAvailable(it)
        }

    /** Nimmt die wartenden Bilder heraus und leert die Liste. */
    fun takePendingImages(): List<ImageAttachment> = synchronized(wartendeBilder) {
        val kopie = wartendeBilder.toList()
        wartendeBilder.clear()
        attachmentState.value = attachmentState.value.copy(pendingImages = 0)
        kopie
    }

    /**
     * Lebt so lange wie die Anwendung.
     *
     * Bewusst kein Scope des Dienstes: Die gelernten Beispiele sollen auch dann noch
     * geschrieben werden, wenn Neon gerade beendet wurde.
     *
     * Aus demselben Grund läuft hier auch eine getippte Frage. Sie lief einmal in
     * `lifecycleScope` der Activity — wer die App verließ oder das Gerät drehte, während
     * Neon rechnete, verlor die Antwort. Im Protokoll stand dann nur
     * `JobCancellationException: Job was cancelled`. Bei einer Minute Ladezeit ist das
     * nicht der seltene Fall, sondern der wahrscheinliche.
     */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val learner = TurnLearner(embeddings) { example ->
        router.learn(example)
        scope.launch { routingExamples.save(example) }
    }

    init {
        // Gelernte Beispiele nachladen. Ohne das begänne der Router nach jedem Start wieder
        // bei der mitgelieferten Startmenge und die Lernschleife bliebe folgenlos.
        //
        // Im Hintergrund und gekapselt: Eine unlesbare Datenbank darf höchstens das
        // Gelernte kosten, nicht den Start.
        scope.launch {
            runCatching { routingExamples.loadAll().forEach { router.learn(it) } }
                .onFailure { NeonLog.e(TAG, "Gelernte Beispiele nicht ladbar", it) }
        }
    }

    /**
     * Ebenfalls träge. `AndroidTts` erzeugt im Konstruktor eine Verbindung zur
     * Sprachausgabe des Systems, die Erkennung eine zum Erkennerdienst — beides braucht
     * niemand, bevor zum ersten Mal gesprochen wird, und beides kann auf einem fremden
     * Gerät fehlschlagen.
     */
    private val asr by lazy { AndroidOnDeviceAsr(appContext) }
    private val tts by lazy { AndroidTts(appContext) }
    private val actionExecutor = DeviceActionExecutor(appContext)

    /** Träge: Der Erkenner lädt sein Modell beim ersten Gebrauch, nicht beim Start. */
    private val imageText by lazy { ImageText(appContext) }

    /**
     * Werkzeuge für Handlungen, die die Regelstufe nicht abdeckt.
     *
     * Termine und Nachrichten enthalten freien Text und relative Zeitangaben — daran
     * scheitert eine feste Grammatik, und genau dafür lohnt sich ein Modellaufruf.
     */
    private val tools = ToolRegistry(
        listOf(
            // **Auch hier zuerst die Rückfrage.** Bei den Gerätewerkzeugen wiegt sie sogar
            // schwerer als beim Programmieren: Ein falsch angelegter Termin steht danach im
            // Kalender, und eine an die falsche Person geschickte Nachricht ist weg. Beides
            // lässt sich nicht mit einem Papierkorb auffangen.
            de.neon.tools.Rueckfrage(),
            CalendarEventTool(appContext),
            ComposeMessageTool(appContext),
        )
    )

    /**
     * Der Behälter, in dem alle Projekte liegen.
     *
     * Unter `filesDir` und nicht im gemeinsamen Speicher: Was hier liegt, gehört der App,
     * verschwindet beim Deinstallieren und ist von anderen Apps nicht lesbar. Die Grenze um
     * das einzelne Projekt zieht [Workspace]; sie ist dort ohne Android geprüft, weil die
     * Pfade aus einem Sprachmodell kommen und daneben das Gedächtnis, der Anhang-Index und
     * die Modelldateien liegen.
     *
     * **Hier war `files/projekt/` einmal das Projekt selbst.** Ein flacher Ordner mit einem
     * Manifest darin — also genau eine App, kein Löschen, kein Verschieben. Wer ein zweites
     * `app-anlegen` auslöste, überschrieb das Manifest des ersten und ließ dessen Quelltext
     * verwaist zurück.
     *
     * Jetzt ist es ein Behälter, und jeder Unterordner darin ein Projekt. `Workspace` bleibt
     * unverändert die Grenze **eines** Projekts und zeigt nur eine Ebene tiefer — deshalb
     * arbeiten Ankeränderungen, Pfadprüfung und die fünfstufige Bau-Kette weiter, ohne dass
     * eine Zeile davon angefasst werden musste.
     */
    val projektbereich = Projektbereich(
        wurzel = File(appContext.filesDir, "projekt"),
        // Bei jedem Zugriff neu gefragt statt beim Bauen eingefroren: Die Freigabe wird in
        // den Systemeinstellungen erteilt und kann dort jederzeit zurueckgenommen werden.
        // Eine gemerkte Liste hiesse, dass eine erteilte Freigabe erst nach einem Neustart
        // wirkt und eine entzogene gar nicht.
        weitereWurzeln = { Speicherfreigabe.wurzeln() },
        // Das aktive Projekt überdauert einen Neustart. Ohne das stünde man nach jedem Start
        // in einem anderen Projekt als dem, in dem man gearbeitet hat.
        gemerkterName = { einstellungen.getString(SCHLUESSEL_PROJEKT, null) },
        merkeName = { name ->
            einstellungen.edit().putString(SCHLUESSEL_PROJEKT, name).apply()
        },
        log = { meldung -> NeonLog.i(TAG_IDE, meldung) },
    ).also { bereich ->
        // **Vorhandene Arbeit darf nicht aus der Ansicht verschwinden.** Bis zu dieser
        // Fassung lagen Manifest und Quelltext direkt im Behälter; jetzt zählt nur noch,
        // was in Unterordnern liegt. Wer die neue Fassung installiert, sähe ohne diesen
        // Umzug ein leeres Projektverzeichnis und müsste glauben, seine Dateien seien weg.
        //
        // Vor allem anderen und nicht im Hintergrund: Der Getter unten legt sonst ein
        // Standardprojekt neben die losen Dateien, und danach ist der Umzug mehrdeutig.
        runCatching { bereich.holeAltesProjektHerein() }
            .onFailure { NeonLog.e(TAG_IDE, "Umzug der alten Ablage scheiterte", it) }
    }

    /**
     * Der Arbeitsbereich des **aktiven** Projekts.
     *
     * Ein Getter und kein Wert: Das aktive Projekt wechselt im Gespräch, und ein einmal
     * gemerkter Arbeitsbereich zeigte danach auf den falschen Ordner. Dasselbe Muster wie bei
     * den Werkzeuglisten, und aus demselben Grund.
     */
    val workspace: Workspace get() = projektbereich.aktiverArbeitsbereich()

    /**
     * Die Python-Umgebung.
     *
     * `null`, solange die Standardbibliothek nicht ausgepackt ist — und dann fehlt auch das
     * Python-Werkzeug in der Grammatik. Ein Werkzeug anzubieten, das jedes Mal scheitert,
     * wäre schlechter als keines: Ein Modell, dem ein Werkzeug angeboten wird, benutzt es.
     */
    @Volatile
    var python: PythonRuntime? = null
        private set

    /**
     * Packt die Standardbibliothek aus, falls nötig.
     *
     * Beim ersten Start dauert das ein paar Sekunden — dreitausend Dateien. Deshalb nicht im
     * Konstruktor: Der Objektgraph wird beim Anwendungsstart gebaut, und eine App, die
     * deswegen fünf Sekunden schwarz bleibt, sieht aus wie eine abgestürzte.
     *
     * Als Fassungskennung dient der Baustand. Er ändert sich genau dann, wenn sich das
     * mitgelieferte ZIP geändert haben kann — eine eigene Nummer wäre eine zweite Wahrheit,
     * die irgendwann nicht mehr stimmt.
     */
    fun richtePythonEin(bauStand: String) {
        val laufzeit = PythonRuntime(
            nativeDir = File(appContext.applicationInfo.nativeLibraryDir),
            dataDir = appContext.filesDir,
            // Damit ein Python-Lauf eine Spur hinterlässt. Vorher war von einem Skript, das
            // still scheiterte, im Protokoll nichts zu sehen — nur die Sprechblase sagte
            // etwas, und die ist nach dem nächsten Satz weg.
            log = { meldung -> NeonLog.i(TAG_IDE, meldung) },
        )
        runCatching {
            val entpackt = laufzeit.einrichten(
                zip = { appContext.assets.open(PythonRuntime.STDLIB_ASSET) },
                fassung = bauStand,
            )
            if (entpackt) NeonLog.i(TAG, "Python-Standardbibliothek ausgepackt")
        }.onFailure {
            NeonLog.e(TAG, "Python-Umgebung ließ sich nicht einrichten", it)
            return
        }

        if (!laufzeit.bereit) {
            NeonLog.e(TAG, "Python-Starter fehlt unter ${laufzeit.launcher.absolutePath}")
            return
        }
        python = laufzeit
        NeonLog.i(TAG, "Python bereit, Standardbibliothek unter ${laufzeit.home}")
    }

    /**
     * Die Werkzeuge fürs Programmieren.
     *
     * Getrennt von [tools], weil die erzwungene Grammatik **jedes** angebotene Werkzeug
     * enthält: `datei-schreiben` neben `termin` zu stellen heißt, dass ein 4-B-Modell bei
     * einer Wissensfrage gelegentlich eine Datei anlegt.
     */
    private val codeTools get() = ToolRegistry(
        WorkspaceToolset.alle(projektbereich, workspace, python, build),
        kopfzeile = projektKopfzeile(),
    )

    /**
     * Der Satz, der dem Modell sagt, wo es gerade steht.
     *
     * **Ohne ihn schreibt Neon in ein Projekt, das es nicht benennen kann.** Alle Pfade sind
     * relativ zum aktiven Projekt; welches das ist, stand nirgends. Damit gab es auch keinen
     * Anlass, `projekt-wechseln` zu benutzen — ein Werkzeug, dessen Voraussetzung im Prompt
     * fehlt, wird nicht gewählt.
     *
     * Die anderen Projekte werden nur genannt, wenn es welche gibt. Eine Aufzählung mit einem
     * Eintrag ist kein Hinweis, sondern zwölf Token Wartezeit.
     */
    private fun projektKopfzeile(): String {
        val alle = projektbereich.projekte()
        val aktiv = projektbereich.aktiv()?.name ?: return ""

        return buildString {
            append("Aktives Projekt: $aktiv. Alle Dateipfade beziehen sich darauf.")
            val andere = alle.map { it.name }.filter { it != aktiv }
            if (andere.isNotEmpty()) {
                append(" Daneben gibt es: ").append(andere.joinToString(", ")).append(".")
            }
        }
    }

    /**
     * Die zuletzt gebaute APK, falls es eine gibt.
     *
     * Im Bauverzeichnis nachgesehen und nicht gemerkt: Ein gemerkter Pfad zeigt nach einem
     * Neustart der App auf eine Datei, die es vielleicht nicht mehr gibt — und ein Knopf,
     * der ins Leere führt, ist schlechter als keiner.
     */
    fun letzteApk(): File? =
        File(workspace.wurzel, de.neon.workspace.AndroidBuild.BAU_VERZEICHNIS)
            .listFiles { f -> f.extension == "apk" && !f.name.startsWith("unsigniert") }
            ?.maxByOrNull { it.lastModified() }

    /**
     * Ob das aktive Projekt gebaut werden kann: Bau-Kette ausgepackt und ein Manifest da.
     *
     * Danach richtet sich, ob der Knopf im Editor überhaupt erscheint. Ein Knopf, der jedes
     * Mal „es gibt kein Android-Projekt" antwortet, ist schlechter als keiner.
     */
    fun kannBauen(): Boolean = build != null && projektbereich.aktiv()?.istAndroidProjekt == true

    /**
     * Baut das aktive Projekt — ohne Umweg über das Sprachmodell.
     *
     * **Warum es diesen Weg neben `app-bauen` gibt.** Über das Gespräch kostet ein Bauvorgang
     * erst eine Einordnung, dann eine Erzeugung von rund vierzig Token, dann den Bau selbst —
     * bei zwölf Token je Sekunde eine halbe Minute Vorlauf für eine Handlung, die keinerlei
     * Entscheidung braucht. Wer im Projektordner steht und bauen will, weiß schon, was er
     * will; das Modell dazwischenzuschalten fügt nur Wartezeit und die Möglichkeit hinzu,
     * dass es etwas anderes tut.
     *
     * Läuft auf [Dispatchers.IO]: Der Kotlin-Compiler braucht auf diesem Gerät eine Minute,
     * und der gehört nicht auf den Hauptfaden.
     */
    suspend fun baueAktivesProjekt(): de.neon.workspace.BuildResult =
        withContext(Dispatchers.IO) {
            val kette = build ?: return@withContext de.neon.workspace.BuildResult(
                gelungen = false,
                apk = null,
                bericht = "Die Bau-Werkzeuge sind noch nicht ausgepackt. Das läuft beim " +
                    "ersten Start und dauert einen Moment.",
                schritt = "Vorbereitung",
            )
            val projekt = projektbereich.aktiv()
            val paket = projekt?.paketname() ?: return@withContext de.neon.workspace.BuildResult(
                gelungen = false,
                apk = null,
                bericht = "In diesem Projekt liegt kein AndroidManifest.xml. Bitte Neon im " +
                    "Chat, eine App anzulegen.",
                schritt = "Vorbereitung",
            )

            NeonLog.i(TAG_IDE, "Bau von Hand angestoßen: ${projekt.name} ($paket)")
            kette.baue(projektbereich.arbeitsbereich(projekt), paket)
        }

    /**
     * Die Bau-Kette für Android-Apps.
     *
     * `null`, solange die Werkzeuge nicht ausgepackt sind — dann fehlen `app-anlegen` und
     * `app-bauen` in der Grammatik. Dieselbe Regel wie bei Python: Ein Werkzeug, das jedes
     * Mal scheitert, ist schlechter als keines.
     */
    @Volatile
    var build: AndroidBuild? = null
        private set

    /**
     * Packt die Bau-Werkzeuge aus und stellt die Kette bereit.
     *
     * Die Java-Werkzeuge liegen als Dex-Archive in den Assets und müssen ins
     * Datenverzeichnis: `dalvikvm` braucht einen Dateipfad, und in einer APK steckende
     * Assets haben keinen. Rund 48 MB, deshalb einmal und nicht bei jedem Start —
     * derselbe Merkdatei-Mechanismus wie bei der Python-Standardbibliothek.
     */
    fun richteBauKetteEin(bauStand: String) {
        val ziel = File(appContext.filesDir, "buildtools")
        val marke = File(ziel, ".fassung")

        val teile = listOf(
            "d8.dex.jar", "kotlinc.dex.jar", "apksigner.dex.jar",
            "android.jar", "kotlin-stdlib.jar", "annotations.jar", "debug.keystore",
        )

        runCatching {
            if (!marke.isFile || marke.readText() != bauStand) {
                ziel.deleteRecursively()
                ziel.mkdirs()
                teile.forEach { name ->
                    appContext.assets.open(name).use { quelle ->
                        File(ziel, name).outputStream().use { quelle.copyTo(it) }
                    }
                }
                // Zuletzt die Marke. Bricht das Kopieren vorher ab, gilt es als nicht getan.
                marke.writeText(bauStand)
                NeonLog.i(TAG, "Bau-Werkzeuge ausgepackt (${teile.size} Dateien)")
            }
        }.onFailure {
            NeonLog.e(TAG, "Bau-Werkzeuge liessen sich nicht auspacken", it)
            return
        }

        val werkzeuge = BuildTools(
            // aapt2 ist ein Programm und kommt aus jniLibs — im Datenverzeichnis duerfte es
            // nicht ausgefuehrt werden.
            aapt2 = File(appContext.applicationInfo.nativeLibraryDir, "libaapt2.so"),
            d8 = File(ziel, "d8.dex.jar"),
            kotlinc = File(ziel, "kotlinc.dex.jar"),
            apksigner = File(ziel, "apksigner.dex.jar"),
            androidJar = File(ziel, "android.jar"),
            kotlinStdlib = File(ziel, "kotlin-stdlib.jar"),
            annotations = File(ziel, "annotations.jar"),
            keystore = File(ziel, "debug.keystore"),
        )

        val fehlt = werkzeuge.fehlend()
        if (fehlt.isNotEmpty()) {
            NeonLog.e(TAG, "Bau-Kette unvollstaendig: ${fehlt.joinToString()}")
            return
        }

        val dalvik = DalvikRunner(cacheDir = File(appContext.cacheDir, "dalvik"))

        // **Beim Start sagen, ob es eine Laufzeit gibt.** Vorher stand hier nur „Bau-Kette
        // bereit" — und bereit war sie nicht: `dalvikvm` wurde in `/system/bin` gesucht, wo
        // es seit Android 10 nicht mehr liegt. Auffallen konnte das erst nach einer Minute
        // Bauzeit im dritten Schritt, und die Meldung dort nannte keinen einzigen Pfad.
        val laufzeit = dalvik.gefundeneLaufzeit()
        if (laufzeit != null) {
            NeonLog.i(TAG_IDE, "Java-Laufzeit: $laufzeit")
        } else {
            NeonLog.w(TAG_IDE, "Keine Java-Laufzeit gefunden:\n${dalvik.befund()}")
        }

        build = AndroidBuild(
            tools = werkzeuge,
            runner = ProcessCommandRunner(),
            java = dalvik,
            log = { meldung -> NeonLog.i(TAG_IDE, meldung) },
        )
        NeonLog.i(TAG, "Bau-Kette bereit")
    }

    val orchestrator = ConversationOrchestrator(
        router = router,
        asr = asr,
        tts = tts,
        lifecycle = lifecycle,
        engine = answerEngine,
        deviceState = { deviceStateProvider.current() },
        actionExecutor = { actionExecutor.execute(it) },
        outcomeStore = outcomeStore,
        // **Funktionen und keine Werte.** Hier stand `codeTools = codeTools`, und das war
        // der Fehler, an dem die Projekterstellung scheiterte: Der Getter wurde genau einmal
        // ausgewertet, beim Bau des Containers — und zu diesem Zeitpunkt packten Python und
        // Bau-Kette im Hintergrund noch ihre fünfzig Megabyte aus. Die Zusammenstellung
        // enthielt vier Datei-Werkzeuge und blieb für die ganze Laufzeit dabei.
        //
        // `app-anlegen`, `app-bauen` und `python` standen damit weder im Prompt noch in der
        // Grammatik. Das Modell konnte sie nicht wählen, weil es sie nicht gab. Im Protokoll
        // stand „Bau-Kette bereit" — bereit war sie, angeboten wurde sie nie.
        tools = { tools },
        codeTools = { codeTools },
        // Bei jedem Durchgang neu gefragt: Wer eben ein Android-Projekt angelegt hat, soll
        // beim nächsten Satz nicht wieder nach der Sprache gefragt werden.
        projektIstAndroid = { projektbereich.aktiv()?.istAndroidProjekt == true },
        memory = { query, limit ->
            runCatching { memory.recall(query, limit) }.getOrDefault(emptyList())
        },
        attachments = { query, limit ->
            runCatching {
                attachments.recall(query, limit).map {
                    AttachmentExcerpt(source = it.chunk.quelle, text = it.chunk.text)
                }
            }.getOrDefault(emptyList())
        },
        learner = learner,
        // Jede Zeile wandert auf die Platte, damit der Verlauf einen Neustart überlebt.
        // Im Hintergrund: Eine hakende Datenbank darf das Gespräch nicht aufhalten.
        onEntry = { entry ->
            scope.launch {
                runCatching { chatHistory.append(entry.toEntity()) }
                    .onFailure { NeonLog.e(TAG, "Verlauf nicht speicherbar", it) }
            }
        },
        // Damit ein Durchgang im Protokoll steht. Bisher stand dort, was llama-server tat,
        // aber nichts darüber, was Neon mit dem Ergebnis machte — und deshalb liess sich
        // nicht sagen, warum von zwei Fragen nur eine beantwortet wurde.
        log = { meldung -> NeonLog.i("NeonTurn", meldung) },
    )

    init {
        // Den Ladefortschritt vom Server bis auf den Bildschirm durchreichen. Ohne das
        // steht bei einem Ladevorgang von einer Minute fünfeinhalb Minuten lang nichts da.
        serverSupervisor.onLoadingProgress = { fortschritt ->
            orchestrator.onServerProgress(
                elapsedMillis = fortschritt.elapsedMillis,
                budgetMillis = fortschritt.budgetMillis,
                lastLine = fortschritt.lastLine,
            )
        }

        // Den gespeicherten Verlauf zurückholen. Ohne das begänne jedes Gespräch bei null,
        // und der Chat wäre nach jedem Schließen der App leer.
        scope.launch {
            runCatching {
                val entries = chatHistory.recent().map { it.toEntry() }
                if (entries.isNotEmpty()) orchestrator.restoreTranscript(entries)
            }.onFailure { NeonLog.e(TAG, "Verlauf nicht ladbar", it) }
        }
    }

    /**
     * Nimmt Anhänge auf: erkennen, auspacken, zerlegen, einbetten, ablegen.
     *
     * Läuft im Hintergrund und meldet den Fortschritt, weil ein Ordner mit ein paar hundert
     * Dateien durchaus einige Sekunden braucht — und eine Oberfläche, die dabei nur steht,
     * sieht aus wie abgestürzt.
     */
    fun addAttachments(sources: List<de.neon.attachments.AttachmentSource>) {
        if (sources.isEmpty()) return
        scope.launch {
            attachmentState.value = attachmentState.value.copy(busy = true, message = null)
            runCatching {
                val ergebnis = AttachmentIngest().ingest(mitTextAusBildern(sources))
                attachments.add(ergebnis.chunks)
                ergebnis
            }.onSuccess { ergebnis ->
                attachmentState.value = AttachmentState(
                    busy = false,
                    files = attachments.paths(),
                    chunkCount = attachments.chunkCount(),
                    message = zusammenfassung(ergebnis),
                    pendingImages = synchronized(wartendeBilder) { wartendeBilder.size },
                )
            }.onFailure {
                NeonLog.e(TAG, "Anhänge nicht aufnehmbar", it)
                attachmentState.value = attachmentState.value.copy(
                    busy = false,
                    message = "Fehlgeschlagen: ${it.message}",
                )
            }
        }
    }

    /**
     * Ersetzt Bilder durch den Text, der darin steht.
     *
     * Das Alltagsmodell kann Bilder technisch nicht sehen. Statt sie als „Binärdatei, nicht
     * lesbar" abzulegen, wird gelesen, was zu lesen ist — Bildschirmfotos, abfotografierte
     * Briefe und Zettel decken den weitaus häufigsten Grund ab, warum jemand ein Bild
     * anhängt.
     *
     * Ein Bild ohne Text bleibt ein Bild und wird als solches vermerkt. Es stillschweigend
     * verschwinden zu lassen wäre schlimmer als es abzulehnen.
     */
    private suspend fun mitTextAusBildern(
        sources: List<de.neon.attachments.AttachmentSource>,
    ): List<de.neon.attachments.AttachmentSource> = sources.map { quelle ->
        if (quelle !is UriSource || !quelle.istBild) return@map quelle

        // Fürs Bildmodell aufheben — aber nur, wenn es eines gibt. Sonst wäre es Ballast,
        // den niemand ansehen kann.
        if (visionAvailable) {
            runCatching {
                val bytes = quelle.open().use { it.readBytes() }
                synchronized(wartendeBilder) {
                    wartendeBilder += ImageAttachment(bytes, quelle.mimeType ?: "image/jpeg")
                }
            }.onFailure { NeonLog.e(TAG, "Bild nicht lesbar für das Bildmodell", it) }
        }

        val text = imageText.read(quelle.uri)
        if (text.isNullOrBlank()) {
            NeonLog.i(TAG, "Kein Text in ${quelle.name}")
            return@map quelle
        }

        // Der Pfad behält den Bildnamen: In einer Antwort soll „aus rechnung.jpg" stehen
        // und nicht ein erfundener Textdateiname.
        BytesSource(
            name = quelle.name,
            path = quelle.path,
            bytes = "Text aus dem Bild ${quelle.name}:\n\n$text".toByteArray(),
        )
    }

    /**
     * Was aufgenommen wurde, in einem Satz.
     *
     * Übersprungenes wird ausdrücklich genannt. Stillschweigend die Hälfte wegzulassen wäre
     * die unangenehmste Art von Fehler: Man fragt etwas, bekommt "steht da nicht" und hat
     * keine Ahnung, dass die Datei nie gelesen wurde.
     */
    private fun zusammenfassung(ergebnis: IngestResult): String = buildString {
        append("${ergebnis.textFileCount} Dateien gelesen, ${ergebnis.chunks.size} Abschnitte")
        val binaer = ergebnis.files.count { it.kind == AttachmentKind.BINAER }
        if (binaer > 0) append(", $binaer nicht lesbar")
        if (ergebnis.skippedCount > 0) append(", ${ergebnis.skippedCount} übersprungen")
        append(".")

        ergebnis.files
            .filter { it.kind == AttachmentKind.UEBERSPRUNGEN }
            .take(3)
            .forEach { append("\n${it.name}: ${it.note}") }
    }

    fun clearAttachments() {
        scope.launch {
            runCatching { attachments.clear() }
            attachmentState.value = AttachmentState()
        }
    }

    fun refreshAttachments() {
        scope.launch {
            runCatching {
                attachmentState.value = AttachmentState(
                    files = attachments.paths(),
                    chunkCount = attachments.chunkCount(),
                )
            }
        }
    }

    /** Löscht den sichtbaren Verlauf, hier und auf der Platte. */
    fun clearChat() {
        orchestrator.clearTranscript()
        scope.launch { runCatching { chatHistory.clear() } }
    }

    /** Die laufende Hörschleife, solange der Dienst aktiv ist. Für den Diagnose-Screen. */
    @Volatile
    private var pipeline: WakeWordPipeline? = null

    val cascadeStats: CascadeStats? get() = pipeline?.stats

    /**
     * Der Schwellwert, ab dem „Hey Neon" als gesagt gilt.
     *
     * Wird hier gehalten und nicht nur in der Hörschleife, damit eine Änderung auch den
     * nächsten Dienststart überdauert. Der richtige Wert hängt von der Umgebung ab — in
     * einer stillen Wohnung darf er niedriger stehen als bei laufendem Fernseher.
     */
    @Volatile
    var wakeWordThreshold: Float = WakeWordPipeline.DEFAULT_WAKE_WORD_THRESHOLD
        set(value) {
            val clamped = value.coerceIn(WakeWordPipeline.MIN_THRESHOLD, WakeWordPipeline.MAX_THRESHOLD)
            field = clamped
            pipeline?.wakeWordThreshold = clamped
        }

    fun routerStats(): RouterStats = RouterStats.from(outcomeStore.recent(200))

    val learnedExampleCount: Int get() = learner.learnedCount

    val knownExampleCount: Int get() = router.knownExampleCount

    /**
     * Die Weckwort-Modelle liegen in `assets/wakeword/`.
     *
     * Fehlen sie, läuft Neon ohne Weckwort weiter — auslösen lässt er sich dann über die
     * Oberfläche. Das ist der Zustand direkt nach dem Klonen des Projekts und darf kein
     * Grund sein, den Start zu verweigern.
     */
    private fun loadWakeWordAssets(): Triple<ByteArray, ByteArray, ByteArray>? = runCatching {
        val assets = appContext.assets
        Triple(
            assets.open("wakeword/melspectrogram.onnx").use { it.readBytes() },
            assets.open("wakeword/embedding_model.onnx").use { it.readBytes() },
            assets.open("wakeword/hey_neon.onnx").use { it.readBytes() },
        )
    }.getOrNull()

    private fun loadVadAsset(): ByteArray? = runCatching {
        appContext.assets.open("vad/silero_vad.onnx").use { it.readBytes() }
    }.getOrNull()

    val wakeWordAvailable: Boolean get() = loadWakeWordAssets() != null && loadVadAsset() != null

    fun serviceDependencies(): NeonForegroundService.Dependencies =
        object : NeonForegroundService.Dependencies {

            private val source = MicrophoneAudioSource()

            private val vad: VoiceActivityDetector = loadVadAsset()
                ?.let { SileroVadOnnx(it) }
                ?: AlwaysSpeechVad

            private val wakeWord: WakeWordDetector = loadWakeWordAssets()
                ?.let { (mel, embedding, model) -> OpenWakeWordDetector(mel, embedding, model) }
                ?: NeverWakeWord

            private val ownPipeline = WakeWordPipeline(
                gate = EnergyGate(),
                vad = vad,
                wakeWord = wakeWord,
                segmenter = SpeechSegmenter(),
                wakeWordThreshold = wakeWordThreshold,
            ).also { pipeline = it }

            override val orchestrator = this@NeonContainer.orchestrator

            override fun listen(): Flow<ListeningEvent> = ownPipeline.listen(source)

            override fun triggerManually() = ownPipeline.triggerManually()

            override fun release() {
                orchestrator.shutdown()
                source.close()
                vad.close()
                wakeWord.close()
                pipeline = null
            }
        }

    fun close() {
        asr.close()
        tts.close()
    }

    private companion object {
        const val TAG = "NeonContainer"

        /**
         * Ein Kennzeichner für die ganze Entwicklungsumgebung.
         *
         * Bau-Kette und Python getrennt zu kennzeichnen wäre ordentlicher und beim Suchen
         * unpraktisch: Wer wissen will, was Neon beim Programmieren getan hat, will beides
         * in einer Reihenfolge sehen — welche Datei geschrieben wurde, was Python dazu
         * sagte, an welchem Bauschritt es hakte. Ein Filter auf `NeonIDE` liefert genau das.
         */
        const val TAG_IDE = "NeonIDE"
        const val SCHLUESSEL_KONTEXT = "kontextfenster"

        /** In welchem Projekt zuletzt gearbeitet wurde. */
        const val SCHLUESSEL_PROJEKT = "aktives-projekt"
    }

    /** Ersatz, solange das VAD-Modell fehlt: lässt alles durch. */
    private object AlwaysSpeechVad : VoiceActivityDetector {
        override fun probability(frame: FloatArray): Float = 1f
        override fun reset() = Unit
        override fun close() = Unit
    }

    /** Ersatz, solange das Weckwortmodell fehlt: löst nie aus. */
    private object NeverWakeWord : WakeWordDetector {
        override fun process(frame: FloatArray): Float = 0f
        override fun reset() = Unit
        override fun close() = Unit
    }
}
