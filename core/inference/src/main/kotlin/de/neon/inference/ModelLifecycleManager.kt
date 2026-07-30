package de.neon.inference

import de.neon.router.ModelSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Wo die Modellgewichte auf der Platte liegen. */
fun interface ModelFileResolver {
    /** @return die Datei, oder `null`, wenn das Modell noch nicht heruntergeladen ist. */
    fun fileFor(model: ModelSpec): File?

    /**
     * Die Projektordatei eines Bildmodells.
     *
     * Voreingestellt `null`: Die allermeisten Modelle bestehen aus genau einer Datei, und
     * jeder Aufrufer, den Bilder nichts angehen, soll sich nicht darum kümmern müssen.
     */
    fun projectorFor(model: ModelSpec): File? = null
}

/**
 * Verwaltet, welches Modell im Speicher liegt.
 *
 * Der eigentliche Trick auf einem Telefon mit 16 GB ist nicht, viel im Speicher zu halten,
 * sondern das Umschalten billig zu machen. Zwei Dinge helfen dabei:
 *
 *  - Die Gewichte werden `mmap`-geladen. Linux hält zuletzt genutzte Seiten im Cache, also
 *    ist der Rückwechsel auf ein kürzlich benutztes Modell fast umsonst — auch wenn es
 *    formal "entladen" wurde.
 *  - Der Router meldet seine Entscheidung an, bevor die Spracherkennung fertig ist. Das
 *    Vorladen läuft dann parallel zur Erkennung statt danach.
 *
 * Deshalb zählt diese Klasse nicht nur mit, was geladen ist, sondern nimmt auch
 * Ankündigungen entgegen.
 */
class ModelLifecycleManager(
    private val engine: InferenceEngine,
    private val resolver: ModelFileResolver,
    /**
     * Wie viel Speicher Modelle insgesamt belegen dürfen.
     *
     * Eine Funktion und keine Zahl, weil sich die Antwort ändert: Was frei ist, hängt davon
     * ab, was sonst auf dem Telefon läuft. Hier stand eine Konstante von fünf Gigabyte, und
     * auf einem Gerät mit 5,3 GB insgesamt hat sie das größte Modell durchgewinkt, bis
     * Android den Prozess erschlug.
     */
    private val memoryBudgetBytes: () -> Long = { DEFAULT_BUDGET_BYTES },
) {

    /** Nur ein Ladevorgang zur Zeit — parallele Ladeversuche würden den Speicher sprengen. */
    private val mutex = Mutex()

    private var current: ModelSpec? = null

    /** Zuletzt benutzte Modelle, neueste zuerst. Näherung an den Zustand des Seitencaches. */
    private val recentlyUsed = ArrayDeque<String>()

    val loadedModelId: String? get() = current?.id

    /**
     * Modelle, die mit hoher Wahrscheinlichkeit noch im Seitencache liegen.
     *
     * Der Router benutzt das für die Hysterese: Ein Modell, das erst vor Sekunden lief,
     * kostet beim erneuten Laden kaum etwas.
     */
    fun warmModelIds(): Set<String> = buildSet {
        current?.let { add(it.id) }
        addAll(recentlyUsed.take(WARM_CACHE_DEPTH))
    }

    sealed interface Result {
        data class Ready(val model: ModelSpec, val wasAlreadyLoaded: Boolean) : Result
        data class Missing(val model: ModelSpec) : Result
        data class TooLarge(val model: ModelSpec, val budgetBytes: Long) : Result
        data class Failed(val model: ModelSpec, val reason: String) : Result
    }

    /**
     * Stellt sicher, dass [model] geladen ist.
     *
     * Läuft bereits ein anderes Modell, wird es vorher entladen — es ist immer genau eines
     * aktiv. Das ist eine bewusste Entscheidung gegen Cleverness: Zwei große Modelle
     * gleichzeitig im Speicher sind auf einem Telefon der sicherste Weg zum Absturz.
     */
    suspend fun ensureLoaded(model: ModelSpec): Result = mutex.withLock {
        if (current?.id == model.id) {
            touch(model.id)
            return Result.Ready(model, wasAlreadyLoaded = true)
        }

        val budget = memoryBudgetBytes()
        if (model.sizeBytes > budget) {
            return Result.TooLarge(model, budget)
        }

        val file = resolver.fileFor(model) ?: return Result.Missing(model)
        if (!file.exists()) return Result.Missing(model)

        current?.let {
            engine.unload()
            touch(it.id)
            current = null
        }

        val loaded = runCatching { engine.load(model, file, resolver.projectorFor(model)) }
        return when {
            loaded.isFailure ->
                Result.Failed(model, loaded.exceptionOrNull()?.message ?: "unbekannter Fehler")

            loaded.getOrDefault(false) -> {
                current = model
                touch(model.id)
                Result.Ready(model, wasAlreadyLoaded = false)
            }

            else -> Result.Failed(model, "die Engine konnte das Modell nicht laden")
        }
    }

    /**
     * Kündigt an, dass [model] gleich gebraucht wird.
     *
     * Der Aufrufer wartet nicht darauf. Läuft gerade schon etwas, passiert nichts — eine
     * laufende Antwort abzubrechen, um vorzuladen, wäre offensichtlich unsinnig.
     */
    suspend fun preload(model: ModelSpec): Boolean {
        if (mutex.isLocked) return false
        if (current?.id == model.id) return true
        return ensureLoaded(model) is Result.Ready
    }

    suspend fun unloadAll() = mutex.withLock {
        current?.let {
            engine.unload()
            touch(it.id)
        }
        current = null
    }

    private fun touch(modelId: String) {
        recentlyUsed.remove(modelId)
        recentlyUsed.addFirst(modelId)
        while (recentlyUsed.size > RECENT_HISTORY) recentlyUsed.removeLast()
    }

    companion object {
        /**
         * Der Rückfallwert, wenn niemand etwas Besseres weiß.
         *
         * Hier standen fünf Gigabyte mit der Begründung „von 16 GB". Das Gerät hatte 5,3 GB
         * insgesamt. Zwei Gigabyte lassen das Alltagsmodell zu und die großen nicht — das
         * ist die richtige Richtung für einen Wert, der geraten ist.
         *
         * Im Betrieb wird nicht geraten: `NeonContainer` gibt die Messung mit.
         */
        const val DEFAULT_BUDGET_BYTES = 2L * 1024 * 1024 * 1024

        private const val RECENT_HISTORY = 4
        private const val WARM_CACHE_DEPTH = 2
    }
}
