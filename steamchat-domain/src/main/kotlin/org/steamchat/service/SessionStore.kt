package org.steamchat.service

data class StoredSteamSession(val username: String, val refreshToken: String)

/**
 * Persists the Steam session (refresh token) across app restarts so the user isn't forced to
 * log in and pass Steam Guard every single launch (master prompt section 15: never plaintext -
 * the Android implementation backs this with Keystore-encrypted storage).
 */
interface SessionStore {
    fun save(session: StoredSteamSession)
    fun load(): StoredSteamSession?
    fun clear()
}

/** No-op store for backends (e.g. FakeSteamService) that don't need persistence. */
object NoOpSessionStore : SessionStore {
    override fun save(session: StoredSteamSession) {}
    override fun load(): StoredSteamSession? = null
    override fun clear() {}
}
