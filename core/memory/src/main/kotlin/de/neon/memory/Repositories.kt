package de.neon.memory

import de.neon.router.EmbeddingProvider
import de.neon.router.LabeledExample
import de.neon.router.TaskCategory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Umwandlung zwischen Datenbankzeile und Router-Beispiel.
 *
 * Als eigenes Objekt, weil das die einzige Stelle mit echter Logik ist — Room selbst lässt
 * sich nur auf einem Gerät prüfen, diese Abbildung dagegen ganz normal.
 */
object RoutingExampleMapper {

    fun toEntity(example: LabeledExample, now: Long): RoutingExampleEntity = RoutingExampleEntity(
        text = example.text,
        embedding = example.embedding,
        category = example.category.name,
        complexity = example.complexity,
        weight = example.weight,
        createdAtMillis = now,
    )

    /**
     * Gibt `null` zurück, wenn die Zeile nicht mehr passt.
     *
     * Passiert, wenn eine Kategorie umbenannt oder das Einbettungsverfahren gewechselt
     * wurde. Eine Zeile mit falscher Vektorlänge würde bei jedem Vergleich eine Ausnahme
     * auslösen — sie stillschweigend zu überspringen ist die einzige sinnvolle Antwort.
     */
    fun toExample(entity: RoutingExampleEntity, expectedDimensions: Int): LabeledExample? {
        if (entity.embedding.size != expectedDimensions) return null
        val category = runCatching { TaskCategory.valueOf(entity.category) }.getOrNull() ?: return null

        return LabeledExample(
            text = entity.text,
            embedding = entity.embedding,
            category = category,
            complexity = entity.complexity.coerceIn(1, 5),
            weight = entity.weight,
        )
    }
}

/**
 * Die gelernten Beispiele für Stufe 1 des Routers, dauerhaft abgelegt.
 *
 * Ohne diese Ablage begänne der Router nach jedem Neustart wieder bei der mitgelieferten
 * Startmenge — die ganze Lernschleife wäre dann folgenlos.
 */
class RoutingExampleRepository(
    private val dao: RoutingExampleDao,
    private val expectedDimensions: Int,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun loadAll(): List<LabeledExample> = withContext(dispatcher) {
        runCatching {
            dao.all().mapNotNull { RoutingExampleMapper.toExample(it, expectedDimensions) }
        }.getOrDefault(emptyList())
    }

    suspend fun save(example: LabeledExample) {
        withContext(dispatcher) {
            runCatching { dao.insert(RoutingExampleMapper.toEntity(example, clock())) }
        }
    }
}

/**
 * Was Neon sich über den Nutzer gemerkt hat.
 *
 * Die Suche läuft über [VectorSearch] gegen alle gespeicherten Aussagen. Bei einigen
 * hundert Einträgen sind das wenige Millisekunden — eine Vektordatenbank wäre hier eine
 * Abhängigkeit für ein Problem, das Neon nicht hat.
 */
class MemoryRepository(
    private val dao: MemoryFactDao,
    private val embeddings: EmbeddingProvider,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun remember(statement: String, topic: String = "allgemein") {
        if (statement.isBlank()) return
        withContext(dispatcher) {
            runCatching {
                val now = clock()
                dao.insert(
                    MemoryFactEntity(
                        statement = statement.trim(),
                        embedding = embeddings.embed(statement),
                        topic = topic,
                        createdAtMillis = now,
                        lastUsedAtMillis = now,
                    )
                )
            }
        }
    }

    /** Die passendsten Erinnerungen zu einer Äußerung, als fertige Sätze. */
    suspend fun recall(query: String, limit: Int): List<String> = withContext(dispatcher) {
        runCatching {
            val vector = embeddings.embed(query)
            val facts = dao.all().filter { it.embedding.size == vector.size }

            val hits = VectorSearch.nearest(
                query = vector,
                items = facts,
                limit = limit,
                minSimilarity = MIN_SIMILARITY,
                embeddingOf = { it.embedding },
            )

            // Benutzte Erinnerungen markieren — daran lässt sich später erkennen, was
            // tatsächlich hilft und was nur Platz belegt.
            val now = clock()
            hits.forEach { runCatching { dao.markUsed(it.item.id, now) } }

            hits.map { it.item.statement }
        }.getOrDefault(emptyList())
    }

    private companion object {
        /**
         * Lieber keine Erinnerung als eine unpassende: Ein danebenliegender Satz im
         * Systemprompt lenkt ein kleines Modell spürbar ab.
         */
        const val MIN_SIMILARITY = 0.45
    }
}
