#!/usr/bin/env bash
#
# Lädt die Modelldateien, die Neon zum Laufen braucht.
#
# Bewusst getrennt vom Gradle-Build: Modellgewichte gehören nicht in ein Git-Repository,
# und welche Quantisierung die beste ist, entscheidet die Messung auf dem Gerät — nicht
# der Zeitpunkt, zu dem dieses Skript geschrieben wurde.
#
#   ./scripts/fetch-models.sh wakeword   nur die Weckwort- und VAD-Modelle (wenige MB)
#   ./scripts/fetch-models.sh llm        die Sprachmodelle (~15 GB)
#   ./scripts/fetch-models.sh all
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets"
MODELS="$ROOT/models"

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
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

fetch_wakeword() {
    log "Weckwort- und VAD-Modelle"

    # Die beiden gemeinsamen openWakeWord-Stufen. Sie sind für alle Weckwörter gleich.
    fetch "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.onnx" \
        "$ASSETS/wakeword/melspectrogram.onnx" || true
    fetch "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.onnx" \
        "$ASSETS/wakeword/embedding_model.onnx" || true

    # Silero VAD.
    fetch "https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx" \
        "$ASSETS/vad/silero_vad.onnx" || true

    if [[ ! -f "$ASSETS/wakeword/neon.onnx" ]]; then
        cat <<'EOF'

  Das Modell für das Weckwort "Neon" fehlt noch — und das lässt sich nicht
  herunterladen, weil es niemand vorher trainiert hat.

  So entsteht es:
    1. https://openwakeword.com/train aufrufen (oder das Notebook
       automatic_model_training.ipynb aus dem openWakeWord-Repository benutzen).
    2. Als Zielwort "Neon" angeben. Empfehlung: zusätzlich ein Modell für
       "Hey Neon" trainieren — zwei Silben allein lösen erfahrungsgemäß häufiger
       falsch aus als eine längere Wendung.
    3. Als Gegenbeispiele ausdrücklich "Neonlicht", "Neonfarbe", "Neonröhre"
       aufnehmen lassen, sonst reagiert Neon auf jedes davon.
    4. Die fertige ONNX-Datei nach app/src/main/assets/wakeword/neon.onnx legen.

  Bis dahin läuft Neon ohne Weckwort: starten über die App oder die Kachel.

EOF
    fi
}

fetch_llm() {
    log "Sprachmodelle"
    cat <<'EOF'

  Die GGUF-Dateien werden hier absichtlich nicht fest verdrahtet.

  Der Grund: Welche Quantisierung auf dem S26 Ultra das beste Verhältnis aus
  Geschwindigkeit, Speicherbedarf und deutscher Sprachqualität liefert, ist eine
  Messfrage. Der Diagnose-Screen aus M1 beantwortet sie auf dem echten Gerät;
  vorher wäre jede Festlegung hier geraten.

  Vorgesehene Startaufstellung (Modell-ID -> Datei unter models/):

    qwen3-0.6b-router     Qwen3 0.6B Instruct, Q4_K_M    (~0,4 GB)
    qwen3-4b-instruct     Qwen3 4B Instruct, Q4_K_M      (~2,5 GB)
    qwen3-8b-thinking     Qwen3 8B, Q4_K_M               (~5,0 GB)
    qwen3-coder-7b        Qwen3 Coder 7B, Q4_K_M         (~4,5 GB)
    gemma-3n-e4b          Gemma 3n E4B                   (~3,0 GB)

  Dateien nach models/<modell-id>.gguf ablegen und mit

      adb push models/<modell-id>.gguf /sdcard/Android/data/de.neon.app/files/models/

  auf das Gerät schieben. Die Namen müssen exakt den IDs aus
  core/router/.../Models.kt entsprechen — danach sucht der ModelStore.

EOF
    mkdir -p "$MODELS"
}

case "${1:-all}" in
    wakeword) fetch_wakeword ;;
    llm) fetch_llm ;;
    all) fetch_wakeword; fetch_llm ;;
    *) echo "Aufruf: $0 [wakeword|llm|all]" >&2; exit 1 ;;
esac

log "fertig"
