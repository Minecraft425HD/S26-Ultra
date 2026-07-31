package de.neon.workspace

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Legt die Dex-Dateien in die von `aapt2` erzeugte APK.
 *
 * **Warum das eine eigene Klasse ist.** `aapt2 link` erzeugt eine APK mit Ressourcen und
 * binärem Manifest, aber ohne Code. `d8` erzeugt Dex-Dateien, aber keine APK. Zwischen beiden
 * fehlt ein Schritt, für den es kein Werkzeug gibt: die Dex-Dateien in das Archiv legen.
 *
 * Eine APK ist ein ZIP, also ist das reine Archivarbeit — und ohne Android prüfbar.
 *
 * **Neu geschrieben statt angehängt.** Ein ZIP lässt sich nicht ohne Weiteres ergänzen; das
 * zentrale Verzeichnis steht am Ende. Der einfache und richtige Weg ist, alles einmal
 * umzukopieren.
 */
object ApkAssembler {

    /**
     * Fügt [dexDateien] in [apk] ein.
     *
     * Die Dateien heißen im Archiv `classes.dex`, `classes2.dex` und so weiter — genau diese
     * Namen sucht der Android-Klassenlader, und zwar lückenlos: Fehlt `classes2.dex`, hört er
     * bei `classes.dex` auf, und die Hälfte der App fehlt ohne jede Meldung. Deshalb wird hier
     * durchnummeriert und nicht der Name von d8 übernommen.
     */
    fun fuegeEin(apk: File, dexDateien: List<File>) {
        require(dexDateien.isNotEmpty()) { "Keine Dex-Datei zum Einfügen" }

        val neu = File(apk.parentFile, "${apk.name}.neu")

        ZipOutputStream(neu.outputStream().buffered()).use { aus ->
            ZipFile(apk).use { alt ->
                alt.entries().asSequence().forEach { eintrag ->
                    // Alte Dex-Einträge überspringen: Bei einem zweiten Bauvorgang lägen
                    // sonst zwei Fassungen desselben Codes im Archiv, und welche gewinnt,
                    // entscheidet die Reihenfolge.
                    if (eintrag.name.matches(DEX_NAME)) return@forEach

                    aus.putNextEntry(ZipEntry(eintrag.name))
                    alt.getInputStream(eintrag).use { it.copyTo(aus) }
                    aus.closeEntry()
                }
            }

            dexDateien.sortedBy { it.name }.forEachIndexed { index, datei ->
                val name = if (index == 0) "classes.dex" else "classes${index + 1}.dex"
                aus.putNextEntry(ZipEntry(name))
                datei.inputStream().use { it.copyTo(aus) }
                aus.closeEntry()
            }
        }

        require(neu.renameTo(apk) || (apk.delete() && neu.renameTo(apk))) {
            "Die fertige APK ließ sich nicht an ihren Platz schieben: $apk"
        }
    }

    /** `classes.dex`, `classes2.dex`, … */
    private val DEX_NAME = Regex("""classes\d*\.dex""")
}
