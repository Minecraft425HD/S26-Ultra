package de.neon.app

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.neon.audio.CascadeStats
import de.neon.router.ModelSpec
import de.neon.router.RouterStats
import de.neon.service.NeonForegroundService
import de.neon.service.NeonState
import de.neon.service.TurnReport
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as NeonApplication).container }

    /** Was nach der Berechtigungsabfrage passieren soll. */
    private var pendingAction: (() -> Unit)? = null

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
    readDiagnostics: () -> Diagnostics,
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
                        Text("Modelle", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        models.forEach { model ->
                            val available = isModelAvailable(model)
                            Text(
                                "${model.displayName} — ${gigabytes(model.sizeBytes)} — " +
                                    if (available) "vorhanden" else "fehlt",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Rolle: ${model.role.name.lowercase()}, " +
                                    "Komplexität ${model.minComplexity}–${model.maxComplexity}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(8.dp))
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
