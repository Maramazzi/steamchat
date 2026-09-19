package org.steamchat.service

import org.steamchat.domain.SteamMessage

/** Account-scoped private disk history. All calls run on the IO dispatcher. */
interface MessageHistoryStore {
    fun load(accountId: Long, friendId: Long): List<SteamMessage>
    fun merge(accountId: Long, friendId: Long, messages: List<SteamMessage>)
}

object NoOpMessageHistoryStore : MessageHistoryStore {
    override fun load(accountId: Long, friendId: Long) = emptyList<SteamMessage>()
    override fun merge(accountId: Long, friendId: Long, messages: List<SteamMessage>) {}
}
