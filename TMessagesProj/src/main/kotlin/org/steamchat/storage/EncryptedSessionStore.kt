package org.steamchat.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.steamchat.service.SessionStore
import org.steamchat.service.StoredSteamSession

/**
 * Keystore-backed session storage (master prompt section 15: never store credentials/tokens in
 * plaintext). Only the refresh token lives here - never the account password.
 */
class EncryptedSessionStore(context: Context) : SessionStore {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "steamchat_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun save(session: StoredSteamSession) {
        prefs.edit()
            .putString(KEY_USERNAME, session.username)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .apply()
    }

    override fun load(): StoredSteamSession? {
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return null
        return StoredSteamSession(username, refreshToken)
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_USERNAME = "username"
        const val KEY_REFRESH_TOKEN = "refresh_token"
    }
}
