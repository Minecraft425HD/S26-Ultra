package de.neon.router

/**
 * Erkennt an Schlüsselwörtern, ob eine Äußerung sensible Daten berührt.
 *
 * Das ist ein Sicherheitsnetz und keine Klassifikation: Es läuft unabhängig vom
 * Router-Modell, damit die Datenschutzentscheidung nicht davon abhängt, dass ein 0.6B-Modell
 * die richtige Antwort gibt. Falsch-positive Treffer sind hier ausdrücklich in Ordnung —
 * sie führen lediglich dazu, dass eine Anfrage lokal bleibt.
 *
 * Solange Neon rein lokal arbeitet, ändert das Ergebnis nichts. Es wird erst wirksam, wenn
 * die optionale Rückfallebene zu einem entfernten Modell eingeschaltet ist.
 */
object PrivacyDetector {

    private val sensitiveTerms = setOf(
        // Kontakte und Kommunikation
        "kontakt", "kontakte", "telefonnummer", "handynummer", "adresse", "anschrift",
        "nachricht", "nachrichten", "sms", "whatsapp", "email", "e-mail", "mail",
        // Gesundheit
        "arzt", "ärztin", "krank", "krankheit", "diagnose", "medikament", "medikamente",
        "rezept", "blutdruck", "therapie", "symptom", "symptome", "psychologe",
        // Finanzen
        "konto", "kontostand", "bank", "iban", "kreditkarte", "gehalt", "lohn",
        "steuer", "steuern", "rechnung", "schulden", "versicherung",
        // Zugangsdaten
        "passwort", "kennwort", "pin", "tan", "zugangsdaten", "login",
        // Standort
        "standort", "wo ich bin", "wo ich wohne", "meine position",
    )

    private val tokenizer = Regex("[^\\p{L}\\d-]+")

    fun isSensitive(text: String): Boolean {
        val lower = text.lowercase()

        // Mehrwortbegriffe direkt prüfen; Einzelwörter über die Wortliste.
        if (sensitiveTerms.any { it.contains(' ') && lower.contains(it) }) return true

        val tokens = lower.split(tokenizer).filter { it.isNotBlank() }.toSet()
        return tokens.any { it in sensitiveTerms }
    }
}
