#!/usr/bin/env python3
"""Trainiert das Weckwortmodell für "Hey Neon".

Aufruf:
    python train.py                 # Standard: "hey neon"
    python train.py --phrase "neon" # anderes Weckwort

Ergebnis ist eine ONNX-Datei, die nach app/src/main/assets/wakeword/ gehört.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

HERE = Path(__file__).parent

# Diese Wörter entscheiden über die Alltagstauglichkeit.
#
# Ohne sie springt das Modell auf jedes Neonlicht an, und "Hey" allein genügt ihm
# ebenfalls. Genau daran scheitern die meisten selbstgebauten Weckwörter — nicht am
# Training der positiven Beispiele, sondern an zu wenigen und zu beliebigen negativen.
HARD_NEGATIVES = [
    # Wörter, die das Weckwort enthalten
    "neonlicht",
    "neonfarbe",
    "neonröhre",
    "neonschild",
    "neongrün",
    "neonreklame",
    # Nur eine der beiden Silben
    "hey",
    "neon",
    "he",
    "nein",
    "neu",
    # Andere Assistenten — die sagt man im selben Tonfall
    "hey google",
    "hey siri",
    "hey alexa",
    "ok google",
    # Ähnlich klingende Wendungen
    "hey leon",
    "hey theo",
    "hey nina",
    "na dann",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phrase", default="hey neon", help="Das Weckwort")
    parser.add_argument(
        "--positives", type=int, default=6000,
        help="Anzahl synthetischer Aussprachen des Weckworts",
    )
    parser.add_argument(
        "--negatives", type=int, default=12000,
        help="Anzahl Gegenbeispiele. Deutlich mehr als positive — Fehlauslösungen "
             "stören im Alltag weit mehr als eine gelegentlich verpasste Ansprache.",
    )
    parser.add_argument(
        "--recordings", type=Path, default=HERE / "recordings",
        help="Ordner mit eigenen WAV-Aufnahmen des Weckworts (optional)",
    )
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--steps", type=int, default=8000)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    slug = args.phrase.replace(" ", "_")
    output = args.output or HERE / f"{slug}.onnx"

    try:
        from openwakeword.train import train_model
        from openwakeword.data import generate_samples
    except ImportError:
        print(
            "Die openWakeWord-Trainingsmodule fehlen.\n"
            "  pip install -r requirements.txt\n\n"
            "Falls die Installation scheitert, geht es auch ohne eigene Umgebung über\n"
            "https://openwakeword.com/train — dort dasselbe Weckwort und dieselben\n"
            "Gegenbeispiele eintragen, die weiter unten stehen.",
            file=sys.stderr,
        )
        print("\nGegenbeispiele:", ", ".join(HARD_NEGATIVES), file=sys.stderr)
        return 1

    print(f"Weckwort: {args.phrase!r}")
    print(f"Positive Beispiele:   {args.positives}")
    print(f"Gegenbeispiele:       {args.negatives} ({len(HARD_NEGATIVES)} Wendungen)")

    own = sorted(args.recordings.glob("*.wav")) if args.recordings.is_dir() else []
    if own:
        print(f"Eigene Aufnahmen:     {len(own)} — das hebt die Trefferquote spürbar")
    else:
        print(
            "Eigene Aufnahmen:     keine\n"
            "  Empfehlung: 30 Mal 'Hey Neon' aufnehmen und als WAV nach\n"
            f"  {args.recordings} legen. Das ist der wirksamste einzelne Schritt."
        )

    print("\nErzeuge Sprachbeispiele …")
    positives = generate_samples(
        text=[args.phrase] * args.positives,
        max_samples=args.positives,
        batch_size=64,
    )

    print("Erzeuge Gegenbeispiele …")
    per_phrase = max(1, args.negatives // len(HARD_NEGATIVES))
    negative_texts = [phrase for phrase in HARD_NEGATIVES for _ in range(per_phrase)]
    negatives = generate_samples(
        text=negative_texts,
        max_samples=args.negatives,
        batch_size=64,
    )

    print("Trainiere …")
    train_model(
        positive_samples=positives,
        negative_samples=negatives,
        custom_positive_clips=[str(path) for path in own] or None,
        steps=args.steps,
        output_path=str(output),
    )

    print(f"\nFertig: {output}")
    print(
        "\nNächster Schritt:\n"
        f"  cp {output} ../../app/src/main/assets/wakeword/\n"
        "\nDanach APK neu bauen lassen und installieren. Springt das Modell zu oft oder zu\n"
        "selten an, zuerst den Schwellwert im Diagnose-Screen verschieben — nicht neu\n"
        "trainieren."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
