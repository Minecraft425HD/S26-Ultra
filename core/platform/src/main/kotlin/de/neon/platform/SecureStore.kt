package de.neon.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Verschlüsselter Ablageort für Geheimnisse.
 *
 * Neon arbeitet lokal und braucht für den Grundbetrieb keine Schlüssel. Zwei Dinge fallen
 * trotzdem an: das Zugriffstoken für Home Assistant und — falls die optionale
 * Rückfallebene eingeschaltet wird — der Schlüssel für einen entfernten Endpunkt. Beides
 * gehört hinter den Hardware-Schlüsselspeicher und nicht in eine gewöhnliche
 * Einstellungsdatei.
 */
class SecureStore(context: Context) {

    private val preferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context.applicationContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    operator fun get(key: String): String? = preferences.getString(key, null)

    operator fun set(key: String, value: String?) {
        preferences.edit().apply {
            if (value.isNullOrBlank()) remove(key) else putString(key, value)
        }.apply()
    }

    fun has(key: String): Boolean = !get(key).isNullOrBlank()

    fun clear() = preferences.edit().clear().apply()

    companion object {
        private const val FILE_NAME = "neon_secure"

        const val KEY_HOME_ASSISTANT_TOKEN = "home_assistant_token"
        const val KEY_HOME_ASSISTANT_URL = "home_assistant_url"
        const val KEY_REMOTE_ENDPOINT_URL = "remote_endpoint_url"
        const val KEY_REMOTE_ENDPOINT_TOKEN = "remote_endpoint_token"
        const val KEY_WEB_SEARCH_ENDPOINT = "web_search_endpoint"
    }
}
