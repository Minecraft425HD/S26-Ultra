package de.neon.inference

import java.io.File

/** Ein laufender Prozess, so wie `/proc` ihn zeigt. */
data class RunningProcess(val pid: Int, val commandLine: String)

/**
 * Findet `llama-server`-Prozesse, die ihre App nicht überlebt haben sollten.
 *
 * **Das Problem.** Neon startet den Server als Kindprozess. Stirbt der App-Prozess — und
 * auf dem Gerät starb er sechsmal hintereinander —, dann wird der Kindprozess **nicht**
 * mitgenommen. Er lädt weiter, hält das Modell offen und belegt Arbeitsspeicher.
 *
 * `ProcessServerSupervisor.stopProcess` kann daran nichts ändern: Es kennt nur den Handle
 * des Prozesses, den es selbst gestartet hat, und der ist mit dem alten App-Prozess
 * verschwunden. Aus einem Tod wird so leicht eine Kettenreaktion — jeder neue Anlauf legt
 * einen weiteren Server daneben.
 *
 * Die Auswahl steht hier als reine Funktion, damit sie ohne Gerät prüfbar ist; das Lesen
 * von `/proc` und das Beenden bleiben in der Android-Schicht.
 */
object OrphanedServers {

    /**
     * Welche der laufenden Prozesse beendet werden müssen.
     *
     * Erfasst wird, was den eigenen Programmpfad in der Befehlszeile trägt — also nur
     * Prozesse aus dem Verzeichnis dieser Installation. Der eigene Prozess und der gerade
     * erwünschte Server bleiben ausgenommen.
     *
     * @param binaryPath der Pfad, unter dem `libllama-server.so` installiert ist.
     * @param ownPid die eigene Prozesskennung, damit Neon sich nicht selbst erschlägt.
     * @param keepPid ein Server, der bleiben soll, oder `null`.
     */
    fun toKill(
        processes: List<RunningProcess>,
        binaryPath: String,
        ownPid: Int,
        keepPid: Int? = null,
    ): List<Int> = processes
        .filter { it.pid != ownPid && it.pid != keepPid }
        .filter { it.commandLine.contains(binaryPath) }
        .map { it.pid }

    /**
     * Liest die laufenden Prozesse aus `/proc`.
     *
     * Ab Android 7 zeigt `/proc` einer App nur noch die eigenen Prozesse — genau die, um die
     * es hier geht. Prozesse, die sich nicht lesen lassen, werden übersprungen: Zwischen dem
     * Auflisten und dem Lesen kann einer verschwinden, und das ist kein Fehler.
     */
    fun readProcesses(proc: File = File("/proc")): List<RunningProcess> =
        runCatching {
            proc.listFiles()?.mapNotNull { verzeichnis ->
                val pid = verzeichnis.name.toIntOrNull() ?: return@mapNotNull null
                // In `cmdline` trennt ein NUL-Byte die Argumente. Als Escape geschrieben und
                // nicht als echtes Zeichen: Ein rohes NUL im Quelltext übersteht
                // nicht jeden Editor und ist in einem Diff nicht zu sehen.
                val zeile = runCatching {
                    File(verzeichnis, "cmdline").readText().replace('\u0000', ' ').trim()
                }.getOrNull() ?: return@mapNotNull null
                if (zeile.isEmpty()) null else RunningProcess(pid, zeile)
            }.orEmpty()
        }.getOrDefault(emptyList())
}
