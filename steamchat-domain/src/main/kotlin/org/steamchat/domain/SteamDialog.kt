package org.steamchat.domain

data class SteamDialog(
    val friend: SteamUser,
    val lastMessage: SteamMessage?,
    val unreadCount: Int,
)
