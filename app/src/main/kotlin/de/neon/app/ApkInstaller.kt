package de.neon.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Bietet eine gebaute App zur Installation an.
 *
 * **Warum ein Intent und nicht `PackageInstaller`.** Der Sitzungs-Weg über `PackageInstaller`
 * wäre der modernere, braucht aber einen Rückkanal, eine Berechtigung und eine
 * Erfolgsmeldung, die asynchron kommt. Ein Intent zeigt denselben Systemdialog, den auch der
 * Browser zeigt, und der Nutzer entscheidet — bei einer App, die ein Sprachmodell gerade
 * geschrieben hat, ist genau das richtig.
 *
 * `REQUEST_INSTALL_PACKAGES` braucht es trotzdem: Ohne die Berechtigung weist Android den
 * Intent ab, und zwar wortlos.
 */
object ApkInstaller {

    /**
     * @return `null`, wenn der Dialog geöffnet wurde; sonst der Grund.
     */
    fun anbieten(context: Context, apk: File): String? {
        if (!apk.isFile) return "Die Datei ${apk.name} gibt es nicht."

        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.protokoll", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            null
        }.getOrElse { fehler ->
            "Die Installation ließ sich nicht anbieten: ${fehler.message}"
        }
    }
}
