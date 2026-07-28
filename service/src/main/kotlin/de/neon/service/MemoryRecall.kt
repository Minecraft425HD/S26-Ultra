package de.neon.service

/**
 * Holt passende Erinnerungen zu einer Äußerung.
 *
 * Bewusst als eigene Schnittstelle im Dienst und nicht als Abhängigkeit auf `core/memory`:
 * Der Gesprächsablauf soll nichts von Room wissen, und in Tests tritt eine feste Liste an
 * die Stelle der Datenbank.
 */
fun interface MemoryRecall {
    suspend fun recall(query: String, limit: Int): List<String>
}
