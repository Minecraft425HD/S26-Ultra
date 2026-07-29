package de.neon.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import de.neon.audio.CascadeStats
import de.neon.audio.WakeWordPipeline
import de.neon.inference.ModelStore
import de.neon.platform.NeonLog
import de.neon.router.ModelSpec
import de.neon.router.RouterStats
import de.neon.service.NeonForegroundService
import de.neon.service.NeonState
import de.neon.service.TurnReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val app by lazy { application as NeonApplication }

    /** Kann fehlen, wenn der Aufbau gescheitert ist. Dann wird der Fehler angezeigt. */
    private val container: NeonContainer? get() = app.container

    /** Was nach der Berechtigungsabfrage passieren soll. */
    private var pendingAction: (() -> Unit)? = null

    /** In welchen Modellplatz die gleich ausgewählte Datei gehört. */
    private var pendingImportModelId: String? = null

    private val importState = MutableStateFlow<ImportState>(ImportState.Idle)

    /**
     * Ob getippte Antworten auch vorgelesen werden.
     *
     * Voreingestellt aus: Wer tippt, sieht auf den Bildschirm, und ein Telefon, das beim
     * Schreiben lospredigt, ist in fremder Gesellschaft unbrauchbar. Umschaltbar, weil es
     * beim Kochen oder Fahren genau andersherum ist.
     */
    private var speakTypedAnswers by mutableStateOf(false)

    /**
     * Der Weg, wie das Modell ohne Entwicklerwerkzeuge auf das Gerät kommt.
     *
     * Die Datei wird per USB in den Download-Ordner kopiert und hier ausgewählt. Kein adb,
     * keine Entwicklereinstellungen, kein Downloader, der bei 2,5 GB über WLAN abbricht.
     */
    private val pickModelFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val modelId = pendingImportModelId
        pendingImportModelId = null
        if (uri == null || modelId == null) {
            importState.value = ImportState.Idle
            return@registerForActivityResult
        }
        importModel(modelId, uri)
    }

    private fun importModel(modelId: String, uri: Uri) {
        val store = container?.modelStore ?: return
        lifecycleScope.launch {
            importState.value = ImportState.Running(modelId, 0)
            val result = withContext(Dispatchers.IO) {
                val expected = contentResolver.openAssetFileDescriptor(uri, "r")
                    ?.use { it.length }
                    ?.takeIf { it > 0 }
                    ?: 0L

                val stream = contentResolver.openInputStream(uri)
                    ?: return@withContext ModelStore.ImportResult.Failed(
                        "Die Datei ließ sich nicht öffnen."
                    )

                store.importFrom(
                    modelId = modelId,
                    source = stream,
                    expectedBytes = expected,
                    onProgress = { copied ->
                        importState.value = ImportState.Running(modelId, copied)
                    },
                )
            }

            importState.value = when (result) {
                is ModelStore.ImportResult.Ok ->
                    ImportState.Done("${gigabytes(result.bytes)} übernommen.")

                is ModelStore.ImportResult.Failed ->
                    ImportState.Done("Fehlgeschlagen: ${result.reason}")
            }
        }
    }

    /**
     * Einzelne Dateien anhängen.
     *
     * Alle Typen zur Auswahl: Was lesbar ist, entscheidet sich am Inhalt und nicht an einem
     * MIME-Typ, den der Anbieter oft ohnehin nur rät.
     */
    private val pickAttachments = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val container = container ?: return@registerForActivityResult
        if (uris.isEmpty()) return@registerForActivityResult
        container.addAttachments(uris.mapNotNull { AndroidSources.fromDocument(this, it) })
    }

    /** Einen ganzen Ordner anhängen, rekursiv. */
    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val container = container ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        // Ohne diese Berechtigung darf nur gelesen werden, solange die Auswahl offen ist —
        // die Aufnahme läuft aber im Hintergrund weiter.
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        container.addAttachments(AndroidSources.fromTree(this, uri))
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val action = pendingAction
        pendingAction = null
        if (granted[Manifest.permission.RECORD_AUDIO] == true) action?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Zuerst nachsehen, ob der letzte Start abgestürzt ist. Dieser Zweig darf nichts
        // vom Container brauchen — sonst stürbe er am selben Fehler, den er anzeigen soll.
        val crash = runCatching { app.crashReporter.lastCrash() }.getOrNull()
        val failure = app.startupFailure

        // Was beim letzten Mal angehängt wurde, liegt in der Datenbank und soll auch nach
        // einem Neustart sichtbar sein.
        container?.refreshAttachments()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val ready = container
                    when {
                        crash != null -> ProblemScreen(
                            title = "Neon ist beim letzten Mal abgestürzt",
                            explanation = "Schick mir diesen Bericht — daraus lässt sich " +
                                "die Ursache ablesen.",
                            detail = crash,
                            onShare = { shareText("Neon-Absturzbericht", crash) },
                            onDismiss = {
                                app.crashReporter.clear()
                                recreate()
                            },
                        )

                        ready == null -> ProblemScreen(
                            title = "Neon konnte nicht vollständig starten",
                            explanation = "Die Oberfläche läuft, aber der Aufbau ist " +
                                "gescheitert. Schick mir diesen Bericht.",
                            detail = failure?.stackTraceToString() ?: "Ursache unbekannt.",
                            onShare = {
                                shareText(
                                    "Neon-Fehlerbericht",
                                    failure?.stackTraceToString() ?: "Ursache unbekannt.",
                                )
                            },
                            onDismiss = null,
                        )

                        else -> {
                            val state = ready.orchestrator.state.collectAsState().value
                            var zeigeDiagnose by remember { mutableStateOf(false) }

                            if (!zeigeDiagnose) {
                                ChatScreen(
                                    entries = ready.orchestrator.transcript.collectAsState().value,
                                    state = state,
                                    // Alles außer Bereit und Lauschen heißt: Neon arbeitet.
                                    // Daran hängt, dass man nicht zweimal absendet.
                                    busy = state != NeonState.GESTOPPT && state != NeonState.LAUSCHEN,
                                    speakAnswers = speakTypedAnswers,
                                    onSpeakAnswers = { speakTypedAnswers = it },
                                    attachments = ready.attachmentState.collectAsState().value,
                                    sources = ready.orchestrator.lastSources.collectAsState().value,
                                    onPickFiles = { pickAttachments.launch(arrayOf("*/*")) },
                                    onPickFolder = { pickFolder.launch(null) },
                                    onClearAttachments = { ready.clearAttachments() },
                                    onSend = { text -> sendText(ready, text) },
                                    onSpeak = { withPermissions { NeonForegroundService.trigger(this) } },
                                    onShowDiagnostics = { zeigeDiagnose = true },
                                    onClear = { ready.clearChat() },
                                )
                            } else {
                                DiagnoseAnsicht(ready, state) { zeigeDiagnose = false }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Eine getippte Frage.
     *
     * Läuft bewusst **ohne** den Vordergrunddienst: Wer tippt, braucht kein Mikrofon, also
     * auch keine Mikrofonberechtigung und keine Benachrichtigung. Neon ist damit auch dann
     * benutzbar, wenn man den Dauerlauscher gar nicht möchte.
     */
    private fun sendText(container: NeonContainer, text: String) {
        lifecycleScope.launch {
            runCatching { container.orchestrator.handleText(text, speak = speakTypedAnswers) }
                .onFailure { NeonLog.e("MainActivity", "Getippte Frage fehlgeschlagen", it) }
        }
    }

    @Composable
    private fun DiagnoseAnsicht(ready: NeonContainer, state: NeonState, onBack: () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onBack) { Text("Zurück zum Chat") }
            }
            NeonScreen(
                state = state,
                lastTurn = ready.orchestrator.lastTurn.collectAsState().value,
                models = ready.registry.generativeModels(),
                            isModelAvailable = { ready.modelStore.isAvailable(it) },
                            wakeWordAvailable = ready.wakeWordAvailable,
                            inferenceAvailable = ready.inferenceAvailable,
                            importState = importState.collectAsState().value,
                            wakeWordThreshold = ready.wakeWordThreshold,
                            onWakeWordThreshold = { ready.wakeWordThreshold = it },
                            onImportModel = { modelId ->
                                pendingImportModelId = modelId
                                importState.value = ImportState.Running(modelId, 0)
                                // GGUF hat keinen eigenen MIME-Typ; deshalb alles anbieten
                                // und die Datei danach an ihren Kennbytes prüfen.
                                pickModelFile.launch(arrayOf("*/*"))
                            },
                            readDiagnostics = {
                                Diagnostics(
                                    cascade = ready.cascadeStats,
                                    router = ready.routerStats(),
                                    knownExamples = ready.knownExampleCount,
                                    learnedExamples = ready.learnedExampleCount,
                                )
                            },
                onShareLog = ::shareLog,
                // this@MainActivity, weil wir hier in einem Column-Bereich stehen: ein
                // blankes this wäre der ColumnScope und nicht der Context.
                onSpeak = { withPermissions { NeonForegroundService.trigger(this@MainActivity) } },
                onStart = { withPermissions { NeonForegroundService.start(this@MainActivity) } },
                onStop = { NeonForegroundService.stop(this@MainActivity) },
            )
        }
    }

    /**
     * Neon wird immer aus einer sichtbaren Oberfläche heraus gestartet.
     *
     * Unter Android 16 ist das keine Höflichkeit, sondern Voraussetzung: Ein Dienst, der
     * das Mikrofon benutzt, darf nur gestartet werden, während die App im Vordergrund ist.
     */
    private fun withPermissions(action: () -> Unit) {
        val needed = buildList {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (needed.isEmpty()) {
            action()
        } else {
            pendingAction = action
            requestPermissions.launch(needed.toTypedArray())
        }
    }

    /**
     * Schickt das Protokoll dorthin, wo du es lesen kannst.
     *
     * Ohne `adb` gibt es sonst keinen Weg an die Fehlermeldungen — und die entscheidende
     * steht meistens in der Ausgabe von llama-server, nicht in dem, was Neon vorliest.
     */
    private fun shareLog() = shareText("Neon-Protokoll", NeonLog.fullText().ifBlank { "leer" })

    private fun shareText(subject: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, subject))
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * Zeigt einen Absturz oder einen gescheiterten Start an.
 *
 * Bewusst ohne jede Abhängigkeit zum Objektgraphen: Dieser Bildschirm muss gerade dann
 * funktionieren, wenn der Rest der App es nicht tut.
 */
@Composable
private fun ProblemScreen(
    title: String,
    explanation: String,
    detail: String,
    onShare: () -> Unit,
    onDismiss: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(explanation, style = MaterialTheme.typography.bodyMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onShare) { Text("Bericht teilen") }
            if (onDismiss != null) {
                OutlinedButton(onClick = onDismiss) { Text("Verwerfen und weiter") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Wo der Modell-Import gerade steht. */
sealed interface ImportState {
    data object Idle : ImportState
    data class Running(val modelId: String, val copiedBytes: Long) : ImportState
    data class Done(val message: String) : ImportState
}

/** Momentaufnahme der Kennzahlen für den Diagnose-Screen. */
private data class Diagnostics(
    val cascade: CascadeStats?,
    val router: RouterStats,
    val knownExamples: Int,
    val learnedExamples: Int,
)

@Composable
private fun NeonScreen(
    state: NeonState,
    lastTurn: TurnReport?,
    models: List<ModelSpec>,
    isModelAvailable: (ModelSpec) -> Boolean,
    wakeWordAvailable: Boolean,
    inferenceAvailable: Boolean,
    importState: ImportState,
    readDiagnostics: () -> Diagnostics,
    wakeWordThreshold: Float,
    onWakeWordThreshold: (Float) -> Unit,
    onImportModel: (String) -> Unit,
    onShareLog: () -> Unit,
    onSpeak: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    var showDiagnostics by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf<Diagnostics?>(null) }

    // Die Kennzahlen werden gepollt statt beobachtet: Sie ändern sich fünfzig Mal je
    // Sekunde, und die Oberfläche jedes Mal neu zu zeichnen wäre genau die Art von
    // Stromverbrauch, die dieser Screen eigentlich messen soll.
    LaunchedEffect(showDiagnostics) {
        while (showDiagnostics) {
            diagnostics = readDiagnostics()
            delay(1_000)
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Neon", style = MaterialTheme.typography.headlineMedium)
            Text(describe(state), style = MaterialTheme.typography.bodyLarge)

            if (!inferenceAvailable) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Inferenz nicht verfügbar", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Der mitgelieferte llama-server fehlt in dieser Installation. " +
                                "Regelbefehle, Spracherkennung und Sprachausgabe " +
                                "funktionieren, echte Fragen beantwortet Neon nicht.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (!wakeWordAvailable) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Weckwort nicht verfügbar", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Die Modelldateien für „Neon“ fehlen unter assets/wakeword/. " +
                                "Führe scripts/fetch-models.sh aus und trainiere das " +
                                "Weckwortmodell. Bis dahin funktioniert „Sprechen“ — nur das " +
                                "freihändige Ansprechen fehlt.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // "Sprechen" startet den Dienst mit, falls er noch nicht läuft. Für den
            // häufigsten Fall — schnell etwas fragen — soll ein Knopfdruck genügen.
            Button(
                onClick = onSpeak,
                modifier = Modifier.fillMaxWidth(),
                enabled = state != NeonState.SPRECHEN,
            ) {
                Text(if (state == NeonState.GESTOPPT) "Sprechen" else "Neon, …")
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onStart) { Text("Dauerbetrieb") }
                OutlinedButton(onClick = onStop) { Text("Beenden") }
            }

            lastTurn?.let { turn ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Letzter Durchgang", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("Verstanden: „${turn.transcript}“")
                        Spacer(Modifier.height(4.dp))
                        Text("Antwort: ${turn.answer}")
                        Spacer(Modifier.height(8.dp))
                        // Die Route sichtbar zu machen ist kein Beiwerk: Nur so lässt sich
                        // beurteilen, ob der Router die richtigen Entscheidungen trifft.
                        Text(
                            if (turn.usedNoModel) "Ohne Sprachmodell beantwortet"
                            else "Modell: ${turn.modelId}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("Grund: ${turn.routeReason}", style = MaterialTheme.typography.bodySmall)
                        Text("Dauer: ${turn.latencyMs} ms", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            OutlinedButton(onClick = { showDiagnostics = !showDiagnostics }) {
                Text(if (showDiagnostics) "Diagnose ausblenden" else "Diagnose anzeigen")
            }

            if (showDiagnostics) {
                diagnostics?.let { DiagnosticsCards(it) }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Protokoll", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Hier steht, warum etwas nicht geklappt hat — auch die Ausgabe " +
                                "von llama-server. Neueste Zeile unten.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))

                        val lines = remember(diagnostics) { NeonLog.recent(40) }
                        if (lines.isEmpty()) {
                            Text("Noch nichts protokolliert.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                lines.forEach {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onShareLog) { Text("Protokoll teilen") }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Weckwort-Empfindlichkeit", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Springt Neon zu oft an, Regler nach rechts. Hört er dich " +
                                "nicht, nach links. Das wirkt sofort — dafür muss nichts " +
                                "neu trainiert werden.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))

                        var value by remember(wakeWordThreshold) {
                            mutableFloatStateOf(wakeWordThreshold)
                        }
                        Slider(
                            value = value,
                            onValueChange = { value = it },
                            onValueChangeFinished = { onWakeWordThreshold(value) },
                            valueRange = WakeWordPipeline.MIN_THRESHOLD..WakeWordPipeline.MAX_THRESHOLD,
                        )
                        Text(
                            "Schwellwert: %.2f".format(value),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Modelle", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Datei per USB auf das Telefon kopieren, dann hier auswählen.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(12.dp))

                        models.forEach { model ->
                            val available = isModelAvailable(model)
                            Text(
                                "${model.displayName} — ${gigabytes(model.sizeBytes)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Rolle: ${model.role.name.lowercase()}, " +
                                    "Komplexität ${model.minComplexity}–${model.maxComplexity}",
                                style = MaterialTheme.typography.bodySmall,
                            )

                            val running = importState as? ImportState.Running
                            when {
                                available -> Text(
                                    "vorhanden",
                                    style = MaterialTheme.typography.bodySmall,
                                )

                                running?.modelId == model.id -> Text(
                                    "wird übernommen — ${gigabytes(running.copiedBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )

                                else -> OutlinedButton(onClick = { onImportModel(model.id) }) {
                                    Text("Importieren")
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }

                        (importState as? ImportState.Done)?.let {
                            Text(it.message, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Die beiden Karten, an denen sich ablesen lässt, ob Neon effizient arbeitet.
 *
 * Die Durchlassquoten zeigen, wie gut die Audio-Kaskade filtert — je weniger Blöcke bis zum
 * Weckwortmodell durchkommen, desto sparsamer der Dauerbetrieb. Der Anteil der Anfragen
 * ohne Sprachmodell zeigt dasselbe eine Ebene höher.
 */
@Composable
private fun DiagnosticsCards(diagnostics: Diagnostics) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Hörschleife", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            val cascade = diagnostics.cascade
            if (cascade == null || cascade.framesRead == 0L) {
                Text("Noch keine Audioblöcke verarbeitet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("Blöcke gelesen: ${cascade.framesRead}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "durch das Energie-Gatter: ${percent(cascade.gatePassRate)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "davon Sprache laut VAD: ${percent(cascade.vadPassRate)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Weckwort erkannt: ${cascade.wakeWordHits} mal",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Je kleiner die zweite Zahl, desto sparsamer läuft Neon im Leerlauf.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Router", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            val router = diagnostics.router
            if (router.total == 0) {
                Text("Noch keine Anfragen.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("Anfragen: ${router.total}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "ohne Sprachmodell beantwortet: ${percent(router.directActionShare)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Median-Dauer: ${router.medianLatencyMs} ms",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                router.perModel.forEach { (modelId, stats) ->
                    Text(
                        "$modelId: ${stats.count}×, ${stats.medianLatencyMs} ms, " +
                            "${stats.totalTokens} Token",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Beispiele für die Einordnung: ${diagnostics.knownExamples} " +
                    "(davon ${diagnostics.learnedExamples} aus dieser Sitzung gelernt)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun percent(value: Double): String = "%.1f %%".format(value * 100)

private fun describe(state: NeonState): String = when (state) {
    NeonState.GESTOPPT -> "Gestoppt"
    NeonState.LAUSCHEN -> "Hört auf „Neon“"
    NeonState.GEWECKT -> "Ich höre …"
    NeonState.ERKENNUNG -> "Verstehe …"
    NeonState.ROUTING -> "Wähle das passende Modell …"
    NeonState.ANTWORT -> "Denke nach …"
    NeonState.SPRECHEN -> "Spricht"
}

private fun gigabytes(bytes: Long): String =
    "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
