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
| Alltag, klein | Qwen3 1.7B | ~1,1 GB | Standard |
| Alltag | Qwen3 4B Instruct | ~2,5 GB | ab Komplexität 3 |
| Denker | Qwen3 8B | ~5,0 GB | auf Abruf |
| Code | Qwen3 Coder 7B | ~4,5 GB | auf Abruf |
| Bild | Gemma 3 4B + mmproj | ~3,1 GB | auf Abruf |

Das Bildmodell besteht aus **zwei** Dateien: den Gewichten und einem Projektor, der
Bildkacheln in den Raum des Sprachmodells übersetzt. Ohne die zweite startet der Server zwar,
kann aber keine Bilder ansehen — deshalb gilt ein Bildmodell erst als verfügbar, wenn beide
da sind, und deshalb zählt es mit der Summe beider Größen. Hier stand zuvor Gemma 3n, für das
es gar keinen Projektor gibt: eine Zeile, die ein Bildmodell benannte, mit dem Bilder
unmöglich waren.

Alle Modelle liegen gleichzeitig auf der Platte — gewechselt wird nur, was im
Arbeitsspeicher ist. Weil die Gewichte per `mmap` geladen werden und Linux zuletzt genutzte
Seiten im Cache hält, kostet der Rückwechsel auf ein kürzlich benutztes Modell fast nichts.

### Der Speicher wird gemessen, nicht angenommen

Hier stand: *„Von 16 GB RAM belegt One UI etwa 6 GB; Neon hält höchstens 5 GB für Modelle
belegt."* Dieselbe Zahl stand als Konstante im Code — an zwei Stellen.

**Das Testgerät hat 5,3 GB insgesamt**, davon waren 1,6 GB frei. Neon hielt fünf Gigabyte für
verfügbar, ließ das 4-B-Modell mit einem Kontextfenster von 16384 Token zu — und Androids
Low-Memory-Killer beendete den Prozess beim Laden, sechsmal hintereinander, ohne eine Zeile
Erklärung.

Der entscheidende Posten ist nicht das Modell, sondern der **Schlüssel-Wert-Speicher**: Die
Gewichte liegen als Dateiseiten im Cache und dürfen jederzeit verdrängt werden, der
Schlüssel-Wert-Speicher nicht. Bei Kontext 16384 sind das 1152 MB anonymer Speicher — aus
1600 MB verfügbaren.

Der erste Versuch, das zu beheben, war deshalb ebenfalls falsch: Wenn `MemAvailable` das
Budget für Modelle wird, fällt das 2,5 GB schwere Alltagsmodell durch, und Neon antwortete
nach **sechs Millisekunden** „Dafür bräuchte ich ein Modell, das nicht in den Speicher
passt" — ohne es überhaupt zu versuchen. **Ein Absturz gegen eine Verweigerung getauscht.**

Es sind also **zwei** Budgets, weil es zwei Arten von Speicher sind:

| Posten | Art | Grenze |
|---|---|---|
| Modellgewichte | `mmap`, Dateiseiten — verdrängbar | Anteil von `MemTotal` (60 %) |
| Schlüssel-Wert-Speicher | anonym — nicht verdrängbar | Anteil von `MemAvailable` (⅕) |
| Rechenpuffer | anonym | im Rest von `MemAvailable` |

Konkret:

- `DeviceStateProvider.weightBudget` leitet das Gewichtsbudget aus `MemTotal` ab. Auf 5,3 GB
  sind das 3,2 GB: genug für das Alltagsmodell, zu wenig für 8B und Coder 7B.
- `ProcessServerSupervisor.passendeKontextgroesse` rechnet den Schlüssel-Wert-Speicher gegen
  `MemAvailable`. Bei 1,5 GB frei ergibt das 4096 Token statt 16384 — 288 MB statt 1152.
  Der Anteil war zuerst ein Drittel, und das war zu großzügig: Bei 1,7 GB frei erlaubte er
  8192 Token mit 576 MB — vier Megabyte unter der eigenen Grenze —, und der Prozess wurde
  erschlagen. Was fehlte, sind die Rechenpuffer: ebenfalls anonym, ebenfalls nicht
  verdrängbar, für ein 4-B-Modell mehrere hundert Megabyte.
- Die Kosten je Token hängen am Modell und stehen in `ModelSpec.kvBytesPerToken`: 73728 beim
  4-B-Modell (36 Schichten), 57344 beim 1.7B (28 Schichten). Als Konstante war das richtig,
  solange es ein Modell gab.
- Der Regler in den Einstellungen ist eine **Obergrenze**, keine Zusage, und sagt daneben,
  was vom freien Speicher tatsächlich passt.

Beide falschen Fassungen dieser Zahl kamen an grünen Tests vorbei, weil die Rechnung nirgends
festgehalten war. `WeightBudgetTest` und `MemoryBudgetTest` prüfen sie jetzt in **beide**
Richtungen: Ein Budget, das alles ablehnt, verhindert jede Antwort; eines, das alles zulässt,
führt zurück zum Abschuss.

> Diese Aufstellung ist ein Startpunkt, kein Ergebnis. Welche Modelle und Quantisierungen auf
> einem gegebenen Gerät das beste Verhältnis aus Geschwindigkeit, Speicher und deutscher
> Sprachqualität liefern, muss dort gemessen werden. Auf dem Testgerät mit 6 GB RAM ist das
> 4-B-Modell die Obergrenze und bleibt zäh, weil seine Gewichte nicht im Seitencache liegen
> bleiben — gemessen 0,22 bis 1,49 Token je Sekunde, je nachdem wie warm der Cache war.
> Deshalb steht **Qwen3 1.7B** in der Aufstellung: 1,1 GB Gewichte und 235 MB Kontext passen
> gleichzeitig hinein. Wie schnell es dort wirklich ist, steht noch aus.

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
- **Abgebrochene Antwort.** Bricht der Strom mitten in der Antwort ab, meldet OkHttp
  `unexpected end of stream on http://127.0.0.1:18080/` — die Meldung sagt, *dass* die
  Gegenseite weg war, und nichts darüber, warum. Neon sieht deshalb nach, ob der
  Serverprozess in diesem Augenblick noch lebt, und unterscheidet zwei Fälle: **tot** heißt
  Speichermangel oder Absturz, und dann hilft ein kleineres Modell oder ein kleineres
  Kontextfenster; **lebt** heißt, die Verbindung hakte, während gerechnet wurde. Ins Protokoll
  geht beides mit Zahlen: Modell, Token bis dahin, Kontextgröße, freier Speicher **in diesem
  Moment** und die letzte Serverzeile. Bei einem Abschuss durch das System ist das die einzige
  Spur, die es je geben wird — `llama-server` bekommt SIGKILL und kann selbst nichts mehr
  sagen. Automatisch wiederholt wird nichts: Bei Speichermangel wäre das dasselbe Modell, derselbe
  Kontext, dasselbe Ende, nur doppelt so spät sichtbar.

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

- **Messung auf dem Gerät.** Es läuft und antwortet. Der Befehlssatz war ein echter Posten
  — gleicher Durchgang, gleicher Cache-Zustand: Prompt 5,01 → **9,28 T/s** (1,85×), Erzeugen
  0,47 → **1,49 T/s** (3,2×). Trotzdem unbenutzbar, und die Zahlen sagen warum: Der erste
  Durchgang lieferte 0,22 T/s, der zweite 1,49. Ein Faktor sieben zwischen zwei gleichen
  Aufgaben ist kein Rechenproblem, sondern der Seitencache — die 2,4 GB Gewichte bleiben auf
  einem 6-GB-Gerät nicht liegen. Deshalb **Qwen3 1.7B**: 1,1 GB Gewichte und 235 MB Kontext
  passen gleichzeitig hinein. Die dortige Geschwindigkeit ist noch nicht gemessen.
- **Frühere Fassung dieses Absatzes:**
  0,71 Token je Sekunde beim Erzeugen, 4,17 beim Verarbeiten. Die Ursache ließ sich an
  der ausgelieferten Datei nachweisen — sie enthielt keinen einzigen `sdot`-Befehl, weil
  `GGML_CPU_ARM_ARCH` im Bauskript fehlte und llama.cpp deshalb für `armv8-a` übersetzte,
  den Grundbefehlssatz von 2011. Mit `armv8.2-a+dotprod+fp16` sind es jetzt 898 solcher
  Befehle. **Wie viel das auf dem Gerät bringt, ist noch nicht gemessen**; bis dahin
  bleiben die Zahlen zu Geschwindigkeit und Akku begründete Schätzungen.
- **`i8mm`.** Der nächste Schritt beim Verarbeiten langer Prompts, bewusst noch nicht
  aktiviert: Kerne ohne diesen Befehl beenden das Programm sofort. Neon protokolliert jetzt
  die Merkmalszeile aus `/proc/cpuinfo` — daran, nicht an einer Vermutung über das Gerät,
  entscheidet sich das.
- **Echte Einbettungen.** Stufe 1 arbeitet lexikalisch. llama-server bringt einen
  `/embedding`-Endpunkt mit; sobald ein Einbettungsmodell dazukommt, ist der Wechsel eine
  Zeile.
- **NPU-Pfad.** llama.cpp läuft auf CPU und GPU. Der Qualcomm-Beschleuniger bliebe ein
  weiterer Sprung bei der Akkulaufzeit.

**615 Testläufe**, `:app:assembleRelease` baut, `llama-server` für arm64 ist gebaut,
16-KB-ausgerichtet, mit Skalarprodukt-Befehlen übersetzt und gegen ein echtes Modell
erprobt.

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
