package de.neon.router

import kotlinx.serialization.Serializable

/**
 * Aufgabenkategorien, auf die Neon eine Äußerung abbildet.
 *
 * Die Kategorie ist der wichtigste Eingabewert für die Modellauswahl: Sie entscheidet,
 * welche Modelle überhaupt in Frage kommen. Die Feinabstimmung übernimmt danach die
 * Komplexität.
 */
@Serializable
enum class TaskCategory {
    /** Begrüßung, Geplauder, kurze persönliche Bemerkungen. */
    SMALLTALK,

    /** Faktenfrage, deren Antwort im Modellwissen liegt. */
    WISSENSFRAGE,

    /** Programmieren, Shell, Konfigurationsdateien, Fehlersuche in Code. */
    CODE,

    /** Rechnen, mehrstufige Schlussfolgerungen, Planung, Knobelaufgaben. */
    LOGIK_MATHE,

    /** Alles, was ein Bild als Eingabe braucht (Kamera, Screenshot). */
    BILD,

    /** Eine Handlung auf dem Gerät oder im Smart Home. */
    GERAETE_AKTION,

    /** Bezieht sich auf gespeichertes Wissen über den Nutzer. */
    PERSOENLICH,

    /** Braucht tagesaktuelle Informationen aus dem Netz. */
    WEB_AKTUELL,

    /** Konnte nicht zugeordnet werden — das Alltagsmodell übernimmt. */
    UNBEKANNT,
}

/** Welche Router-Stufe die Analyse geliefert hat. Nur für Diagnose und Lernschleife. */
@Serializable
enum class AnalysisSource {
    /** Stufe 0 — feste Grammatik, keine Inferenz. */
    REGELN,

    /** Stufe 1 — Embedding-kNN gegen gelernte Beispiele. */
    KNN,

    /** Stufe 2 — das kleine Router-Modell. */
    ROUTER_LLM,

    /** Keine Stufe war sich sicher; es gilt die Standardannahme. */
    RUECKFALL,
}

/** Eine Nutzeräußerung, so wie sie beim Router ankommt. */
data class Utterance(
    val text: String,
    /** Liegt ein Kamerabild oder Screenshot bei? */
    val hasImage: Boolean = false,
    /** Hat der Nutzer ausdrücklich um eine gründlichere Antwort gebeten? */
    val explicitDeepThinking: Boolean = false,
    val locale: String = "de-DE",
    /**
     * Eine Kategorie, die schon feststeht — dann wird nicht mehr geschätzt.
     *
     * **Der Fall, für den es das gibt.** Hat Neon gerade „Android oder Python?" gefragt, dann
     * hat es damit bereits festgestellt, dass ein Bauauftrag vorliegt. Die Antwort darauf ist
     * für sich genommen kein Programmierauftrag — „Android" allein würde als Wissensfrage
     * eingeordnet, und die Werkzeugkette liefe nicht an. Genau das ist auf dem Gerät
     * passiert.
     *
     * Gehört zu denselben harten Tatsachen wie [hasImage]: Etwas, das die App sicher weiß und
     * worüber kein Modell abstimmen muss.
     */
    val bekannteKategorie: TaskCategory? = null,
)

/**
 * Das Ergebnis der Analysestufen 0–2.
 *
 * [complexity] ist eine Schätzung von 1 (trivial) bis 5 (mehrstufig, verlangt echtes
 * Nachdenken). Sie entscheidet zusammen mit [category], welches Modell groß genug sein muss.
 */
@Serializable
data class RouteAnalysis(
    val category: TaskCategory,
    val complexity: Int,
    val needsWeb: Boolean = false,
    val needsVision: Boolean = false,
    /**
     * Enthält die Äußerung Daten, die das Gerät nicht verlassen dürfen — Kontakte,
     * Nachrichten, Gesundheit, Finanzen, Standort?
     */
    val isPrivate: Boolean = false,
    /** 0.0 bis 1.0. Unterhalb der Schwelle eskaliert der Router auf die nächste Stufe. */
    val confidence: Double,
    val source: AnalysisSource,
) {
    init {
        require(complexity in MIN_COMPLEXITY..MAX_COMPLEXITY) {
            "Komplexität muss zwischen $MIN_COMPLEXITY und $MAX_COMPLEXITY liegen, war $complexity"
        }
        require(confidence in 0.0..1.0) { "Zuversicht muss zwischen 0 und 1 liegen, war $confidence" }
    }

    companion object {
        const val MIN_COMPLEXITY = 1
        const val MAX_COMPLEXITY = 5
    }
}
