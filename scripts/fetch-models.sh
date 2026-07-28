#!/usr/bin/env bash
#
# Holt die kleinen Modelldateien, die Neon zum Lauschen braucht.
#
# Das Sprachmodell ist bewusst nicht dabei: 2,5 GB gehören nicht in ein Skript, sondern
# per USB auf das Telefon und von dort über "Modell importieren" in die App.
#
#   ./scripts/fetch-models.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets"

log()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*" >&2; }

fetch() {
    local url="$1" target="$2"
    if [[ -f "$target" ]]; then
        log "vorhanden: $(basename "$target")"
        return 0
    fi
    mkdir -p "$(dirname "$target")"
    log "lade $(basename "$target")"
    if ! curl -fSL --retry 3 -o "$target.part" "$url"; then
        warn "Download fehlgeschlagen: $url"
        rm -f "$target.part"
        return 1
    fi
    mv "$target.part" "$target"
}

log "Weckwort- und VAD-Modelle"

# Die beiden gemeinsamen openWakeWord-Stufen. Sie sind für alle Weckwörter gleich und
# machen den Großteil der Rechenzeit aus — nur die dritte Stufe ist weckwortspezifisch.
fetch "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.onnx" \
    "$ASSETS/wakeword/melspectrogram.onnx" || true
fetch "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.onnx" \
    "$ASSETS/wakeword/embedding_model.onnx" || true

# Silero VAD — die zweite Stufe der Kaskade.
fetch "https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx" \
    "$ASSETS/vad/silero_vad.onnx" || true

if [[ ! -f "$ASSETS/wakeword/hey_neon.onnx" ]]; then
    cat <<'EOF'

  Das Modell für "Hey Neon" fehlt — und das lässt sich nicht herunterladen, weil es
  niemand vorher trainiert hat.

      cd tools/train-wakeword
      pip install -r requirements.txt
      python train.py
      cp hey_neon.onnx ../../app/src/main/assets/wakeword/

  Auf einer NVIDIA-Karte rund 30 Minuten. Siehe tools/train-wakeword/README.md;
  besonders der Abschnitt zu eigenen Aufnahmen lohnt sich.

  Bis dahin funktioniert Neon vollständig über den Knopf "Sprechen" — es fehlt nur das
  freihändige Ansprechen.

EOF
fi

log "fertig"
