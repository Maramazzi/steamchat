package org.steamchat.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import org.steamchat.domain.SteamDialog
import org.steamchat.domain.SteamChatGroup
import org.steamchat.domain.SteamGame
import org.steamchat.domain.SteamGroupMessage
import org.steamchat.domain.SteamIncomingVoiceCall
import org.steamchat.domain.SteamMessage
import org.steamchat.domain.SteamEmoticon
import org.steamchat.domain.SteamSticker
import org.steamchat.domain.SteamNameHistoryEntry
import org.steamchat.domain.SteamProfileStats
import org.steamchat.domain.SteamStatus
import org.steamchat.domain.SteamGamePresence
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
        SteamUser(
            76561190000000101L,
            "Alex",
            null,
            SteamStatus.ONLINE,
            game = SteamGamePresence.Playing(appId = 730, gameId = 730L, name = "Counter-Strike 2"),
        ),
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
    private val chatGroupsFlow = MutableStateFlow<List<SteamChatGroup>>(emptyList())
    private val groupMessages = mutableMapOf<Pair<Long, Long>, MutableStateFlow<List<SteamGroupMessage>>>()
    private val connectionStateFlow = MutableStateFlow(SteamConnectionState.DISCONNECTED)
    override val incomingVoiceCall = MutableStateFlow<SteamIncomingVoiceCall?>(null)

    private val levelByUser: Map<Long, Int> = mapOf(me.steamId64 to 24) + friends.mapIndexed { i, f -> f.steamId64 to (i + 1) * 11 }
    private val gamesByUser: Map<Long, List<SteamGame>> = mapOf(
        me.steamId64 to listOf(
            SteamGame(730, "Counter-Strike 2", null, 128_400),
            SteamGame(570, "Dota 2", null, 54_200),
            SteamGame(440, "Team Fortress 2", null, 3_100),
        ),
        friends[0].steamId64 to listOf(SteamGame(730, "Counter-Strike 2", null, 9_800)),
    )

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

    override fun observeChatGroups(): StateFlow<List<SteamChatGroup>> = chatGroupsFlow

    override suspend fun getProfileStats(steamId64: Long): SteamProfileStats = SteamProfileStats(
        level = levelByUser[steamId64],
        groupsCount = if (steamId64 == me.steamId64) 14 else null,
        statusText = if (steamId64 == me.steamId64) "тестовый статус профиля" else null,
        badgeCount = if (steamId64 == me.steamId64) 18 else null,
        badgeIconUrls = if (steamId64 == me.steamId64) {
            listOf(
                "https://community.fastly.steamstatic.com/public/images/badges/02_years/steamyears7_80.png",
                "https://community.fastly.steamstatic.com/public/images/badges/13_gamecollector/50_80.png",
            )
        } else {
            emptyList()
        },
        xpToNextLevel = if (steamId64 == me.steamId64) 1150 else null,
        xpProgressPercent = if (steamId64 == me.steamId64) 67 else null,
        avatarFrameUrl = if (steamId64 == me.steamId64) {
            "https://shared.akamai.steamstatic.com/community_assets/images/items/1210230/918d6cbab2b4a4cdc9776f39fdfe64932a809d81.png"
        } else {
            null
        },
        screenshotCount = if (steamId64 == me.steamId64) 37 else null,
    )

    override suspend fun getOwnedGames(steamId64: Long): List<SteamGame> = gamesByUser[steamId64].orEmpty()

    override suspend fun getNameHistory(steamId64: Long): List<SteamNameHistoryEntry> =
        if (steamId64 == me.steamId64) {
            listOf(
                SteamNameHistoryEntry("SteamChat Tester", "17 Dec, 2025 @ 2:30am"),
                SteamNameHistoryEntry("OldTesterName", "1 Aug, 2025 @ 5:08am"),
            )
        } else {
            emptyList()
        }

    override suspend fun getAvailableEmoticons(): List<SteamEmoticon> = listOf(
        SteamEmoticon("steamhappy", useCount = 12),
        SteamEmoticon("steamsad", useCount = 3),
        SteamEmoticon("crtstressed", useCount = 1),
    )

    override suspend fun getAvailableStickers(): List<SteamSticker> = listOf(
        SteamSticker("PartyFrog", useCount = 4),
        SteamSticker("ThumbsUpGuy", useCount = 2),
    )

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
        incomingMessages.value = message
    }

    override suspend fun markAsRead(friendSteamId64: Long) {
        unreadCounts[friendSteamId64] = 0
        dialogsFlow.value = buildDialogs()
    }

    override fun observeGroupMessages(groupId: Long, channelId: Long): StateFlow<List<SteamGroupMessage>> =
        groupMessages.getOrPut(groupId to channelId) { MutableStateFlow(emptyList()) }

    override suspend fun loadOlderGroupMessages(groupId: Long, channelId: Long): Boolean = false

    override suspend fun sendGroupMessage(groupId: Long, channelId: Long, text: String) = Unit

    override suspend fun markGroupChannelRead(groupId: Long, channelId: Long) = Unit

    override suspend fun joinChannelVoice(groupId: Long, channelId: Long): Boolean = true

    override suspend fun leaveChannelVoice(groupId: Long, channelId: Long) = Unit

    override suspend fun resolveUsers(steamId64s: List<Long>): List<SteamUser> =
        (friends + me).filter { it.steamId64 in steamId64s }

    override suspend fun initiateWebRtcProbe(offerJson: String, browserName: String, browserVersion: String): String =
        error("WebRTC probe requires a real Steam session")
    override suspend fun requestOneOnOneWebRtcProbe(partnerSteamId64: Long): Long =
        error("WebRTC probe requires a real Steam session")
    override suspend fun joinOneOnOneWebRtcProbe(voiceChatId: Long, partnerSteamId64: Long) =
        error("WebRTC probe requires a real Steam session")
    override suspend fun acknowledgeWebRtcProbeUpdate(version: Long) =
        error("WebRTC probe requires a real Steam session")
    override fun observeWebRtcProbeEvents(): Flow<org.steamchat.domain.SteamWebRtcProbeEvent> = kotlinx.coroutines.flow.emptyFlow()
    override fun cancelWebRtcProbe() = Unit
    override suspend fun answerIncomingVoiceCall(call: SteamIncomingVoiceCall, accepted: Boolean) = true

    override suspend fun sendCallSignal(friendSteamId64: Long, hangup: Boolean): String? = "OK"

    private fun buildDialogs(): List<SteamDialog> =
        friends.take(3).map { friend ->
            SteamDialog(
                friend = friend,
                lastMessage = messagesByFriend[friend.steamId64]?.lastOrNull(),
                unreadCount = unreadCounts[friend.steamId64] ?: 0,
            )
        }
}
