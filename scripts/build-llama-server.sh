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

# Ein fester Stand, kein `master`.
#
# Hier stand `git pull --ff-only`, und das hat eine Runde gekostet: Beim Nachbessern der
# -march-Flags holte das Skript nebenbei einen neuen Programmstand — von e9fa078 auf
# 3018a11. Damit waren zwei Dinge auf einmal geändert, und als der App-Prozess danach
# sechsmal beim Laden vom System erschlagen wurde, war nicht mehr zu unterscheiden, welche
# der beiden Änderungen es war.
#
# e9fa078 ist der Stand, der auf dem Gerät nachweislich in 62 Sekunden geladen und
# geantwortet hat. Aktualisiert wird künftig absichtlich und einzeln:
#
#   LLAMA_CPP_REV=<commit> ./scripts/build-llama-server.sh
#
# Der **vollständige** Hash, nicht die Kurzform: GitHub liefert einen einzelnen Commit nur
# gegen den ganzen Namen aus („couldn't find remote ref"), und eine Abkürzung wäre hier
# ohnehin nur eine weitere Stelle, an der etwas mehrdeutig sein kann.
LLAMA_CPP_REV="${LLAMA_CPP_REV:-e9fa0781f1c25fc4fe8c86be1edc6970661ad6f0}"

if [[ -d "$WORK/.git" ]]; then
    log "llama.cpp auf $LLAMA_CPP_REV bringen"
else
    log "llama.cpp anlegen"
    git init -q "$WORK"
    git -C "$WORK" remote add origin https://github.com/ggml-org/llama.cpp
fi

# --depth 1 auf einen Commit: GitHub erlaubt das, und es spart das ganze Archiv.
git -C "$WORK" fetch -q --depth 1 origin "$LLAMA_CPP_REV" \
    || die "llama.cpp $LLAMA_CPP_REV ließ sich nicht holen."
git -C "$WORK" checkout -q --force FETCH_HEAD
git -C "$WORK" submodule update -q --init --recursive --depth 1 2>/dev/null || true

BUILT_REV="$(git -C "$WORK" rev-parse --short HEAD)"
log "gebaut wird llama.cpp $BUILT_REV"

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
#
# GGML_CPU_ARM_ARCH ist der dritte entscheidende Schalter — und der, dessen Fehlen am
# längsten unbemerkt blieb. Ohne ihn übersetzt llama.cpp für `armv8-a`, den
# Grundbefehlssatz von 2011:
#
#     if (GGML_CPU_ARM_ARCH)
#         list(APPEND ARCH_FLAGS -march=${GGML_CPU_ARM_ARCH})
#     elseif(GGML_CPU_ALL_VARIANTS)
#         set(ARM_MCPU "armv8-a")
#
# GGML_NATIVE hilft hier nicht: Beim Übersetzen für ein anderes Gerät überspringt CMake
# die Erkennung der eigenen Maschine, und das ist auch richtig so — sonst stünden die
# Merkmale des Bau-Rechners in einer Datei fürs Telefon.
#
# Die Folge war messbar: In der ausgelieferten Datei stand kein einziger `sdot`-Befehl.
# llama.cpp rechnete die 4-Bit-Gewichte in Schleifen aus Einzelmultiplikationen aus, und
# auf dem Gerät kamen 0,71 Token je Sekunde heraus statt der erwarteten 15 bis 25.
#
# `dotprod` (ARMv8.2) und `fp16` gibt es auf praktisch jedem arm64-Telefon seit 2018.
# Bewusst **ohne** `+i8mm`: Das brächte beim Verarbeiten langer Prompts noch einmal etwas,
# aber ein Kern ohne diesen Befehl beendet das Programm sofort mit SIGILL. Dann wäre Neon
# nicht langsam, sondern gar nicht da. Ob i8mm dazukommt, entscheidet die Merkmalszeile
# aus /proc/cpuinfo, die Neon jetzt protokolliert — nicht eine Vermutung über das Gerät.
ARM_ARCH="${GGML_CPU_ARM_ARCH:-armv8.2-a+dotprod+fp16}"

cmake -B "$WORK/build-arm64" -G Ninja -S "$WORK" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-33 \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DGGML_CPU_ARM_ARCH="$ARM_ARCH" \
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

# Genauso nachmessen statt hoffen: Ob die Beschleunigungsbefehle wirklich drin sind.
#
# Eine Datei, die für armv8-a übersetzt wurde, ist von einer richtig übersetzten äußerlich
# nicht zu unterscheiden — gleiche Größe, gleicher Aufbau, startet einwandfrei. Sie fällt
# erst auf dem Telefon auf, und dort nur als "Neon braucht zwei Minuten für einen Satz".
# Genau so ist es passiert.
log "Befehlssatz prüfen"
OBJDUMP="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump"
[[ -x "$OBJDUMP" ]] || OBJDUMP="$(command -v llvm-objdump || true)"
if [[ -n "$OBJDUMP" ]]; then
    SDOT=$("$OBJDUMP" -d --no-show-raw-insn "$TARGET" 2>/dev/null \
        | grep -cE '[[:space:]](sdot|udot)[[:space:]]' || true)
    (( SDOT > 0 )) || die "kein einziger sdot-Befehl in $TARGET.
     Damit rechnet llama.cpp quantisierte Matrizen in Einzelschritten aus — auf dem Gerät
     sind das rund 0,7 Token je Sekunde statt 15 bis 25. Ursache ist fast immer ein
     stehengebliebenes Bauverzeichnis: -DGGML_CPU_ARM_ARCH wirkt nur beim Konfigurieren.
     $WORK/build-arm64 löschen und neu konfigurieren."
    log "  $SDOT Skalarprodukt-Befehle gefunden (-march=$ARM_ARCH)"
else
    printf '\033[1;33m??\033[0m %s\n' "llvm-objdump fehlt — Befehlssatz nicht geprüft." >&2
fi

# Und den Programmstand aus der fertigen Datei zurücklesen.
#
# llama.cpp legt seinen Commit als Zeichenkette in die Binärdatei. Genau daran liess sich
# nachweisen, dass zwei verschiedene Stände im Umlauf waren — nachdem das Bauskript selbst
# nirgends vermerkt hatte, was es gebaut hatte. Ein Skript, das sein Ergebnis nicht
# nachliest, behauptet nur.
#
# Kein `grep -q` in einer Pipeline: Unter `set -o pipefail` schlägt die ganze Pipeline fehl,
# sobald ein Glied fehlschlägt — und `grep -q` beendet sich beim ersten Treffer, wodurch
# `strings` ein SIGPIPE bekommt und mit Fehler endet. Der Treffer wäre da, die Prüfung
# meldete trotzdem "nicht gefunden". Genau das ist hier passiert. Deshalb `grep -c`, das
# seine Eingabe zu Ende liest.
log "Programmstand in der Datei prüfen"
REV_TREFFER=$(strings -a "$TARGET" | grep -cxF "$BUILT_REV" || true)
if (( REV_TREFFER > 0 )); then
    log "  llama.cpp $BUILT_REV steht in der Datei"
else
    die "In $TARGET steht nicht der Commit $BUILT_REV, der gebaut werden sollte.
     Gefunden: $(strings -a "$TARGET" | grep -oE '^[0-9a-f]{7}$' | head -3 | tr '\n' ' ')
     Meist ein stehengebliebenes Bauverzeichnis: $WORK/build-arm64 löschen."
fi

log "fertig: $TARGET ($(du -h "$TARGET" | cut -f1), llama.cpp $BUILT_REV, Ausrichtung $WORST Byte)"
file "$TARGET" || true
