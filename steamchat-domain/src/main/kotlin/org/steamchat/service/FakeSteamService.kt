package org.steamchat.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import org.steamchat.domain.SteamDialog
import org.steamchat.domain.SteamMessage
import org.steamchat.domain.SteamStatus
import org.steamchat.domain.SteamUser

/**
 * Lets the UI run end-to-end (login -> friends -> chat) without a Steam account or network
 * access, per section 13 of the master prompt: 5 friends, 3 dialogs, 50 messages, 2 unread.
 */
class FakeSteamService : SteamService {

    private val me = SteamUser(
        steamId64 = 76561190000000001L,
        personaName = "SteamChat Tester",
        avatarUrl = null,
        status = SteamStatus.ONLINE,
    )

    private val friends = listOf(
        SteamUser(76561190000000101L, "Alex", null, SteamStatus.ONLINE, gameName = "Counter-Strike 2"),
        SteamUser(76561190000000102L, "Boris", null, SteamStatus.OFFLINE),
        SteamUser(76561190000000103L, "Chloe", null, SteamStatus.AWAY),
        SteamUser(76561190000000104L, "Dana", null, SteamStatus.OFFLINE),
        SteamUser(76561190000000105L, "Egor", null, SteamStatus.BUSY),
    )

    private val messagesByFriend: MutableMap<Long, MutableList<SteamMessage>> = run {
        val dialogFriends = friends.take(3)
        val map = mutableMapOf<Long, MutableList<SteamMessage>>()
        var messageId = 1L
        var timestamp = System.currentTimeMillis() - 3_600_000L

        // 50 messages spread across the first 3 friends, alternating sender.
        repeat(50) { i ->
            val friend = dialogFriends[i % dialogFriends.size]
            val outgoing = i % 2 == 0
            val list = map.getOrPut(friend.steamId64) { mutableListOf() }
            list += SteamMessage(
                id = messageId++,
                chatPartnerSteamId64 = friend.steamId64,
                senderSteamId64 = if (outgoing) me.steamId64 else friend.steamId64,
                text = "Message #${i + 1} with ${friend.personaName}",
                timestamp = timestamp,
                isOutgoing = outgoing,
            )
            timestamp += 60_000L
        }
        map
    }

    private val unreadCounts: MutableMap<Long, Int> = mutableMapOf(
        friends[0].steamId64 to 1,
        friends[1].steamId64 to 1,
    )

    private val incomingMessages = MutableStateFlow<SteamMessage?>(null)

    private val currentUserFlow = MutableStateFlow<SteamUser?>(null)
    private val friendsFlow = MutableStateFlow<List<SteamUser>>(emptyList())
    private val dialogsFlow = MutableStateFlow<List<SteamDialog>>(emptyList())
    private val connectionStateFlow = MutableStateFlow(SteamConnectionState.DISCONNECTED)

    override val connectionState: StateFlow<SteamConnectionState> get() = connectionStateFlow

    override suspend fun login(username: String, password: String, guardHandler: SteamGuardHandler): SteamLoginResult {
        connectionStateFlow.value = SteamConnectionState.CONNECTING
        currentUserFlow.value = me
        friendsFlow.value = friends
        dialogsFlow.value = buildDialogs()
        connectionStateFlow.value = SteamConnectionState.CONNECTED
        return SteamLoginResult.Success
    }

    override suspend fun resumeSession(): SteamLoginResult = login("fake", "fake", object : SteamGuardHandler {
        override suspend fun provideDeviceCode(previousWasIncorrect: Boolean) = ""
        override suspend fun provideEmailCode(email: String?, previousWasIncorrect: Boolean) = ""
        override suspend fun confirmViaMobileApp() = true
    })

    override suspend fun logout() {
        connectionStateFlow.value = SteamConnectionState.DISCONNECTED
        currentUserFlow.value = null
        friendsFlow.value = emptyList()
        dialogsFlow.value = emptyList()
    }

    override fun observeCurrentUser(): StateFlow<SteamUser?> = currentUserFlow

    override fun observeDialogs(): StateFlow<List<SteamDialog>> = dialogsFlow

    override fun observeFriends(): StateFlow<List<SteamUser>> = friendsFlow

    override suspend fun getMessageHistory(friendSteamId64: Long): List<SteamMessage> =
        messagesByFriend[friendSteamId64].orEmpty()

    override fun observeMessages(friendSteamId64: Long): Flow<SteamMessage> =
        incomingMessages.filterNotNull().filter { it.chatPartnerSteamId64 == friendSteamId64 }

    override suspend fun sendMessage(friendSteamId64: Long, text: String) {
        val list = messagesByFriend.getOrPut(friendSteamId64) { mutableListOf() }
        val message = SteamMessage(
            id = (list.maxOfOrNull { it.id } ?: 0L) + 1,
            chatPartnerSteamId64 = friendSteamId64,
            senderSteamId64 = me.steamId64,
            text = text,
            timestamp = System.currentTimeMillis(),
            isOutgoing = true,
        )
        list += message
        dialogsFlow.value = buildDialogs()
    }

    override suspend fun markAsRead(friendSteamId64: Long) {
        unreadCounts[friendSteamId64] = 0
        dialogsFlow.value = buildDialogs()
    }

    private fun buildDialogs(): List<SteamDialog> =
        friends.take(3).map { friend ->
            SteamDialog(
                friend = friend,
                lastMessage = messagesByFriend[friend.steamId64]?.lastOrNull(),
                unreadCount = unreadCounts[friend.steamId64] ?: 0,
            )
        }
}
