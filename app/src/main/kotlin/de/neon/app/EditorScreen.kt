package de.neon.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import de.neon.workspace.SourceSelection
import kotlinx.coroutines.launch

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
    /** Alle Projekte, alphabetisch. */
    projekte: List<String>,
    /** In welchem gerade gearbeitet wird. `null`, solange es keines gibt. */
    aktivesProjekt: String?,
    onProjektWaehlen: (String) -> Unit,
    /** Legt ein Projekt an. Gibt den bereinigten Namen zurück, oder `null` bei Unsinn. */
    onProjektAnlegen: (String) -> String?,
    onProjektWegraeumen: (String) -> Unit,
    dateien: List<String>,
    /** Liest eine Datei. `null`, wenn es sie nicht gibt. */
    lies: (String) -> String?,
    /** Speichert eine Datei. */
    speichere: (String, String) -> Unit,
    /** Legt eine Datei in den Papierkorb. `false`, wenn es nicht ging. */
    onDateiWegraeumen: (String) -> Boolean,
    /** Benennt um oder verschiebt. `false`, wenn das Ziel belegt ist oder hinausführt. */
    onDateiUmbenennen: (String, String) -> Boolean,
    /** Stellt eine Frage zu einem markierten Abschnitt. */
    frage: (frage: String, selection: String) -> Unit,
    /**
     * Baut das aktive Projekt. `null`, wenn hier nichts zu bauen ist.
     *
     * **Der Knopf, der bisher fehlte.** Bauen ging nur über das Gespräch — erst eine
     * Einordnung, dann ein Werkzeugaufruf, dann der Bau. Eine halbe Minute Vorlauf für eine
     * Handlung, die gar keine Entscheidung enthält.
     */
    onBauen: (suspend () -> String)?,
    /**
     * Die zuletzt gebaute APK, falls es eine gibt.
     *
     * Ohne diesen Knopf läge das Ergebnis eines minutenlangen Bauvorgangs in einem
     * Verzeichnis, an das man ohne Dateimanager nicht herankommt.
     */
    gebauteApk: java.io.File?,
    onInstallieren: (java.io.File) -> Unit,
    onZurueck: () -> Unit,
) {
    var offen by remember { mutableStateOf<String?>(null) }
    var inhalt by remember { mutableStateOf(TextFieldValue("")) }
    var geaendert by remember { mutableStateOf(false) }
    var frageText by remember { mutableStateOf("") }
    var zeigeProjekte by remember { mutableStateOf(false) }
    var baubericht by remember { mutableStateOf<String?>(null) }
    var bautGerade by remember { mutableStateOf(false) }
    val bereich = rememberCoroutineScope()

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
            Column(modifier = Modifier.weight(1f)) {
                // Der Projektname ist der Titel und zugleich der Knopf zur Liste. Ohne diese
                // Anzeige wüsste niemand, in welchem der Projekte die Dateien darunter
                // liegen — und genau das war die Klage: keine Ordnung im Projektbereich.
                TextButton(
                    onClick = { zeigeProjekte = !zeigeProjekte },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        aktivesProjekt ?: "Kein Projekt",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (zeigeProjekte) "  ▴" else "  ▾  (${projekte.size})",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
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

        if (zeigeProjekte) {
            Projektliste(
                projekte = projekte,
                aktiv = aktivesProjekt,
                onWaehlen = { name ->
                    onProjektWaehlen(name)
                    // Die offene Datei gehört zum alten Projekt. Sie stehen zu lassen hieße,
                    // dass „Speichern" sie in ein Projekt schreibt, in dem sie nie war.
                    offen = null
                    baubericht = null
                    zeigeProjekte = false
                },
                onAnlegen = { wunsch ->
                    onProjektAnlegen(wunsch)?.also {
                        offen = null
                        baubericht = null
                        zeigeProjekte = false
                    }
                },
                onWegraeumen = { name ->
                    onProjektWegraeumen(name)
                    offen = null
                    baubericht = null
                },
            )
            return@Column
        }

        val geoeffnet = offen
        if (geoeffnet == null) {
            if (onBauen != null || baubericht != null) {
                Baubereich(
                    kannBauen = onBauen != null && !bautGerade,
                    laeuft = bautGerade,
                    bericht = baubericht,
                    onBauen = {
                        bautGerade = true
                        baubericht = null
                        bereich.launch {
                            baubericht = onBauen?.invoke()
                            bautGerade = false
                        }
                    },
                )
                HorizontalDivider()
            }

            Dateiliste(
                dateien = dateien,
                onOeffnen = { pfad ->
                    offen = pfad
                    inhalt = TextFieldValue(lies(pfad).orEmpty())
                    geaendert = false
                    frageText = ""
                },
                onWegraeumen = onDateiWegraeumen,
                onUmbenennen = onDateiUmbenennen,
            )
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

/**
 * Die Projekte, mit Wechseln, Anlegen und Papierkorb.
 *
 * **Warum das hier hingehört und nicht nur ins Gespräch.** Aufräumen ist keine Aufgabe für ein
 * Sprachmodell: Es kostet eine halbe Minute, kann den Namen verwechseln, und der Nutzer sieht
 * die Liste ohnehin vor sich. Über das Gespräch bleibt es trotzdem möglich — für den Fall, dass
 * jemand gerade spricht statt tippt.
 *
 * **Der Papierkorb steht ausdrücklich dran.** Wer „Löschen" liest, zögert; wer „In den
 * Papierkorb" liest, räumt auf. Der Unterschied entscheidet, ob der Bereich ordentlich bleibt.
 */
@Composable
private fun Projektliste(
    projekte: List<String>,
    aktiv: String?,
    onWaehlen: (String) -> Unit,
    onAnlegen: (String) -> String?,
    onWegraeumen: (String) -> Unit,
) {
    var neuerName by remember { mutableStateOf("") }
    // Welches Projekt gerade nachfragt, ob es wirklich weg soll. Ein Papierkorb-Knopf ohne
    // Rückfrage direkt neben dem Öffnen-Knopf wird irgendwann versehentlich getroffen.
    var fragtNach by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = neuerName,
                onValueChange = { neuerName = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Neues Projekt") },
                placeholder = { Text("etwa zaehler") },
            )
            Button(
                onClick = { if (onAnlegen(neuerName.trim()) != null) neuerName = "" },
                enabled = neuerName.isNotBlank(),
            ) { Text("Anlegen") }
        }
        HorizontalDivider()

        if (projekte.isEmpty()) {
            Text(
                "Noch kein Projekt. Leg eines an, oder bitte Neon im Chat um eine App — " +
                    "dann entsteht eines mit dem passenden Namen.",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(projekte) { name ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { onWaehlen(name) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            if (name == aktiv) "$name  ●" else name,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium
                                .copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                    if (fragtNach == name) {
                        TextButton(onClick = {
                            onWegraeumen(name)
                            fragtNach = null
                        }) { Text("Wirklich?") }
                        TextButton(onClick = { fragtNach = null }) { Text("Nein") }
                    } else {
                        TextButton(onClick = { fragtNach = name }) { Text("Papierkorb") }
                    }
                }
            }
        }
    }
}

/**
 * Der Bau-Knopf im Projektordner — und was dabei herauskam.
 *
 * Der Bericht bleibt stehen, bis das nächste Mal gebaut oder das Projekt gewechselt wird. Bei
 * einem Vorgang, der eine Minute dauert und an fünf Stellen scheitern kann, ist die Meldung
 * das eigentliche Ergebnis: „aapt2: error: resource not found" in Zeile 12 sagt, was zu tun
 * ist — ein verschwundener Hinweis sagt gar nichts.
 */
@Composable
private fun Baubereich(
    kannBauen: Boolean,
    laeuft: Boolean,
    bericht: String?,
    onBauen: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onBauen, enabled = kannBauen) { Text("App bauen") }
            if (laeuft) {
                Text(
                    "Der Kotlin-Compiler braucht auf diesem Gerät eine Minute.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        bericht?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.labelSmall
                    .copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

/**
 * Die Dateien des Projekts, mit Umbenennen und Papierkorb.
 *
 * **Beides fehlte vollständig.** Wer eine Datei an die richtige Stelle legen wollte, musste sie
 * neu schreiben und die alte stehen lassen — und dann las das Modell beim nächsten Auflisten
 * die Leiche mit.
 */
@Composable
private fun Dateiliste(
    dateien: List<String>,
    onOeffnen: (String) -> Unit,
    onWegraeumen: (String) -> Boolean,
    onUmbenennen: (String, String) -> Boolean,
) {
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

    // Welche Datei gerade umbenannt wird, und worauf. Nur eine gleichzeitig: Zwei offene
    // Eingabefelder in einer Liste sind eine Einladung, die falsche zu bestätigen.
    var umbenennt by remember { mutableStateOf<String?>(null) }
    var zielname by remember { mutableStateOf("") }
    var meldung by remember { mutableStateOf<String?>(null) }

    meldung?.let {
        Text(
            it,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(dateien) { pfad ->
            if (umbenennt == pfad) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = zielname,
                        onValueChange = { zielname = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Neuer Pfad") },
                    )
                    TextButton(
                        onClick = {
                            val ziel = zielname.trim()
                            meldung = if (ziel.isNotBlank() && onUmbenennen(pfad, ziel)) {
                                umbenennt = null
                                null
                            } else {
                                "„$ziel\" ging nicht — belegt, leer, oder außerhalb des Projekts."
                            }
                        },
                    ) { Text("Übernehmen") }
                    TextButton(onClick = { umbenennt = null; meldung = null }) { Text("Zurück") }
                }
                return@items
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onOeffnen(pfad) }, modifier = Modifier.weight(1f)) {
                    Text(
                        pfad,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium
                            .copy(fontFamily = FontFamily.Monospace),
                    )
                }
                TextButton(onClick = { umbenennt = pfad; zielname = pfad; meldung = null }) {
                    Text("Umbenennen")
                }
                TextButton(onClick = {
                    meldung = if (onWegraeumen(pfad)) "$pfad liegt im Papierkorb."
                    else "$pfad ließ sich nicht wegräumen."
                }) { Text("Papierkorb") }
            }
        }
    }
}
