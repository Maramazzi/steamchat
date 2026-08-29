package org.steamchat.steamkit

import `in`.dragonbra.javasteam.enums.EPersonaState
import org.steamchat.domain.SteamStatus

internal fun EPersonaState?.toSteamStatus(): SteamStatus = when (this) {
    EPersonaState.Online -> SteamStatus.ONLINE
    EPersonaState.Busy -> SteamStatus.BUSY
    EPersonaState.Away -> SteamStatus.AWAY
    EPersonaState.Snooze -> SteamStatus.SNOOZE
    EPersonaState.LookingToTrade -> SteamStatus.LOOKING_TO_TRADE
    EPersonaState.LookingToPlay -> SteamStatus.LOOKING_TO_PLAY
    else -> SteamStatus.OFFLINE // covers Offline and Invisible - both read as "not available" to us
}

/** Confirmed format: https://avatars.akamai.steamstatic.com/{hash}_full.jpg */
internal fun avatarUrl(avatarHash: ByteArray?): String? {
    if (avatarHash == null || avatarHash.isEmpty() || avatarHash.all { it == 0.toByte() }) return null
    val hex = avatarHash.joinToString("") { "%02x".format(it) }
    return "https://avatars.akamai.steamstatic.com/${hex}_full.jpg"
}
