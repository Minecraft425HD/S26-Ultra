#!/usr/bin/env bash
#
# Holt aapt2 samt Abhängigkeiten für das Telefon und macht sie in einer App startbar.
#
# aapt2 ist der Ressourcen-Compiler von Android. Ohne ihn gibt es kein kompiliertes
# AndroidManifest.xml und damit keine APK — er ist die Sperre auf dem Weg zu "eine Android-App
# auf dem Telefon bauen".
#
# **Warum nicht selbst gebaut.** Das Android SDK liefert aapt2 nur als x86-64-Linux-Programm.
# Selbstbauen heißt: die aapt2-Quellen aus frameworks/base gegen protobuf, libpng, expat,
# zlib, abseil und fmt linken. Machbar, aber ein eigenes Vorhaben. Termux hat das gemacht,
# veröffentlicht das Rezept und die Prüfsummen. Das ist nicht selbst gebaut, aber auch nicht
# anonym: Die Dateien sind hier festgenagelt und werden gegen ihre Prüfsumme geprüft.
#
# **Zwei Dinge stehen einer fremden Binärdatei im Weg, und beide werden hier geradegezogen.**
#
#  1. Der RUNPATH zeigt auf /data/data/com.termux/files/usr/lib. Eine andere App kommt dort
#     nicht hin. Er wird auf $ORIGIN gesetzt — das Verzeichnis, in dem die Datei selbst liegt,
#     und genau dorthin entpackt Android die mitgelieferten Bibliotheken.
#  2. Zwei Abhängigkeiten heißen libz.so.1 und libexpat.so.1. Androids Installer entpackt nur
#     Dateien, deren Name auf .so **endet** — versionierte Namen bleiben im APK liegen und
#     werden nie gefunden. Sie werden umbenannt, und die Verweise darauf mit.
#
# Gemessen (nicht vermutet): Diese Fassung ist auf 16-KB-Seiten ausgerichtet. Ein älterer
# Bau aus dem Android-11-Zeitalter war das nicht und wäre auf einem Gerät mit 16-KB-Seiten
# nicht startbar gewesen.
#
# Voraussetzungen: curl, ar, tar, patchelf.
#
#   ./scripts/fetch-android-tools.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${ANDROID_TOOLS_DIR:-$ROOT/.android-tools}"
TARGET_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"

log()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m!!\033[0m %s\n' "$*" >&2; exit 1; }

for werkzeug in curl ar tar patchelf; do
    command -v "$werkzeug" >/dev/null || die "$werkzeug fehlt."
done

DEPOT="https://packages.termux.dev/apt/termux-main/pool/main"

# Feste Fassungen mit Prüfsumme, keine "neueste".
#
# Dieselbe Lehre wie bei llama.cpp: Dort holte das Bauskript nebenbei einen neuen Stand,
# und als danach etwas nicht mehr funktionierte, waren zwei Dinge auf einmal geändert. Wer
# aktualisieren will, ändert hier eine Zeile — absichtlich und einzeln.
#
# Die Prüfsummen stehen im Paket-Index des Depots und sind von dort übernommen.
# Dateinamen und Prüfsummen sind **abgelesen** und nicht ausgedacht. Beim ersten Anlauf hatte
# ich drei Fassungsnummern geschätzt; zwei davon gab es nicht, und das Skript ist zu Recht
# darüber gestolpert. Wer aktualisiert, holt sich die Zeilen aus dem Paket-Index:
#
#   curl -sS "$DEPOT/../dists/stable/main/binary-aarch64/Packages"
PAKETE=(
    "aapt2|a/aapt2/aapt2_16.0.0.4-1_aarch64.deb|d35298f13ec26eee362d4e84f534b29b8e5f288b86c89d803ba4fb8ccb9784aa"
    "abseil-cpp|a/abseil-cpp/abseil-cpp_20260526.0_aarch64.deb|e489fac652cddc39d9436141e627285f1034a545a06fbb19c420514a419ad877"
    "fmt|f/fmt/fmt_1:11.2.0_aarch64.deb|0377ac55cc99e409a5a2ba55a7cacf86fc1f79f330c2998801e293e95cac1996"
    "libc++|libc/libc++/libc++_29_aarch64.deb|bb9f12113c137aa0e8513bb51cc49fe77a5ce3ca39ab9e92c57d228ecdf00222"
    "libexpat|libe/libexpat/libexpat_2.8.2_aarch64.deb|6f5eb2fd14b6fe4d7bb79bf7f0f3d7fc838fea07402477a172b147304366b372"
    "libpng|libp/libpng/libpng_1.6.58_aarch64.deb|e47937405c72734867513cf0c63d27f36400d462666b65dfada984667d7228c4"
    "libprotobuf|libp/libprotobuf/libprotobuf_2:35.1_aarch64.deb|a1ba7c7f0e5903a2134662653d3e7b9ffceaa78bdd00e07ac985e2d313ebc738"
    "libzopfli|libz/libzopfli/libzopfli_1.0.3-5_aarch64.deb|95cd7cb2209fbafb25825f5fcd4f86f021512175608e038b1c3d8d3fa0a4fe40"
    "zlib|z/zlib/zlib_1.3.2_aarch64.deb|75e7d0af17fcc3b40004309fdc00a1ddb9ae08346dce5e269902c34ac3966ac9"
)

mkdir -p "$WORK/pakete" "$WORK/entpackt" "$TARGET_DIR"

for eintrag in "${PAKETE[@]}"; do
    IFS='|' read -r name pfad summe <<<"$eintrag"
    datei="$WORK/pakete/$(basename "${pfad//%3a/_}")"

    if [[ ! -f "$datei" ]]; then
        log "hole $name"
        curl -sSL --fail --max-time 300 -o "$datei" "$DEPOT/$pfad" \
            || die "$name ließ sich nicht holen: $DEPOT/$pfad"
    fi

    if [[ -n "$summe" ]]; then
        ist="$(sha256sum "$datei" | cut -d' ' -f1)"
        [[ "$ist" == "$summe" ]] || die "Prüfsumme von $name stimmt nicht.
     erwartet: $summe
     erhalten: $ist
     Entweder wurde die Fassung im Depot ersetzt, oder unterwegs hat jemand mitgeschrieben."
        log "  Prüfsumme stimmt"
    fi

    # Jedes Paket in ein eigenes Verzeichnis: Zwei Pakete können dieselbe Datei mitbringen,
    # und dann wäre ohne Trennung nicht mehr zu sagen, welche gewonnen hat.
    ziel="$WORK/entpackt/$name"
    rm -rf "$ziel" && mkdir -p "$ziel"
    (cd "$ziel" && ar x "$datei" && for t in data.tar.xz data.tar.zst data.tar.gz; do
        [[ -f "$t" ]] && tar xf "$t" && break
    done) || die "$name ließ sich nicht entpacken."
done

# Alles Ausführbare und alle Bibliotheken einsammeln.
log "Dateien einsammeln"
PREFIX="data/data/com.termux/files/usr"

hole() {
    local quelle="$1" ziel="$2"
    [[ -f "$quelle" ]] || return 1
    cp -f "$quelle" "$TARGET_DIR/$ziel"
    chmod 0755 "$TARGET_DIR/$ziel"
}

# aapt2 heißt in der APK libaapt2.so, aus demselben Grund wie llama-server: Der Installer
# entpackt nur lib*.so in ein Verzeichnis, aus dem ausgeführt werden darf. Das
# Datenverzeichnis der App scheidet aus, dort verbietet die W^X-Regel das Ausführen.
hole "$WORK/entpackt/aapt2/$PREFIX/bin/aapt2" "libaapt2.so" \
    || die "aapt2 nicht im Paket gefunden."

# Die Bibliotheken. Versionierte Namen werden zu lib*.so, sonst entpackt Android sie nicht.
#
# MITGEBRACHT hält fest, was dieses Skript abgelegt hat. Nur diese Dateien darf es später
# wieder wegräumen — llama-server liegt im selben Verzeichnis und gehört einem anderen Skript.
MITGEBRACHT=("libaapt2.so")
declare -A UMBENANNT=()
for verzeichnis in "$WORK/entpackt"/*/; do
    for bibliothek in "$verzeichnis$PREFIX/lib"/*.so*; do
        [[ -f "$bibliothek" ]] || continue
        basis="$(basename "$bibliothek")"

        # libz.so.1.3.2 und libz.so.1 sind Verknüpfungen auf dieselbe Datei. Genommen wird
        # die echte Datei, benannt wird sie nach dem Namen, den aapt2 verlangt.
        case "$basis" in
            *.so) neu="$basis" ;;
            *.so.*)
                # libz.so.1.3.2 -> libz.so ; libexpat.so.1.11.2 -> libexpat.so
                neu="${basis%%.so.*}.so"
                UMBENANNT["${basis}"]="$neu"
                ;;
            *) continue ;;
        esac

        # Die Buchführung **zuerst**, und zwar unabhängig davon, ob gleich kopiert wird.
        #
        # Hier stand sie hinter dem Kopieren, und das war beim zweiten Lauf falsch: Lagen die
        # Dateien schon da, sprang das Skript weiter, ohne sie zu vermerken — und wusste
        # danach nicht mehr, was ihm gehört. Es räumte nichts weg und meldete „3 MB" statt
        # elf. Ein Skript, das beim zweiten Lauf etwas anderes tut als beim ersten, ist
        # schlimmer als eines, das gar nichts tut.
        MITGEBRACHT+=("$neu")

        # Nur einmal je Zielname, und die größte Datei gewinnt: Bei einer Verknüpfung und
        # ihrem Ziel ist die echte Datei die größere.
        if [[ -f "$TARGET_DIR/$neu" ]] \
            && (( $(stat -c %s "$TARGET_DIR/$neu") >= $(stat -c %s "$bibliothek") )); then
            continue
        fi
        hole "$bibliothek" "$neu"
    done
done

# Die Verweise geradeziehen.
#
# Ausdrücklich über MITGEBRACHT und **nicht** über lib*.so. Beim ersten Anlauf stand hier ein
# Platzhalter über das Verzeichnis, und damit lief patchelf auch über libllama-server.so —
# eine eingecheckte Datei, die einem anderen Skript gehört und die dadurch als geändert
# auftauchte. Ein Skript, das mehr anfasst als es mitgebracht hat, ist kein Werkzeug, sondern
# ein Risiko.
log "RUNPATH und Abhängigkeitsnamen anpassen"
for name in "${MITGEBRACHT[@]}"; do
    datei="$TARGET_DIR/$name"
    [[ -f "$datei" ]] || continue
    # Ohne das sucht der Linker in Termux' Verzeichnis, das es hier nicht gibt.
    patchelf --set-rpath '$ORIGIN' "$datei" 2>/dev/null || true

    # Jeden versionierten Verweis auf den umbenannten Namen zeigen lassen.
    while read -r bedarf; do
        case "$bedarf" in
            *.so.*)
                neu="${bedarf%%.so.*}.so"
                patchelf --replace-needed "$bedarf" "$neu" "$datei" \
                    || warn "  $bedarf in $(basename "$datei") nicht umgehängt"
                ;;
        esac
    done < <(patchelf --print-needed "$datei" 2>/dev/null || true)

    # Und die Bibliothek muss sich selbst so nennen, wie sie heißt.
    #
    # **Das ist die Zeile, an der wochenlang jeder Bauversuch gescheitert ist.** Aus
    # `libz.so.1.3.2` wurde oben `libz.so` — Dateiname und DT_NEEDED der Abhängigen wurden
    # angepasst, der SONAME **in** der Datei blieb `libz.so.1`. Auf dem Telefon sah das so
    # aus:
    #
    #   cannot find "libz.so" from verneed[0] in DT_NEEDED list for ".../libpng16.so"
    #
    # Eine Meldung, die in die Irre führt: `libz.so` steht sehr wohl in DT_NEEDED. Androids
    # Linker vergleicht die Datei aus dem `verneed`-Abschnitt aber nicht mit DT_NEEDED,
    # sondern mit dem **SONAME der bereits geladenen Abhängigkeit**. libpng16.so verlangte
    # Symbolversionen aus `libz.so`, geladen war eine Bibliothek, die sich `libz.so.1` nannte
    # — und damit galt sie als etwas anderes.
    #
    # Nur, wo es schon einen SONAME gibt: aapt2 selbst ist ein Programm und hat keinen.
    soname="$(patchelf --print-soname "$datei" 2>/dev/null || true)"
    if [[ -n "$soname" && "$soname" != "$name" ]]; then
        patchelf --set-soname "$name" "$datei" \
            || warn "  SONAME von $name nicht auf $name gesetzt (war $soname)"
    fi
done

# Was niemand braucht, kommt weg.
#
# abseil bringt über hundert einzelne Bibliotheken mit; aapt2 braucht davon rund die Hälfte.
# Die anderen kosten nicht viel Platz — zwei Megabyte —, aber sie sind schlimmer als
# unnötig: Eine Bibliothek, die niemand referenziert, fällt bei einer Aktualisierung nicht
# auf, wenn sie fehlt, und niemand merkt, dass sie nie gebraucht wurde.
#
# Gerechnet wird der **transitive** Abschluss ab libaapt2.so. Nur was von dort erreichbar
# ist, bleibt liegen.
log "unbenutzte Bibliotheken wegräumen"

SYSTEM=" libc.so libm.so libdl.so liblog.so libstdc++.so libandroid.so "
GEBRAUCHT=" libaapt2.so "
RAND="libaapt2.so"

while [[ -n "$RAND" ]]; do
    NAECHSTE=""
    for name in $RAND; do
        [[ -f "$TARGET_DIR/$name" ]] || continue
        while read -r bedarf; do
            [[ -n "$bedarf" ]] || continue
            [[ "$SYSTEM" == *" $bedarf "* ]] && continue
            [[ "$GEBRAUCHT" == *" $bedarf "* ]] && continue
            GEBRAUCHT+="$bedarf "
            NAECHSTE+="$bedarf "
        done < <(patchelf --print-needed "$TARGET_DIR/$name" 2>/dev/null || true)
    done
    RAND="$NAECHSTE"
done

WEGGERAEUMT=0
for name in "${MITGEBRACHT[@]}"; do
    [[ "$GEBRAUCHT" == *" $name "* ]] && continue
    [[ -f "$TARGET_DIR/$name" ]] || continue
    rm -f "$TARGET_DIR/$name"
    WEGGERAEUMT=$((WEGGERAEUMT + 1))
done
log "  $WEGGERAEUMT weggeräumt, $(($(echo "$GEBRAUCHT" | wc -w) - 1)) Bibliotheken bleiben"

# Und jetzt nachmessen, statt es zu behaupten.
log "Ergebnis prüfen"
FEHLER=0

for name in "${MITGEBRACHT[@]}"; do
    datei="$TARGET_DIR/$name"
    [[ -f "$datei" ]] || continue

    # 16-KB-Seiten. Genau daran ist in diesem Projekt schon einmal alles gescheitert, und
    # ein älterer aapt2-Bau hätte es wieder getan.
    schlechteste=$(readelf -lW "$datei" \
        | awk '$1 == "LOAD" { print $NF }' \
        | while read -r wert; do printf '%d\n' "$wert"; done \
        | sort -n | head -1)
    if [[ -z "$schlechteste" ]]; then
        warn "  $name: keine LOAD-Segmente"
        continue
    fi
    if (( schlechteste < 16384 )); then
        warn "  $name: nur auf $schlechteste Byte ausgerichtet — auf einem Gerät mit
     16-KB-Seiten nicht ladbar."
        FEHLER=1
    fi

    # Kein versionierter Verweis darf übrig sein: Er zeigte auf eine Datei, die Android
    # nie auspackt, und das fällt erst beim Starten auf dem Telefon auf.
    uebrig=$(patchelf --print-needed "$datei" 2>/dev/null | grep -c '\.so\.' || true)
    if (( uebrig > 0 )); then
        warn "  $name: $uebrig versionierte Abhängigkeit(en) übrig:
     $(patchelf --print-needed "$datei" | grep '\.so\.' | tr '\n' ' ')"
        FEHLER=1
    fi

    # Und jede Abhängigkeit, die nicht vom System kommt, muss daneben liegen.
    while read -r bedarf; do
        case "$bedarf" in
            libc.so|libm.so|libdl.so|libz.so|liblog.so|libstdc++.so) continue ;;
        esac
        [[ -f "$TARGET_DIR/$bedarf" ]] || {
            warn "  $name braucht $bedarf, das fehlt in $TARGET_DIR"
            FEHLER=1
        }
    done < <(patchelf --print-needed "$datei" 2>/dev/null || true)

    # Die Bibliothek muss sich so nennen, wie sie heißt.
    #
    # Diese Prüfung ist der eigentliche Grund, warum dieser Abschnitt so lang ist. Der
    # Fehler, den sie fängt, war auf dem Telefon **nicht** als das erkennbar, was er war:
    # Der Linker meldete eine Datei als fehlend, die in DT_NEEDED steht. Wer das im
    # Protokoll liest, sucht an der falschen Stelle. Hier ist es eine Zeile.
    soname="$(patchelf --print-soname "$datei" 2>/dev/null || true)"
    if [[ -n "$soname" && "$soname" != "$name" ]]; then
        warn "  $name nennt sich selbst $soname. Androids Linker vergleicht die verneed-
     Einträge der Abhängigen mit dem SONAME, nicht mit dem Dateinamen — auf dem Gerät
     scheitert das Laden mit „cannot find ... from verneed\"."
        FEHLER=1
    fi

    # Und jede Symbolversion muss von einer Bibliothek kommen, die auch so heißt.
    #
    # Die Gegenprobe zur Zeile darüber, von der anderen Seite aus: Sie fängt auch den Fall,
    # dass ein verneed-Eintrag auf eine Bibliothek zeigt, die gar nicht mitkommt.
    while read -r quelle; do
        [[ -n "$quelle" ]] || continue
        case "$quelle" in
            libc.so|libm.so|libdl.so|libz.so|liblog.so|libstdc++.so) continue ;;
        esac
        [[ -f "$TARGET_DIR/$quelle" ]] || {
            warn "  $name verlangt Symbolversionen aus $quelle, das fehlt in $TARGET_DIR"
            FEHLER=1
        }
    done < <(readelf -VW "$datei" 2>/dev/null \
        | sed -n 's/.*Version: [0-9]*  File: \([^ ]*\)  Cnt.*/\1/p')
done

(( FEHLER == 0 )) || die "Die Werkzeuge sind so nicht startbar. Siehe die Meldungen oben."

log "fertig:"
GESAMT=0
for name in "${MITGEBRACHT[@]}"; do
    datei="$TARGET_DIR/$name"
    [[ -f "$datei" ]] || continue
    GESAMT=$((GESAMT + $(stat -c %s "$datei")))
done
printf '    aapt2 %s samt %d Bibliotheken, zusammen %d MB\n' \
    "$(du -h "$TARGET_DIR/libaapt2.so" | cut -f1)" \
    "$(($(echo "$GEBRAUCHT" | wc -w) - 1))" \
    "$((GESAMT / 1024 / 1024))"
