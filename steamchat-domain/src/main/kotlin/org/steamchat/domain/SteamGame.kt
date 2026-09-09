package org.steamchat.domain

/** One entry in a Steam account's owned-games list (steamchat-steamkit's Player.getOwnedGames()). */
data class SteamGame(
    val appId: Int,
    val name: String,
    val iconUrl: String?,
    val playtimeMinutesForever: Int,
)
