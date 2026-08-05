#!/usr/bin/env bash
#
# Holt CPython für das Telefon und legt es in die APK-Quellen.
#
# **Warum nicht selbst gebaut.** Python 3.13 hat Android zu einer offiziell unterstützten
# Plattform gemacht, und python.org veröffentlicht seither fertige Bauten dafür — mitsamt
# Signatur. Selbst zu bauen wäre möglich (`./android.py build arm64-v8a`), würde aber zwanzig
# Minuten jeder CI-Runde kosten und am Ende dasselbe ergeben, nur mit schlechterer Herkunft:
# Ein selbst gebautes CPython ist von niemandem gegengezeichnet, dieses von python.org schon.
#
# Nachgemessen (nicht vermutet): Alle 80 mitgelieferten Bibliotheken sind auf 16-KB-Seiten
# ausgerichtet, einschließlich der 67 Erweiterungsmodule der Standardbibliothek. Eine einzige
# falsch ausgerichtete davon hätte genau ein Modul abgerissen — und das fällt erst beim Import
# auf, also im Betrieb.
#
# **Zwei Wege in die APK, aus einem Grund.**
#
#  - Die großen Bibliotheken (libpython, OpenSSL, SQLite) gehen nach jniLibs. Androids
#    Installer entpackt sie beim Installieren in ein Verzeichnis, das der Linker findet.
#  - Die Standardbibliothek geht als ZIP nach assets. Sie besteht aus über dreitausend
#    Dateien, darunter 67 Erweiterungsmodule, deren Namen nicht auf `lib` beginnen — der
#    Installer würde sie gar nicht auspacken. Neon entpackt das ZIP beim ersten Start einmal
#    in sein Datenverzeichnis; von dort kann Python sie laden.
#
# Voraussetzungen: curl, tar, zip.
#
#   ./scripts/fetch-python.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${PYTHON_DIR:-$ROOT/.python-android}"
JNILIBS="$ROOT/app/src/main/jniLibs/arm64-v8a"
ASSETS="$ROOT/app/src/main/assets"

log()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m!!\033[0m %s\n' "$*" >&2; exit 1; }

for werkzeug in curl tar zip; do
    command -v "$werkzeug" >/dev/null || die "$werkzeug fehlt."
done

# Feste Fassung mit Prüfsumme. Dieselbe Lehre wie bei llama.cpp und aapt2: Wer "neueste"
# schreibt, ändert beim nächsten Lauf unbeabsichtigt zwei Dinge auf einmal.
#
# Die Prüfsumme ist selbst gerechnet und nicht abgelesen — python.org veröffentlicht zu diesen
# Archiven keine .sha256-Datei, sondern .sig und .sigstore. Wer die Signatur prüfen will:
#
#   python -m sigstore verify identity --cert-identity ... python-VERSION-...tar.gz
#
# Hier steht die Prüfsumme, weil sie ohne weitere Werkzeuge nachvollziehbar ist und in der CI
# denselben Zweck erfüllt: Was einmal geprüft wurde, bleibt dasselbe.
PYTHON_VERSION="${PYTHON_VERSION:-3.14.6}"
PYTHON_SHA256="38bbe77d3167b5cd554e03b1021324926f09f3825202b065951dd7638e9c37e5"

ARCHIV="python-$PYTHON_VERSION-aarch64-linux-android.tar.gz"
QUELLE="https://www.python.org/ftp/python/$PYTHON_VERSION/$ARCHIV"

mkdir -p "$WORK" "$JNILIBS" "$ASSETS"

if [[ ! -f "$WORK/$ARCHIV" ]]; then
    log "hole CPython $PYTHON_VERSION für aarch64"
    curl -sSL --fail --max-time 600 -o "$WORK/$ARCHIV" "$QUELLE" \
        || die "CPython ließ sich nicht holen: $QUELLE"
fi

IST="$(sha256sum "$WORK/$ARCHIV" | cut -d' ' -f1)"
[[ "$IST" == "$PYTHON_SHA256" ]] || die "Prüfsumme von $ARCHIV stimmt nicht.
     erwartet: $PYTHON_SHA256
     erhalten: $IST
     Entweder wurde die Fassung ersetzt, oder unterwegs hat jemand mitgeschrieben."
log "  Prüfsumme stimmt"

log "entpacken"
rm -rf "$WORK/entpackt" && mkdir -p "$WORK/entpackt"
tar xzf "$WORK/$ARCHIV" -C "$WORK/entpackt"

PREFIX="$WORK/entpackt/prefix"
[[ -d "$PREFIX/lib" ]] || die "prefix/lib fehlt im Archiv — hat sich der Aufbau geändert?"

STDLIB="$(find "$PREFIX/lib" -maxdepth 1 -type d -name 'python3.*' | head -1)"
[[ -n "$STDLIB" ]] || die "Standardbibliothek nicht gefunden."
KURZFASSUNG="$(basename "$STDLIB")"   # etwa python3.14

# Die Bibliotheken der obersten Ebene nach jniLibs.
#
# Versionierte Namen wie libsqlite3.so.0 werden übergangen: Androids Installer entpackt nur
# Dateien, deren Name auf .so endet, und die unversionierte Fassung liegt daneben.
log "Bibliotheken nach jniLibs"
MITGEBRACHT=()
declare -A UMTAUFEN=()
for bibliothek in "$PREFIX/lib"/*.so; do
    [[ -f "$bibliothek" ]] || continue
    name="$(basename "$bibliothek")"
    cp -f "$bibliothek" "$JNILIBS/$name"
    chmod 0644 "$JNILIBS/$name"
    MITGEBRACHT+=("$name")

    # Die Bibliothek muss sich so nennen, wie sie hier heißt.
    #
    # Termux baut OpenSSL und SQLite unter eigenen Namen: Die Datei heißt `libssl.so`, der
    # SONAME darin lautet `libssl_python.so`, und `libssl.so` verlangt entsprechend
    # `libcrypto_python.so`. In Termux gibt es diese Datei; hier nicht — Androids Installer
    # legt sie als `libcrypto.so` ab. Der Linker suchte also nach einem Namen, den es im
    # Verzeichnis nicht gibt.
    #
    # Aufgefallen ist derselbe Fehler auf der aapt2-Seite, wo er jeden Bauversuch zerlegt
    # hat. Hier ist er stiller: Es scheitert allein `import ssl`, und zwar erst dann.
    soname="$(patchelf --print-soname "$JNILIBS/$name" 2>/dev/null || true)"
    if [[ -n "$soname" && "$soname" != "$name" ]]; then
        patchelf --set-soname "$name" "$JNILIBS/$name" \
            || warn "  SONAME von $name bleibt $soname"
        UMTAUFEN["$soname"]="$name"
    fi
done

# Und jeden Verweis auf den alten Namen mitziehen.
#
# Zwei Durchgänge, weil `libssl.so` auf `libcrypto_python.so` zeigt und beide erst nach dem
# ersten Durchgang bekannt sind. Ein einzelner Durchgang hätte die Reihenfolge im
# Verzeichnis zur Bedingung gemacht — und die ist nirgends zugesichert.
for name in "${MITGEBRACHT[@]}"; do
    for alt in "${!UMTAUFEN[@]}"; do
        patchelf --replace-needed "$alt" "${UMTAUFEN[$alt]}" "$JNILIBS/$name" 2>/dev/null || true
    done
done
(( ${#MITGEBRACHT[@]} > 0 )) || die "keine Bibliothek gefunden."

# Die Standardbibliothek als ZIP.
#
# Ohne Kompression der .so-Dateien wäre das ZIP größer als nötig; mit Kompression dauert das
# Entpacken beim ersten Start länger. Genommen wird die Kompression: Sie kostet einmal ein
# paar Sekunden, das Herunterladen der APK kostet jeden.
log "Standardbibliothek einpacken ($KURZFASSUNG)"
rm -f "$ASSETS/python-stdlib.zip"
(cd "$PREFIX/lib" && zip -q -r -9 "$ASSETS/python-stdlib.zip" "$KURZFASSUNG" \
    -x '*/test/*' -x '*/tests/*' -x '*/idlelib/*' -x '*/tkinter/*' -x '*/turtledemo/*' \
    -x '*__pycache__*') \
    || die "Standardbibliothek ließ sich nicht einpacken."

# Was ausgelassen wird und warum:
#   test, tests    — die Testsuite von CPython, rund 25 MB, auf einem Telefon nutzlos
#   idlelib        — die IDLE-Oberfläche, braucht Tk
#   tkinter        — Tk gibt es auf Android nicht, der Import scheitert ohnehin
#   turtledemo     — Beispiele für ein Modul, das Tk braucht
#   __pycache__    — Bytecode für eine andere Fassung, wird ohnehin neu erzeugt

log "Ergebnis prüfen"
FEHLER=0

# 16-KB-Seiten für alles, was mitgeht — auch für die Erweiterungsmodule im ZIP.
#
# Diese Prüfung ist der Grund, warum dieses Skript nicht einfach kopiert: Eine einzelne
# falsch ausgerichtete Erweiterung reißt genau ein Modul ab, und zwar erst beim Import. Wer
# `import sqlite3` schreibt und einen Linker-Fehler bekommt, sucht lange.
pruefe_ausrichtung() {
    local datei="$1" name="$2"
    local schlechteste
    schlechteste=$(readelf -lW "$datei" 2>/dev/null \
        | awk '$1 == "LOAD" { print $NF }' \
        | while read -r wert; do printf '%d\n' "$wert"; done \
        | sort -n | head -1)
    [[ -n "$schlechteste" ]] || return 0
    if (( schlechteste < 16384 )); then
        warn "  $name: nur auf $schlechteste Byte ausgerichtet"
        FEHLER=1
    fi
}

for name in "${MITGEBRACHT[@]}"; do
    pruefe_ausrichtung "$JNILIBS/$name" "$name"

    # Jede Bibliothek muss sich so nennen, wie sie heißt, und darf nur nach Namen greifen,
    # die es hier gibt. Androids Linker sucht nach dem Namen aus DT_NEEDED und vergleicht
    # verneed-Einträge mit dem SONAME der geladenen Datei — beides sind Namen **in** der
    # Datei, keine Dateinamen. Weichen sie ab, scheitert der Import, und zwar erst auf dem
    # Telefon.
    soname="$(patchelf --print-soname "$JNILIBS/$name" 2>/dev/null || true)"
    if [[ -n "$soname" && "$soname" != "$name" ]]; then
        warn "  $name nennt sich selbst $soname"
        FEHLER=1
    fi
    while read -r bedarf; do
        case "$bedarf" in
            lib[cmd]*.so|liblog.so|libz.so|libstdc++.so|libandroid.so|libdl.so) continue ;;
        esac
        [[ -f "$JNILIBS/$bedarf" ]] || {
            warn "  $name braucht $bedarf, das fehlt in $JNILIBS"
            FEHLER=1
        }
    done < <(patchelf --print-needed "$JNILIBS/$name" 2>/dev/null || true)
done

ANZAHL_MODULE=0
while read -r modul; do
    pruefe_ausrichtung "$modul" "$(basename "$modul")"
    ANZAHL_MODULE=$((ANZAHL_MODULE + 1))
done < <(find "$STDLIB" -name '*.so' -type f)

# Und die Standardbibliothek muss das enthalten, wonach Neon gleich greift.
#
# Kein `grep -q` in dieser Pipeline. Es beendet sich beim ersten Treffer, `unzip` bekommt
# dadurch ein SIGPIPE, und unter `set -o pipefail` scheitert die ganze Pipeline — der Treffer
# ist da, die Prüfung meldet trotzdem "fehlt". Genau das ist hier passiert, und zwar bei allen
# sechs Modulen auf einmal. Die Falle steht seit Wochen in scripts/build-llama-server.sh
# beschrieben, eine Datei weiter; ich bin trotzdem hineingelaufen. `grep -c` liest seine
# Eingabe zu Ende.
#
# Verzeichnisse mit Schrägstrich, denn `json` und `sqlite3` sind Pakete und keine Dateien —
# nach `json.py` zu suchen wäre auch ohne die SIGPIPE-Falle fehlgeschlagen.
log "Standardbibliothek auf Vollständigkeit prüfen"
INHALT="$(unzip -l "$ASSETS/python-stdlib.zip")"
for modul in os.py subprocess.py "json/__init__.py" "sqlite3/__init__.py" \
             "encodings/__init__.py" "zipfile/__init__.py"; do
    TREFFER=$(printf '%s\n' "$INHALT" | grep -cF "$KURZFASSUNG/$modul" || true)
    if (( TREFFER == 0 )); then
        warn "  $modul fehlt in python-stdlib.zip"
        FEHLER=1
    fi
done

# Und die Testsuite darf **nicht** drin sein: Sie ist rund 25 MB, auf einem Telefon nutzlos,
# und wenn sie versehentlich mitgeht, fällt das nur an der APK-Größe auf.
TESTS=$(printf '%s\n' "$INHALT" | grep -cE "$KURZFASSUNG/test/" || true)
if (( TESTS > 0 )); then
    warn "  die Testsuite ist mitgegangen: $TESTS Einträge"
    FEHLER=1
fi

# Und passt der Starter noch zu dieser Python-Fassung?
#
# **Der Fehler, den das verhindert.** libpython3.14.so und libpython3.15.so sind zwei
# verschiedene Namen. Der Starter ist gegen einen davon gebaut und trägt ihn fest in sich. Wird
# hier die Python-Fassung angehoben, ohne den Starter neu zu bauen, liegt in der APK ein
# Programm, das eine Bibliothek verlangt, die nicht mehr mitkommt — und das fällt erst auf dem
# Telefon auf, als "Python startet nicht".
#
# Der Starter ist eingecheckt, weil die CI kein NDK hat. Genau deshalb kann er hier veralten,
# ohne dass es jemand merkt.
LAUNCHER="$JNILIBS/libpython-launcher.so"
if [[ -f "$LAUNCHER" ]] && command -v readelf >/dev/null; then
    KURZE_FASSUNG="${KURZFASSUNG#python}"       # 3.14
    VERLANGT=$(readelf -dW "$LAUNCHER" | grep -oE 'libpython[0-9.]+\.so' | head -1)
    if [[ "$VERLANGT" != "libpython$KURZE_FASSUNG.so" ]]; then
        warn "  Der Starter verlangt $VERLANGT, mitgeliefert wird libpython$KURZE_FASSUNG.so.
     Mit scripts/build-python-launcher.sh neu bauen (braucht ANDROID_NDK)."
        FEHLER=1
    else
        log "  der Starter passt zu $KURZFASSUNG"
    fi
fi

(( FEHLER == 0 )) || die "So ist die Umgebung nicht benutzbar. Siehe die Meldungen oben."

log "fertig:"
printf '    CPython %s, %d Bibliotheken (%s), %d Erweiterungsmodule geprüft\n' \
    "$PYTHON_VERSION" "${#MITGEBRACHT[@]}" \
    "$(du -ch "${MITGEBRACHT[@]/#/$JNILIBS/}" | tail -1 | cut -f1)" \
    "$ANZAHL_MODULE"
printf '    python-stdlib.zip %s\n' "$(du -h "$ASSETS/python-stdlib.zip" | cut -f1)"
