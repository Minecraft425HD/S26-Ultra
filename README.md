# Neon

Ein Sprachassistent für das Samsung Galaxy S26 Ultra, der **mehrere lokale Sprachmodelle**
verwaltet und für jede Frage das passende auswählt. Alles läuft auf dem Gerät.

Die Grundidee: Ein Assistent, der jede Anfrage an dasselbe große Modell schickt,
verschwendet Akku. Neon ordnet erst ein, was gefragt wurde, und wählt danach das kleinste
Modell, das der Aufgabe gewachsen ist — für einen großen Teil der Alltagsbefehle gar keines.

## Wie die Modellauswahl funktioniert

Vier Stufen, jede teurer als die vorherige. Es wird nur so weit eskaliert, wie nötig.

| Stufe | Verfahren | Kosten | Wofür |
|---|---|---|---|
| 0 | Feste deutsche Grammatik | keine Inferenz | „Timer fünf Minuten", „Licht aus", „wie spät" |
| 1 | Ähnlichkeitssuche über gelernte Beispiele | ~10 ms | die große Mehrheit der Fragen |
| 2 | Router-Modell (0.6B) mit erzwungenem JSON | ~100–300 ms | wenn Stufe 1 unsicher ist |
| 3 | Punktebewertung der Modelle | — | wägt Qualität, Ladezeit und Energie ab |
| 4 | Eskalation | nur im Zweifel | erst klein antworten, groß nachlegen |

Stufe 0 ist der größte Einzelhebel: Jeder Treffer dort vermeidet eine Modellinferenz
vollständig.

Stufe 1 kommt **ohne Modelldatei** aus. Sie misst Ähnlichkeit über gehashte Zeichen-n-Gramme
und Wörter statt über ein neuronales Einbettungsmodell — das erfasst keine Bedeutung, dafür
läuft es vom ersten Start an, ohne Download und ohne Tokenizer. Gemessen an
fünfundzwanzig Äußerungen, die nicht in der Startmenge stehen: 88 Prozent richtig
einsortiert, ein selbstbewusster Fehlgriff, zwei Fälle sauber an Stufe 2 weitergereicht.
Die Grenze zeigt derselbe Test: „wer hat die Glühbirne **erfunden**" landet falsch, weil
„erfunden" und „entwickelt" sich keine Buchstaben teilen. Ein neuronaler Einbetter löst das
und kann über dieselbe Schnittstelle an die Stelle treten.

Die wichtigste Regel in Stufe 3 ist die **Hysterese**. Ein bereits geladenes Modell, das die
Aufgabe bewältigt, schlägt ein geringfügig besseres, das erst mehrere Sekunden lang von der
Platte gelesen werden müsste — ein vermiedener Modellwechsel spart mehr Energie, als die
etwas bessere Antwort wert ist. Bei einem klaren Qualitätsvorsprung, etwa dem Code-Spezialisten
bei einer Programmierfrage, gewinnt trotzdem der Spezialist.

Der Router **lernt mit**: Jeder Durchgang wird zurückgehalten und erst bewertet, wenn die
nächste Frage kommt. Wer innerhalb von zwanzig Sekunden fast dasselbe noch einmal fragt, war
unzufrieden; wer das Thema wechselt, offenbar nicht. Daraus wächst die Beispielmenge für
Stufe 1, ganz ohne Trainings-Infrastruktur.

Dazwischen liegt bewusst ein Graubereich, in dem **nichts** gelernt wird. Die beiden Fehler
wiegen nämlich unterschiedlich schwer: Eine übersehene Umformulierung würde eine
nachweislich schlechte Route als gutes Beispiel abspeichern und den Klassifikator aktiv
verschlechtern — ein übersehenes Lob kostet nur ein Beispiel, das man ohnehin nicht
gebraucht hätte. Die Grenzen liegen in der gemessenen Lücke: Umformulierungen erreichen
0,47 bis 0,85 Ähnlichkeit, Themenwechsel unter 0,05.

## Das Modell-Ensemble

| Rolle | Kandidat (Q4) | Größe | Residenz |
|---|---|---|---|
| Router | Qwen3 0.6B | ~0,4 GB | dauerhaft |
| Embeddings | EmbeddingGemma 300M | ~0,2 GB | dauerhaft |
| Alltag | Qwen3 4B Instruct | ~2,5 GB | Standard |
| Denker | Qwen3 8B | ~5,0 GB | auf Abruf |
| Code | Qwen3 Coder 7B | ~4,5 GB | auf Abruf |
| Bild | Gemma 3n E4B | ~3,0 GB | auf Abruf |

Von 16 GB RAM belegt One UI etwa 6 GB; Neon hält höchstens 5 GB für Modelle belegt. Bei
1 TB Speicher liegen alle gleichzeitig auf der Platte — gewechselt wird nur, was im RAM ist.
Weil die Gewichte per `mmap` geladen werden und Linux zuletzt genutzte Seiten im Cache hält,
kostet der Rückwechsel auf ein kürzlich benutztes Modell fast nichts.

> Diese Aufstellung ist ein Startpunkt, kein Ergebnis. Welche Modelle und Quantisierungen
> auf dem S26 Ultra wirklich das beste Verhältnis aus Geschwindigkeit, Speicher und
> deutscher Sprachqualität liefern, muss auf dem Gerät gemessen werden.

## Akku-Strategie

Der Dauerlauscher ist der einzige echte Dauerverbraucher. Deshalb eine Kaskade, in der die
teuren Stufen fast nie laufen:

1. **Energie-Gatter** — rechnet nur die Lautstärke aus, praktisch kostenlos. Verwirft im
   stillen Raum fast alles. Der Schwellwert folgt dem Grundrauschen, damit er in der Wohnung
   und im Zug gleichermaßen funktioniert.
2. **Silero-VAD** — läuft nur bei Geräusch. Sortiert Türenschlagen und Musik aus.
3. **Weckwortmodell** — läuft nur bei erkannter Sprache.
4. **Spracherkennung** — startet erst nach „Neon". Dauerhafte Spracherkennung wäre der
   klassische Akkufresser und wird bewusst vermieden.

Dazu die Energie-Policy: bei Hitze oder unter 20 % Akku nur kleine Modelle, am Ladegerät
alles erlaubt.

Der Diagnose-Screen in der App zeigt die Durchlassquoten der Audiostufen und den Anteil der
Anfragen, die ganz ohne Sprachmodell beantwortet wurden. Beides sind die Größen, an denen
sich ablesen lässt, ob Neon tatsächlich sparsam arbeitet — statt es nur zu behaupten.

## Aufbau

```
core/router/      Die gesamte Entscheidungslogik. Reines Kotlin, kein Android.
core/audio/       Ringpuffer, Energie-Gatter, VAD, Weckwort, Hörschleife.
core/speech/      Spracherkennung und -ausgabe.
core/inference/   InferenceEngine, Modell-Lebenszyklus, llama.cpp-Anbindung.
core/memory/      Room-Datenbank, Vektorsuche, Langzeitgedächtnis.
core/tools/       Werkzeug-Framework und Android-Aktionen.
core/platform/    Akku-, Hitze- und Netzzustand; verschlüsselte Ablage.
service/          Vordergrunddienst, Zustandsautomat, Schnellzugriff-Kachel.
app/              Oberfläche und Verdrahtung.
```

Dass `core/router` bewusst ohne Android-Abhängigkeiten auskommt, ist kein Selbstzweck: So
läuft die gesamte Entscheidungslogik als gewöhnliche JVM-Unit-Tests, ohne Emulator und ohne
Gerät.

Vorgesehen, aber noch nicht umgesetzt: die Inferenz in einem eigenen Prozess
(`android:process` plus AIDL), damit ein Modell, das den Speicher sprengt, nicht die
Hörschleife mitreißt. Derzeit läuft alles in einem Prozess — der Ladefehler wird zwar
sauber gemeldet, ein echter Speicherüberlauf würde aber die ganze App treffen.

## Bauen

```bash
./gradlew :app:assembleDebug          # APK
./gradlew test testDebugUnitTest      # alle Tests
./gradlew :core:router:test           # nur die Router-Logik, ohne Android-SDK
```

Ein Android-SDK mit API 36 wird gebraucht (`local.properties` mit `sdk.dir=…`), ein NDK nur
für den nativen Teil.

### Modelle besorgen

```bash
./scripts/fetch-models.sh wakeword    # Weckwort- und VAD-Modelle, wenige MB
./scripts/fetch-models.sh llm         # Anleitung für die Sprachmodelle
```

Das Weckwortmodell für „Neon" muss selbst trainiert werden — dafür gibt es niemanden, der
es vorher gebaut hätte. `fetch-models.sh` erklärt den Weg über openWakeWord. **Empfehlung:**
zusätzlich „Hey Neon" trainieren. Zwei Silben allein lösen erfahrungsgemäß häufiger falsch
aus, und „Neonlicht" oder „Neonfarbe" gehören unbedingt als Gegenbeispiele ins Training.

Fehlt das Weckwortmodell, bleibt Neon voll bedienbar: Der Knopf **„Sprechen"** startet die
Aufnahme direkt und nimmt den Dienst mit hoch, falls er noch nicht läuft. Was dann fehlt,
ist ausschließlich das freihändige Ansprechen.

### Lokale Inferenz

```bash
./scripts/fetch-native-deps.sh
./gradlew :app:assembleDebug -Pneon.buildNative=true
```

Ohne diesen Schritt baut die App normal; nur die Antwortgenerierung meldet dann, dass die
native Bibliothek fehlt.

## Warum es keinen Autostart nach dem Neustart gibt

Android 16 verbietet es, aus `BOOT_COMPLETED` heraus einen Mikrofondienst zu starten — und
das ist richtig so. Neon legt nach einem Neustart stattdessen eine Benachrichtigung an; ein
Tippen darauf gilt als Nutzerhandlung und erlaubt den Start. Androids stromsparende
Hotword-Schnittstelle (`AlwaysOnHotwordDetector`) steht seit Android 12 nur noch System-Apps
offen, ist für eine sideloadbare App also keine Option.

## Stand

### Was nach dem Installieren sofort funktioniert

Ohne Download, ohne Training, ohne NDK:

- **„Sprechen" drücken und reden.** Aufnahme, Sprachendpunkt-Erkennung, Spracherkennung
  über Androids lokalen Erkenner, Sprachausgabe.
- **Die Regelstufe.** „Timer fünf Minuten", „Wecker auf halb sieben", „Taschenlampe an",
  „öffne Spotify", „wie spät ist es" — beantwortet und ausgeführt, ganz ohne Sprachmodell.
- **Der Router.** Ordnet ein, wählt ein Modell und begründet die Wahl im Diagnose-Screen.
- **Der Diagnose-Screen.** Durchlassquoten der Audiostufen, Anteil der Anfragen ohne
  Sprachmodell, Latenzen je Modell.

### Was noch nicht funktioniert

- **Freihändiges Ansprechen.** Braucht ein trainiertes Weckwortmodell für „Neon" — das kann
  niemand vorher gebaut haben. Bis dahin: Knopf statt Zuruf.
- **Antworten auf echte Fragen.** Braucht die llama.cpp-Bibliothek *und* die GGUF-Dateien.
  Fehlt eines von beidem, sagt Neon das ausdrücklich, statt stumm zu bleiben.
- **Dauerhafte Erinnerungen.** Die Room-Tabellen stehen, angeschlossen ist bisher nur der
  Sitzungsspeicher.
- **Prozesstrennung, NPU-Pfad, Messung auf dem Gerät.**

**257 Unit-Tests**, `:app:assembleDebug` baut.

Die JNI-Brücke unter `core/inference/src/main/cpp/` ist die einzige Datei im Projekt, die
noch nie kompiliert wurde — sie braucht NDK und llama.cpp-Quellen, die beide nicht im
Repository liegen. llama.cpp ändert seine API regelmäßig; beim ersten Bauen ist mit
Anpassungen zu rechnen.

## Lizenz

GPL-3.0
