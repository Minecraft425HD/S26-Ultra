package de.neon.memory

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hält die handgeschriebene Migration und Rooms Erwartung zusammen.
 *
 * Room vergleicht beim Öffnen das vorgefundene Schema mit dem, was es aus den Entitäten
 * ableitet, und verweigert bei jeder Abweichung den Dienst — ein fehlendes `NOT NULL`
 * genügt. Das passiert erst auf dem Gerät und erst bei denen, die ein Update installieren
 * statt neu anzufangen: also genau bei dem, dessen Daten man nicht verlieren will.
 *
 * Deshalb wird hier nicht die Migration ausgeführt, sondern ihr SQL gegen das von Room
 * exportierte Schema gehalten. Ändert jemand [ChatEntryEntity], ohne die Migration
 * nachzuziehen, scheitert dieser Test — nicht das Telefon.
 */
class MigrationSchemaTest {

    @Test
    fun `die Migration erzeugt genau die Tabelle, die Room erwartet`() {
        val erwartet = createSqlAus(schemaDatei(), "chat_entries")

        assertEquals(
            normalisiert(erwartet),
            normalisiert(NeonDatabase.CREATE_CHAT_ENTRIES),
            "Migration und Room-Schema laufen auseinander",
        )
    }

    @Test
    fun `das exportierte Schema hat die erwartete Version`() {
        val inhalt = schemaDatei().readText()
        assertTrue(
            Regex("\"version\"\\s*:\\s*2").containsMatchIn(inhalt),
            "die Schemadatei gehört nicht zu Version 2",
        )
    }

    @Test
    fun `die alten Tabellen sind noch da`() {
        // Eine additive Migration darf nichts wegnehmen. Fiele eine dieser Tabellen weg,
        // wären Gedächtnis oder gelernte Beispiele verloren — und zwar unbemerkt.
        val inhalt = schemaDatei().readText()
        listOf("route_outcomes", "routing_examples", "memory_facts").forEach {
            assertTrue(inhalt.contains("\"tableName\": \"$it\""), "Tabelle $it fehlt im Schema")
        }
    }

    /**
     * Zieht das `createSql` der gesuchten Tabelle heraus.
     *
     * Von Hand statt mit einem JSON-Leser: Dieses Modul bringt keinen mit, und für eine
     * einzelne Zeichenkette lohnt keine weitere Abhängigkeit.
     */
    private fun createSqlAus(datei: File, tabelle: String): String {
        val inhalt = datei.readText()
        val block = Regex(
            "\"tableName\"\\s*:\\s*\"$tabelle\".*?\"createSql\"\\s*:\\s*\"(.*?)\"\\s*,",
            RegexOption.DOT_MATCHES_ALL,
        ).find(inhalt)

        val roh = assertNotNull(block, "createSql für $tabelle nicht gefunden").groupValues[1]
        return roh
            .replace("\\\"", "\"")
            .replace("\\n", " ")
            .replace("\\u0060", "`")
            // Room schreibt einen Platzhalter statt des Tabellennamens.
            .replace("\${TABLE_NAME}", tabelle)
    }

    private fun normalisiert(sql: String): String =
        sql.replace(Regex("\\s+"), " ").trim().removeSuffix(";")

    private fun schemaDatei(): File {
        val wurzel = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Projektwurzel nicht gefunden")

        val datei = File(wurzel, "core/memory/schemas/de.neon.memory.NeonDatabase/2.json")
        assertTrue(
            datei.isFile,
            "Schemadatei fehlt: ${datei.path}. Sie entsteht beim Bauen und gehört ins Repository.",
        )
        return datei
    }
}
