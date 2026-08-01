package de.neon.tools

/**
 * Beendet eine Werkzeugkette.
 *
 * **Warum eine Kette überhaupt ein Abschlusswerkzeug braucht.** Die Grammatik erzwingt einen
 * Werkzeugaufruf — das ist ihr Zweck: Ein 4-B-Modell beschreibt sonst in Prosa, was es tun
 * würde, statt es zu tun. Genau deshalb kann das Modell aber nicht einfach aufhören. Es gibt
 * in der Grammatik keine Form für „ich bin fertig", also gäbe es auch keine Antwort, die das
 * bedeutet: In der nächsten Runde müsste es irgendein Werkzeug wählen, und es würde eines
 * wählen — `dateien-auflisten` etwa, weil es dasteht.
 *
 * Dieses Werkzeug ist diese Form. Es tut nichts außer der Kette ein Ende zu geben.
 *
 * **Was es kostet.** Für einen Auftrag mit genau einer Handlung ist es eine zusätzliche
 * Runde, also eine weitere Erzeugung von rund dreißig Token — auf dem kleinen Modell etwa
 * eine Sekunde. Das ist der Preis dafür, dass „leg das Projekt an und bau es" in einem Satz
 * funktioniert statt in zweien. Die Alternative wäre, die Grammatik auch freien Text zulassen
 * zu lassen; dann fiele das Modell bei der ersten Gelegenheit in Prosa zurück, und die
 * Werkzeuge blieben wieder unbenutzt.
 */
class Fertig : Tool {

    override val spec = ToolSpec(
        name = "fertig",
        description = "Beendet die Arbeit an diesem Auftrag. Nimm das, sobald alles " +
            "Verlangte getan ist — und nur dann. Solange noch ein Schritt aussteht, " +
            "ruf stattdessen das Werkzeug für diesen Schritt auf.",
        parameters = listOf(
            ToolParameter(
                "zusammenfassung",
                ParameterType.STRING,
                "Ein Satz darüber, was am Ende herausgekommen ist.",
                // **Nicht verpflichtend, und das ist wichtig.** Die Registry weist einen
                // Aufruf mit fehlendem Pflichtargument ab, bevor dieses Werkzeug überhaupt
                // an die Reihe kommt — und dann endete eine erledigte Arbeit mit „Dazu
                // fehlt mir noch eine Angabe". Das Ende einer Kette darf nicht daran
                // scheitern, dass das Modell den Schlusssatz vergisst; was getan wurde, ist
                // getan, und die vorigen Runden haben es bereits gesagt.
                required = false,
            ),
        ),
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val zusammenfassung = arguments["zusammenfassung"].orEmpty().trim()
        // Auch ohne Zusammenfassung gelungen: Die Kette ist zu Ende, und das ist die
        // Auskunft, um die es hier geht. Ein Fehlschlag wäre irreführend — er würde eine
        // erledigte Arbeit als gescheitert melden.
        return ToolResult.Ok(zusammenfassung.ifBlank { "Erledigt." })
    }

    companion object {
        /** Damit der Gesprächsablauf den Abschluss erkennt, ohne den Namen zu erraten. */
        const val NAME = "fertig"
    }
}
