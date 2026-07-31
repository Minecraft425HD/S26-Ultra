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

# Der Kotlin-Compiler.
#
# Er kommt aus Maven Central und nicht aus dem SDK — dort gibt es ihn nicht. Festgenagelt
# gegen eine Prüfsumme, wie alles Fremde in diesem Projekt.
#
# Gemessen: 57 MB werden in 67 Sekunden zu 16 MB Dex. Deutlich weniger, als ich geschätzt
# hatte; die Plattform-Klassen sind am Ende der dickere Posten.
KOTLIN_VERSION="${KOTLIN_VERSION:-2.1.0}"
MAVEN="https://repo1.maven.org/maven2"
CACHE="${TOOLCHAIN_CACHE:-$ROOT/.toolchain}"
mkdir -p "$CACHE"

# name|pfad in Maven|SHA-256|Einstiegsklasse oder leer, wenn nur Klassenpfad
MAVEN_TEILE=(
    "kotlinc|org/jetbrains/kotlin/kotlin-compiler-embeddable/$KOTLIN_VERSION/kotlin-compiler-embeddable-$KOTLIN_VERSION.jar|c1b139a6f251c3b99e92befa326cb75d93a001d74c3ac601155a8cdb0d253783|org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
    "kotlin-stdlib|org/jetbrains/kotlin/kotlin-stdlib/$KOTLIN_VERSION/kotlin-stdlib-$KOTLIN_VERSION.jar|d6f91b7b0f306cca299fec74fb7c34e4874d6f5ec5b925a0b4de21901e119c3f|"
    "annotations|org/jetbrains/annotations/13.0/annotations-13.0.jar|ace2a10dc8e2d5fd34925ecac03e4988b2c0f851650c94b8cef49ba1bd111478|"
)

for eintrag in "${MAVEN_TEILE[@]}"; do
    IFS='|' read -r name pfad summe einstieg <<<"$eintrag"
    quelle="$CACHE/$(basename "$pfad")"

    if [[ ! -f "$quelle" ]]; then
        log "hole $name"
        curl -sSL --fail --max-time 600 -o "$quelle" "$MAVEN/$pfad" \
            || die "$name ließ sich nicht holen: $MAVEN/$pfad"
    fi

    ist="$(sha256sum "$quelle" | cut -d' ' -f1)"
    [[ "$ist" == "$summe" ]] || die "Prüfsumme von $name stimmt nicht.
     erwartet: $summe
     erhalten: $ist"

    if [[ -z "$einstieg" ]]; then
        # Ein reiner Klassenpfad-Bestandteil. Er wird **nicht** gedext: Der Kotlin-Compiler
        # liest ihn als Java-Bytecode, um dagegen zu übersetzen. Eine Dex-Fassung wäre für
        # diesen Zweck unbrauchbar.
        cp -f "$quelle" "$ASSETS/$name.jar"
        log "  $name.jar $(du -h "$ASSETS/$name.jar" | cut -f1) (Klassenpfad, ungedext)"
        continue
    fi

    ziel="$ASSETS/$name.dex.jar"
    log "wandle $name um ($(du -h "$quelle" | cut -f1)) — das dauert etwa eine Minute"
    rm -f "$ziel"
    "$D8" --release --min-api "$MIN_API" --lib "$PLATTFORM" --output "$ziel" "$quelle" \
        || die "$name ließ sich nicht in Dex umwandeln."

    pfad_klasse="${einstieg//./\/}"
    rm -rf "$ROOT/.toolchain-probe" && mkdir -p "$ROOT/.toolchain-probe"
    unzip -q -o "$ziel" -d "$ROOT/.toolchain-probe"
    treffer=$(grep -ac "$pfad_klasse" "$ROOT/.toolchain-probe"/classes*.dex 2>/dev/null \
        | awk -F: '{s+=$NF} END{print s+0}')
    rm -rf "$ROOT/.toolchain-probe"

    (( treffer > 0 )) || die "In $ziel steht die Einstiegsklasse $einstieg nicht."
    log "  $(du -h "$ziel" | cut -f1), $einstieg gefunden"
done

# Die Plattform-Klassen. Gegen sie übersetzt der Kotlin-Compiler, und aapt2 braucht sie zum
# Auflösen der Ressourcen-Verweise. Ungedext, aus demselben Grund wie kotlin-stdlib.
log "Plattform-Klassen übernehmen"
cp -f "$PLATTFORM" "$ASSETS/android.jar"
log "  android.jar $(du -h "$ASSETS/android.jar" | cut -f1)"

# Ein Schlüssel zum Signieren der gebauten Apps.
#
# **Ausdrücklich keine Sicherheitsgrenze.** Android weigert sich, eine unsignierte APK zu
# installieren; irgendein Schlüssel muss also her. Dieser liegt offen in der App, und das ist
# richtig so: Er beglaubigt nichts, er erfüllt nur eine Formvorschrift. Wer eine hier gebaute
# App weitergeben will, signiert sie mit einem eigenen Schlüssel.
#
# Dieselbe Überlegung wie beim Schlüssel dieses Projekts, und aus demselben Grund
# hingeschrieben: Ein Schlüssel ohne diesen Satz daneben wird irgendwann für einen echten
# gehalten.
if [[ ! -f "$ASSETS/debug.keystore" ]]; then
    log "Schlüssel zum Signieren erzeugen"
    command -v keytool >/dev/null || die "keytool fehlt (JDK nötig)."
    keytool -genkeypair -v \
        -keystore "$ASSETS/debug.keystore" \
        -storepass neonneon -keypass neonneon \
        -alias neon-build -keyalg RSA -keysize 2048 -validity 10950 \
        -dname "CN=Neon On-Device Build, OU=Neon, O=Neon, C=DE" \
        >/dev/null 2>&1 \
        || die "Der Signierschlüssel ließ sich nicht erzeugen."
    log "  debug.keystore erzeugt"
fi

log "fertig:"
for datei in "$ASSETS"/d8.dex.jar "$ASSETS"/apksigner.dex.jar "$ASSETS"/kotlinc.dex.jar \
             "$ASSETS"/kotlin-stdlib.jar "$ASSETS"/annotations.jar "$ASSETS"/android.jar \
             "$ASSETS"/debug.keystore; do
    [[ -f "$datei" ]] && printf '    %-22s %s\n' "$(basename "$datei")" "$(du -h "$datei" | cut -f1)"
done
printf '    %-22s %s\n' "zusammen" \
    "$(du -ch "$ASSETS"/*.jar "$ASSETS"/debug.keystore 2>/dev/null | tail -1 | cut -f1)"
