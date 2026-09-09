package org.steamchat.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SteamInboxTest {
    private val friend = SteamDialog(SteamUser(7, "Друг", null, SteamStatus.ONLINE),
        SteamMessage(1, 7, 7, "Привет", 100, false), 0)
    private val group = SteamChatGroup(7, "Группа", null, null, 0, 1,
        listOf(SteamChatChannel(1, "Общий", false, 0, emptyList(), "Новое", 200, false)), false)

    @Test
    fun `one inbox sorts direct and group conversations by latest activity`() {
        assertEquals(listOf("group:7", "friend:7"), steamInbox(listOf(friend), listOf(group)).map { it.key })
    }

    @Test
    fun `local folder distinguishes equal friend and group IDs and keeps missing memberships`() {
        val folder = SteamChatFolder("folder", "Игры", setOf("friend:7", "group:8"))
        assertEquals(listOf("friend:7"), steamInbox(listOf(friend), listOf(group), folder).map { it.key })
        assertTrue(steamInbox(emptyList(), emptyList(), folder).isEmpty())
        assertEquals(setOf("friend:7", "group:8"), folder.chatKeys)
        assertEquals(2, steamInbox(listOf(friend), listOf(group.copy(id = 8)), folder).size)
    }

    @Test
    fun `empty folders stay empty and equal timestamps have stable ordering`() {
        assertTrue(steamInbox(listOf(friend), listOf(group), SteamChatFolder("f", "Пустая", emptySet())).isEmpty())
        val a = group.copy(id = 8, channels = emptyList())
        val b = group.copy(id = 9, channels = emptyList())
        assertEquals(steamInbox(emptyList(), listOf(a, b)), steamInbox(emptyList(), listOf(b, a)))
    }
}
