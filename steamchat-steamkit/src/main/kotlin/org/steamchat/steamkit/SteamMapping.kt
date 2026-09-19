package org.steamchat.steamkit

import `in`.dragonbra.javasteam.enums.EPersonaState
import `in`.dragonbra.javasteam.types.GameID
import org.steamchat.domain.SteamGamePresence
import org.steamchat.domain.SteamStatus
import org.steamchat.domain.SteamUser

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

internal fun gamePresence(
    gameAppId: Int,
    gameId: GameID,
    gameName: String?,
    richPresence: Map<String, String>,
): SteamGamePresence {
    val appId = gameAppId.takeIf { it > 0 }
    val id = gameId.toUInt64().takeIf { it != 0L }
    val name = gameName?.takeIf { it.isNotBlank() }
    return if (appId == null && id == null && name == null) {
        SteamGamePresence.NotPlaying
    } else {
        SteamGamePresence.Playing(appId, id, name, richPresence)
    }
}

internal fun withResolvedGameName(user: SteamUser, appId: Int, name: String): SteamUser {
    val playing = user.game as? SteamGamePresence.Playing ?: return user
    if (playing.appId != appId || playing.name != null) return user
    return user.copy(game = playing.copy(name = name))
}
