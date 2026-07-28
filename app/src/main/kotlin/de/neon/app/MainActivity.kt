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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.neon.router.ModelSpec
import de.neon.service.NeonForegroundService
import de.neon.service.NeonState
import de.neon.service.TurnReport

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as NeonApplication).container }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            NeonForegroundService.start(this)
        }
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
                        onStart = ::ensurePermissionsAndStart,
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
    private fun ensurePermissionsAndStart() {
        val needed = buildList {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (needed.isEmpty()) NeonForegroundService.start(this)
        else requestPermissions.launch(needed.toTypedArray())
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun NeonScreen(
    state: NeonState,
    lastTurn: TurnReport?,
    models: List<ModelSpec>,
    isModelAvailable: (ModelSpec) -> Boolean,
    wakeWordAvailable: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    var showDiagnostics by remember { mutableStateOf(false) }

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
                                "Führe scripts/fetch-models.sh aus. Bis dahin lässt sich " +
                                "Neon nur hier über die Schaltfläche starten.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onStart) { Text("Zuhören") }
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
                Text(if (showDiagnostics) "Modelle ausblenden" else "Modelle anzeigen")
            }

            if (showDiagnostics) {
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
