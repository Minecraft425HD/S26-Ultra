package de.neon.app

import android.Manifest
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

    private val container by lazy { (application as NeonApplication).container }

    /** Was nach der Berechtigungsabfrage passieren soll. */
    private var pendingAction: (() -> Unit)? = null

    /** In welchen Modellplatz die gleich ausgewählte Datei gehört. */
    private var pendingImportModelId: String? = null

    private val importState = MutableStateFlow<ImportState>(ImportState.Idle)

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

                container.modelStore.importFrom(
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

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val action = pendingAction
        pendingAction = null
        if (granted[Manifest.permission.RECORD_AUDIO] == true) action?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    NeonScreen(
                        state = container.orchestrator.state.collectAsState().value,
                        lastTurn = container.orchestrator.lastTurn.collectAsState().value,
                        models = container.registry.generativeModels(),
                        isModelAvailable = { container.modelStore.isAvailable(it) },
                        wakeWordAvailable = container.wakeWordAvailable,
                        inferenceAvailable = container.inferenceAvailable,
                        importState = importState.collectAsState().value,
                        wakeWordThreshold = container.wakeWordThreshold,
                        onWakeWordThreshold = { container.wakeWordThreshold = it },
                        onImportModel = { modelId ->
                            pendingImportModelId = modelId
                            importState.value = ImportState.Running(modelId, 0)
                            // GGUF hat keinen eigenen MIME-Typ; deshalb alles anbieten und
                            // die Datei danach an ihren Kennbytes prüfen.
                            pickModelFile.launch(arrayOf("*/*"))
                        },
                        readDiagnostics = {
                            Diagnostics(
                                cascade = container.cascadeStats,
                                router = container.routerStats(),
                                knownExamples = container.knownExampleCount,
                                learnedExamples = container.learnedExampleCount,
                            )
                        },
                        onSpeak = { withPermissions { NeonForegroundService.trigger(this) } },
                        onStart = { withPermissions { NeonForegroundService.start(this) } },
                        onStop = { NeonForegroundService.stop(this) },
                    )
                }
            }
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

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
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
