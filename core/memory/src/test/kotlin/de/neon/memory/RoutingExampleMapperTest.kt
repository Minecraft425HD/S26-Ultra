package de.neon.memory

import de.neon.router.LabeledExample
import de.neon.router.TaskCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutingExampleMapperTest {

    private val example = LabeledExample(
        text = "wie hoch ist der kölner dom",
        embedding = floatArrayOf(0.1f, 0.2f, 0.3f),
        category = TaskCategory.WISSENSFRAGE,
        complexity = 2,
        weight = 2.0,
    )

    @Test
    fun `haelt Hin- und Rueckweg unveraendert`() {
        val entity = RoutingExampleMapper.toEntity(example, now = 1_000)
        val restored = RoutingExampleMapper.toExample(entity, expectedDimensions = 3)

        assertNotNull(restored)
        assertEquals(example.text, restored.text)
        assertEquals(example.category, restored.category)
        assertEquals(example.complexity, restored.complexity)
        assertEquals(example.weight, restored.weight)
        assertTrue(example.embedding.contentEquals(restored.embedding))
    }

    @Test
    fun `ueberspringt Zeilen mit falscher Vektorlaenge`() {
        // Passiert nach einem Wechsel des Einbettungsverfahrens. Solche Zeilen zu benutzen
        // würde bei jedem Vergleich eine Ausnahme auslösen.
        val entity = RoutingExampleMapper.toEntity(example, now = 0)
        assertNull(RoutingExampleMapper.toExample(entity, expectedDimensions = 512))
    }

    @Test
    fun `ueberspringt unbekannte Kategorien`() {
        val entity = RoutingExampleMapper.toEntity(example, now = 0).copy(category = "KOCHREZEPT")
        assertNull(RoutingExampleMapper.toExample(entity, expectedDimensions = 3))
    }

    @Test
    fun `begrenzt eine ausserhalb liegende Komplexitaet`() {
        val entity = RoutingExampleMapper.toEntity(example, now = 0).copy(complexity = 99)
        val restored = RoutingExampleMapper.toExample(entity, expectedDimensions = 3)
        assertEquals(5, assertNotNull(restored).complexity)
    }

    @Test
    fun `merkt sich den Zeitpunkt`() {
        val entity = RoutingExampleMapper.toEntity(example, now = 42)
        assertEquals(42, entity.createdAtMillis)
    }
}
