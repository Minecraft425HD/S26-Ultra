package de.neon.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import java.util.Calendar

/**
 * Legt einen Kalendertermin an.
 *
 * Genau der Fall, für den es Werkzeuge gibt: „Trag mir morgen um zehn einen Zahnarzttermin
 * ein" enthält freien Text und eine relative Zeitangabe. Eine feste Grammatik wie in Stufe 0
 * scheitert daran; ein Sprachmodell zerlegt es mühelos.
 *
 * Der Termin wird über einen Intent angelegt, nicht direkt in den Kalender geschrieben. Das
 * kostet einen Bestätigungsschritt, spart aber die Kalenderberechtigung — und bei einer
 * missverstandenen Äußerung sieht man vorher, was gleich im Kalender steht.
 */
class CalendarEventTool(private val context: Context) : Tool {

    override val spec = ToolSpec(
        name = "termin",
        description = "Legt einen Termin im Kalender an.",
        parameters = listOf(
            ToolParameter("titel", ParameterType.STRING, "Worum es geht"),
            ToolParameter(
                "stunde", ParameterType.INTEGER,
                "Stunde im 24-Stunden-Format",
            ),
            ToolParameter("minute", ParameterType.INTEGER, "Minute", required = false),
            ToolParameter(
                "tage_ab_heute", ParameterType.INTEGER,
                "0 für heute, 1 für morgen", required = false,
            ),
        ),
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val title = arguments["titel"].orEmpty().trim()
        val hour = arguments["stunde"]?.toIntOrNull()
            ?: return ToolResult.Failed("Zu welcher Uhrzeit?", "Stunde fehlt oder ist keine Zahl")
        if (hour !in 0..23) {
            return ToolResult.Failed("Diese Uhrzeit kenne ich nicht.", "Stunde außerhalb 0–23")
        }

        val minute = arguments["minute"]?.toIntOrNull()?.takeIf { it in 0..59 } ?: 0
        val daysAhead = arguments["tage_ab_heute"]?.toIntOrNull()?.coerceIn(0, 365) ?: 0

        val start = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, daysAhead)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
        }

        return runCatching {
            val intent = Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.timeInMillis)
                .putExtra(
                    CalendarContract.EXTRA_EVENT_END_TIME,
                    start.timeInMillis + DEFAULT_DURATION_MILLIS,
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            val whenText = when (daysAhead) {
                0 -> "heute"
                1 -> "morgen"
                else -> "in $daysAhead Tagen"
            }
            ToolResult.Ok("Termin $whenText um %d:%02d Uhr: $title.".format(hour, minute))
        }.getOrElse {
            ToolResult.Failed(
                "Ich konnte den Kalender nicht öffnen.",
                it.message ?: "kein Kalender installiert",
            )
        }
    }

    private companion object {
        const val DEFAULT_DURATION_MILLIS = 60L * 60 * 1000
    }
}

/**
 * Bereitet eine Nachricht vor.
 *
 * Abgeschickt wird sie ausdrücklich nicht — Neon öffnet nur die App mit fertigem Text. Eine
 * Fehlerkennung des Weckworts oder ein missverstandener Satz darf niemals dazu führen, dass
 * jemand eine Nachricht bekommt, die so nie gemeint war.
 */
class ComposeMessageTool(private val context: Context) : Tool {

    override val spec = ToolSpec(
        name = "nachricht",
        description = "Bereitet eine Nachricht vor. Der Nutzer schickt sie selbst ab.",
        parameters = listOf(
            ToolParameter("text", ParameterType.STRING, "Der Inhalt der Nachricht"),
            ToolParameter(
                "nummer", ParameterType.STRING,
                "Telefonnummer, falls bekannt", required = false,
            ),
        ),
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val text = arguments["text"].orEmpty().trim()
        if (text.isEmpty()) {
            return ToolResult.Failed("Was soll ich schreiben?", "leerer Nachrichtentext")
        }

        val number = arguments["nummer"]?.filter { it.isDigit() || it == '+' }.orEmpty()

        return runCatching {
            val uri = if (number.isNotEmpty()) Uri.parse("smsto:$number") else Uri.parse("smsto:")
            val intent = Intent(Intent.ACTION_SENDTO, uri)
                .putExtra("sms_body", text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult.Ok("Die Nachricht steht bereit — abschicken musst du selbst.")
        }.getOrElse {
            ToolResult.Failed(
                "Ich konnte die Nachrichten-App nicht öffnen.",
                it.message ?: "keine Nachrichten-App gefunden",
            )
        }
    }
}
