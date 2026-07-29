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

## Wie die Inferenz läuft

Neon liefert das fertige `llama-server`-Programm mit (13 MB, arm64) und spricht es über HTTP
auf `localhost` an. Das ist keine Notlösung, sondern löst vier Dinge auf einmal:

- **Kein eigener C++-Code.** Frühere Fassungen hatten eine handgeschriebene JNI-Brücke, die
  nie kompiliert wurde. llama.cpp ändert seine API regelmäßig; ein gepflegtes Programm zu
  benutzen ist verlässlicher, als eine Brücke hinterherzupflegen.
- **GBNF-Grammatik ist dabei.** Sie erzwingt gültiges JSON bei der Einordnung in Stufe 2 und
  bei Werkzeugaufrufen. Auch ein kleines Modell kann damit nicht aus der Form fallen.
- **Eigener Prozess.** Ein Modell, das den Speicher sprengt, reißt die Hörschleife nicht mit.
- **Prüfbar ohne Telefon.** `LlamaServerIntegrationTest` startet einen echten Server mit
  einem kleinen Modell und prüft Streaming, Grammatik und Abbruch. Genau dieser Test hat zwei
  Fehler gefunden, die sonst ausgeliefert worden wären: eine über mehrere Zeilen umgebrochene
  Grammatik (der Server lieferte daraufhin wortlos nichts) und eine JSON-Null, die als
  Zeichenkette gelesen wurde und jeder gesprochenen Antwort ein „null" vorangestellt hätte.

Je Serverlauf wird genau ein Modell bedient; beim Wechsel startet der Prozess neu. llama.cpp
kann zwar mehrere Modelle über einen Router-Modus verwalten, schaltet den auf Android aber
bewusst ab, weil er Kindprozesse braucht. Die Hysterese in der Auswahl-Policy vermeidet
solche Wechsel ohnehin, wo es geht.

Neu bauen — nur nötig, um llama.cpp zu aktualisieren:

```bash
ANDROID_NDK=/pfad/zum/ndk ./scripts/build-llama-server.sh
```

### 16-KB-Speicherseiten

Jedes Gerät, das mit Android 16 und mindestens 8 GB Arbeitsspeicher ausgeliefert wird — das
S26 Ultra also —, arbeitet mit 16-KB-Speicherseiten statt der früheren 4 KB. Der
Android-Linker weist jede ELF-Datei ab, deren `LOAD`-Segmente gröber ausgerichtet sind: Das
Programm startet nicht, die Bibliothek lädt nicht.

Das ist deshalb heimtückisch, weil davon außerhalb des Telefons nichts auffällt. Die APK
baut, die Tests laufen, die Datei sieht normal aus — und auf dem Gerät antwortet Neon
einfach nie. Genau so lag es hier: `llama-server` war auf 4 KB ausgerichtet, und
ONNX Runtime 1.20.0 lieferte die große Bibliothek auf 16 KB, die JNI-Brücke daneben aber auf
4 KB.

Zwei Vorkehrungen halten das jetzt in Ordnung. Das Bauskript richtet ausdrücklich auf 16 KB
aus (`-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`; NDK r27 tut das nur auf Zuruf, erst r28 von
selbst) und misst am Ende nach. Und die CI prüft **jede** `lib/arm64-v8a/*.so` der fertigen
APK, nicht nur die selbstgebaute — damit fällt derselbe Fehler auch bei einer künftigen
Abhängigkeit vor der Auslieferung auf statt danach.

## Wenn etwas schiefgeht

Auf einem Telefon ohne Entwicklungsumgebung ist ein Fehler sonst eine Sackgasse: Android
zeigt „App wurde beendet" und sonst nichts, der Logcat ist ohne `adb` nicht erreichbar.
Deshalb zwei Wege:

- **Absturz beim Start.** Beim nächsten Öffnen zeigt Neon statt der Oberfläche den
  Fehlerbericht mit vollständiger Aufrufliste und einem **Teilen**-Knopf. Der Handler dafür
  wird als allererste Anweisung installiert, noch vor allem anderen.
- **Fehler im Betrieb.** *Diagnose → Protokoll* zeigt die letzten Zeilen, ebenfalls mit
  **Teilen**. Die Ausgabe von `llama-server` steht vollständig darin.

Der Aufbau der Anwendung ist gekapselt: Scheitert ein Bestandteil, startet Neon trotzdem und
zeigt den Grund, statt zu sterben. Sprachausgabe, Spracherkennung und Datenbank entstehen
erst bei der ersten Benutzung — ein Assistent, der nicht startet, weil die Sprachausgabe
hakt, wäre schlecht gebaut.

Abgesichert ist das durch `app/src/test/kotlin/de/neon/app/StartupTest.kt`: Er erzeugt den
Objektgraphen, die Anwendung und die Hauptansicht unter Robolectric — mit dem einfachsten und
zugleich wichtigsten Anspruch überhaupt, nämlich dass die App starten kann.

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

## In Betrieb nehmen

Es wird **kein** PC mit Entwicklungsumgebung gebraucht — weder adb noch Gradle noch NDK.

### 1. APK holen

Auf dem Telefon diesen Link öffnen und antippen:

**https://github.com/Minecraft425HD/S26-Ultra/releases/latest/download/neon.apk**

Er zeigt immer auf die neueste gebaute Fassung. Kein GitHub-Login, kein ZIP, kein
Ablaufdatum. Android fragt einmal nach der Erlaubnis, Apps aus dieser Quelle zu
installieren. Danach Mikrofon und Benachrichtigungen erlauben.

Alle Fassungen tragen denselben Signierschlüssel, deshalb lässt sich eine neuere **über**
eine bestehende Installation legen — das importierte Sprachmodell, die gelernten Beispiele
und das Gedächtnis bleiben erhalten.

Ab hier funktionieren bereits: der Knopf **„Sprechen"**, die Regelstufe (Timer, Wecker,
Taschenlampe, App-Start), Spracherkennung und Sprachausgabe.

### 2. Sprachmodell übernehmen

Qwen3 4B Instruct als GGUF herunterladen (Q4_K_M, rund 2,5 GB), per USB auf das Telefon
kopieren, dann in Neon unter *Diagnose → Modelle* auf **Importieren** tippen und die Datei
auswählen. Neon prüft die Kennbytes und lehnt versehentlich heruntergeladene Fehlerseiten ab.

Danach beantwortet Neon echte Fragen.

### 3. Weckwort trainieren

```bash
./scripts/fetch-models.sh          # VAD und die gemeinsamen openWakeWord-Stufen
cd tools/train-wakeword && pip install -r requirements.txt && python train.py
cp hey_neon.onnx ../../app/src/main/assets/wakeword/
```

Rund 30 Minuten auf einer NVIDIA-Karte. Danach hört Neon freihändig auf **„Hey Neon"**.
Details in `tools/train-wakeword/README.md` — besonders der Abschnitt über eigene
Aufnahmen lohnt sich.

Springt das Weckwort zu oft oder zu selten an: erst den Regler im Diagnose-Screen
verschieben, nicht neu trainieren.

## Selbst bauen

```bash
./gradlew :app:assembleDebug          # APK
./gradlew test testDebugUnitTest      # alle Tests
./gradlew :core:router:test           # nur die Router-Logik, ohne Android-SDK
```

Ein Android-SDK mit API 36 wird gebraucht (`local.properties` mit `sdk.dir=…`). Ein NDK nur,
wenn `llama-server` neu gebaut werden soll.

Der Integrationstest gegen einen echten llama-server überspringt sich selbst, wenn Server
oder Testmodell fehlen, und sagt das ausdrücklich in der Ausgabe. Mit beidem:

```bash
NEON_TEST_SERVER=/pfad/zu/llama-server NEON_TEST_MODEL=/pfad/zu/klein.gguf \
  ./gradlew :core:inference:testDebugUnitTest
```

## Warum es keinen Autostart nach dem Neustart gibt

Android 16 verbietet es, aus `BOOT_COMPLETED` heraus einen Mikrofondienst zu starten — und
das ist richtig so. Neon legt nach einem Neustart stattdessen eine Benachrichtigung an; ein
Tippen darauf gilt als Nutzerhandlung und erlaubt den Start. Androids stromsparende
Hotword-Schnittstelle (`AlwaysOnHotwordDetector`) steht seit Android 12 nur noch System-Apps
offen, ist für eine sideloadbare App also keine Option.

## Stand

### Nach dem Installieren sofort

Ohne Download, ohne Training: „Sprechen" drücken und reden. Die Regelstufe führt Timer,
Wecker, Taschenlampe und App-Start aus, der Router ordnet ein und begründet seine Wahl im
Diagnose-Screen, Neon antwortet gesprochen.

### Nach Schritt 2 und 3

Echte Antworten vom Sprachmodell, freihändiges Ansprechen mit „Hey Neon".

### Was weiterhin offen ist

- **Messung auf dem Gerät.** Wie schnell Qwen3 4B auf dem Snapdragon 8 Elite Gen 5 wirklich
  ist und was der Dauerlauscher kostet, steht noch aus. Bis dahin sind alle Zahlen zu
  Geschwindigkeit und Akku in diesem Projekt begründete Schätzungen.
- **Echte Einbettungen.** Stufe 1 arbeitet lexikalisch. llama-server bringt einen
  `/embedding`-Endpunkt mit; sobald ein Einbettungsmodell dazukommt, ist der Wechsel eine
  Zeile.
- **NPU-Pfad.** llama.cpp läuft auf CPU und GPU. Der Qualcomm-Beschleuniger bliebe ein
  weiterer Sprung bei der Akkulaufzeit.

**439 Unit-Tests**, `:app:assembleRelease` baut, `llama-server` für arm64 ist gebaut,
ausgerichtet und gegen ein echtes Modell erprobt.

### Was die Tests hier grundsätzlich nicht sehen

Die Prüfläufe laufen auf der JVM, auch die mit Robolectric. Wo Android eine andere
Implementierung mitbringt als das OpenJDK, kann ein Test hier grün sein und die App auf dem
Telefon trotzdem sterben. Zwei Stellen, an denen genau das passiert ist:

- **Reguläre Ausdrücke.** Hinter `java.util.regex` steht auf Android ICU, auf der JVM das
  OpenJDK. Das eingebettete Unicode-Flag kennt nur letzteres; Neon starb daran in einem
  Klasseninitialisierer, bevor irgendetwas zu sehen war. Deshalb geht jedes Muster über
  `PortableRegex`, das die Wortgrenze ausschreibt statt sie einer Voreinstellung zu
  überlassen — und ein Test durchsucht die Quellen nach den bekannten Eigenheiten.
- **Native Bibliotheken.** Siehe den Abschnitt zu 16-KB-Speicherseiten weiter oben. Geprüft
  wird in der CI an der fertigen APK.

Beide Male ist der Schutz derselbe: Nicht das Verhalten nachstellen, sondern das
Erzeugnis nachmessen.

## Lizenz

GPL-3.0
