package de.neon.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Die Auswahl rechnet mit der Datei, nicht mit dem Eintrag.
 *
 * [ModelSpec.sizeBytes] steht im Quelltext und beschreibt eine Datei, die es zu diesem
 * Zeitpunkt noch gar nicht gibt — jemand importiert sie später. Auf dem Gerät wich sie um
 * den Faktor zwölf ab: Der Eintrag `qwen3-coder-7b` nennt 4,5 GB, die importierte Datei hatte
 * 378 MB.
 *
 * Betroffen war nicht nur eine Anzeige. An der Größe hängen vier Entscheidungen: ob ein
 * Modell in den Speicher passt, ob es im Sparmodus zugelassen ist, welches bei gleicher
 * Abdeckung gewinnt und welches als letzte Rettung bleibt. Alle vier liefen mit der
 * Behauptung, und zwar auch dann, wenn die Wahrheit einen Fingerbreit entfernt auf der
 * Platte lag.
 */
class GemesseneGroesseTest {

    private val mb = 1024L * 1024
    private val gb = 1024 * mb

    private fun spec(id: String, deklariert: Long, maxComplexity: Int = 5) = ModelSpec(
        id = id,
        displayName = id,
        role = ModelRole.CODE,
        sizeBytes = deklariert,
        capabilities = setOf(Capability.TEXT),
        maxComplexity = maxComplexity,
        tokensPerSecond = 13.0,
        loadCostMillis = 3_200,
        energyPerToken = 2.1,
    )

    private val coder = spec("qwen3-coder-7b", deklariert = (4.5 * gb).toLong())

    private fun zustand(freiBytes: Long, gemessen: Map<String, Long> = emptyMap()) =
        DeviceState.unknown().copy(
            availableMemoryBytes = freiBytes,
            gemesseneGroessen = gemessen,
        )

    @Test
    fun `ohne Messung bleibt die Angabe aus der Registry`() {
        // Solange die Datei fehlt, ist die Schätzung das Beste, was es gibt — und besser als
        // gar keine Zahl, denn ohne sie liefe jede Speicherprüfung ins Leere.
        assertEquals((4.5 * gb).toLong(), zustand(freiBytes = 8 * gb).groesse(coder))
    }

    @Test
    fun `mit Messung gilt die Datei`() {
        val zustand = zustand(freiBytes = 8 * gb, gemessen = mapOf(coder.id to 378 * mb))
        assertEquals(378 * mb, zustand.groesse(coder))
    }

    @Test
    fun `ein kleiner gemessenes Modell passt in einen Speicher, in dem der Eintrag nicht passt`() {
        val eng = 1 * gb

        assertFalse(
            zustand(freiBytes = eng).fitsInMemory(coder),
            "ohne Messung gilt der Eintrag mit 4,5 GB — der passt in 1 GB nicht",
        )
        assertTrue(
            zustand(freiBytes = eng, gemessen = mapOf(coder.id to 378 * mb)).fitsInMemory(coder),
            "gemessen sind es 378 MB, und die passen",
        )
    }

    @Test
    fun `ein groesser gemessenes Modell passt nicht mehr, obwohl der Eintrag es zuliesse`() {
        // Die gefährliche Richtung: Der Eintrag untertreibt, Neon lädt los, Android erschlägt
        // den Prozess. Diese Prüfung ist der Grund, warum die Messung beide Wege gehen muss
        // und nicht nur der bequeme.
        val untertrieben = spec("angeblich-klein", deklariert = 1 * gb)
        val platz = 2 * gb

        assertTrue(zustand(freiBytes = platz).fitsInMemory(untertrieben))
        assertFalse(
            zustand(freiBytes = platz, gemessen = mapOf(untertrieben.id to 5 * gb))
                .fitsInMemory(untertrieben),
        )
    }

    @Test
    fun `im Sparmodus entscheidet die gemessene Groesse ueber die Zulassung`() {
        // Der Sparmodus verbannt große Modelle. „Groß" muss dabei heißen: wirklich groß.
        val sparmodus = DeviceState.unknown().copy(
            batteryPercent = 10,
            isCharging = false,
            availableMemoryBytes = 8 * gb,
            gemesseneGroessen = mapOf(coder.id to 378 * mb),
        )
        assertTrue(sparmodus.isConstrained, "die Vorbedingung des Tests")

        val policy = SelectionPolicy(ModelRegistry(listOf(coder)))
        val analyse = RouteAnalysis(
            category = TaskCategory.CODE,
            complexity = 3,
            confidence = 0.9,
            source = AnalysisSource.KNN,
        )

        val gewaehlt = policy.select(analyse, sparmodus)
        assertFalse(
            gewaehlt.constraintsRelaxed,
            "378 MB sind kein großes Modell — der Sparmodus darf sie nicht verbannen und " +
                "damit die Auswahl in die Lockerung zwingen",
        )
        assertEquals(coder.id, gewaehlt.model.id)
    }
}
