package de.neon.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import de.neon.workspace.SourceSelection

/**
 * Der Editor: Dateien ansehen, ändern, und zu einer markierten Stelle fragen.
 *
 * **Warum die Markierung der eigentliche Punkt dieses Bildschirms ist.** Eine Datei zu öffnen
 * kann jeder Texteditor. Was Neon davon unterscheidet, ist die Frage *„was macht das hier"* zu
 * einer Stelle, auf die man gerade zeigt — und dafür muss die Oberfläche wissen, was markiert
 * ist, und es so weitergeben, dass eine Antwort auf eine Zeilennummer zeigen kann.
 *
 * Die Umrechnung von Zeichenpositionen in Zeilennummern liegt in [SourceSelection] und ist
 * dort ohne Gerät geprüft. Hier steht nur, was man sieht und antippen kann.
 */
@Composable
fun EditorScreen(
    dateien: List<String>,
    /** Liest eine Datei. `null`, wenn es sie nicht gibt. */
    lies: (String) -> String?,
    /** Speichert eine Datei. */
    speichere: (String, String) -> Unit,
    /** Stellt eine Frage zu einem markierten Abschnitt. */
    frage: (frage: String, selection: String) -> Unit,
    /**
     * Die zuletzt gebaute APK, falls es eine gibt.
     *
     * Sie entsteht im Gespräch („bau die App"), nicht hier — dieser Bildschirm bietet nur
     * die Installation an. Ohne diesen Knopf läge das Ergebnis eines minutenlangen
     * Bauvorgangs in einem Verzeichnis, an das man ohne Dateimanager nicht herankommt.
     */
    gebauteApk: java.io.File?,
    onInstallieren: (java.io.File) -> Unit,
    onZurueck: () -> Unit,
) {
    var offen by remember { mutableStateOf<String?>(null) }
    var inhalt by remember { mutableStateOf(TextFieldValue("")) }
    var geaendert by remember { mutableStateOf(false) }
    var frageText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Dieselbe Größe wie im Chat, aus demselben Grund: `safeDrawing` ist die
            // Vereinigung aus Systemleisten, Aussparung und Tastatur und kann sich nicht mit
            // sich selbst addieren. Einzelabstände zu stapeln hat das Eingabefeld im Chat
            // doppelt so hoch springen lassen wie die Tastatur.
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Projekt", style = MaterialTheme.typography.titleMedium)
                gebauteApk?.let {
                    Text(
                        "gebaut: ${it.name}, ${it.length() / 1024} KB",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                offen?.let {
                    Text(
                        if (geaendert) "$it — ungespeichert" else it,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                gebauteApk?.let { apk ->
                    TextButton(onClick = { onInstallieren(apk) }) { Text("Installieren") }
                }
                TextButton(onClick = onZurueck) { Text("Zum Chat") }
            }
        }
        HorizontalDivider()

        val geoeffnet = offen
        if (geoeffnet == null) {
            Dateiliste(dateien) { pfad ->
                offen = pfad
                inhalt = TextFieldValue(lies(pfad).orEmpty())
                geaendert = false
                frageText = ""
            }
            return@Column
        }

        OutlinedTextField(
            value = inhalt,
            onValueChange = {
                // Nur als geändert merken, wenn sich der **Text** geändert hat. Eine reine
                // Änderung der Markierung ist keine Bearbeitung, und ein "ungespeichert",
                // das beim Antippen erscheint, glaubt beim dritten Mal niemand mehr.
                if (it.text != inhalt.text) geaendert = true
                inhalt = it
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )

        val auswahl = SourceSelection.ausZeichenbereich(
            pfad = geoeffnet,
            inhalt = inhalt.text,
            start = inhalt.selection.start,
            ende = inhalt.selection.end,
        )

        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                markierungshinweis(inhalt.selection, auswahl),
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(6.dp))

            OutlinedTextField(
                value = frageText,
                onValueChange = { frageText = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                placeholder = { Text("Frage zur markierten Stelle") },
            )
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        frage(frageText.trim(), auswahl.alsPromptBlock())
                        frageText = ""
                    },
                    enabled = frageText.isNotBlank(),
                ) { Text("Neon fragen") }

                OutlinedButton(
                    onClick = {
                        speichere(geoeffnet, inhalt.text)
                        geaendert = false
                    },
                    enabled = geaendert,
                ) { Text("Speichern") }

                OutlinedButton(onClick = { offen = null }) { Text("Schließen") }
            }
        }
    }
}

/**
 * Was markiert ist, in einer Zeile.
 *
 * Sichtbar zu machen, **welche** Zeilen gleich in die Frage gehen, ist kein Beiwerk: Ohne das
 * schickt man eine Frage zu einer Stelle ab, die man nicht gemeint hat, und wundert sich über
 * die Antwort. Bei leerer Markierung gilt die Zeile mit dem Cursor — auch das muss dastehen,
 * sonst wirkt die Antwort wie geraten.
 */
private fun markierungshinweis(bereich: TextRange, auswahl: SourceSelection): String {
    val zeilen = auswahl.bereich
    return when {
        bereich.collapsed -> "Cursor in Zeile ${zeilen.first} — die Frage bezieht sich darauf"
        zeilen.first == zeilen.last -> "Zeile ${zeilen.first} markiert"
        else -> "Zeilen ${zeilen.first} bis ${zeilen.last} markiert"
    }
}

@Composable
private fun Dateiliste(dateien: List<String>, onOeffnen: (String) -> Unit) {
    if (dateien.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Noch keine Dateien im Projekt.", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Bitte Neon im Chat, etwas anzulegen — etwa „schreib mir ein Python-Skript, " +
                    "das die ersten zwanzig Primzahlen ausgibt\".",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(dateien) { pfad ->
            TextButton(
                onClick = { onOeffnen(pfad) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                Text(
                    pfad,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium
                        .copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}
