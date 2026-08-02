package de.neon.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bei starker Hitze gewinnt das kleine Modell — jetzt gemessen statt vermutet.
 *
 * **Die Zahlen stammen aus einem Geräteprotokoll vom 2. August.** Seit der Wärmezustand in
 * der Antwortzeile steht, lässt sich die Frage beantworten, die vorher offen war: ob der
 * Tempoverfall Hitze ist oder nur der Taktverlauf des Prozessors.
 *
 * ```
 * 10:29:37  1.7B  471 Token,  19561 ms  ·  Wärme none      27,59 t/s
 * 10:30:22  4B    103 Token,  22051 ms  ·  Wärme moderate  12,77 t/s
 * 10:34:12  4B    999 Token, 138155 ms  ·  Wärme severe     8,29 t/s
 * 17:05:28  1.7B  662 Token,  28647 ms  ·  Wärme moderate  25,92 t/s
 * 18:55:56  1.7B  624 Token,  25342 ms  ·  Wärme light     26,72 t/s
 * ```
 *
 * Es ist Hitze, und sie trifft die beiden Modelle völlig ungleich: Das große verliert ein
 * Drittel, das kleine praktisch nichts. Das ist plausibel — gedrosselt wird anhaltende
 * Rechenlast, und davon hat das 4-B-Modell dreimal so viel je Token.
 *
 * Die Sparmodus-Grenze lag bei drei Gigabyte und ließ das 4-B-Modell (2,33 GB) auch bei
 * `severe` durch. Genau das ist im Protokoll passiert: 138 Sekunden für eine Antwort, für die
 * das kleine Modell rund 35 gebraucht hätte.
 */
class HitzeTest {

    private val gb = 1024L * 1024 * 1024

    private val registry = ModelRegistry.defaultForS26Ultra()

    private fun modell(id: String) = registry.generativeModels().first { it.id == id }

    /** Die gemessenen Dateigrößen vom Gerät, nicht die Angaben aus der Registry. */
    private val gemessen = mapOf(
        "qwen3-1.7b-instruct" to 1_056L * 1024 * 1024,
        "qwen3-4b-instruct" to 2_381L * 1024 * 1024,
    )

    private fun zustand(waerme: ThermalStatus) = DeviceState.unknown().copy(
        thermalStatus = waerme,
        availableMemoryBytes = 8 * gb,
        gemesseneGroessen = gemessen,
        // Nur die beiden Modelle, die auf dem Gerät tatsächlich liegen. Ohne das stünden
        // auch der Denker und das Bildmodell zur Wahl, die niemand importiert hat.
        availableModelIds = gemessen.keys,
    )

    @Test
    fun `ohne Hitze ist das Alltagsmodell zugelassen`() {
        val policy = SelectionPolicy(registry)
        val gewaehlt = policy.select(analyse(komplexitaet = 3), zustand(ThermalStatus.NONE))

        assertTrue(
            gewaehlt.model.id in setOf("qwen3-4b-instruct", "qwen3-1.7b-instruct"),
            "unerwartete Wahl: ${gewaehlt.model.id}",
        )
    }

    @Test
    fun `bei maessiger Hitze bleibt das Alltagsmodell erlaubt`() {
        // Bei `moderate` verliert das 4-B-Modell messbar, aber nicht dramatisch: 12,77 statt
        // rund 15 Token je Sekunde. Das ist kein Grund, es auszuschließen.
        val zustand = zustand(ThermalStatus.MODERATE)
        assertTrue(zustand.isConstrained, "Vorbedingung: der Sparmodus greift")
        assertTrue(
            zustand.groesse(modell("qwen3-4b-instruct")) <= zustand.groessengrenze(),
            "das Alltagsmodell fiel bei moderater Hitze heraus",
        )
    }

    @Test
    fun `bei starker Hitze faellt das Alltagsmodell heraus, das kleine bleibt`() {
        val heiss = zustand(ThermalStatus.SEVERE)

        assertTrue(
            heiss.groesse(modell("qwen3-4b-instruct")) > heiss.groessengrenze(),
            "das 4-B-Modell ist bei severe weiterhin zugelassen — 138 s für eine Antwort",
        )
        assertTrue(
            heiss.groesse(modell("qwen3-1.7b-instruct")) <= heiss.groessengrenze(),
            "das kleine Modell wurde mit ausgeschlossen — dann bliebe gar nichts",
        )
    }

    @Test
    fun `bei starker Hitze waehlt die Auswahl das kleine Modell`() {
        // Komplexität 2 — eine Aufgabe, die das kleine Modell laut seinem Band abdeckt.
        val policy = SelectionPolicy(registry)
        val gewaehlt = policy.select(analyse(komplexitaet = 2), zustand(ThermalStatus.SEVERE))

        assertEquals(
            "qwen3-1.7b-instruct",
            gewaehlt.model.id,
            "bei severe muss das kleine Modell gewinnen: es hält 26 Token je Sekunde, " +
                "während das große auf 8,3 fällt",
        )
    }

    @Test
    fun `eine Aufgabe, die das kleine Modell nicht abdeckt, geht auch heiss ans grosse`() {
        // **Das ist Absicht und keine Lücke.** Das 1.7B deckt Komplexität bis 2 ab. Bei einer
        // schwereren Frage bleibt nur das große Modell, auch wenn es heiß ist — und dann ist
        // eine langsame Antwort besser als keine. Die Lockerungsregel der Auswahl greift
        // genau hier, und dieser Test hält fest, dass sie es weiterhin tut.
        val gewaehlt = SelectionPolicy(registry)
            .select(analyse(komplexitaet = 3), zustand(ThermalStatus.SEVERE))

        assertEquals("qwen3-4b-instruct", gewaehlt.model.id)
        assertTrue(
            gewaehlt.constraintsRelaxed,
            "die Wahl kam ohne Lockerung zustande — dann greift die Hitzegrenze nicht",
        )
    }

    @Test
    fun `ein schon geladenes Modell wird bei Hitze nicht getauscht`() {
        // Es neu zu tauschen hieße: Serverneustart, Modell von der Platte lesen, Prompt neu
        // rechnen — alles unter Volllast, und das befeuert genau die Hitze, die man loswerden
        // will. Die Ausnahme für geladene Modelle stand schon vorher da; hier steht, dass sie
        // auch bei starker Hitze gilt.
        val heiss = zustand(ThermalStatus.SEVERE).copy(
            loadedModelIds = setOf("qwen3-4b-instruct"),
        )
        val gewaehlt = SelectionPolicy(registry).select(analyse(komplexitaet = 3), heiss)

        assertEquals("qwen3-4b-instruct", gewaehlt.model.id)
    }

    private fun analyse(komplexitaet: Int) = RouteAnalysis(
        category = TaskCategory.WISSENSFRAGE,
        complexity = komplexitaet,
        confidence = 0.9,
        source = AnalysisSource.KNN,
    )
}
