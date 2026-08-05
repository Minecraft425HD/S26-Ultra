package de.neon.tools

/**
 * Fragt nach, statt zu raten.
 *
 * **Warum das ein Werkzeug ist und keine Höflichkeit.** Auf diesem Gerät kostet eine Antwort
 * des 4-B-Modells bis zu einer Minute und ein Bauversuch mehrere. Rät Neon falsch, ist diese
 * Zeit weg — und die richtige Antwort kostet sie danach noch einmal. Eine Rückfrage kostet
 * zehn Sekunden. Ab der ersten vermiedenen Fehlleistung ist sie billiger, und sie wird
 * häufiger nötig sein als einmal.
 *
 * **Warum es trotzdem selten benutzt werden soll.** Ein Assistent, der bei „mach das Licht
 * an" nachfragt, welches Licht, ist kein Assistent. Die Beschreibung nennt deshalb
 * ausdrücklich die Bedingung — mehrere ernsthaft verschiedene Lesarten — und nicht bloß
 * „wenn du unsicher bist". Ein Modell, dem man Unsicherheit als Auslöser gibt, ist immer
 * unsicher.
 *
 * Der Ablauf danach ist der eines gewöhnlichen Werkzeugergebnisses: Die Frage wird
 * gesprochen, der Durchgang endet, und die Antwort des Nutzers kommt als nächste Äußerung
 * an — mit der Frage im Verlauf, damit der Bezug erhalten bleibt.
 */
class Rueckfrage : Tool {

    override val spec = ToolSpec(
        name = NAME,
        description = "Fragt nach, bevor du etwas tust. Nimm das immer, wenn der Auftrag " +
            "mehrere ernsthaft verschiedene Lesarten hat — vor allem: Android-App oder " +
            "Python-Skript? Welches Projekt? Was genau soll die App können? Lieber einmal " +
            "fragen als das Falsche bauen. Nicht, wenn die Antwort im Auftrag schon steht.",
        parameters = listOf(
            ToolParameter(
                "frage",
                ParameterType.STRING,
                "Ein Satz. Nenne die Möglichkeiten, zwischen denen du wählst",
            ),
        ),
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val frage = arguments["frage"].orEmpty().trim()
        if (frage.isBlank()) {
            // Kein Ok mit leerem Text: Neon stünde stumm da, und der Nutzer wüsste nicht,
            // dass auf ihn gewartet wird.
            return ToolResult.Failed(
                "Ich wollte nachfragen, habe aber die Frage nicht zustande gebracht.",
                "leere Rückfrage",
            )
        }
        return ToolResult.Ok(frage)
    }

    companion object {
        /**
         * Damit der Gesprächsablauf die Rückfrage erkennt, ohne den Namen zu erraten.
         *
         * Er muss sie erkennen: Eine Rückfrage beendet die Werkzeugkette, gleich wie viele
         * Runden noch offen wären. Weiterzuarbeiten hieße, die eigene Frage zu übergehen.
         */
        const val NAME = "rueckfrage"
    }
}
