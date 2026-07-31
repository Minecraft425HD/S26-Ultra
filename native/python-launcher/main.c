/*
 * Startet CPython als eigenen Prozess.
 *
 * **Warum es diese Datei gibt.** Das offizielle Python-Paket für Android von python.org bringt
 * keinen Interpreter mit — nur libpython3.x.so, die zum Einbetten gedacht ist. Wer Python
 * benutzen will, ruft aus eigenem C-Code Py_BytesMain oder Py_Initialize auf.
 *
 * **Warum ein Prozess und keine Einbettung in die App.** Hier laufen Skripte, die ein
 * Sprachmodell geschrieben hat. Eine Endlosschleife, ein Speicherfresser, ein Absturz im
 * C-Teil einer Erweiterung — all das ist zu erwarten und nichts davon darf Neon mitnehmen.
 * Genau dieselbe Überlegung trägt llama-server: Ein Modell, das den Speicher sprengt, reißt
 * die Hörschleife nicht mit. Ein eigener Prozess lässt sich außerdem abbrechen, und ein
 * eingebetteter Interpreter nicht.
 *
 * Py_BytesMain ist dasselbe main(), das das echte python3-Programm benutzt. Damit verhält
 * sich dieser Starter wie ein gewöhnliches python3: Argumente, Rückgabewert, stdin, stdout,
 * stderr — alles unverändert. Das ist der Grund, so wenig wie möglich hier zu tun: Jede Zeile
 * mehr wäre eine Abweichung von einem Verhalten, das Millionen Skripte voraussetzen.
 *
 * Wo die Standardbibliothek liegt, sagt PYTHONHOME. Das setzt Neon beim Starten, denn der
 * Pfad steht erst zur Laufzeit fest — er liegt im Datenverzeichnis der App.
 */
#include <Python.h>

int main(int argc, char **argv) {
    return Py_BytesMain(argc, argv);
}
