package de.neon.router

/**
 * Startmenge für den kNN-Klassifikator.
 *
 * Ohne diese Beispiele wäre Stufe 1 beim ersten Start blind und jede Anfrage müsste über das
 * Router-Modell laufen. Die Gewichtung ist niedrig (1.0 gegenüber 2.0 für Gelerntes), damit
 * echte Nutzung diese Vorgaben mit der Zeit überstimmt.
 */
object SeedExamples {

    data class Seed(val text: String, val category: TaskCategory, val complexity: Int)

    /** Erzeugt die gelabelten Beispiele, indem alle Texte eingebettet werden. */
    fun materialize(embeddings: EmbeddingProvider): List<LabeledExample> =
        all.map { seed ->
            LabeledExample(
                text = seed.text,
                embedding = embeddings.embed(seed.text),
                category = seed.category,
                complexity = seed.complexity,
                weight = 1.0,
            )
        }

    val all: List<Seed> = listOf(
        // --- Smalltalk ---------------------------------------------------------------
        Seed("hallo neon wie geht es dir", TaskCategory.SMALLTALK, 1),
        Seed("guten morgen", TaskCategory.SMALLTALK, 1),
        Seed("erzähl mir einen witz", TaskCategory.SMALLTALK, 1),
        Seed("danke dir", TaskCategory.SMALLTALK, 1),
        Seed("wie war dein tag", TaskCategory.SMALLTALK, 1),
        Seed("mir ist langweilig", TaskCategory.SMALLTALK, 1),
        Seed("was hältst du von regen", TaskCategory.SMALLTALK, 2),

        // --- Wissensfragen -----------------------------------------------------------
        Seed("wie hoch ist der eiffelturm", TaskCategory.WISSENSFRAGE, 1),
        Seed("wer hat die relativitätstheorie entwickelt", TaskCategory.WISSENSFRAGE, 1),
        Seed("was ist die hauptstadt von norwegen", TaskCategory.WISSENSFRAGE, 1),
        Seed("erklär mir wie ein kühlschrank funktioniert", TaskCategory.WISSENSFRAGE, 2),
        Seed("was ist der unterschied zwischen viren und bakterien", TaskCategory.WISSENSFRAGE, 2),
        Seed("erklär mir quantenverschränkung", TaskCategory.WISSENSFRAGE, 3),
        Seed("warum ist der himmel blau", TaskCategory.WISSENSFRAGE, 2),
        Seed("wie funktioniert ein verbrennungsmotor genau", TaskCategory.WISSENSFRAGE, 3),

        // --- Code --------------------------------------------------------------------
        Seed("schreib mir ein python skript das dateien umbenennt", TaskCategory.CODE, 3),
        Seed("wie sortiere ich eine liste in kotlin", TaskCategory.CODE, 2),
        Seed("was bedeutet dieser nullpointer fehler", TaskCategory.CODE, 3),
        Seed("schreib eine funktion die primzahlen findet", TaskCategory.CODE, 3),
        Seed("wie schreibe ich eine for schleife in javascript", TaskCategory.CODE, 2),
        Seed("erklär mir was ein pointer in c ist", TaskCategory.CODE, 3),
        Seed("baue mir eine rest api mit fastapi", TaskCategory.CODE, 4),
        Seed("warum kompiliert mein rust programm nicht", TaskCategory.CODE, 4),
        Seed("schreib mir eine regex für email adressen", TaskCategory.CODE, 3),

        // --- Logik und Mathematik ----------------------------------------------------
        Seed("was ist siebzehn mal dreiundzwanzig", TaskCategory.LOGIK_MATHE, 2),
        Seed("wenn drei maler drei wände in drei stunden streichen wie lange brauchen neun", TaskCategory.LOGIK_MATHE, 4),
        Seed("löse die gleichung zwei x plus fünf gleich siebzehn", TaskCategory.LOGIK_MATHE, 3),
        Seed("plane mir eine dreitägige reise nach rom mit budget", TaskCategory.LOGIK_MATHE, 4),
        Seed("wie viel prozent sind dreißig von zweihundert", TaskCategory.LOGIK_MATHE, 2),
        Seed("denk dir eine strategie für dieses schachendspiel aus", TaskCategory.LOGIK_MATHE, 5),
        Seed("vergleiche die vor und nachteile von wärmepumpe und gasheizung", TaskCategory.LOGIK_MATHE, 4),
        Seed("beweise dass die wurzel aus zwei irrational ist", TaskCategory.LOGIK_MATHE, 5),

        // --- Bild --------------------------------------------------------------------
        Seed("was ist auf diesem bild zu sehen", TaskCategory.BILD, 2),
        Seed("lies mir vor was auf dem schild steht", TaskCategory.BILD, 2),
        Seed("welche pflanze ist das", TaskCategory.BILD, 2),
        Seed("beschreib mir dieses foto", TaskCategory.BILD, 2),
        Seed("was steht auf diesem screenshot", TaskCategory.BILD, 2),

        // --- Geräteaktionen (die Stufe 0 verfehlt hat) -------------------------------
        Seed("mach das wlan an", TaskCategory.GERAETE_AKTION, 1),
        Seed("schick meiner schwester eine nachricht dass ich später komme", TaskCategory.GERAETE_AKTION, 2),
        Seed("trag mir morgen um zehn einen zahnarzttermin ein", TaskCategory.GERAETE_AKTION, 2),
        Seed("spiel etwas musik ab", TaskCategory.GERAETE_AKTION, 1),
        Seed("stell die heizung im wohnzimmer wärmer", TaskCategory.GERAETE_AKTION, 2),
        Seed("mach einen screenshot", TaskCategory.GERAETE_AKTION, 1),

        // --- Persönliches Gedächtnis -------------------------------------------------
        Seed("merk dir dass ich keinen koriander mag", TaskCategory.PERSOENLICH, 1),
        Seed("was hatte ich dir letzte woche über meinen urlaub erzählt", TaskCategory.PERSOENLICH, 2),
        Seed("wie heißt nochmal mein hausarzt", TaskCategory.PERSOENLICH, 1),
        Seed("woran wollte ich heute abend denken", TaskCategory.PERSOENLICH, 1),
        Seed("was sind meine lieblingsgerichte", TaskCategory.PERSOENLICH, 1),
        Seed("erinnerst du dich was ich über das projekt gesagt habe", TaskCategory.PERSOENLICH, 2),

        // --- Aktuelles aus dem Netz --------------------------------------------------
        Seed("wie wird das wetter morgen", TaskCategory.WEB_AKTUELL, 1),
        Seed("was gibt es heute für nachrichten", TaskCategory.WEB_AKTUELL, 2),
        Seed("wie steht der dax gerade", TaskCategory.WEB_AKTUELL, 1),
        Seed("wann spielt deutschland das nächste mal", TaskCategory.WEB_AKTUELL, 1),
        Seed("gibt es stau auf der a3", TaskCategory.WEB_AKTUELL, 1),
        Seed("was kostet ein flug nach lissabon nächste woche", TaskCategory.WEB_AKTUELL, 3),
        Seed("fass mir die aktuellen schlagzeilen zusammen", TaskCategory.WEB_AKTUELL, 2),
    )
}
