package org.steamchat.domain

/** Local folder membership uses a namespace so a friend and group cannot collide. */
data class SteamChatFolder(val id: String, val name: String, val chatKeys: Set<String>)

sealed interface SteamInboxEntry {
    val key: String
    val name: String
    val timestamp: Long

    data class Direct(val dialog: SteamDialog) : SteamInboxEntry {
        override val key get() = "friend:${dialog.friend.steamId64}"
        override val name get() = dialog.friend.personaName
        override val timestamp get() = dialog.lastMessage?.timestamp ?: 0L
    }

    data class Group(val group: SteamChatGroup) : SteamInboxEntry {
        override val key get() = "group:${group.id}"
        override val name get() = group.name.ifBlank { "Группа Steam" }
        override val timestamp get() = group.channels.mapNotNull { it.lastMessageAt }.maxOrNull() ?: 0L
    }
}

fun steamInbox(
    dialogs: List<SteamDialog>,
    groups: List<SteamChatGroup>,
    folder: SteamChatFolder? = null,
): List<SteamInboxEntry> =
    (dialogs.map { SteamInboxEntry.Direct(it) } + groups.map { SteamInboxEntry.Group(it) })
        .filter { folder == null || it.key in folder.chatKeys }
        .sortedWith(compareByDescending<SteamInboxEntry> { it.timestamp }.thenBy { it.key })
