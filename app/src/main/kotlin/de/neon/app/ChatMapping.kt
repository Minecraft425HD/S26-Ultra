package de.neon.app

import de.neon.memory.ChatEntryEntity
import de.neon.service.ChatEntry

/**
 * Übersetzt zwischen Gesprächszeile und Datenbankzeile.
 *
 * Warum überhaupt zwei Typen: `core/memory` kennt den Dienst nicht und der Dienst die
 * Datenbank nicht — nur die App kennt beide. Diese Trennung ist der Grund, warum sich der
 * Gesprächsablauf ohne Room und ohne Gerät prüfen lässt. Die Übersetzung ist der kleine
 * Preis dafür, und sie steht genau an der einen Stelle, die beide Seiten sieht.
 */
internal fun ChatEntry.toEntity(): ChatEntryEntity = ChatEntryEntity(
    fromUser = fromUser,
    text = text,
    timestampMillis = timestampMillis,
    modelId = modelId,
    routeReason = routeReason,
    latencyMs = latencyMs,
)

internal fun ChatEntryEntity.toEntry(): ChatEntry = ChatEntry(
    fromUser = fromUser,
    text = text,
    timestampMillis = timestampMillis,
    modelId = modelId,
    routeReason = routeReason,
    latencyMs = latencyMs,
)
