#!/usr/bin/env bash
#
# Bereitet die Java-Werkzeuge der Android-Bau-Kette für das Telefon vor.
#
# **Das Problem.** `d8` (Klassen zu Dex) und `apksigner` (APK signieren) sind Java-Programme.
# Auf einem Rechner startet man sie mit `java -jar`; auf Android gibt es keine JVM, sondern
# ART, und die führt kein Java-Bytecode aus, sondern Dex. Eine JAR-Datei ist dort so
# unbrauchbar wie eine Textdatei.
#
# **Die Lösung, und warum sie nicht im Kreis läuft.** Dex-Dateien werden mit `d8` erzeugt —
# also mit genau dem Programm, das hier umgewandelt werden soll. Der Ausweg ist, das **hier**
# zu tun, auf einem Rechner mit JVM: `d8` wandelt sich selbst um, einmal, und das Ergebnis
# kommt fertig mit in die APK. Auf dem Telefon läuft dann die Dex-Fassung und wandelt den Code
# um, den Neon schreibt.
#
# Gemessen: 25 Sekunden für d8, aus 18 MB werden 3,4 MB. apksigner braucht 5 Sekunden für
# 347 KB. Zusammen weniger als vier Megabyte — der Kotlin-Compiler wird der dicke Posten.
#
# Die Ergebnisse gehen nach `assets` und nicht nach `jniLibs`: Es sind keine Bibliotheken,
# sondern Archive. Neon kopiert sie beim ersten Gebrauch ins Datenverzeichnis und lädt sie
# über einen `DexClassLoader`.
#
# Voraussetzungen: Android SDK mit build-tools und einer Plattform, `java`.
#
#   ANDROID_HOME=/pfad/zum/sdk ./scripts/build-android-toolchain.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets"

log()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m!!\033[0m %s\n' "$*" >&2; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
[[ -d "$SDK" ]] || die "Android SDK nicht gefunden: $SDK (ANDROID_HOME setzen)"

# Die neueste vorhandene Fassung der build-tools. Nicht festgenagelt, weil hier nichts
# ausgeliefert wird, was von ihr abhängt: d8 erzeugt Dex, und Dex ist über min-api
# festgelegt, nicht über die Fassung des Werkzeugs.
BUILD_TOOLS="$(ls -1d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
[[ -n "$BUILD_TOOLS" ]] || die "keine build-tools unter $SDK/build-tools"
BUILD_TOOLS="${BUILD_TOOLS%/}"

D8="$BUILD_TOOLS/d8"
[[ -x "$D8" ]] || die "d8 fehlt: $D8"

# Die Plattform-Klassen als Bezug. Ohne sie meldet d8 fehlende Verweise auf java.*-Klassen,
# die auf Android sehr wohl da sind.
PLATTFORM="$(ls -1 "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
[[ -n "$PLATTFORM" ]] || die "keine android.jar unter $SDK/platforms"

# Dieselbe Untergrenze wie die App selbst. Ein höherer Wert erlaubt d8 modernere
# Dex-Befehle; ein niedrigerer erzwänge Ersatzcode, den niemand braucht.
MIN_API="${MIN_API:-33}"

mkdir -p "$ASSETS"

log "build-tools: $(basename "$BUILD_TOOLS"), Plattform: $(basename "$(dirname "$PLATTFORM")"), min-api $MIN_API"

# Was umgewandelt wird, und woran man erkennt, dass es geklappt hat.
#
# Die Einstiegsklasse ist nicht Zierde: Eine Dex-Datei, die entsteht und die falsche oder gar
# keine Klasse enthält, fällt sonst erst auf dem Telefon auf — als
# `ClassNotFoundException` mitten in einem Bauvorgang.
WERKZEUGE=(
    "d8|com.android.tools.r8.D8"
    "apksigner|com.android.apksigner.ApkSignerTool"
)

for eintrag in "${WERKZEUGE[@]}"; do
    IFS='|' read -r name einstieg <<<"$eintrag"
    quelle="$BUILD_TOOLS/lib/$name.jar"
    ziel="$ASSETS/$name.dex.jar"

    [[ -f "$quelle" ]] || die "$quelle fehlt."

    log "wandle $name um ($(du -h "$quelle" | cut -f1))"
    rm -f "$ziel"
    "$D8" --release --min-api "$MIN_API" --lib "$PLATTFORM" --output "$ziel" "$quelle" \
        || die "$name ließ sich nicht in Dex umwandeln."

    # Nachsehen statt hoffen. Der Klassenname steht in der Dex-Datei als Pfad mit
    # Schrägstrichen.
    pfad="${einstieg//./\/}"
    rm -rf "$ROOT/.toolchain-probe" && mkdir -p "$ROOT/.toolchain-probe"
    unzip -q -o "$ziel" -d "$ROOT/.toolchain-probe"

    # `$NF` und nicht `$2`: `grep -c` stellt den Dateinamen nur voran, wenn es **mehrere**
    # Dateien gibt. Bei d8 entstehen zwei Dex-Dateien und die Zeile lautet
    # `classes2.dex:2`; bei apksigner entsteht eine und sie lautet schlicht `1`. Mit `$2`
    # zählte der zweite Fall als null — und meldete eine Einstiegsklasse als fehlend, die
    # da war. Das letzte Feld ist in beiden Fällen die Zahl.
    treffer=$(grep -ac "$pfad" "$ROOT/.toolchain-probe"/classes*.dex 2>/dev/null \
        | awk -F: '{s+=$NF} END{print s+0}')
    rm -rf "$ROOT/.toolchain-probe"

    (( treffer > 0 )) || die "In $ziel steht die Einstiegsklasse $einstieg nicht.
     Ohne sie scheitert der Bauvorgang auf dem Telefon mit ClassNotFoundException — und
     zwar erst, wenn jemand ihn benutzt."

    log "  $(du -h "$ziel" | cut -f1), $einstieg gefunden"
done

log "fertig:"
for eintrag in "${WERKZEUGE[@]}"; do
    IFS='|' read -r name _ <<<"$eintrag"
    printf '    %-20s %s\n' "$name.dex.jar" "$(du -h "$ASSETS/$name.dex.jar" | cut -f1)"
done
