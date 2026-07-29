package de.neon.memory

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Ein Eintrag im Protokoll der Durchgänge.
 *
 * Grundlage sowohl für den Diagnose-Screen als auch für die Lernschleife des Routers.
 * Verlässt das Gerät nie.
 */
@Entity(tableName = "route_outcomes")
data class RouteOutcomeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val utteranceText: String,
    val category: String,
    val complexity: Int,
    val analysisSource: String,
    /** `null`, wenn die Regelstufe die Anfrage ohne Modell erledigt hat. */
    val modelId: String?,
    val latencyMs: Long,
    val tokensGenerated: Int,
    val userSignal: String,
    val timestampMillis: Long,
)

/**
 * Ein gelabeltes Beispiel für den kNN-Klassifikator.
 *
 * Wird beim Start geladen und wächst mit jeder Rückmeldung. Das ist der Grund, warum der
 * Router mit der Zeit persönlich wird, ohne dass irgendwo trainiert werden müsste.
 */
@Entity(tableName = "routing_examples")
data class RoutingExampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val embedding: FloatArray,
    val category: String,
    val complexity: Int,
    val weight: Double,
    val createdAtMillis: Long,
) {
    // FloatArray hat keine sinnvolle strukturelle Gleichheit; Room verlangt aber
    // equals/hashCode für data classes mit Arrays.
    override fun equals(other: Any?): Boolean = this === other || (other is RoutingExampleEntity && other.id == id)
    override fun hashCode(): Int = id.hashCode()
}

/**
 * Etwas, das Neon sich über den Nutzer gemerkt hat.
 *
 * Getrennt von den Gesprächsprotokollen, weil hier nicht der Wortlaut zählt, sondern die
 * Aussage — "mag keinen Koriander" bleibt richtig, auch wenn das Gespräch längst vorbei ist.
 */
@Entity(tableName = "memory_facts")
data class MemoryFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val statement: String,
    val embedding: FloatArray,
    /** Grobe Einordnung wie "vorlieben", "termine", "personen". */
    val topic: String,
    val createdAtMillis: Long,
    val lastUsedAtMillis: Long,
    val useCount: Int = 0,
) {
    override fun equals(other: Any?): Boolean = this === other || (other is MemoryFactEntity && other.id == id)
    override fun hashCode(): Int = id.hashCode()
}

/**
 * Eine Zeile des sichtbaren Gesprächsverlaufs.
 *
 * Bewusst getrennt von [RouteOutcomeEntity]: Dort steht, *wie* eine Antwort zustande kam,
 * hier, *was* gesagt wurde. Das Protokoll wird beschnitten und ausgewertet, der Verlauf
 * bleibt so, wie man ihn gelesen hat.
 */
@Entity(tableName = "chat_entries")
data class ChatEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromUser: Boolean,
    val text: String,
    val timestampMillis: Long,
    val modelId: String? = null,
    val routeReason: String? = null,
    val latencyMs: Long = 0,
)

@Dao
interface ChatEntryDao {

    @Insert
    suspend fun insert(entry: ChatEntryEntity): Long

    /**
     * Die neuesten Zeilen — beim Anzeigen wieder umgedreht.
     *
     * Andersherum („die ältesten :limit") würde nach ein paar hundert Durchgängen immer
     * denselben uralten Gesprächsanfang liefern.
     */
    @Query("SELECT * FROM chat_entries ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ChatEntryEntity>

    @Query("DELETE FROM chat_entries")
    suspend fun clear()

    /** Hält den Verlauf in Grenzen, ohne ihn je ganz wegzuwerfen. */
    @Query("DELETE FROM chat_entries WHERE id NOT IN (SELECT id FROM chat_entries ORDER BY id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)
}

/** Speichert Vektoren als kompaktes Byte-Feld statt als Text. */
class FloatArrayConverter {

    @TypeConverter
    fun toBytes(value: FloatArray?): ByteArray? {
        if (value == null) return null
        val buffer = ByteBuffer.allocate(value.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        value.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    @TypeConverter
    fun fromBytes(value: ByteArray?): FloatArray? {
        if (value == null) return null
        val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(value.size / 4) { buffer.getFloat() }
    }
}

@Dao
interface RouteOutcomeDao {

    @Insert
    suspend fun insert(outcome: RouteOutcomeEntity): Long

    @Query("SELECT * FROM route_outcomes ORDER BY timestampMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<RouteOutcomeEntity>

    @Query("SELECT COUNT(*) FROM route_outcomes WHERE modelId IS NULL")
    suspend fun directActionCount(): Int

    @Query("SELECT COUNT(*) FROM route_outcomes")
    suspend fun totalCount(): Int

    /** Alte Einträge wegräumen — das Protokoll soll nicht unbegrenzt wachsen. */
    @Query("DELETE FROM route_outcomes WHERE timestampMillis < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int
}

@Dao
interface RoutingExampleDao {

    @Insert
    suspend fun insert(example: RoutingExampleEntity): Long

    @Query("SELECT * FROM routing_examples")
    suspend fun all(): List<RoutingExampleEntity>

    @Query("SELECT COUNT(*) FROM routing_examples")
    suspend fun count(): Int

    @Query("DELETE FROM routing_examples")
    suspend fun clear()
}

@Dao
interface MemoryFactDao {

    @Insert
    suspend fun insert(fact: MemoryFactEntity): Long

    @Query("SELECT * FROM memory_facts")
    suspend fun all(): List<MemoryFactEntity>

    @Query("UPDATE memory_facts SET useCount = useCount + 1, lastUsedAtMillis = :now WHERE id = :id")
    suspend fun markUsed(id: Long, now: Long)

    @Query("DELETE FROM memory_facts WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [
        RouteOutcomeEntity::class,
        RoutingExampleEntity::class,
        MemoryFactEntity::class,
        ChatEntryEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(FloatArrayConverter::class)
abstract class NeonDatabase : RoomDatabase() {

    abstract fun routeOutcomes(): RouteOutcomeDao
    abstract fun routingExamples(): RoutingExampleDao
    abstract fun memoryFacts(): MemoryFactDao
    abstract fun chatEntries(): ChatEntryDao

    companion object {

        /**
         * Fügt den Gesprächsverlauf hinzu, ohne etwas anzufassen.
         *
         * Ausdrücklich **keine** zerstörende Migration: In dieser Datenbank stehen das
         * persönliche Gedächtnis und die gelernten Routing-Beispiele. Sie beim Update
         * wegzuwerfen, weil eine Tabelle dazukommt, wäre der teuerste denkbare Preis für
         * die bequemste Zeile Code.
         */
        /**
         * Muss Spalte für Spalte dem entsprechen, was Room aus [ChatEntryEntity] ableitet.
         *
         * Room vergleicht beim Öffnen das tatsächliche Schema mit dem erwarteten und
         * verweigert bei jeder Abweichung den Dienst — auch bei einer Kleinigkeit wie einem
         * fehlenden NOT NULL. Als Konstante herausgezogen, damit ein Test sie gegen das von
         * Room exportierte Schema halten kann, statt dass es beim Nutzer auffällt.
         */
        const val CREATE_CHAT_ENTRIES =
            "CREATE TABLE IF NOT EXISTS `chat_entries` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`fromUser` INTEGER NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "`timestampMillis` INTEGER NOT NULL, " +
                "`modelId` TEXT, " +
                "`routeReason` TEXT, " +
                "`latencyMs` INTEGER NOT NULL)"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_CHAT_ENTRIES)
            }
        }

        fun create(context: Context): NeonDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                NeonDatabase::class.java,
                "neon.db",
            ).addMigrations(MIGRATION_1_2).build()
    }
}
