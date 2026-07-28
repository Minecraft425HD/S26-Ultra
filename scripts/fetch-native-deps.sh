#!/usr/bin/env bash
#
# Holt die llama.cpp-Quellen für die lokale Inferenz.
#
# Ohne diesen Schritt baut die App ganz normal — nur die Antwortgenerierung meldet dann,
# dass die native Bibliothek fehlt. Regelbefehle, Spracherkennung und Sprachausgabe
# funktionieren auch ohne.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="$ROOT/core/inference/src/main/cpp/llama.cpp"

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

if [[ -d "$TARGET/.git" ]]; then
    log "llama.cpp ist bereits vorhanden — aktualisiere"
    git -C "$TARGET" pull --ff-only
else
    log "klone llama.cpp nach core/inference/src/main/cpp/"
    mkdir -p "$(dirname "$TARGET")"
    git clone --depth 1 https://github.com/ggml-org/llama.cpp "$TARGET"
fi

cat <<'EOF'

  Nächster Schritt: mit dem nativen Teil bauen.

      ./gradlew :app:assembleDebug -Pneon.buildNative=true

  Voraussetzung ist ein installiertes Android-NDK (r27 oder neuer). Gebaut wird nur
  für arm64-v8a — die einzige Architektur, die auf dem Zielgerät läuft.

  Auf dem Adreno des Snapdragon 8 Elite lässt sich ein Teil der Rechnung über OpenCL
  auslagern. Ob sich das lohnt, ist eine Messfrage: Der GPU-Pfad ist bei kleinen
  Modellen nicht automatisch schneller als die CPU-Kerne, kann aber kühler laufen.
  Der Diagnose-Screen vergleicht beides auf dem Gerät.

EOF
