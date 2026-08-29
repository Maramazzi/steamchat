package org.steamchat.domain

data class SteamUser(
    val steamId64: Long,
    val personaName: String,
    val avatarUrl: String?,
    val status: SteamStatus,
    /** Currently played game title, null when not in-game. */
    val gameName: String? = null,
)
