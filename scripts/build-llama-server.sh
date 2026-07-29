#!/usr/bin/env bash
#
# Baut llama-server für das Telefon (arm64-v8a) und legt ihn in die APK-Quellen.
#
# Das Ergebnis ist ein eigenständiges Programm, das nur an libc, libm und libdl hängt —
# alles Android-Systembibliotheken. Es wird als libllama-server.so abgelegt, weil der
# Installer nur Dateien mit diesem Namensmuster in ein Verzeichnis entpackt, aus dem
# ausgeführt werden darf.
#
# Voraussetzungen: Android-NDK (r27 oder neuer), cmake, ninja, git.
#
#   ANDROID_NDK=/pfad/zum/ndk ./scripts/build-llama-server.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${LLAMA_CPP_DIR:-$ROOT/.llama.cpp}"
TARGET_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
TARGET="$TARGET_DIR/libllama-server.so"

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m!!\033[0m %s\n' "$*" >&2; exit 1; }

[[ -n "${ANDROID_NDK:-}" ]] || die "ANDROID_NDK ist nicht gesetzt."
[[ -d "$ANDROID_NDK" ]] || die "ANDROID_NDK zeigt auf kein Verzeichnis: $ANDROID_NDK"
command -v cmake >/dev/null || die "cmake fehlt."
command -v ninja >/dev/null || die "ninja fehlt."

if [[ -d "$WORK/.git" ]]; then
    log "llama.cpp aktualisieren"
    git -C "$WORK" pull --ff-only
else
    log "llama.cpp klonen"
    git clone --depth 1 https://github.com/ggml-org/llama.cpp "$WORK"
fi

log "für arm64-v8a konfigurieren"
# BUILD_SHARED_LIBS=OFF ist entscheidend: So entsteht ein einzelnes, in sich geschlossenes
# Programm statt eines Bündels aus Programm und mehreren .so-Dateien, die zur Laufzeit
# gefunden werden müssten.
#
# ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON ist ebenso entscheidend, nur unauffälliger:
# Geräte, die mit Android 16 und mindestens 8 GB Arbeitsspeicher ausgeliefert werden — das
# S26 Ultra gehört dazu —, arbeiten mit 16-KB-Speicherseiten. Der Android-Linker weist ein
# Programm ab, dessen LOAD-Segmente nur auf 4 KB ausgerichtet sind; es lässt sich schlicht
# nicht starten. NDK r27 richtet nur auf Zuruf auf 16 KB aus, erst r28 tut es von selbst.
# Die Linker-Flags stehen zusätzlich da, damit die Ausrichtung auch dann erhalten bleibt,
# wenn das Projekt später auf ein neueres NDK oder einen anderen Bauweg wechselt.
cmake -B "$WORK/build-arm64" -G Ninja -S "$WORK" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-33 \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DLLAMA_CURL=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DGGML_OPENMP=OFF \
    -DGGML_LLAMAFILE=OFF

log "bauen (dauert einige Minuten)"
cmake --build "$WORK/build-arm64" --target llama-server -j"$(nproc)"

STRIP="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
[[ -x "$STRIP" ]] || STRIP="$(command -v llvm-strip || true)"
[[ -n "$STRIP" ]] || die "llvm-strip nicht gefunden."

mkdir -p "$TARGET_DIR"
log "Symbole entfernen und ablegen"
"$STRIP" --strip-all -o "$TARGET" "$WORK/build-arm64/bin/llama-server"

# Nachmessen statt hoffen. Eine falsch ausgerichtete Binärdatei sieht völlig normal aus —
# sie fällt erst auf dem Telefon auf, und dort nur als "Neon antwortet nie".
log "Seitenausrichtung prüfen"
READELF="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
[[ -x "$READELF" ]] || READELF="$(command -v readelf || true)"
[[ -n "$READELF" ]] || die "readelf nicht gefunden."

# Umrechnung der hexadezimalen Ausrichtung in der Shell statt in awk: strtonum gibt es
# nur in gawk, und mancher Rechner bringt mawk mit.
WORST=$("$READELF" -lW "$TARGET" \
    | awk '$1 == "LOAD" { print $NF }' \
    | while read -r WERT; do printf '%d\n' "$WERT"; done \
    | sort -n | head -1)
[[ -n "$WORST" ]] || die "keine LOAD-Segmente in $TARGET gefunden."
if (( WORST < 16384 )); then
    die "LOAD-Segmente nur auf $WORST Byte ausgerichtet. Auf einem Gerät mit 16-KB-Seiten
     lässt sich das Programm nicht starten. Bauverzeichnis $WORK/build-arm64 löschen und
     neu konfigurieren — der Schalter wirkt nur beim Konfigurieren, nicht beim Bauen."
fi

log "fertig: $TARGET ($(du -h "$TARGET" | cut -f1), Ausrichtung $WORST Byte)"
file "$TARGET" || true
