package org.steamchat.domain

/** A Steam Chat group with the channels visible to the signed-in user. */
data class SteamChatGroup(
    val id: Long,
    val name: String,
    val tagline: String?,
    val avatarUrl: String?,
    val activeMemberCount: Int,
    val defaultChannelId: Long?,
    val channels: List<SteamChatChannel>,
    val hasUnread: Boolean,
)

/**
 * Steam channels always carry text. [voiceAllowed] means the same channel additionally supports
 * Steam voice chat; it does not turn it into a separate non-text channel.
 */
data class SteamChatChannel(
    val id: Long,
    val name: String,
    val voiceAllowed: Boolean,
    val voiceMemberCount: Int,
    /** Real Steam IDs of whoever is currently in this channel's voice chat - presence only, no audio (see CLAUDE.md: ChatRoom.joinVoiceChat has no media transport anywhere in JavaSteam). Resolve to names/avatars via SteamService.resolveUsers - not necessarily friends, so not looked up automatically. */
    val voiceMemberSteamIds: List<Long>,
    val lastMessage: String?,
    val lastMessageAt: Long?,
    val hasUnread: Boolean,
)

/** Stable identity of a server-side group message within its channel. */
data class SteamGroupMessageId(
    val serverTimestamp: Int,
    val ordinal: Int,
)

data class SteamGroupMessage(
    val id: SteamGroupMessageId,
    val groupId: Long,
    val channelId: Long,
    val senderSteamId64: Long?,
    val senderName: String?,
    val senderAvatarUrl: String?,
    val text: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val isDeleted: Boolean = false,
    val isSystem: Boolean = false,
)
