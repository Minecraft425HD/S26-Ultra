package de.neon.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.neon.service.ChatEntry
import de.neon.service.LoadingStatus
import de.neon.service.NeonState

/**
 * Der Hauptbildschirm: das Gespräch zum Mitlesen.
 *
 * Bis hierher war Neon ausschließlich hörbar — die Antwort wurde vorgelesen und war danach
 * weg. Für alles, was man nachschlagen, kopieren oder vergleichen will, ist das unbrauchbar,
 * und für Quelltext erst recht. Deshalb steht der Verlauf jetzt im Vordergrund und die
 * Kennzahlen dahinter.
 */
@Composable
fun ChatScreen(
    entries: List<ChatEntry>,
    state: NeonState,
    busy: Boolean,
    speakAnswers: Boolean,
    onSpeakAnswers: (Boolean) -> Unit,
    attachments: AttachmentState,
    sources: List<String>,
    loading: LoadingStatus?,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onClearAttachments: () -> Unit,
    onSend: (String) -> Unit,
    onSpeak: () -> Unit,
    onShowDiagnostics: () -> Unit,
    /** Führt zum Editor: Projektdateien ansehen, ändern, zu markierten Stellen fragen. */
    onShowEditor: () -> Unit,
    onClear: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Ans Ende springen, sobald eine Zeile dazukommt. Ohne das wächst das Gespräch
    // unsichtbar unterhalb des Bildschirmrands weiter.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Ein einziger Abstand für alles, was den Bildschirm verdeckt.
            //
            // **Der Fehler, den das behebt.** Hier stand `.imePadding().navigationBarsPadding()`.
            // Das ist eine Verdopplung, die man nicht sieht, solange die Tastatur zu ist: Der
            // Tastaturbereich reicht bis zum unteren Bildschirmrand und schließt die
            // Navigationsleiste ein. Deren Höhe wurde also ein zweites Mal aufgeschlagen.
            //
            // `safeDrawing` ist die Vereinigung aus Systemleisten, Aussparung und Tastatur —
            // **eine** Größe, die sich nicht mit sich selbst addieren kann. Nebenbei behebt
            // das eine Lücke: Die Statusleiste oben hat vorher niemand berücksichtigt.
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Kopfzeile(
            state = state,
            hasEntries = entries.isNotEmpty(),
            onShowDiagnostics = onShowDiagnostics,
            onShowEditor = onShowEditor,
            onClear = onClear,
        )
        HorizontalDivider()

        if (entries.isEmpty()) {
            LeererVerlauf(Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries) { Blase(it) }
            }
        }

        if (busy) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.height(16.dp).widthIn(min = 16.dp))
                Column {
                    Text(beschreibe(state), style = MaterialTheme.typography.bodySmall)

                    // Die mitlaufende Zahl. Ohne sie sieht eine Minute Ladezeit genauso aus
                    // wie ein Hänger — und man gibt auf, bevor die Antwort kommt.
                    loading?.let {
                        Text(
                            buildString {
                                append("${it.elapsedMillis / 1000} s")
                                if (it.budgetMillis > 0) append(" von höchstens ${it.budgetMillis / 1000} s")
                                it.lastLine?.let { zeile -> append(" · ${zeile.take(60)}") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        Anhaenge(
            state = attachments,
            sources = sources,
            onPickFiles = onPickFiles,
            onPickFolder = onPickFolder,
            onClear = onClearAttachments,
        )

        HorizontalDivider()
        Eingabe(
            draft = draft,
            onDraft = { draft = it },
            busy = busy,
            speakAnswers = speakAnswers,
            onSpeakAnswers = onSpeakAnswers,
            onSend = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    draft = ""
                    onSend(text)
                }
            },
            onSpeak = onSpeak,
        )
    }
}

@Composable
private fun Kopfzeile(
    state: NeonState,
    hasEntries: Boolean,
    onShowDiagnostics: () -> Unit,
    onShowEditor: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Neon", style = MaterialTheme.typography.titleLarge)
            Text(beschreibe(state), style = MaterialTheme.typography.bodySmall)
        }
        if (hasEntries) {
            TextButton(onClick = onClear) { Text("Leeren") }
        }
        TextButton(onClick = onShowEditor) { Text("Projekt") }
        TextButton(onClick = onShowDiagnostics) { Text("Diagnose") }
    }
}

@Composable
private fun LeererVerlauf(modifier: Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text("Noch nichts besprochen.", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Tippe eine Frage oder drücke auf Sprechen. Der Verlauf bleibt erhalten, " +
                    "auch wenn du die App schließt.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Eine Zeile des Gesprächs.
 *
 * In einem [SelectionContainer], damit sich Antworten markieren und kopieren lassen — bei
 * Quelltext oder einer Adresse ist genau das der Zweck der Textausgabe.
 */
@Composable
private fun Blase(entry: ChatEntry) {
    val vomNutzer = entry.fromUser
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (vomNutzer) Alignment.End else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    when {
                        vomNutzer -> MaterialTheme.colorScheme.primaryContainer
                        // Hinweise sind Mitteilungen über Neon, keine Antworten. Sie sollen
                        // auch so aussehen, sonst hält man sie für eine.
                        entry.notice -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            SelectionContainer {
                Text(
                    entry.text.ifBlank { "(leer)" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        vomNutzer -> MaterialTheme.colorScheme.onPrimaryContainer
                        entry.notice -> MaterialTheme.colorScheme.onTertiaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        // Die Herkunft der Antwort. Nicht Beiwerk: Nur daran lässt sich ablesen, ob der
        // Router vernünftig entschieden hat — und ob die Antwort überhaupt von einem
        // Modell kam oder von der Regelstufe.
        if (!vomNutzer && (entry.modelId != null || entry.routeReason != null)) {
            Text(
                buildString {
                    append(entry.modelId ?: "ohne Modell")
                    if (entry.latencyMs > 0) append(" · ${entry.latencyMs} ms")
                    // Token je Sekunde. Die eine Zahl, an der sich ablesen lässt, ob eine
                    // Änderung am Unterbau etwas gebracht hat — vorher musste man dafür
                    // das Protokoll teilen und die Zeilen von llama-server suchen.
                    tempo(entry.tokenCount, entry.latencyMs)?.let { append(" · $it") }
                    entry.routeReason?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

/**
 * Token je Sekunde als kurzer Text — `null`, wenn sich nichts Sinnvolles ausrechnen lässt.
 *
 * Die Latenz enthält auch das Laden des Modells; beim ersten Durchgang steht deshalb eine
 * zu kleine Zahl. Das ist hinnehmbar: Verglichen wird ohnehin über mehrere Antworten, und
 * eine leicht zu pessimistische Angabe ist besser als gar keine.
 */
internal fun tempo(tokens: Int, latencyMs: Long): String? {
    if (tokens <= 0 || latencyMs <= 0) return null
    val proSekunde = tokens * 1000.0 / latencyMs
    // Unter zehn ist die Nachkommastelle die Auskunft — 0,7 gegen 4,2 ist der ganze
    // Unterschied zwischen unbenutzbar und zäh. Darüber wäre sie nur Rauschen.
    // Fest auf Deutsch: Die ganze Oberfläche ist deutsch, und ohne Angabe hinge das
    // Dezimalzeichen an der Spracheinstellung des Telefons.
    val zahl = if (proSekunde < 10) {
        String.format(java.util.Locale.GERMANY, "%.1f", proSekunde)
    } else {
        proSekunde.toInt().toString()
    }
    return "$zahl T/s"
}

/**
 * Die Anhänge: was dran ist, was zuletzt benutzt wurde, und wie man welche hinzufügt.
 *
 * Beides sichtbar zu machen — das Angehängte *und* die tatsächlich benutzten Fundstellen —
 * ist der Unterschied zwischen einem Assistenten, dem man glauben muss, und einem, den man
 * nachprüfen kann. Gerade weil nur ein Bruchteil der Dateien in den Prompt geht, darf nicht
 * im Dunkeln bleiben, welcher.
 */
@Composable
private fun Anhaenge(
    state: AttachmentState,
    sources: List<String>,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onClear: () -> Unit,
) {
    var offen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { offen = !offen }) {
                Text(
                    when {
                        state.busy -> "Nehme Anhänge auf …"
                        state.files.isEmpty() -> "Anhängen"
                        else -> "${'$'}{state.files.size} Dateien angehängt"
                    }
                )
            }
            Spacer(Modifier.weight(1f))
            if (sources.isNotEmpty()) {
                Text(
                    "benutzt: ${'$'}{sources.size}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (offen) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickFiles, enabled = !state.busy) { Text("Dateien") }
                OutlinedButton(onClick = onPickFolder, enabled = !state.busy) { Text("Ordner") }
                if (state.files.isNotEmpty()) {
                    TextButton(onClick = onClear, enabled = !state.busy) { Text("Alle entfernen") }
                }
            }

            if (state.pendingImages > 0) {
                Text(
                    "${state.pendingImages} Bild(er) gehen mit der nächsten Frage ans " +
                        "Bildmodell — danach nicht mehr, weil jedes Bild Kontext kostet.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }

            if (state.files.isNotEmpty()) {
                Text(
                    "${'$'}{state.chunkCount} durchsuchbare Abschnitte",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    state.files.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (sources.isNotEmpty()) {
                Text(
                    "Zuletzt benutzte Stellen:",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                sources.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun Eingabe(
    draft: String,
    onDraft: (String) -> Unit,
    busy: Boolean,
    speakAnswers: Boolean,
    onSpeakAnswers: (Boolean) -> Unit,
    onSend: () -> Unit,
    onSpeak: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Frag mich etwas") },
                maxLines = 5,
            )
            Button(onClick = onSend, enabled = draft.isNotBlank() && !busy) { Text("Senden") }
        }

        Spacer(Modifier.height(4.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onSpeak, enabled = !busy) { Text("Sprechen") }
            Spacer(Modifier.weight(1f))
            Text("Vorlesen", style = MaterialTheme.typography.bodySmall)
            Switch(checked = speakAnswers, onCheckedChange = onSpeakAnswers)
        }
    }
}

private fun beschreibe(state: NeonState): String = when (state) {
    NeonState.GESTOPPT -> "Bereit"
    NeonState.LAUSCHEN -> "Hört auf „Neon“"
    NeonState.GEWECKT -> "Ich höre …"
    NeonState.ERKENNUNG -> "Verstehe …"
    NeonState.ROUTING -> "Wähle das passende Modell …"
    NeonState.MODELL_LAEDT -> "Modell wird geladen — beim ersten Mal etwa eine Minute"
    NeonState.ANTWORT -> "Denke nach …"
    NeonState.SPRECHEN -> "Spricht"
}
