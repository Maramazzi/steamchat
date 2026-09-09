package org.steamchat.ui

import org.telegram.messenger.ApplicationLoader
import org.steamchat.service.SteamService
import org.steamchat.steamkit.JavaSteamService
import org.steamchat.storage.EncryptedSessionStore
import org.steamchat.storage.PrefsBadgesCache

/**
 * Single shared backend instance so the login/dialogs/chat screens all see the same session.
 * Replaced by real DI (Hilt/Koin/manual) later. Swap to org.steamchat.service.FakeSteamService()
 * for offline UI iteration without a real Steam account.
 */
object SteamServiceHolder {
    val service: SteamService by lazy {
        JavaSteamService(
            EncryptedSessionStore(ApplicationLoader.applicationContext),
            PrefsBadgesCache(ApplicationLoader.applicationContext),
        )
    }
}
