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
cmake -B "$WORK/build-arm64" -G Ninja -S "$WORK" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-33 \
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

log "fertig: $TARGET ($(du -h "$TARGET" | cut -f1))"
file "$TARGET" || true
