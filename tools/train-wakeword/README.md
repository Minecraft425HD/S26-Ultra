# Weckwort „Hey Neon" trainieren

Rund 30 Minuten auf einer NVIDIA-Karte, etwa eine Stunde auf einer CPU. Alternativ läuft
alles unverändert in Google Colab.

Am Ende liegt eine Datei `hey_neon.onnx` (wenige hundert Kilobyte), die nach
`app/src/main/assets/wakeword/` gehört.

## Warum „Hey Neon" und nicht „Neon"

Zweisilbige Weckwörter lösen deutlich häufiger falsch aus. „Neon" steckt außerdem in
Wörtern, die im Alltag wirklich vorkommen — Neonlicht, Neonfarbe, Neonröhre. Ein Assistent,
der bei jedem davon anspringt, wird nach zwei Tagen abgeschaltet.

## Ablauf

```bash
python -m venv .venv && source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
python train.py
```

`train.py` macht vier Dinge:

1. **Positive Beispiele erzeugen.** Mehrere tausend Aussprachen von „Hey Neon" mit
   wechselnden Stimmen, Geschwindigkeiten, Tonhöhen und Raumhall.
2. **Gegenbeispiele erzeugen.** Das ist der Teil, der über die Alltagstauglichkeit
   entscheidet, und er wird meistens zu knapp bemessen. Enthalten sind ausdrücklich:
   `Neonlicht`, `Neonfarbe`, `Neonröhre`, `Neonschild`, `Hey Google`, `Hey Siri`,
   `Hey Alexa` sowie `Hey` und `Neon` **einzeln** — Letzteres ist wichtig, sonst genügt
   dem Modell später eine der beiden Silben.
3. **Hintergrundgeräusche mischen.** Ohne sie funktioniert das Modell nur in einem stillen
   Zimmer.
4. **Trainieren und als ONNX ablegen.**

## Eigene Stimme dazunehmen (empfohlen)

Nimm dreißig Mal „Hey Neon" auf — unterschiedlich laut, aus verschiedenen Abständen, ruhig
auch mal genuschelt — und lege die WAV-Dateien in `recordings/`. `train.py` findet sie von
selbst. Das hebt die Erkennungsrate für deine Stimme spürbar und ist der wirksamste einzelne
Schritt.

Aufnehmen geht mit jedem Telefon; 16 kHz Mono reicht völlig.

## Danach

```
cp hey_neon.onnx ../../app/src/main/assets/wakeword/
```

Dann die APK neu bauen lassen (der GitHub-Ablauf erledigt das bei jedem Push) und
installieren.

## Wenn es zu oft oder zu selten anspringt

Nicht neu trainieren — erst den Schwellwert verschieben. Der steht im Diagnose-Screen der
App und wirkt sofort:

- **springt zu oft an** → Schwellwert erhöhen (Richtung 0,9)
- **hört dich nicht** → Schwellwert senken (Richtung 0,5)

Erst wenn beides zusammen nicht funktioniert, lohnt ein weiterer Trainingslauf — dann mit
mehr eigenen Aufnahmen und mehr Gegenbeispielen aus deiner tatsächlichen Umgebung.
