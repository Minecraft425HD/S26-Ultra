package de.neon.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welche übrig gebliebenen Server beendet werden — und welche nicht.
 *
 * **Der Anlass.** Neon startet `llama-server` als Kindprozess. Stirbt der App-Prozess,
 * überlebt das Kind: Es lädt weiter und hält 2,4 GB Modell offen. `stopProcess()` kann
 * daran nichts ändern, weil es nur den Handle kennt, der mit dem alten App-Prozess
 * verschwunden ist.
 *
 * Auf dem Gerät starb die App sechsmal in vier Minuten. Sechs übrig gebliebene Server
 * machen aus einem Speicherproblem eine Kettenreaktion.
 *
 * Beim Erschlagen fremder Prozesse ist die Auswahl der ganze Punkt — deshalb steht sie als
 * reine Funktion da und wird hier festgenagelt.
 */
class OrphanedServersTest {

    private val pfad = "/data/app/de.neon.app-xY7/lib/arm64/libllama-server.so"

    private val laufend = listOf(
        RunningProcess(1000, "de.neon.app"),
        RunningProcess(1042, "$pfad --model /data/.../qwen3-4b-instruct.gguf --port 18080"),
        RunningProcess(1088, "$pfad --model /data/.../qwen3-4b-instruct.gguf --port 18080"),
        RunningProcess(1200, "/system/bin/logd"),
        RunningProcess(1300, "/data/app/de.andere.app-Z9/lib/arm64/libllama-server.so --model x"),
    )

    @Test
    fun `beendet die eigenen uebrig gebliebenen Server`() {
        val opfer = OrphanedServers.toKill(laufend, pfad, ownPid = 1000)

        assertEquals(listOf(1042, 1088), opfer)
    }

    @Test
    fun `verschont den eigenen Prozess`() {
        // Sonst erschlägt Neon sich selbst beim Aufräumen — ein Fehler, der wie ein
        // Absturz aussähe und beim nächsten Start sofort wieder passierte.
        val opfer = OrphanedServers.toKill(laufend, pfad, ownPid = 1000)

        assertFalse(1000 in opfer)
    }

    @Test
    fun `verschont einen Server, der bleiben soll`() {
        val opfer = OrphanedServers.toKill(laufend, pfad, ownPid = 1000, keepPid = 1042)

        assertEquals(listOf(1088), opfer)
    }

    @Test
    fun `verschont llama-server aus einer anderen Installation`() {
        // Der Pfad enthält den Paketnamen. Ein gleichnamiges Programm einer anderen App
        // gehört nicht uns — und ab Android 7 wäre es ohnehin nicht sichtbar. Die Prüfung
        // steht trotzdem: Sie kostet nichts und beschreibt die Absicht.
        val opfer = OrphanedServers.toKill(laufend, pfad, ownPid = 1000)

        assertFalse(1300 in opfer, "ein fremder Prozess wurde ausgewählt")
    }

    @Test
    fun `ohne Uebriggebliebene wird nichts beendet`() {
        val nurWirSelbst = listOf(
            RunningProcess(1000, "de.neon.app"),
            RunningProcess(1200, "/system/bin/logd"),
        )

        assertTrue(OrphanedServers.toKill(nurWirSelbst, pfad, ownPid = 1000).isEmpty())
    }

    @Test
    fun `eine leere Prozessliste ist kein Fehler`() {
        assertTrue(OrphanedServers.toKill(emptyList(), pfad, ownPid = 1000).isEmpty())
    }

    @Test
    fun `das Lesen von proc wirft nicht`() {
        // In dieser Umgebung gibt es /proc, auf anderen Systemen nicht. Beides muss gehen.
        val prozesse = OrphanedServers.readProcesses()
        println("${prozesse.size} Prozesse gelesen")

        // Und die Befehlszeilen dürfen keine NUL-Bytes mehr enthalten, sonst greift der
        // Vergleich mit dem Pfad nicht.
        assertTrue(prozesse.none { it.commandLine.contains('\u0000') })
    }
}
