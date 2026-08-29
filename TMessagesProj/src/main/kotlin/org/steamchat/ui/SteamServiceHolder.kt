package org.steamchat.ui

import org.steamchat.service.SteamService
import org.steamchat.steamkit.JavaSteamService

/**
 * Single shared backend instance so the login/dialogs/chat screens all see the same session.
 * Replaced by real DI (Hilt/Koin/manual) later. Swap to org.steamchat.service.FakeSteamService()
 * for offline UI iteration without a real Steam account.
 */
object SteamServiceHolder {
    val service: SteamService = JavaSteamService()
}
