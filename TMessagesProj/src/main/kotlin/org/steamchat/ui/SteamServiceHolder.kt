package org.steamchat.ui

import org.steamchat.service.FakeSteamService
import org.steamchat.service.SteamService

/**
 * Stage 2 wiring only: a single shared fake backend so the dialogs list and chat screen see the
 * same data. Replaced by real DI (Hilt/Koin/manual) once the real SteamKit-backed service lands.
 */
object SteamServiceHolder {
    val service: SteamService = FakeSteamService()
}
