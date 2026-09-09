package org.steamchat.steamkit

import `in`.dragonbra.javasteam.enums.EAccountType
import `in`.dragonbra.javasteam.enums.EUniverse
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CChatRoomGroupState
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CChatRoomState
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CChatRoom_GetChatRoomGroupSummary_Response
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CUserChatRoomGroupState
import `in`.dragonbra.javasteam.types.SteamID
import org.steamchat.domain.SteamChatChannel
import org.steamchat.domain.SteamChatGroup

internal object SteamGroupMapping {

    fun fromSummary(
        summary: CChatRoom_GetChatRoomGroupSummary_Response,
        userState: CUserChatRoomGroupState,
    ): SteamChatGroup {
        val roomStates = userState.userChatRoomStateList.associateBy { it.chatId }
        val groupMuted = userState.unreadIndicatorMuted
        val channels = summary.chatRoomsList
            .sortedBy { it.sortOrder }
            .map { room ->
                val state = roomStates[room.chatId]
                room.toDomain(
                    hasUnread = !groupMuted && state != null &&
                        !state.unreadIndicatorMuted && state.timeFirstUnread > 0,
                )
            }
        return SteamChatGroup(
            id = summary.chatGroupId,
            name = summary.chatGroupName,
            tagline = summary.chatGroupTagline.takeIf { it.isNotBlank() },
            avatarUrl = groupAvatarUrl(summary.avatarUgcUrl, summary.chatGroupAvatarSha.toByteArray()),
            activeMemberCount = summary.activeMemberCount,
            defaultChannelId = summary.defaultChatId.takeIf { it != 0L } ?: channels.firstOrNull()?.id,
            channels = channels,
            hasUnread = channels.any { it.hasUnread },
        )
    }

    fun mergeState(existing: SteamChatGroup, state: CChatRoomGroupState): SteamChatGroup {
        val unreadByChannel = existing.channels.associate { it.id to it.hasUnread }
        val header = state.headerState
        val channels = state.chatRoomsList
            .sortedBy { it.sortOrder }
            .map { it.toDomain(unreadByChannel[it.chatId] == true) }
        return existing.copy(
            name = header.chatName.takeIf { it.isNotBlank() } ?: existing.name,
            tagline = header.tagline.takeIf { it.isNotBlank() } ?: existing.tagline,
            avatarUrl = groupAvatarUrl(header.avatarUgcUrl, header.avatarSha.toByteArray()) ?: existing.avatarUrl,
            defaultChannelId = state.defaultChatId.takeIf { it != 0L }
                ?: existing.defaultChannelId
                ?: channels.firstOrNull()?.id,
            channels = channels,
            hasUnread = channels.any { it.hasUnread },
        )
    }

    fun channels(
        rooms: List<CChatRoomState>,
        existing: SteamChatGroup,
    ): List<SteamChatChannel> {
        val unreadByChannel = existing.channels.associate { it.id to it.hasUnread }
        return rooms.sortedBy { it.sortOrder }.map { room ->
            room.toDomain(unreadByChannel[room.chatId] == true)
        }
    }

    private fun CChatRoomState.toDomain(hasUnread: Boolean) = SteamChatChannel(
        id = chatId,
        name = chatName,
        voiceAllowed = voiceAllowed,
        voiceMemberCount = membersInVoiceCount,
        voiceMemberSteamIds = membersInVoiceList.map { accountIdToSteamId64(it) },
        lastMessage = lastMessage.takeIf { it.isNotBlank() },
        lastMessageAt = timeLastMessage.takeIf { it > 0 }?.toLong()?.times(1000L),
        hasUnread = hasUnread,
    )

    /** Same conversion JavaSteamService.accountIdToSteamId64 uses for group message senders - every member here is a regular individual account, never a clan/anon id. */
    private fun accountIdToSteamId64(accountId: Int): Long =
        SteamID(accountId.toLong() and 0xffffffffL, EUniverse.Public, EAccountType.Individual).convertToUInt64()

    internal fun groupAvatarUrl(ugcUrl: String?, sha: ByteArray): String? {
        val direct = ugcUrl?.takeIf { it.isNotBlank() }?.let(::normalizeUrl)
        if (direct != null) return direct
        if (sha.size < 3) return null
        val hex = sha.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val path = sha.take(3).joinToString("/") { (it.toInt() and 0xff).toString(16) }
        return "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/chaticons/$path/${hex}_256.jpg"
    }

    private fun normalizeUrl(url: String): String = if (url.startsWith("//")) "https:$url" else url
}
