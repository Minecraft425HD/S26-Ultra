#!/usr/bin/env bash
#
# Baut den Starter, der CPython als eigenen Prozess ausführt.
#
# Das offizielle Python-Paket von python.org bringt keinen Interpreter mit, nur
# libpython3.x.so. Dieser Starter ist die kleinste denkbare Brücke: ein main(), das
# Py_BytesMain aufruft — dasselbe main(), das auch das echte python3 benutzt.
#
# **Warum ein Prozess und keine Einbettung.** Hier laufen Skripte, die ein Sprachmodell
# geschrieben hat. Endlosschleifen, Speicherfresser und Abstürze im C-Teil einer Erweiterung
# sind zu erwarten, und nichts davon darf Neon mitnehmen. Dieselbe Überlegung wie beim
# llama-server. Ein eigener Prozess lässt sich außerdem abbrechen; ein eingebetteter
# Interpreter nicht.
#
# Das Ergebnis heißt libpython-launcher.so, weil Androids Installer nur Dateien mit diesem
# Namensmuster in ein Verzeichnis entpackt, aus dem ausgeführt werden darf — genau wie bei
# llama-server und aapt2.
#
# Voraussetzungen: Android-NDK (r27 oder neuer) und die Python-Dateien aus
# scripts/fetch-python.sh.
#
#   ANDROID_NDK=/pfad/zum/ndk ./scripts/build-python-launcher.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
QUELLE="$ROOT/native/python-launcher/main.c"
JNILIBS="$ROOT/app/src/main/jniLibs/arm64-v8a"
ZIEL="$JNILIBS/libpython-launcher.so"
PYTHON_DIR="${PYTHON_DIR:-$ROOT/.python-android}"

log()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m!!\033[0m %s\n' "$*" >&2; exit 1; }

[[ -n "${ANDROID_NDK:-}" ]] || die "ANDROID_NDK ist nicht gesetzt."
[[ -f "$QUELLE" ]] || die "$QUELLE fehlt."

CLANG="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang"
[[ -x "$CLANG" ]] || die "aarch64-Clang nicht gefunden: $CLANG"

# Die Kopfdateien und die Bibliothek liegen im entpackten Python-Paket.
PREFIX="$PYTHON_DIR/entpackt/prefix"
[[ -d "$PREFIX/include" ]] || die "Python-Kopfdateien fehlen unter $PREFIX/include.
     Zuerst scripts/fetch-python.sh laufen lassen (setzt PYTHON_DIR, falls abweichend)."

INCLUDE="$(find "$PREFIX/include" -maxdepth 1 -type d -name 'python3.*' | head -1)"
[[ -n "$INCLUDE" ]] || die "kein python3.x-Verzeichnis unter $PREFIX/include"

# Die Fassung aus dem Verzeichnisnamen: python3.14 -> 3.14. Nicht fest verdrahtet, damit ein
# Wechsel der Python-Fassung nicht an zwei Stellen nachgezogen werden muss — genau die Art
# Doppelpflege, die in diesem Projekt schon Fehler verursacht hat.
KURZ="$(basename "$INCLUDE")"          # python3.14
FASSUNG="${KURZ#python}"               # 3.14

log "baue Starter gegen CPython $FASSUNG"
mkdir -p "$JNILIBS"

"$CLANG" \
    -O2 -fPIE -pie \
    -I"$INCLUDE" \
    -L"$PREFIX/lib" \
    -o "$ZIEL" \
    "$QUELLE" \
    "-lpython$FASSUNG" \
    -Wl,-rpath,'$ORIGIN' \
    -Wl,-z,max-page-size=16384 \
    -Wl,-z,common-page-size=16384 \
    || die "Der Starter ließ sich nicht übersetzen."

chmod 0755 "$ZIEL"

# Nachmessen statt behaupten — dieselben drei Fragen wie bei llama-server und aapt2.
log "Ergebnis prüfen"

READELF="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
[[ -x "$READELF" ]] || READELF="$(command -v readelf || true)"
[[ -n "$READELF" ]] || die "readelf nicht gefunden."

# 16-KB-Seiten. Ein Programm, das daran scheitert, startet auf dem Zielgerät gar nicht.
SCHLECHTESTE=$("$READELF" -lW "$ZIEL" \
    | awk '$1 == "LOAD" { print $NF }' \
    | while read -r WERT; do printf '%d\n' "$WERT"; done \
    | sort -n | head -1)
[[ -n "$SCHLECHTESTE" ]] || die "keine LOAD-Segmente in $ZIEL."
(( SCHLECHTESTE >= 16384 )) || die "LOAD-Segmente nur auf $SCHLECHTESTE Byte ausgerichtet.
     Auf einem Gerät mit 16-KB-Seiten lässt sich das Programm nicht starten."

# Positionsunabhängig. Android verlangt das von jedem Programm; ein nicht-PIE-Programm wird
# vom Linker abgewiesen, und zwar mit einer Meldung, die niemand sieht.
TYP=$("$READELF" -hW "$ZIEL" | awk '/^  Type:/ { print $2 }')
[[ "$TYP" == "DYN" ]] || die "Der Starter ist $TYP statt DYN — Android verlangt ein
     positionsunabhängiges Programm (-fPIE -pie)."

# Und die Bibliothek muss daneben liegen, sonst findet der Linker sie zur Laufzeit nicht.
BEDARF=$("$READELF" -dW "$ZIEL" | grep -c "libpython$FASSUNG.so" || true)
(( BEDARF > 0 )) || die "Der Starter verweist nicht auf libpython$FASSUNG.so."

RPATH=$("$READELF" -dW "$ZIEL" | grep -cE 'R(UN)?PATH.*ORIGIN' || true)
(( RPATH > 0 )) || die "Kein RUNPATH auf \$ORIGIN — der Starter würde libpython nicht finden."

if [[ ! -f "$JNILIBS/libpython$FASSUNG.so" ]]; then
    die "libpython$FASSUNG.so fehlt in $JNILIBS.
     Zuerst scripts/fetch-python.sh laufen lassen."
fi

log "fertig: $ZIEL ($(du -h "$ZIEL" | cut -f1), CPython $FASSUNG, Ausrichtung $SCHLECHTESTE Byte)"
file "$ZIEL" || true
