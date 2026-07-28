package de.neon.router

/**
 * Eine Handlung, die Neon ohne jedes Sprachmodell ausführen kann.
 *
 * Jeder Treffer hier bedeutet: keine Inferenz, keine Modellladezeit, kein nennenswerter
 * Stromverbrauch. Das ist der größte Einzelhebel für die Akkulaufzeit, weil ein großer Teil
 * der täglichen Befehle in diese Handvoll Muster fällt.
 */
sealed interface DeviceAction {
    data object TellTime : DeviceAction
    data object TellDate : DeviceAction
    data class SetTimer(val seconds: Int) : DeviceAction
    data class SetAlarm(val hour: Int, val minute: Int) : DeviceAction
    data class SwitchLight(val on: Boolean, val room: String?) : DeviceAction
    data class Flashlight(val on: Boolean) : DeviceAction
    data class ChangeVolume(val direction: Int) : DeviceAction
    data class SetVolume(val percent: Int) : DeviceAction
    data class OpenApp(val appName: String) : DeviceAction
    data class CallContact(val contact: String) : DeviceAction
    data object Cancel : DeviceAction
}

/**
 * Stufe 0 des Routers: feste Grammatik für die häufigsten Befehle.
 *
 * Trifft keine Regel, gibt der Matcher `null` zurück und die teureren Stufen übernehmen.
 * Ein Treffer ist immer eindeutig — deshalb steht die Zuversicht fest auf 1.0.
 */
class RuleMatcher(
    /** Wörter, mit denen Neon angesprochen wird und die vor der Auswertung wegfallen. */
    private val wakeWords: Set<String> = setOf("neon", "hey", "ok", "okay", "hallo"),
) {

    data class Match(val action: DeviceAction, val analysis: RouteAnalysis)

    fun match(utterance: Utterance): Match? {
        // Ein Bild kann keine Regel verarbeiten — sofort an die späteren Stufen.
        if (utterance.hasImage) return null

        val normalized = normalize(utterance.text)
        if (normalized.isEmpty()) return null
        val tokens = normalized.split(" ")

        val action = matchCancel(normalized)
            ?: matchTime(normalized)
            ?: matchDate(normalized)
            ?: matchTimer(normalized, tokens)
            ?: matchAlarm(normalized, tokens)
            ?: matchFlashlight(normalized)
            ?: matchLight(normalized)
            ?: matchVolume(normalized, tokens)
            ?: matchOpenApp(normalized)
            ?: matchCall(normalized)
            ?: return null

        return Match(action, ANALYSIS)
    }

    /**
     * Kleinschreibung, Satzzeichen weg, Anrede weg, Mehrfach-Leerzeichen zusammenfassen.
     *
     * Die Spracherkennung liefert je nach Modell mit oder ohne Satzzeichen — deshalb wird
     * hier vereinheitlicht, statt in jedem Muster beide Fälle abzudecken.
     */
    private fun normalize(raw: String): String {
        val cleaned = raw.lowercase()
            .replace(PUNCTUATION, " ")
            .split(' ')
            .filter { it.isNotBlank() }

        // Anrede nur am Anfang entfernen: "neon licht aus" -> "licht aus".
        var start = 0
        while (start < cleaned.size && cleaned[start] in wakeWords) start++
        return cleaned.subList(start, cleaned.size).joinToString(" ")
    }

    private fun matchCancel(text: String): DeviceAction? =
        if (CANCEL.containsMatchIn(text)) DeviceAction.Cancel else null

    private fun matchTime(text: String): DeviceAction? =
        if (TIME.containsMatchIn(text)) DeviceAction.TellTime else null

    private fun matchDate(text: String): DeviceAction? =
        if (DATE.containsMatchIn(text)) DeviceAction.TellDate else null

    private fun matchTimer(text: String, tokens: List<String>): DeviceAction? {
        if (!TIMER.containsMatchIn(text)) return null

        // Feste Wendungen, die keine Zahl enthalten.
        if (HALF_HOUR.containsMatchIn(text)) return DeviceAction.SetTimer(30 * 60)
        if (QUARTER_HOUR.containsMatchIn(text)) return DeviceAction.SetTimer(15 * 60)

        val number = GermanNumbers.findQuantity(tokens, ::isDurationUnit) ?: return null
        val unitToken = tokens.getOrNull(number.index + 1).orEmpty()
        val seconds = when {
            unitToken.startsWith("sekunde") -> number.value
            unitToken.startsWith("minute") -> number.value * 60
            unitToken.startsWith("stunde") -> number.value * 3600
            // "timer auf fünf" ohne Einheit heißt umgangssprachlich Minuten.
            else -> number.value * 60
        }
        return if (seconds > 0) DeviceAction.SetTimer(seconds) else null
    }

    private fun isDurationUnit(token: String): Boolean =
        token.startsWith("sekunde") || token.startsWith("minute") || token.startsWith("stunde")

    private fun matchAlarm(text: String, tokens: List<String>): DeviceAction? {
        if (!ALARM.containsMatchIn(text)) return null

        // Ausgeschriebene Uhrzeit: "um 7:30", "um 07 30".
        CLOCK_TIME.find(text)?.let { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: return@let
            val minute = m.groupValues[2].toIntOrNull() ?: 0
            if (hour in 0..23 && minute in 0..59) return DeviceAction.SetAlarm(hour, minute)
        }

        // "weck mich um sieben" — Zahlwort hinter "um", falls vorhanden.
        val umIndex = tokens.indexOf("um")
        val rest = tokens.drop(if (umIndex >= 0) umIndex + 1 else 0)
        val hour = GermanNumbers.findQuantity(rest) { it == "uhr" } ?: return null
        if (hour.value !in 0..23) return null

        val afterHour = rest.drop(hour.index + 1).filter { it != "uhr" }
        val minute = GermanNumbers.findFirst(afterHour)?.value?.takeIf { it in 0..59 } ?: 0
        return DeviceAction.SetAlarm(hour.value, minute)
    }

    private fun matchFlashlight(text: String): DeviceAction? {
        if (!FLASHLIGHT.containsMatchIn(text)) return null
        return switchState(text)?.let { DeviceAction.Flashlight(it) }
    }

    private fun matchLight(text: String): DeviceAction? {
        if (!LIGHT.containsMatchIn(text)) return null
        val on = switchState(text) ?: return null
        return DeviceAction.SwitchLight(on, extractRoom(text))
    }

    /**
     * "an"/"ein" bedeutet einschalten, "aus" ausschalten.
     *
     * Die Prüfung läuft auf ganzen Wörtern, damit "einen" oder "ausgehen" nicht
     * versehentlich als Schaltbefehl gelesen werden.
     */
    private fun switchState(text: String): Boolean? {
        val words = text.split(" ")
        return when {
            words.any { it == "aus" || it == "ausschalten" || it == "abschalten" } -> false
            words.any { it == "an" || it == "ein" || it == "anschalten" || it == "einschalten" } -> true
            else -> null
        }
    }

    private fun extractRoom(text: String): String? {
        val match = ROOM.find(text) ?: return null
        return match.groupValues[2].trim().takeIf { it.isNotEmpty() }
    }

    private fun matchVolume(text: String, tokens: List<String>): DeviceAction? {
        if (VOLUME_UP.containsMatchIn(text)) return DeviceAction.ChangeVolume(+1)
        if (VOLUME_DOWN.containsMatchIn(text)) return DeviceAction.ChangeVolume(-1)
        if (!VOLUME_SET.containsMatchIn(text)) return null
        val percent = GermanNumbers.findQuantity(tokens) { it == "prozent" }?.value ?: return null
        return if (percent in 0..100) DeviceAction.SetVolume(percent) else null
    }

    private fun matchOpenApp(text: String): DeviceAction? {
        val match = OPEN_APP.find(text) ?: return null
        val app = match.groupValues[2].trim()
        return if (app.isEmpty()) null else DeviceAction.OpenApp(app)
    }

    private fun matchCall(text: String): DeviceAction? {
        val match = CALL.find(text) ?: return null
        val contact = (match.groupValues[2].ifBlank { match.groupValues[3] }).trim()
        return if (contact.isEmpty()) null else DeviceAction.CallContact(contact)
    }

    private companion object {
        /** Stufe-0-Treffer sind per Definition eindeutig und trivial. */
        val ANALYSIS = RouteAnalysis(
            category = TaskCategory.GERAETE_AKTION,
            complexity = 1,
            confidence = 1.0,
            source = AnalysisSource.REGELN,
        )

        /**
         * Alle Muster laufen mit Unicode-fähigen Wortgrenzen.
         *
         * Ohne `(?U)` zählt Java Umlaute nicht als Wortzeichen — `\böffne` greift dann
         * nicht, weil vor dem "ö" keine Wortgrenze erkannt wird. Bei einer deutschen
         * Grammatik ist das keine Feinheit, sondern ein stiller Totalausfall.
         */
        fun rx(pattern: String) = Regex("(?U)$pattern")

        val PUNCTUATION = Regex("[.,;:!?\"'()\\[\\]{}]")

        val CANCEL = rx("\\b(stopp?|abbrechen|abbruch|vergiss es|lass gut sein|nichts)\\b")
        val TIME = rx("\\b(wie spät|wie viel uhr|wieviel uhr|uhrzeit)\\b")
        val DATE = rx("\\b(welches datum|welcher tag|was für ein tag|datum heute|der wievielte)\\b")

        val TIMER = rx("\\b(timer|eieruhr|kurzzeitwecker)\\b")
        val HALF_HOUR = rx("\\b(halbe stunde)\\b")
        val QUARTER_HOUR = rx("\\b(viertelstunde|viertel stunde)\\b")

        val ALARM = rx("\\b(wecker|weck mich)\\b")
        val CLOCK_TIME = rx("\\b(\\d{1,2})[:. ](\\d{2})\\b")

        val FLASHLIGHT = rx("\\b(taschenlampe|blitzlicht)\\b")
        val LIGHT = rx("\\b(licht|lampe|lampen|beleuchtung)\\b")
        val ROOM = rx("\\b(im|in der|in dem)\\s+(\\p{L}+)")

        val VOLUME_UP = rx("\\b(lauter|mach lauter)\\b")
        val VOLUME_DOWN = rx("\\b(leiser|mach leiser)\\b")
        val VOLUME_SET = rx("\\b(lautstärke|lautstaerke|volume)\\b")

        val OPEN_APP = rx("\\b(öffne|oeffne|starte|start)\\s+(?:die\\s+|den\\s+|das\\s+|app\\s+)?(\\p{L}[\\p{L}\\d ]*)")
        val CALL = rx("\\b(?:(ruf|rufe)\\s+(\\p{L}[\\p{L} ]*?)\\s+an\\b|(?:anrufen)\\s+(\\p{L}[\\p{L} ]*))")
    }
}
