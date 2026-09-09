package org.steamchat.steamkit

import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CChatRoomState
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CChatRoom_GetChatRoomGroupSummary_Response
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CUserChatRoomGroupState
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CUserChatRoomState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SteamGroupMappingTest {

    @Test
    fun `summary maps sorted text channels voice state and real unread flags`() {
        val laterRoom = CChatRoomState.newBuilder()
            .setChatId(20L)
            .setChatName("voice")
            .setVoiceAllowed(true)
            .addMembersInVoice(123)
            .addMembersInVoice(456)
            .setTimeLastMessage(1_700_000_000)
            .setLastMessage("hello")
            .setSortOrder(2)
            .build()
        val firstRoom = CChatRoomState.newBuilder()
            .setChatId(10L)
            .setChatName("general")
            .setSortOrder(1)
            .build()
        val summary = CChatRoom_GetChatRoomGroupSummary_Response.newBuilder()
            .setChatGroupId(7L)
            .setChatGroupName("Group")
            .setChatGroupTagline("Tagline")
            .setActiveMemberCount(42)
            .setDefaultChatId(10L)
            .addChatRooms(laterRoom)
            .addChatRooms(firstRoom)
            .setAvatarUgcUrl("//cdn.example/avatar.jpg")
            .build()
        val userState = CUserChatRoomGroupState.newBuilder()
            .addUserChatRoomState(
                CUserChatRoomState.newBuilder()
                    .setChatId(20L)
                    .setTimeFirstUnread(1_699_999_999),
            )
            .build()

        val group = SteamGroupMapping.fromSummary(summary, userState)

        assertEquals(listOf(10L, 20L), group.channels.map { it.id })
        assertEquals("https://cdn.example/avatar.jpg", group.avatarUrl)
        assertEquals(42, group.activeMemberCount)
        assertFalse(group.channels[0].hasUnread)
        assertTrue(group.channels[1].hasUnread)
        assertTrue(group.channels[1].voiceAllowed)
        assertEquals(2, group.channels[1].voiceMemberCount)
        assertEquals(listOf(76561197960265851L, 76561197960266184L), group.channels[1].voiceMemberSteamIds)
        assertEquals(1_700_000_000_000L, group.channels[1].lastMessageAt)
        assertTrue(group.hasUnread)
    }

    @Test
    fun `muted group suppresses unread indicator without deleting server state`() {
        val summary = CChatRoom_GetChatRoomGroupSummary_Response.newBuilder()
            .setChatGroupId(7L)
            .addChatRooms(CChatRoomState.newBuilder().setChatId(10L))
            .build()
        val userState = CUserChatRoomGroupState.newBuilder()
            .setUnreadIndicatorMuted(true)
            .addUserChatRoomState(
                CUserChatRoomState.newBuilder().setChatId(10L).setTimeFirstUnread(123),
            )
            .build()

        val group = SteamGroupMapping.fromSummary(summary, userState)

        assertFalse(group.channels.single().hasUnread)
        assertFalse(group.hasUnread)
    }

    @Test
    fun `legacy chat avatar sha maps to the Steam chat icon CDN`() {
        val sha = byteArrayOf(0x01, 0x02, 0x0f, 0x10)

        val url = SteamGroupMapping.groupAvatarUrl(null, sha)

        assertEquals(
            "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/chaticons/1/2/f/01020f10_256.jpg",
            url,
        )
    }
}
