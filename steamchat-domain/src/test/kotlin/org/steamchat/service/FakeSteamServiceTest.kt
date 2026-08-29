package org.steamchat.service

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FakeSteamServiceTest {

    @Test
    fun `login seeds 5 friends, 3 dialogs, 50 messages, 2 unread`() = runTest {
        val service = FakeSteamService()

        val result = service.login("user", "pass", NoOpGuardHandler)
        assertEquals(SteamLoginResult.Success, result)

        val friends = service.observeFriends().value
        assertEquals(5, friends.size)

        val dialogs = service.observeDialogs().value
        assertEquals(3, dialogs.size)
        assertEquals(2, dialogs.sumOf { it.unreadCount })

        val totalMessages = dialogs.sumOf { service.getMessageHistory(it.friend.steamId64).size }
        assertEquals(50, totalMessages)
    }

    @Test
    fun `sendMessage appends to history and updates dialog last message`() = runTest {
        val service = FakeSteamService()
        service.login("user", "pass", NoOpGuardHandler)
        val friendId = service.observeFriends().value.first().steamId64
        val before = service.getMessageHistory(friendId).size

        service.sendMessage(friendId, "hello")

        val after = service.getMessageHistory(friendId)
        assertEquals(before + 1, after.size)
        assertEquals("hello", after.last().text)
        assertEquals(after.last(), service.observeDialogs().value.first { it.friend.steamId64 == friendId }.lastMessage)
    }

    private object NoOpGuardHandler : SteamGuardHandler {
        override suspend fun provideDeviceCode(previousWasIncorrect: Boolean) = ""
        override suspend fun provideEmailCode(email: String?, previousWasIncorrect: Boolean) = ""
        override suspend fun confirmViaMobileApp() = true
    }
}
