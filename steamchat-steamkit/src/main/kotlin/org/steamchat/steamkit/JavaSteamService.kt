package org.steamchat.steamkit

import com.google.gson.JsonParser
import `in`.dragonbra.javasteam.enums.EChatEntryType
import `in`.dragonbra.javasteam.enums.EAccountType
import `in`.dragonbra.javasteam.enums.EClientPersonaStateFlag
import `in`.dragonbra.javasteam.enums.EFriendRelationship
import `in`.dragonbra.javasteam.enums.EPersonaState
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.enums.EUIMode
import `in`.dragonbra.javasteam.enums.EUniverse
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CChatRoom_ChatMessageModified_Notification
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CChatRoom_ChatRoomGroupRoomsChange_Notification
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CChatRoom_ChatRoomHeaderState_Notification
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CChatRoom_GetMessageHistory_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CChatRoom_GetMessageHistory_Response
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CChatRoom_GetMyChatRoomGroups_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CChatRoom_IncomingChatMessage_Notification
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CChatRoom_JoinVoiceChat_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CChatRoom_LeaveVoiceChat_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CChatRoom_SendChatMessage_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.CChatRoom_SetSessionActiveChatRoomGroups_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient.ChatRoomClient_NotifyChatGroupUserStateChanged_Notification
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesFriendmessagesSteamclient.CFriendMessages_GetRecentMessages_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPlayerSteamclient.CPlayer_GetEmoticonList_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPlayerSteamclient.CPlayer_GetOwnedGames_Request
import `in`.dragonbra.javasteam.rpc.service.ChatRoom
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesFriendmessagesSteamclient.CFriendMessages_IncomingMessage_Notification
import `in`.dragonbra.javasteam.rpc.service.ChatRoomClient
import `in`.dragonbra.javasteam.rpc.service.FriendMessagesClient
import `in`.dragonbra.javasteam.rpc.service.FriendMessages
import `in`.dragonbra.javasteam.rpc.service.Player
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.FriendMsgHistoryCallback
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.FriendsListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.PersonaStateCallback
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.ChatMode
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser as JavaSteamUserHandler
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.AccountInfoCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.types.SteamID
import org.steamchat.domain.SteamGamePresence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.steamchat.domain.SteamDialog
import org.steamchat.domain.SteamEmoticon
import org.steamchat.domain.SteamChatGroup
import org.steamchat.domain.SteamGame
import org.steamchat.domain.SteamGroupMessage
import org.steamchat.domain.SteamGroupMessageId
import org.steamchat.domain.SteamIncomingVoiceCall
import org.steamchat.domain.SteamSticker
import org.steamchat.domain.SteamMessage
import org.steamchat.domain.SteamNameHistoryEntry
import org.steamchat.domain.SteamProfileStats
import org.steamchat.domain.SteamStatus
import org.steamchat.domain.SteamUser
import org.steamchat.service.BadgesCache
import org.steamchat.service.CachedBadges
import org.steamchat.service.NoOpBadgesCache
import org.steamchat.service.SessionStore
import org.steamchat.service.SteamConnectionState
import org.steamchat.service.SteamGuardHandler
import org.steamchat.service.SteamLoginResult
import org.steamchat.service.SteamService
import org.steamchat.service.StoredSteamSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Real Steam backend on top of JavaSteam - the same API surface exercised end-to-end (real
 * login, Steam Guard, friends, live message send/receive) by /spike, now behind the
 * [SteamService] contract instead of ad-hoc sample code. No SteamKit2/JavaSteam type is exposed
 * outside this class (section 7 of the master prompt).
 *
 * Doubles as both the [SteamService] boundary (repository) and the realtime Steam Client data
 * source: the CM connection/callback wiring below (login/friends/messages/mergePersona) *is* the
 * client data source, just not pulled into its own class - it's one cohesive, already-tested,
 * heavily stateful flow (shared mutable session/callback state), and splitting it out would be
 * real risk for no external caller besides this class to benefit from the seam. [webDataSource]
 * *is* pulled out (see [SteamWebDataSource]) because that half is the opposite: pure functions,
 * no shared state, genuinely swappable in a test.
 */
class JavaSteamService internal constructor(
    private val sessionStore: SessionStore,
    private val badgesCache: BadgesCache,
    private val webDataSource: SteamWebDataSource,
) : SteamService {

    /** Public entry point - unchanged signature for existing callers (e.g. SteamServiceHolder), which can't name the internal [SteamWebDataSource] type anyway. */
    constructor(sessionStore: SessionStore, badgesCache: BadgesCache = NoOpBadgesCache) :
        this(sessionStore, badgesCache, RealSteamWebDataSource)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var steamUserHandler: JavaSteamUserHandler? = null
    @Volatile private var activeSteamClient: SteamClient? = null
    private var steamFriendsHandler: SteamFriends? = null
    private var levelsHandler: FriendsLevelsHandler? = null
    private var stickerListHandler: StickerListHandler? = null
    private var voiceCallHandler: VoiceCallHandler? = null
    @Volatile private var webRtcProbeSession: WebRtcProbeSession? = null
    private val webRtcProbeEvents = MutableSharedFlow<org.steamchat.domain.SteamWebRtcProbeEvent>(extraBufferCapacity = 16)
    private var playerService: Player? = null
    private var friendMessagesService: FriendMessages? = null
    private var chatRoomService: ChatRoom? = null
    private var storedGuardData: String? = null

    /** Set from FriendsListCallback; there's no per-friend equivalent, so this is self-only. */
    private var myGroupsCount: Int? = null

    /** getProfileStats()'s on-demand level fetch, matching getMessageHistory's pendingHistoryRequests pattern. */
    private val pendingLevelRequests = ConcurrentHashMap<Int, CompletableDeferred<Int>>()

    /** One in flight request at a time is enough for a picker a user opens by hand - no per-key map needed like levels (which are looked up per-friend). */
    private val pendingStickerRequest = java.util.concurrent.atomic.AtomicReference<CompletableDeferred<List<SteamSticker>>?>(null)

    /** EXPERIMENTAL call-signal probe - see sendCallSignal(). */
    private val pendingCallSignal = java.util.concurrent.atomic.AtomicReference<CompletableDeferred<String>?>(null)

    /** Serialises login()/resumeSession() so two overlapping attempts can never build two clients. */
    private val loginMutex = Mutex()

    /**
     * Set once the credential+Guard auth flow succeeds. Steam's CM servers reconnect fairly
     * routinely (load balancing, migrations) - without this, every reconnect re-ran the full
     * credential auth flow from scratch, which meant a brand new Guard confirmation prompt on the
     * user's phone each time. Real Steam clients resume with the existing session instead.
     */
    private var cachedLogOnDetails: LogOnDetails? = null

    private val friendsById = ConcurrentHashMap<Long, SteamUser>()
    private val knownUsersById = ConcurrentHashMap<Long, SteamUser>()
    private val friendIds = ConcurrentHashMap.newKeySet<Long>()
    private val messagesByFriend = ConcurrentHashMap<Long, MutableList<SteamMessage>>()
    private val unreadCounts = ConcurrentHashMap<Long, Int>()
    private val nextMessageId = AtomicLong(1)

    private data class GroupChannelKey(val groupId: Long, val channelId: Long)
    private data class GroupHistoryCursor(val serverTimestamp: Int, val ordinal: Int)

    private val groupMessages = ConcurrentHashMap<GroupChannelKey, MutableStateFlow<List<SteamGroupMessage>>>()
    private val groupHistoryCursors = ConcurrentHashMap<GroupChannelKey, GroupHistoryCursor>()
    private val exhaustedGroupHistories = ConcurrentHashMap.newKeySet<GroupChannelKey>()
    private val groupHistoryMutex = Mutex()

    /** Steam keeps recent friend-message history server-side; fetched once per friend, on demand. */
    private val historyLoaded = ConcurrentHashMap.newKeySet<Long>()
    private val pendingHistoryRequests = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()

    private val _connectionState = MutableStateFlow(SteamConnectionState.DISCONNECTED)
    private val _currentUser = MutableStateFlow<SteamUser?>(null)
    private val _friends = MutableStateFlow<List<SteamUser>>(emptyList())
    private val _dialogs = MutableStateFlow<List<SteamDialog>>(emptyList())
    private val _chatGroups = MutableStateFlow<List<SteamChatGroup>>(emptyList())
    private val _incomingVoiceCall = MutableStateFlow<SteamIncomingVoiceCall?>(null)
    private val incomingMessages = MutableSharedFlow<SteamMessage>(extraBufferCapacity = 64)

    override val connectionState: StateFlow<SteamConnectionState> get() = _connectionState
    override val incomingVoiceCall: StateFlow<SteamIncomingVoiceCall?> get() = _incomingVoiceCall

    override suspend fun login(username: String, password: String, guardHandler: SteamGuardHandler): SteamLoginResult = loginMutex.withLock {
        if (_connectionState.value == SteamConnectionState.CONNECTED) return@withLock SteamLoginResult.Success

        val client = SteamClient()
        activeSteamClient = client
        val manager = CallbackManager(client)
        val user = client.getHandler(JavaSteamUserHandler::class.java)!!
        val friends = client.getHandler(SteamFriends::class.java)!!
        steamUserHandler = user
        steamFriendsHandler = friends

        // SteamUnifiedMessages may already be auto-registered like SteamUser/SteamFriends are;
        // add it ourselves only if it isn't, rather than assuming either way.
        val unifiedMessages = client.getHandler(SteamUnifiedMessages::class.java)
            ?: SteamUnifiedMessages().also { client.addHandler(it) }
        playerService = unifiedMessages.createService(Player::class.java)
        friendMessagesService = unifiedMessages.createService(FriendMessages::class.java)
        chatRoomService = unifiedMessages.createService(ChatRoom::class.java)
        // Registering the client service is what lets SteamUnifiedMessages decode and dispatch
        // ChatRoomClient notifications; the instance itself has no outbound role here.
        unifiedMessages.createService(ChatRoomClient::class.java)
        // Same reason as ChatRoomClient: registering the client service is what lets
        // SteamUnifiedMessages decode and dispatch its notifications.
        unifiedMessages.createService(FriendMessagesClient::class.java)
        val levelsHandlerForThisClient = FriendsLevelsHandler()
        client.addHandler(levelsHandlerForThisClient)
        levelsHandler = levelsHandlerForThisClient
        val stickerListHandlerForThisClient = StickerListHandler()
        client.addHandler(stickerListHandlerForThisClient)
        stickerListHandler = stickerListHandlerForThisClient
        val voiceCallHandlerForThisClient = VoiceCallHandler(
            onIncomingCall = { voiceChatId, partner ->
                _incomingVoiceCall.value = SteamIncomingVoiceCall(voiceChatId, partner)
            },
            onCallEnded = { voiceChatId ->
                if (_incomingVoiceCall.value?.voiceChatId == voiceChatId) _incomingVoiceCall.value = null
                // Also forwarded to the active call: the hangup may land on this connection rather
                // than the web one that owns the call, and a missed one leaves it running.
                webRtcProbeEvents.tryEmit(org.steamchat.domain.SteamWebRtcProbeEvent.CallEnded(voiceChatId))
            },
        )
        client.addHandler(voiceCallHandlerForThisClient)
        voiceCallHandler = voiceCallHandlerForThisClient
        // isRunning and subscriptions are local to this one client/attempt (not fields) so a
        // retried login() after a failure can never share pump-loop state with the previous,
        // by-then-dead attempt - see the High-severity "second SteamClient" finding this replaces.
        val isRunning = AtomicBoolean(true)
        val subscriptions = mutableListOf<AutoCloseable>()
        val loginResult = CompletableDeferred<SteamLoginResult>()

        // Cleared once the one-time credential auth attempt resolves (success or failure), so
        // neither the plaintext password nor the Activity-holding guard handler stays reachable
        // for the pump's entire lifetime via this long-lived ConnectedCallback subscription.
        var pendingPassword: String? = password
        var pendingAuthenticator: GuardHandlerAuthenticator? = GuardHandlerAuthenticator(serviceScope, guardHandler)

        subscriptions += manager.subscribe(ConnectedCallback::class.java) {
            val cached = cachedLogOnDetails
            if (cached != null) {
                // Reconnect after an already-successful login: resume with the same session
                // instead of running a brand new credential+Guard flow (see cachedLogOnDetails doc).
                user.logOn(cached)
                return@subscribe
            }
            val pwd = pendingPassword
            val auth = pendingAuthenticator
            if (pwd == null || auth == null) return@subscribe
            serviceScope.launch {
                try {
                    val authDetails = AuthSessionDetails()
                    authDetails.username = username
                    authDetails.password = pwd
                    authDetails.persistentSession = true
                    authDetails.guardData = storedGuardData
                    authDetails.authenticator = auth

                    val authSession = client.authentication.beginAuthSessionViaCredentials(authDetails).await()
                    val pollResponse = authSession.pollingWaitForResult().await()

                    pollResponse.newGuardData?.let { storedGuardData = it }

                    val details = LogOnDetails()
                    details.username = pollResponse.accountName
                    details.accessToken = pollResponse.refreshToken
                    details.loginID = 149
                    details.chatMode = ChatMode.NEW_STEAM_CHAT
                    details.uiMode = EUIMode.Web
                    cachedLogOnDetails = details
                    sessionStore.save(StoredSteamSession(pollResponse.accountName, pollResponse.refreshToken))
                    user.logOn(details)
                } catch (e: Exception) {
                    isRunning.set(false)
                    client.disconnect()
                    val reason = if (e is java.util.concurrent.CancellationException) {
                        "Steam не ответил за 10 секунд. Проверьте сеть и повторите вход"
                    } else {
                        e.message ?: e.toString()
                    }
                    if (!loginResult.isCompleted) loginResult.complete(SteamLoginResult.Failure(reason))
                } finally {
                    pendingPassword = null
                    pendingAuthenticator = null
                }
            }
        }

        subscriptions += manager.subscribe(DisconnectedCallback::class.java) { cb ->
            cancelWebRtcProbe()
            _incomingVoiceCall.value = null
            if (cb.isUserInitiated) {
                _connectionState.value = SteamConnectionState.DISCONNECTED
                isRunning.set(false)
            } else {
                _connectionState.value = SteamConnectionState.RECONNECTING
                Thread.sleep(2000L)
                client.connect()
            }
        }

        subscriptions += manager.subscribe(LoggedOnCallback::class.java) { cb ->
            if (cb.result != EResult.OK) {
                _connectionState.value = SteamConnectionState.DISCONNECTED
                // Whatever details we tried (fresh or resumed) were rejected - don't keep retrying
                // a dead session on the next app launch, and stop the pump now so the
                // DisconnectedCallback this triggers can never take the reconnect branch and retry
                // the same rejected credentials forever.
                cachedLogOnDetails = null
                sessionStore.clear()
                isRunning.set(false)
                client.disconnect()
                if (!loginResult.isCompleted) loginResult.complete(SteamLoginResult.Failure(cb.result.toString()))
            } else {
                _connectionState.value = SteamConnectionState.CONNECTED
                val steamId64 = user.steamID?.convertToUInt64() ?: 0L
                _currentUser.value = SteamUser(
                    steamId64 = steamId64,
                    personaName = username,
                    avatarUrl = null,
                    status = SteamStatus.ONLINE,
                )
                serviceScope.launch { refreshChatGroups() }
                if (!loginResult.isCompleted) loginResult.complete(SteamLoginResult.Success)
            }
        }

        subscriptions += manager.subscribe(LoggedOffCallback::class.java) {
            cancelWebRtcProbe()
            _incomingVoiceCall.value = null
            _connectionState.value = SteamConnectionState.DISCONNECTED
        }

        subscriptions += manager.subscribe(AccountInfoCallback::class.java) { cb ->
            friends.setPersonaState(EPersonaState.Online)
            friends.requestOfflineMessages()
            _currentUser.value = _currentUser.value?.copy(personaName = cb.personaName)

            // PersonaStateCallback for our own account (see below) only carries a real avatar/game
            // if the server was actually asked with the right flags - explicitly request our own
            // full info once so "currently playing" has a chance of ever being populated for self.
            // Deliberately the 1-arg overload (flags defaults to 0): passing 0 makes the library
            // substitute client.configuration.defaultPersonaStateFlags itself (confirmed by
            // disassembling SteamFriends.requestFriendInfo - flags==0 branches into
            // getDefaultPersonaStateFlags()), which is PlayerName+Presence+SourceID+GameExtraInfo+
            // LastSeen. A hand-picked flag set here previously omitted Presence, which turned out to
            // gate avatar_hash - the response to that narrower request came back with a zeroed
            // avatar and wiped out the good one the initial post-login broadcast had already set.
            val myId = user.steamID
            if (myId != null) {
                friends.requestFriendInfo(listOf(myId))
            }
        }

        subscriptions += manager.subscribe(FriendsSteamLevelsCallback::class.java) { cb ->
            cb.levelByAccountId.forEach { (accountId, level) ->
                pendingLevelRequests.remove(accountId)?.complete(level)
            }
        }

        subscriptions += manager.subscribe(StickerListCallback::class.java) { cb ->
            pendingStickerRequest.getAndSet(null)?.complete(cb.stickers)
        }

        subscriptions += manager.subscribe(VoiceCallPreAuthorizeCallback::class.java) { cb ->
            pendingCallSignal.getAndSet(null)?.complete(cb.result.name)
        }

        subscriptions += manager.subscribe(PersonaStateCallback::class.java) { cb ->
            val id = cb.friendId.convertToUInt64()
            val isSelf = id == user.steamID?.convertToUInt64()
            // Steam reports our own presence through the same callback as friends - real Steam has
            // no "message yourself" feature, so self is routed into _currentUser instead of
            // friendsById; this is also the only place _currentUser's avatar/status/gameName ever
            // get filled in (login() only knows the name).
            val merged = mergePersona(if (isSelf) _currentUser.value else knownUsersById[id], id, cb)
            if (isSelf) {
                _currentUser.value = merged
            } else {
                knownUsersById[id] = merged
                if (id in friendIds) {
                    friendsById[id] = merged
                    _friends.value = friendsById.values.sortedBy { it.personaName }
                    rebuildDialogs()
                }
            }
            updateGroupMessageIdentity(merged)
        }

        subscriptions += manager.subscribe(FriendsListCallback::class.java) { cb ->
            if (!cb.isIncremental) friendIds.clear()
            cb.friendList.forEach { entry ->
                val id = entry.steamID.convertToUInt64()
                if (entry.steamID.isIndividualAccount && entry.relationship == EFriendRelationship.Friend) {
                    friendIds += id
                    knownUsersById[id]?.let { friendsById[id] = it }
                } else {
                    friendIds -= id
                    friendsById.remove(id)
                }
            }
            friendsById.keys.removeIf { it !in friendIds }
            _friends.value = friendsById.values.sortedBy { it.personaName }
            rebuildDialogs()
            // Names/avatars/status arrive per-friend via PersonaStateCallback. Group ("clan")
            // membership is already tracked internally by SteamFriends for this same callback -
            // just read the count back out for the own-profile screen.
            myGroupsCount = friends.getClanCount()
            // Chat list needs a real last-message preview for every dialog, not just the ones the
            // user already opened this session - getMessageHistory() used to run only on chat open.
            // Reuses that exact function (its historyLoaded guard makes repeat calls a no-op, and
            // it already calls rebuildDialogs() itself once a reply lands), so no new pipeline.
            // ponytail: one RPC per friend, no batching/stagger - fine for a normal friend list,
            // add throttling if this ever needs to scale to hundreds of friends.
            friendIds.forEach { id -> serviceScope.launch { getMessageHistory(id) } }
        }

        subscriptions += manager.subscribe(FriendMsgHistoryCallback::class.java) { cb ->
            val friendId = cb.steamID.convertToUInt64()
            if (cb.result == EResult.OK) {
                val myId = _currentUser.value?.steamId64 ?: 0L
                val historyMessages = cb.messages.map { historyEntry ->
                    val senderId = historyEntry.steamID.convertToUInt64()
                    SteamMessage(
                        id = nextMessageId.getAndIncrement(),
                        chatPartnerSteamId64 = friendId,
                        senderSteamId64 = senderId,
                        text = historyEntry.message,
                        timestamp = historyEntry.timestamp.time,
                        isOutgoing = senderId == myId,
                    )
                }
                // compute() replaces the whole list atomically instead of mutating shared state in
                // place, so a concurrent read (getMessageHistory) or a concurrent writer
                // (incoming notifications/sendMessage, on other threads) can never see a half-updated list.
                messagesByFriend.compute(friendId) { _, existing ->
                    (historyMessages + existing.orEmpty())
                        .distinctBy { Triple(it.senderSteamId64, it.timestamp, it.text) }
                        .sortedBy { it.timestamp }
                        .toMutableList()
                }
                rebuildDialogs()
            }
            pendingHistoryRequests.remove(friendId)?.complete(Unit)
        }

        // Not the legacy FriendMsgCallback (ClientFriendMsgIncoming): that one only arrives in
        // Steam's old chat mode, and this session logs on with ChatMode.NEW_STEAM_CHAT - which the
        // voice work requires - so it never fires. Live symptom of getting this wrong: nothing
        // appears in a chat until the app is restarted and history is fetched again.
        subscriptions += manager.subscribeServiceNotification(
            FriendMessagesClient::class.java,
            CFriendMessages_IncomingMessage_Notification.Builder::class.java,
        ) { cb ->
            val body = cb.body.build()
            val text = body.messageNoBbcode.takeIf { it.isNotBlank() } ?: body.message
            if (body.chatEntryType == EChatEntryType.ChatMsg.code() && !text.isNullOrEmpty()) {
                val friendId = body.steamidFriend
                // localEcho means we sent it ourselves from another session (the desktop client),
                // so it belongs in the conversation as an outgoing message and is already read.
                val outgoing = body.localEcho
                val message = SteamMessage(
                    id = nextMessageId.getAndIncrement(),
                    chatPartnerSteamId64 = friendId,
                    senderSteamId64 = if (outgoing) _currentUser.value?.steamId64 ?: 0L else friendId,
                    text = text,
                    timestamp = body.rtime32ServerTimestamp.toLong().takeIf { it > 0L }?.times(1000L)
                        ?: System.currentTimeMillis(),
                    isOutgoing = outgoing,
                )
                messagesByFriend.compute(friendId) { _, existing -> (existing.orEmpty() + message).toMutableList() }
                if (!outgoing) unreadCounts[friendId] = (unreadCounts[friendId] ?: 0) + 1
                rebuildDialogs()
                incomingMessages.tryEmit(message)
            }
        }

        subscriptions += manager.subscribeServiceNotification(
            ChatRoomClient::class.java,
            CChatRoom_IncomingChatMessage_Notification.Builder::class.java,
        ) { cb -> handleIncomingGroupMessage(cb.body.build()) }

        subscriptions += manager.subscribeServiceNotification(
            ChatRoomClient::class.java,
            CChatRoom_ChatMessageModified_Notification.Builder::class.java,
        ) { cb -> handleModifiedGroupMessages(cb.body.build()) }

        subscriptions += manager.subscribeServiceNotification(
            ChatRoomClient::class.java,
            CChatRoom_ChatRoomGroupRoomsChange_Notification.Builder::class.java,
        ) { cb -> handleGroupRoomsChanged(cb.body.build()) }

        subscriptions += manager.subscribeServiceNotification(
            ChatRoomClient::class.java,
            CChatRoom_ChatRoomHeaderState_Notification.Builder::class.java,
        ) { cb -> handleGroupHeaderChanged(cb.body.build()) }

        subscriptions += manager.subscribeServiceNotification(
            ChatRoomClient::class.java,
            ChatRoomClient_NotifyChatGroupUserStateChanged_Notification.Builder::class.java,
        ) {
            // This notification covers joins/leaves and preference changes and already carries a
            // fresh summary. Re-reading the authoritative list keeps all derived unread/channel
            // state consistent instead of partially reconstructing it from an action enum.
            serviceScope.launch { refreshChatGroups() }
        }

        _connectionState.value = SteamConnectionState.CONNECTING
        Thread {
            client.connect()
            while (isRunning.get()) {
                manager.runWaitCallbacks(1000L)
            }
            subscriptions.forEach { it.close() }
        }.start()

        loginResult.await()
    }

    override suspend fun resumeSession(): SteamLoginResult {
        val stored = sessionStore.load() ?: return SteamLoginResult.Failure("no stored session")
        val details = LogOnDetails()
        details.username = stored.username
        details.accessToken = stored.refreshToken
        details.loginID = 149
        details.chatMode = ChatMode.NEW_STEAM_CHAT
        details.uiMode = EUIMode.Web
        cachedLogOnDetails = details
        // password/guardHandler are unused here: cachedLogOnDetails being pre-set makes
        // ConnectedCallback take the resume branch instead of the credential-auth branch.
        return login(stored.username, "", NoOpSteamGuardHandler)
    }

    override suspend fun logout() {
        cancelWebRtcProbe()
        _incomingVoiceCall.value = null
        cachedLogOnDetails = null
        sessionStore.clear()
        steamUserHandler?.logOff()
        _chatGroups.value = emptyList()
        groupMessages.clear()
        groupHistoryCursors.clear()
        exhaustedGroupHistories.clear()
    }

    override fun observeCurrentUser(): StateFlow<SteamUser?> = _currentUser

    override suspend fun initiateWebRtcProbe(offerJson: String, browserName: String, browserVersion: String): String {
        check(connectionState.value == SteamConnectionState.CONNECTED) { "Steam is not connected" }
        cancelWebRtcProbe()
        val session = WebRtcProbeSession(fetchWebLogonToken()) { webRtcProbeEvents.tryEmit(it) }
        webRtcProbeSession = session
        return session.initiate(offerJson, browserName, browserVersion)
    }

    override fun observeWebRtcProbeEvents(): Flow<org.steamchat.domain.SteamWebRtcProbeEvent> = webRtcProbeEvents

    override suspend fun requestOneOnOneWebRtcProbe(partnerSteamId64: Long): Long =
        webRtcProbeSession?.requestOneOnOne(partnerSteamId64)
            ?: error("WebRTC probe is not connected")

    override suspend fun joinOneOnOneWebRtcProbe(voiceChatId: Long, partnerSteamId64: Long) {
        val session = webRtcProbeSession ?: error("WebRTC probe is not connected")
        session.joinOneOnOne(voiceChatId, partnerSteamId64)
        // This session answering the call is what accepts it, so the ringing state is done with.
        if (_incomingVoiceCall.value?.voiceChatId == voiceChatId) _incomingVoiceCall.value = null
    }

    override suspend fun answerIncomingVoiceCall(call: SteamIncomingVoiceCall, accepted: Boolean): Boolean {
        val current = _incomingVoiceCall.value
        if (current != call) return false
        return try {
            voiceCallHandler?.answerOneOnOne(call.voiceChatId, call.partnerSteamId64, accepted) == true
        } finally {
            if (_incomingVoiceCall.value == call) _incomingVoiceCall.value = null
        }
    }

    override suspend fun acknowledgeWebRtcProbeUpdate(version: Long) =
        webRtcProbeSession?.acknowledgeUpdate(version)
            ?: error("WebRTC probe is not connected")

    override fun cancelWebRtcProbe() {
        webRtcProbeSession?.close()
        webRtcProbeSession = null
    }

    private suspend fun fetchWebLogonToken(): WebRtcWebLogon = withContext(Dispatchers.IO) {
        val webSession = createSteamCommunityWebSession()
        val steamId = checkNotNull(_currentUser.value?.steamId64) { "Steam account is unavailable" }
        val connection = URI("https://steamcommunity.com/chat/clientjstoken").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Valve Steam Client")
            connection.setRequestProperty("Cookie", webSession.cookieHeader)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(0)
            connection.outputStream.close()
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Steam web token request failed: HTTP ${connection.responseCode}"
            }
            val json = connection.inputStream.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
            check(json.get("logged_in")?.asBoolean == true) { "Steam web session was not accepted" }
            val responseSteamId = json.get("steamid")?.asString?.toLongOrNull()
            check(responseSteamId == steamId) { "Steam web token belongs to another account" }
            WebRtcWebLogon(
                accountName = checkNotNull(json.get("account_name")?.asString),
                steamId = steamId,
                token = checkNotNull(json.get("token")?.asString),
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun createSteamCommunityWebSession(): SteamCommunityWebSession {
        val client = checkNotNull(activeSteamClient) { "Steam session is unavailable" }
        val steamId = checkNotNull(_currentUser.value?.steamId64) { "Steam account is unavailable" }
        val refreshToken = checkNotNull(cachedLogOnDetails?.accessToken) { "Steam refresh token is unavailable" }
        val accessToken = client.authentication
            .generateAccessTokenForApp(SteamID(steamId), refreshToken)
            .await().accessToken
        val sessionId = UUID.randomUUID().toString().replace("-", "")
        val loginCookie = URLEncoder.encode("$steamId||$accessToken", StandardCharsets.UTF_8.name())
        return SteamCommunityWebSession(
            sessionId = sessionId,
            cookieHeader = "sessionid=$sessionId; steamLoginSecure=$loginCookie",
        )
    }

    override fun observeDialogs(): StateFlow<List<SteamDialog>> = _dialogs

    override fun observeFriends(): StateFlow<List<SteamUser>> = _friends

    override fun observeChatGroups(): StateFlow<List<SteamChatGroup>> = _chatGroups

    override suspend fun getProfileStats(steamId64: Long): SteamProfileStats {
        val isOwn = steamId64 == _currentUser.value?.steamId64

        // No "currently playing" here on purpose: that's realtime state and belongs to
        // PersonaStateCallback/GameDataBlob -> SteamUser.game (see mergePersona). This used to
        // scrape it from ?xml=1 as well, which gave the UI two sources for one fact - and the
        // scraped one, being a single snapshot taken when a screen opened, could never be
        // invalidated, so it kept showing a game the person had already quit.
        // statusText/badgeCount/badgeIconUrls/xpToNextLevel/xpProgressPercent below are all
        // genuinely static-ish profile data with no live protocol equivalent, which is what the
        // web pages are for.
        // fetchLevel (protocol, no HTTP involved) and fetchWebProfile (three HTTP round trips)
        // don't depend on each other - run them concurrently so a slow/stalled network call never
        // delays the other. Sequential here previously meant a slow web fetch delayed even the
        // level circle.
        return coroutineScope {
            // Refreshes the live avatar/online-status (header + dialogs list) via the normal
            // PersonaStateCallback/_currentUser|friendsById path - fire-and-forget, not awaited.
            launch(Dispatchers.IO) { steamFriendsHandler?.requestFriendInfo(listOf(SteamID(steamId64))) }

            val levelDeferred = async { fetchLevel(steamId64) }
            val webProfile = webDataSource.fetchProfile(steamId64)
            // badgesCache is a fallback for when the fetch above comes back empty (rate-limited or
            // a network hiccup - confirmed live: HTTP 429 from steamcommunity.com under heavy
            // testing, and separately confirmed the ?xml=1 fetch specifically failing intermittently
            // too - the "О себе" card going missing "not always", reported live, was this exact gap:
            // statusText had no fallback at all before, unlike badges), not a reason to skip either
            // fetch: none of this changes often enough for a stale cache hit to be harmful, but
            // xpToNextLevel/xpProgressPercent only ever come from a badges/ fetch that actually
            // happened, so skipping that fetch would mean the XP bar never shows again for a profile
            // whose badges got cached once. badgeCount/badgeIconUrls/avatarFrameUrl (from badges/)
            // and statusText (from the independent ?xml=1 fetch) fall back *separately* - see
            // CachedBadges - so one endpoint failing this time never blanks out a still-good cached
            // value from the other.
            val webBadgeCount = webProfile.badgeCount
            val webStatusText = webProfile.statusText
            val cached = if (webBadgeCount == null || webStatusText == null) badgesCache.get(steamId64) else null
            val badgeCount = webBadgeCount ?: cached?.count
            val badgeIconUrls = if (webBadgeCount != null) webProfile.badgeIconUrls else cached?.iconUrls.orEmpty()
            val avatarFrameUrl = if (webBadgeCount != null) webProfile.avatarFrameUrl else cached?.avatarFrameUrl
            val statusText = webStatusText ?: cached?.statusText
            if (webBadgeCount != null || webStatusText != null) {
                badgesCache.put(steamId64, CachedBadges(badgeCount, badgeIconUrls, avatarFrameUrl, statusText))
            }
            SteamProfileStats(
                level = levelDeferred.await(),
                groupsCount = if (isOwn) myGroupsCount else null,
                statusText = statusText,
                badgeCount = badgeCount,
                badgeIconUrls = badgeIconUrls,
                xpToNextLevel = webProfile.xpToNextLevel,
                xpProgressPercent = webProfile.xpProgressPercent,
                avatarFrameUrl = avatarFrameUrl,
                screenshotCount = webProfile.screenshotCount,
            )
        }
    }

    private suspend fun fetchLevel(steamId64: Long): Int? {
        val handler = levelsHandler ?: return null
        val accountId = SteamID(steamId64).accountID.toInt()
        val deferred = CompletableDeferred<Int>()
        pendingLevelRequests[accountId] = deferred
        handler.requestLevels(listOf(steamId64))
        val level = withTimeoutOrNull(5000L) { deferred.await() }
        pendingLevelRequests.remove(accountId)
        return level
    }

    /**
     * The account's own real sticker set (ClientGetEmoticonList/ClientEmoticonList - see
     * StickerListHandler) - for a picker, same reasoning as getAvailableEmoticons: picking a real
     * owned name is what makes `/Sticker {name}` something Steam's own client actually resolves.
     */
    override suspend fun getAvailableStickers(): List<SteamSticker> {
        val handler = stickerListHandler ?: return emptyList()
        val deferred = CompletableDeferred<List<SteamSticker>>()
        pendingStickerRequest.set(deferred)
        handler.requestStickerList()
        val stickers = withTimeoutOrNull(8000L) { deferred.await() }
        pendingStickerRequest.set(null)
        return stickers.orEmpty()
    }

    override suspend fun sendCallSignal(friendSteamId64: Long, hangup: Boolean): String? {
        val handler = voiceCallHandler ?: return null
        val myId = _currentUser.value?.steamId64 ?: return null
        val deferred = CompletableDeferred<String>()
        pendingCallSignal.set(deferred)
        handler.sendPreAuthorize(myId, friendSteamId64, hangup)
        val result = withTimeoutOrNull(8000L) { deferred.await() }
        pendingCallSignal.set(null)
        return result
    }

    override suspend fun getOwnedGames(steamId64: Long): List<SteamGame> {
        val player = playerService ?: return emptyList()
        return try {
            withTimeoutOrNull(8000L) {
                val request = CPlayer_GetOwnedGames_Request.newBuilder()
                    .setSteamid(steamId64)
                    .setIncludeAppinfo(true)
                    .build()
                val response = player.getOwnedGames(request).toFuture().await()
                if (response.result != EResult.OK) return@withTimeoutOrNull emptyList()
                response.body.gamesList.map { game ->
                    SteamGame(
                        appId = game.appid,
                        name = game.name,
                        iconUrl = game.imgIconUrl?.takeIf { it.isNotBlank() },
                        playtimeMinutesForever = game.playtimeForever,
                    )
                }.sortedByDescending { it.playtimeMinutesForever }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * The account's own real, sendable emoticon set - not a guess at what shortcode names exist.
     * Picking from this list (rather than typing a shortcode freehand) is what makes a sent
     * `:name:` guaranteed to be something Steam's own client actually recognises and renders as an
     * image on the other end, instead of showing as literal text.
     */
    override suspend fun getAvailableEmoticons(): List<SteamEmoticon> {
        val player = playerService ?: return emptyList()
        return try {
            withTimeoutOrNull(8000L) {
                val request = CPlayer_GetEmoticonList_Request.newBuilder().build()
                val response = player.getEmoticonList(request).toFuture().await()
                if (response.result != EResult.OK) return@withTimeoutOrNull emptyList()
                response.body.emoticonsList.map { entry -> SteamEmoticon(entry.name, entry.useCount) }
                    .sortedByDescending { it.useCount }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getNameHistory(steamId64: Long): List<SteamNameHistoryEntry> = webDataSource.fetchNameHistory(steamId64)

    /**
     * First call per friend triggers a requestMessageHistory() round trip to Steam's servers and
     * waits (up to 5s) for the FriendMsgHistoryCallback response, so history survives session
     * restarts even though messagesByFriend itself is in-memory only. Later calls just return the
     * cache - no persisted local disk store yet (that's still section 17/21 MVP-2: offline access
     * with zero network at all).
     */
    override suspend fun getMessageHistory(friendSteamId64: Long): List<SteamMessage> {
        if (historyLoaded.add(friendSteamId64)) {
            val fetched = fetchRecentMessages(friendSteamId64)
            if (fetched.isNotEmpty()) {
                mergeHistory(friendSteamId64, fetched)
            } else {
                // Nothing from the unified service (not logged in yet, RPC error, timeout) - fall
                // back to the classic handler so a chat still opens with whatever Steam will give.
                val friends = steamFriendsHandler
                if (friends != null) {
                    withContext(Dispatchers.IO) {
                        val deferred = CompletableDeferred<Unit>()
                        pendingHistoryRequests[friendSteamId64] = deferred
                        friends.requestMessageHistory(SteamID(friendSteamId64))
                        withTimeoutOrNull(5000L) { deferred.await() }
                        pendingHistoryRequests.remove(friendSteamId64)
                    }
                }
            }
        }
        return messagesByFriend[friendSteamId64]?.toList().orEmpty()
    }

    /**
     * Real conversation history, via the unified FriendMessages service the modern Steam client
     * uses - not SteamFriends.requestMessageHistory(), whose classic callback was measured live
     * returning only the last 3 messages of a conversation ("count=3" for a chat with far more in
     * it), which is what made an opened chat look like it had lost everything older.
     *
     * getRecentMessages takes an explicit count, so history depth is ours to choose rather than
     * whatever tail the old call felt like handing back.
     */
    private suspend fun fetchRecentMessages(friendSteamId64: Long): List<SteamMessage> {
        val service = friendMessagesService ?: return emptyList()
        val myId = _currentUser.value?.steamId64 ?: return emptyList()
        return try {
            withTimeoutOrNull(8000L) {
                val request = CFriendMessages_GetRecentMessages_Request.newBuilder()
                    .setSteamid1(myId)
                    .setSteamid2(friendSteamId64)
                    .setCount(HISTORY_MESSAGE_COUNT)
                    // Plain text, not BBCode: the chat renders Steam :emoticon: shortcodes itself
                    // (SteamEmoticons) and has no BBCode parser, so asking for markup would only
                    // surface raw [tags] in the bubbles.
                    .setBbcodeFormat(false)
                    .build()
                val response = withContext(Dispatchers.IO) { service.getRecentMessages(request).toFuture().await() }
                if (response.result != EResult.OK) return@withTimeoutOrNull emptyList()

                val myAccountId = SteamID(myId).accountID
                response.body.messagesList.map { entry ->
                    val outgoing = entry.accountid.toLong() == myAccountId
                    SteamMessage(
                        id = nextMessageId.getAndIncrement(),
                        chatPartnerSteamId64 = friendSteamId64,
                        senderSteamId64 = if (outgoing) myId else friendSteamId64,
                        text = entry.message.orEmpty(),
                        // Unified service timestamps are unix *seconds*; SteamMessage is millis.
                        timestamp = entry.timestamp.toLong() * 1000L,
                        isOutgoing = outgoing,
                    )
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Same atomic merge/dedup the classic history callback uses, so both paths agree. */
    private fun mergeHistory(friendSteamId64: Long, fetched: List<SteamMessage>) {
        messagesByFriend.compute(friendSteamId64) { _, existing ->
            (fetched + existing.orEmpty())
                .distinctBy { Triple(it.senderSteamId64, it.timestamp, it.text) }
                .sortedBy { it.timestamp }
                .toMutableList()
        }
        rebuildDialogs()
    }

    override fun observeMessages(friendSteamId64: Long): Flow<SteamMessage> =
        incomingMessages.filter { it.chatPartnerSteamId64 == friendSteamId64 }

    override suspend fun sendMessage(friendSteamId64: Long, text: String) {
        val friends = steamFriendsHandler ?: error("SteamService.sendMessage called before login")
        withContext(Dispatchers.IO) {
            friends.sendChatMessage(SteamID(friendSteamId64), EChatEntryType.ChatMsg, text)
        }
        val message = SteamMessage(
            id = nextMessageId.getAndIncrement(),
            chatPartnerSteamId64 = friendSteamId64,
            senderSteamId64 = _currentUser.value?.steamId64 ?: 0L,
            text = text,
            timestamp = System.currentTimeMillis(),
            isOutgoing = true,
        )
        messagesByFriend.compute(friendSteamId64) { _, existing -> (existing.orEmpty() + message).toMutableList() }
        rebuildDialogs()
        incomingMessages.tryEmit(message)
    }

    override suspend fun sendMedia(friendSteamId64: Long, filePath: String) = withContext(Dispatchers.IO) {
        SteamChatMediaUploader.uploadToFriend(createSteamCommunityWebSession(), friendSteamId64, filePath)
    }

    override suspend fun markAsRead(friendSteamId64: Long) {
        unreadCounts[friendSteamId64] = 0
        rebuildDialogs()
    }

    override fun observeGroupMessages(groupId: Long, channelId: Long): StateFlow<List<SteamGroupMessage>> =
        groupMessages.computeIfAbsent(GroupChannelKey(groupId, channelId)) {
            MutableStateFlow(emptyList())
        }

    override suspend fun loadOlderGroupMessages(groupId: Long, channelId: Long): Boolean =
        groupHistoryMutex.withLock {
            val key = GroupChannelKey(groupId, channelId)
            if (key in exhaustedGroupHistories) return@withLock false
            val service = chatRoomService ?: return@withLock false
            val cursor = groupHistoryCursors[key]
            try {
                val request = CChatRoom_GetMessageHistory_Request.newBuilder()
                    .setChatGroupId(groupId)
                    .setChatId(channelId)
                    .setMaxCount(GROUP_HISTORY_PAGE_SIZE)
                    .apply {
                        // Valve's reference client leaves these absent for the newest page, then
                        // uses the oldest received message as the next page boundary.
                        if (cursor != null) {
                            setLastTime(cursor.serverTimestamp)
                            setLastOrdinal(cursor.ordinal)
                        }
                    }
                    .build()
                val response = withTimeoutOrNull(8000L) {
                    withContext(Dispatchers.IO) { service.getMessageHistory(request).toFuture().await() }
                } ?: return@withLock true
                if (response.result != EResult.OK) return@withLock true

                val messages = response.body.messagesList.map { entry ->
                    entry.toDomainMessage(groupId, channelId)
                }
                mergeGroupMessages(key, messages)
                requestGroupMessageAuthors(messages)
                messages.minWithOrNull(compareBy<SteamGroupMessage> { it.id.serverTimestamp }.thenBy { it.id.ordinal })
                    ?.let { oldest ->
                        groupHistoryCursors[key] = GroupHistoryCursor(
                            serverTimestamp = oldest.id.serverTimestamp,
                            ordinal = oldest.id.ordinal,
                        )
                    }
                val hasMore = response.body.moreAvailable
                if (!hasMore || messages.isEmpty()) exhaustedGroupHistories += key
                hasMore && messages.isNotEmpty()
            } catch (e: Exception) {
                // A transient CM/RPC failure must remain retryable; it is not proof that history
                // is exhausted.
                true
            }
        }

    override suspend fun sendGroupMessage(groupId: Long, channelId: Long, text: String) {
        val service = chatRoomService ?: error("SteamService.sendGroupMessage called before login")
        val response = withTimeoutOrNull(8000L) {
            val request = CChatRoom_SendChatMessage_Request.newBuilder()
                .setChatGroupId(groupId)
                .setChatId(channelId)
                .setMessage(text)
                .build()
            withContext(Dispatchers.IO) { service.sendChatMessage(request).toFuture().await() }
        } ?: error("Steam group message timed out")
        if (response.result != EResult.OK) error("Steam group message failed: ${response.result}")

        val body = response.body
        val myId = _currentUser.value?.steamId64
        val sent = SteamGroupMessage(
            id = SteamGroupMessageId(body.serverTimestamp, body.ordinal),
            groupId = groupId,
            channelId = channelId,
            senderSteamId64 = myId,
            senderName = _currentUser.value?.personaName,
            senderAvatarUrl = _currentUser.value?.avatarUrl,
            text = body.messageWithoutBbCode.takeIf { it.isNotBlank() }
                ?: body.modifiedMessage.takeIf { it.isNotBlank() }
                ?: text,
            timestamp = body.serverTimestamp.toLong() * 1000L,
            isOutgoing = true,
        )
        mergeGroupMessages(GroupChannelKey(groupId, channelId), listOf(sent))
        updateGroupChannelActivity(sent, unread = false)
    }

    override suspend fun sendGroupMedia(groupId: Long, channelId: Long, filePath: String) =
        withContext(Dispatchers.IO) {
            SteamChatMediaUploader.uploadToGroup(createSteamCommunityWebSession(), groupId, channelId, filePath)
        }

    override suspend fun markGroupChannelRead(groupId: Long, channelId: Long) {
        val service = chatRoomService ?: return
        val key = GroupChannelKey(groupId, channelId)
        val latestTimestamp = groupMessages[key]?.value
            ?.maxOfOrNull { it.id.serverTimestamp }
            ?: ((System.currentTimeMillis() / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val request = `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesChatSteamclient
            .CChatRoom_AckChatMessage_Notification.newBuilder()
            .setChatGroupId(groupId)
            .setChatId(channelId)
            .setTimestamp(latestTimestamp)
            .build()
        withContext(Dispatchers.IO) { service.ackChatMessage(request) }
        updateGroupChannelUnread(groupId, channelId, hasUnread = false)
    }

    override suspend fun joinChannelVoice(groupId: Long, channelId: Long): Boolean {
        val service = chatRoomService ?: return false
        return try {
            withTimeoutOrNull(8000L) {
                val request = CChatRoom_JoinVoiceChat_Request.newBuilder()
                    .setChatGroupId(groupId)
                    .setChatId(channelId)
                    .build()
                val response = withContext(Dispatchers.IO) { service.joinVoiceChat(request).toFuture().await() }
                response.result == EResult.OK
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun leaveChannelVoice(groupId: Long, channelId: Long) {
        val service = chatRoomService ?: return
        try {
            withTimeoutOrNull(8000L) {
                val request = CChatRoom_LeaveVoiceChat_Request.newBuilder()
                    .setChatGroupId(groupId)
                    .setChatId(channelId)
                    .build()
                withContext(Dispatchers.IO) { service.leaveVoiceChat(request).toFuture().await() }
            }
        } catch (e: Exception) {
            // Best-effort: if this fails, Steam will eventually drop a stale voice session itself.
        }
    }

    /**
     * Arbitrary Steam IDs (e.g. voice-channel members) aren't necessarily real friends, so this
     * deliberately does not touch friendsById/observeFriends() - only knownUsersById, the same
     * cache requestGroupMessageAuthors() already uses to resolve group message senders without
     * polluting the actual friends list/dialogs screen.
     */
    override suspend fun resolveUsers(steamId64s: List<Long>): List<SteamUser> {
        val handler = steamFriendsHandler
        val ids = steamId64s.distinct()
        val unresolved = ids.filter { knownUser(it) == null }
        if (handler != null && unresolved.isNotEmpty()) {
            withContext(Dispatchers.IO) { handler.requestFriendInfo(unresolved.map(::SteamID)) }
            withTimeoutOrNull(4000L) {
                while (unresolved.any { knownUser(it) == null }) delay(150L)
            }
        }
        return ids.mapNotNull(::knownUser)
    }

    private suspend fun refreshChatGroups() {
        val service = chatRoomService ?: return
        try {
            val response = withTimeoutOrNull(8000L) {
                val request = CChatRoom_GetMyChatRoomGroups_Request.newBuilder().build()
                withContext(Dispatchers.IO) { service.getMyChatRoomGroups(request).toFuture().await() }
            } ?: return
            if (response.result != EResult.OK) return

            val summaries = response.body.chatRoomGroupsList
                .filter { it.hasGroupSummary() && it.hasUserChatGroupState() && !it.groupSummary.disabled }
                .map { SteamGroupMapping.fromSummary(it.groupSummary, it.userChatGroupState) }
            publishGroups(summaries)

            val groupIds = summaries.map { it.id }
            if (groupIds.isEmpty()) return
            val activeResponse = withTimeoutOrNull(8000L) {
                val request = CChatRoom_SetSessionActiveChatRoomGroups_Request.newBuilder()
                    .addAllChatGroupIds(groupIds)
                    .addAllChatGroupsDataRequested(groupIds)
                    .build()
                withContext(Dispatchers.IO) { service.setSessionActiveChatRoomGroups(request).toFuture().await() }
            } ?: return
            if (activeResponse.result != EResult.OK) return

            val byId = summaries.associateBy { it.id }.toMutableMap()
            activeResponse.body.chatStatesList.forEach { state ->
                val groupId = state.headerState.chatGroupId
                val existing = byId[groupId] ?: return@forEach
                byId[groupId] = SteamGroupMapping.mergeState(existing, state)
            }
            publishGroups(byId.values.toList())
        } catch (_: Exception) {
            // Preserve the last valid snapshot on reconnect or a transient unified-RPC failure.
        }
    }

    private fun handleIncomingGroupMessage(callback: CChatRoom_IncomingChatMessage_Notification) {
        val senderId = callback.steamidSender.takeIf { it != 0L }
        val sender = senderId?.let(::knownUser)
        val message = SteamGroupMessage(
            id = SteamGroupMessageId(callback.timestamp, callback.ordinal),
            groupId = callback.chatGroupId,
            channelId = callback.chatId,
            senderSteamId64 = senderId,
            senderName = sender?.personaName,
            senderAvatarUrl = sender?.avatarUrl,
            text = callback.messageNoBbcode.takeIf { it.isNotBlank() } ?: callback.message,
            timestamp = callback.timestamp.toLong() * 1000L,
            isOutgoing = senderId != null && senderId == _currentUser.value?.steamId64,
            isSystem = callback.hasServerMessage(),
        )
        mergeGroupMessages(GroupChannelKey(callback.chatGroupId, callback.chatId), listOf(message))
        requestGroupMessageAuthors(listOf(message))
        updateGroupChannelActivity(message, unread = !message.isOutgoing)
    }

    private fun handleModifiedGroupMessages(callback: CChatRoom_ChatMessageModified_Notification) {
        val key = GroupChannelKey(callback.chatGroupId, callback.chatId)
        val changes = callback.messagesList.associateBy { SteamGroupMessageId(it.serverTimestamp, it.ordinal) }
        val flow = groupMessages[key] ?: return
        flow.value = flow.value.map { message ->
            changes[message.id]?.let { message.copy(isDeleted = it.deleted) } ?: message
        }
    }

    private fun handleGroupRoomsChanged(callback: CChatRoom_ChatRoomGroupRoomsChange_Notification) {
        replaceGroup(callback.chatGroupId) { group ->
            val channels = SteamGroupMapping.channels(callback.chatRoomsList, group)
            group.copy(
                defaultChannelId = callback.defaultChatId.takeIf { it != 0L }
                    ?: group.defaultChannelId
                    ?: channels.firstOrNull()?.id,
                channels = channels,
                hasUnread = channels.any { it.hasUnread },
            )
        }
    }

    private fun handleGroupHeaderChanged(callback: CChatRoom_ChatRoomHeaderState_Notification) {
        val header = callback.headerState
        replaceGroup(header.chatGroupId) { group ->
            group.copy(
                name = header.chatName.takeIf { it.isNotBlank() } ?: group.name,
                tagline = header.tagline.takeIf { it.isNotBlank() } ?: group.tagline,
                avatarUrl = SteamGroupMapping.groupAvatarUrl(
                    header.avatarUgcUrl,
                    header.avatarSha.toByteArray(),
                ) ?: group.avatarUrl,
            )
        }
    }

    private fun CChatRoom_GetMessageHistory_Response.ChatMessage.toDomainMessage(
        groupId: Long,
        channelId: Long,
    ): SteamGroupMessage {
        val senderId = sender.takeIf { it != 0 }?.let(::accountIdToSteamId64)
        val identity = senderId?.let(::knownUser)
        return SteamGroupMessage(
            id = SteamGroupMessageId(serverTimestamp, ordinal),
            groupId = groupId,
            channelId = channelId,
            senderSteamId64 = senderId,
            senderName = identity?.personaName,
            senderAvatarUrl = identity?.avatarUrl,
            text = message,
            timestamp = serverTimestamp.toLong() * 1000L,
            isOutgoing = senderId != null && senderId == _currentUser.value?.steamId64,
            isDeleted = deleted,
            isSystem = hasServerMessage(),
        )
    }

    private fun accountIdToSteamId64(accountId: Int): Long = SteamID(
        accountId.toLong() and 0xffffffffL,
        EUniverse.Public,
        EAccountType.Individual,
    ).convertToUInt64()

    private fun knownUser(steamId64: Long): SteamUser? =
        _currentUser.value?.takeIf { it.steamId64 == steamId64 } ?: knownUsersById[steamId64]

    private fun requestGroupMessageAuthors(messages: List<SteamGroupMessage>) {
        val handler = steamFriendsHandler ?: return
        val unknownIds = messages.mapNotNull { it.senderSteamId64 }
            .filter { it != _currentUser.value?.steamId64 && !knownUsersById.containsKey(it) }
            .distinct()
        if (unknownIds.isEmpty()) return
        serviceScope.launch(Dispatchers.IO) {
            handler.requestFriendInfo(unknownIds.map(::SteamID))
        }
    }

    private fun updateGroupMessageIdentity(user: SteamUser) {
        groupMessages.values.forEach { flow ->
            if (flow.value.none { it.senderSteamId64 == user.steamId64 }) return@forEach
            flow.value = flow.value.map { message ->
                if (message.senderSteamId64 == user.steamId64) {
                    message.copy(senderName = user.personaName, senderAvatarUrl = user.avatarUrl)
                } else {
                    message
                }
            }
        }
    }

    private fun mergeGroupMessages(key: GroupChannelKey, added: List<SteamGroupMessage>) {
        val flow = groupMessages.computeIfAbsent(key) { MutableStateFlow(emptyList()) }
        flow.value = (flow.value + added)
            .distinctBy { it.id }
            .sortedWith(compareBy<SteamGroupMessage> { it.id.serverTimestamp }.thenBy { it.id.ordinal })
    }

    private fun updateGroupChannelActivity(message: SteamGroupMessage, unread: Boolean) {
        replaceGroup(message.groupId) { group ->
            val channels = group.channels.map { channel ->
                if (channel.id != message.channelId) channel else channel.copy(
                    lastMessage = message.text.takeIf { it.isNotBlank() },
                    lastMessageAt = message.timestamp,
                    hasUnread = channel.hasUnread || unread,
                )
            }
            group.copy(channels = channels, hasUnread = channels.any { it.hasUnread })
        }
    }

    private fun updateGroupChannelUnread(groupId: Long, channelId: Long, hasUnread: Boolean) {
        replaceGroup(groupId) { group ->
            val channels = group.channels.map { channel ->
                if (channel.id == channelId) channel.copy(hasUnread = hasUnread) else channel
            }
            group.copy(channels = channels, hasUnread = channels.any { it.hasUnread })
        }
    }

    private fun replaceGroup(groupId: Long, transform: (SteamChatGroup) -> SteamChatGroup) {
        publishGroups(_chatGroups.value.map { if (it.id == groupId) transform(it) else it })
    }

    private fun publishGroups(groups: List<SteamChatGroup>) {
        _chatGroups.value = groups.sortedWith(
            compareByDescending<SteamChatGroup> { group -> group.channels.maxOfOrNull { it.lastMessageAt ?: 0L } ?: 0L }
                .thenBy { it.name.lowercase() },
        )
    }

    /**
     * One entry per friend, not just friends we've exchanged messages with this session -
     * otherwise a freshly logged-in account with real friends but no in-session messages yet
     * sees a permanently blank dialogs screen with nothing to tap.
     */
    private fun rebuildDialogs() {
        _dialogs.value = friendsById.values.map { friend ->
            SteamDialog(
                friend = friend,
                lastMessage = messagesByFriend[friend.steamId64]?.lastOrNull(),
                unreadCount = unreadCounts[friend.steamId64] ?: 0,
            )
        }.sortedByDescending { it.lastMessage?.timestamp ?: 0L }
    }

    /**
     * PersonaStateCallback.statusFlags says which fields *this* message actually carries - Steam
     * sends several of these per identity around login/friend-list-load, each scoped to a
     * different subset of fields (confirmed live by logging statusFlags: one wave carries
     * everything including Status, a later one omits Status and reports every field it does carry
     * as its zero value). Blindly overwriting with every callback meant the later, narrower message
     * stomped a real avatar/online-status with defaults. Only touch a field when its flag is
     * present; otherwise keep whatever we already had.
     */
    // internal, not private: exercised directly by JavaSteamServiceTest with real PersonaStateCallback
    // fixtures (same pattern as SteamWebProfile.get()) - constructing a full JavaSteamService via its
    // real constructor needs a live CM connection, so the merge logic has to be reachable on its own.
    internal fun mergePersona(existing: SteamUser?, id: Long, cb: PersonaStateCallback): SteamUser {
        val flags = cb.statusFlags
        val name = if (EClientPersonaStateFlag.PlayerName in flags) cb.playerName else existing?.personaName
        val avatar = if (EClientPersonaStateFlag.Presence in flags) avatarUrl(cb.avatarHash) else existing?.avatarUrl
        val status = if (EClientPersonaStateFlag.Status in flags) cb.personaState.toSteamStatus() else existing?.status
        // SteamKit2's handler updates these three protobuf fields under GameDataBlob.
        // GameExtraInfo is a different protocol bit and must not gate game presence.
        val game = if (EClientPersonaStateFlag.GameDataBlob in flags) {
            gamePresence(
                // cb.gamePlayedAppId is the direct game_played_app_id protobuf field - NOT
                // cb.gameId.appID. GameID is a packed type/id (see GameID.isMod()/isShortcut()/
                // isP2PFile()); for a mod or shortcut, bit-extracting .appID from it can yield a
                // number that isn't a real Steam app at all, which would build a bogus/misleading
                // CDN header-image URL. gamePlayedAppId is 0 for exactly those cases, which
                // gamePresence() already treats as absent. Kotlin exposes this as gamePlayedAppId,
                // not gameAppID/getGameAppID() - the jar's public getter is @JvmName-renamed to
                // getGameAppID() to match SteamKit2's C# convention for Java callers, but Kotlin
                // code sees the original property name (confirmed by compiling against the jar).
                gameAppId = cb.gamePlayedAppId,
                gameId = cb.gameId,
                gameName = cb.gameName,
                richPresence = if (EClientPersonaStateFlag.RichPresence in flags) {
                    cb.richPresence.associate { it.key to it.value }
                } else {
                    (existing?.game as? SteamGamePresence.Playing)?.richPresence.orEmpty()
                },
            )
        } else if (EClientPersonaStateFlag.RichPresence in flags) {
            when (val current = existing?.game) {
                is SteamGamePresence.Playing ->
                    current.copy(richPresence = cb.richPresence.associate { it.key to it.value })
                else -> current ?: SteamGamePresence.Unknown
            }
        } else {
            existing?.game ?: SteamGamePresence.Unknown
        }
        return SteamUser(
            steamId64 = id,
            personaName = name?.takeIf { it.isNotBlank() } ?: existing?.personaName.orEmpty(),
            avatarUrl = avatar,
            status = status ?: SteamStatus.OFFLINE,
            game = game,
        )
    }

    private companion object {
        /**
         * How much conversation to pull on first open. Steam's own client fetches a comparable
         * window; the old classic call was measured returning 3 messages regardless of how much
         * the conversation actually had.
         */
        const val HISTORY_MESSAGE_COUNT = 200
        const val GROUP_HISTORY_PAGE_SIZE = 100
    }

    private object NoOpSteamGuardHandler : SteamGuardHandler {
        override suspend fun provideDeviceCode(previousWasIncorrect: Boolean) = ""
        override suspend fun provideEmailCode(email: String?, previousWasIncorrect: Boolean) = ""
        override suspend fun confirmViaMobileApp() = true
    }
}
