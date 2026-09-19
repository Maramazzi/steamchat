package org.steamchat.steamkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.steamchat.domain.SteamMessage
import org.steamchat.domain.SteamUser
import org.steamchat.domain.SteamStatus
import org.steamchat.service.NoOpSessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.steamchat.service.MessageHistoryStore
import org.steamchat.service.NoOpBadgesCache

class MessageRecoveryTest {
    @Test fun `retry grows to a limit and resets after login`() {
        val retry = ReconnectBackoff()
        assertEquals(listOf(2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L), List(7) { retry.nextDelay() })
        repeat(1000) { assertEquals(60000L, retry.nextDelay()) }
        retry.reset()
        assertEquals(2000L, retry.nextDelay())
    }

    @Test fun `history refresh preserves cached messages and removes server duplicates`() {
        val cached = SteamMessage(1, 42, 42, "old", 1000, false)
        val newer = cached.copy(id = 2, text = "new", timestamp = 2000)
        assertEquals(listOf(cached, newer), mergeMessageHistory(listOf(cached), listOf(newer, cached.copy(id = 99))))
    }

    @Test fun `late response from another account cannot publish history`() {
        val service = JavaSteamService(NoOpSessionStore)
        service.javaClass.getDeclaredField("historyAccountId").apply { isAccessible = true }.set(service, 2L)
        @Suppress("UNCHECKED_CAST")
        val user = service.javaClass.getDeclaredField("_currentUser").apply { isAccessible = true }.get(service) as MutableStateFlow<SteamUser?>
        user.value = SteamUser(2L, "B", null, SteamStatus.ONLINE)
        val merge = service.javaClass.declaredMethods.single { it.name == "mergeHistory" }.apply { isAccessible = true }
        assertFalse(merge.invoke(service, 42L, listOf(SteamMessage(1, 42, 42, "account A", 1000, false)), false, 1L) as Boolean)
        assertEquals(emptyList<SteamMessage>(), service.observeMessageHistory(42).value)
    }

    @Test fun `old waiting cache request cannot mark another accounts cache loaded`() = runBlocking {
        val loads = mutableListOf<Long>()
        val cached = SteamMessage(1, 42, 42, "B history", 1000, false)
        val store = object : MessageHistoryStore {
            override fun load(accountId: Long, friendId: Long): List<SteamMessage> { loads += accountId; return listOf(cached) }
            override fun merge(accountId: Long, friendId: Long, messages: List<SteamMessage>) {}
        }
        val service = JavaSteamService(NoOpSessionStore, NoOpBadgesCache, store)
        @Suppress("UNCHECKED_CAST")
        val user = service.javaClass.getDeclaredField("_currentUser").apply { isAccessible = true }.get(service) as MutableStateFlow<SteamUser?>
        val account = service.javaClass.getDeclaredField("historyAccountId").apply { isAccessible = true }
        account.set(service, 1L); user.value = SteamUser(1L, "A", null, SteamStatus.ONLINE)
        @Suppress("UNCHECKED_CAST")
        val mutexes = service.javaClass.getDeclaredField("historyMutexes").apply { isAccessible = true }.get(service) as MutableMap<Long, Mutex>
        val mutex = Mutex(locked = true)
        mutexes[42L] = mutex
        val old = launch(start = CoroutineStart.UNDISPATCHED) { service.getMessageHistory(42) }
        account.set(service, 2L); user.value = SteamUser(2L, "B", null, SteamStatus.ONLINE)
        mutex.unlock(); old.join()
        assertEquals(listOf(cached), service.getMessageHistory(42))
        assertEquals(listOf(2L), loads)
    }
}
