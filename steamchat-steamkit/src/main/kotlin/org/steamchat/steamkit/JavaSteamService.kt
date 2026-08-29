package org.steamchat.steamkit

import `in`.dragonbra.javasteam.enums.EChatEntryType
import `in`.dragonbra.javasteam.enums.EPersonaState
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.FriendMsgCallback
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.FriendsListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.PersonaStateCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser as JavaSteamUserHandler
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.AccountInfoCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.types.SteamID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.steamchat.domain.SteamDialog
import org.steamchat.domain.SteamMessage
import org.steamchat.domain.SteamStatus
import org.steamchat.domain.SteamUser
import org.steamchat.service.SteamConnectionState
import org.steamchat.service.SteamGuardHandler
import org.steamchat.service.SteamLoginResult
import org.steamchat.service.SteamService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Real Steam backend on top of JavaSteam - the same API surface exercised end-to-end (real
 * login, Steam Guard, friends, live message send/receive) by /spike, now behind the
 * [SteamService] contract instead of ad-hoc sample code. No SteamKit2/JavaSteam type is exposed
 * outside this class (section 7 of the master prompt).
 */
class JavaSteamService : SteamService {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var steamUserHandler: JavaSteamUserHandler? = null
    private var steamFriendsHandler: SteamFriends? = null
    private var storedGuardData: String? = null
    private var isRunning = false
    private val subscriptions = mutableListOf<AutoCloseable>()

    /**
     * Set once the credential+Guard auth flow succeeds. Steam's CM servers reconnect fairly
     * routinely (load balancing, migrations) - without this, every reconnect re-ran the full
     * credential auth flow from scratch, which meant a brand new Guard confirmation prompt on the
     * user's phone each time. Real Steam clients resume with the existing session instead.
     */
    private var cachedLogOnDetails: LogOnDetails? = null
    private var pendingLogin: CompletableDeferred<SteamLoginResult>? = null

    private val friendsById = ConcurrentHashMap<Long, SteamUser>()
    private val messagesByFriend = ConcurrentHashMap<Long, MutableList<SteamMessage>>()
    private val unreadCounts = ConcurrentHashMap<Long, Int>()
    private val nextMessageId = AtomicLong(1)

    private val _connectionState = MutableStateFlow(SteamConnectionState.DISCONNECTED)
    private val _currentUser = MutableStateFlow<SteamUser?>(null)
    private val _friends = MutableStateFlow<List<SteamUser>>(emptyList())
    private val _dialogs = MutableStateFlow<List<SteamDialog>>(emptyList())
    private val incomingMessages = MutableSharedFlow<SteamMessage>(extraBufferCapacity = 64)

    override val connectionState: StateFlow<SteamConnectionState> get() = _connectionState

    override suspend fun login(username: String, password: String, guardHandler: SteamGuardHandler): SteamLoginResult {
        pendingLogin?.let { existing -> if (!existing.isCompleted) return existing.await() }
        if (_connectionState.value == SteamConnectionState.CONNECTED) return SteamLoginResult.Success

        val client = SteamClient()
        val manager = CallbackManager(client)
        val user = client.getHandler(JavaSteamUserHandler::class.java)!!
        val friends = client.getHandler(SteamFriends::class.java)!!
        steamUserHandler = user
        steamFriendsHandler = friends

        val authenticator = GuardHandlerAuthenticator(serviceScope, guardHandler)
        val loginResult = CompletableDeferred<SteamLoginResult>()
        pendingLogin = loginResult

        subscriptions += manager.subscribe(ConnectedCallback::class.java) {
            val cached = cachedLogOnDetails
            if (cached != null) {
                // Reconnect after an already-successful login: resume with the same session
                // instead of running a brand new credential+Guard flow (see cachedLogOnDetails doc).
                user.logOn(cached)
                return@subscribe
            }
            serviceScope.launch {
                try {
                    val authDetails = AuthSessionDetails()
                    authDetails.username = username
                    authDetails.password = password
                    authDetails.persistentSession = true
                    authDetails.guardData = storedGuardData
                    authDetails.authenticator = authenticator

                    val authSession = client.authentication.beginAuthSessionViaCredentials(authDetails).await()
                    val pollResponse = authSession.pollingWaitForResult().await()

                    pollResponse.newGuardData?.let { storedGuardData = it }

                    val details = LogOnDetails()
                    details.username = pollResponse.accountName
                    details.accessToken = pollResponse.refreshToken
                    details.loginID = 149
                    cachedLogOnDetails = details
                    user.logOn(details)
                } catch (e: Exception) {
                    if (!loginResult.isCompleted) loginResult.complete(SteamLoginResult.Failure(e.message ?: e.toString()))
                    user.logOff()
                }
            }
        }

        subscriptions += manager.subscribe(DisconnectedCallback::class.java) { cb ->
            if (cb.isUserInitiated) {
                _connectionState.value = SteamConnectionState.DISCONNECTED
                isRunning = false
            } else {
                _connectionState.value = SteamConnectionState.RECONNECTING
                Thread.sleep(2000L)
                client.connect()
            }
        }

        subscriptions += manager.subscribe(LoggedOnCallback::class.java) { cb ->
            if (cb.result != EResult.OK) {
                _connectionState.value = SteamConnectionState.DISCONNECTED
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
                if (!loginResult.isCompleted) loginResult.complete(SteamLoginResult.Success)
            }
        }

        subscriptions += manager.subscribe(LoggedOffCallback::class.java) {
            _connectionState.value = SteamConnectionState.DISCONNECTED
        }

        subscriptions += manager.subscribe(AccountInfoCallback::class.java) { cb ->
            friends.setPersonaState(EPersonaState.Online)
            friends.requestOfflineMessages()
            _currentUser.value = _currentUser.value?.copy(personaName = cb.personaName)
        }

        subscriptions += manager.subscribe(PersonaStateCallback::class.java) { cb ->
            val id = cb.friendId.convertToUInt64()
            friendsById[id] = SteamUser(
                steamId64 = id,
                personaName = cb.playerName ?: friendsById[id]?.personaName.orEmpty(),
                avatarUrl = avatarUrl(cb.avatarHash),
                status = cb.personaState.toSteamStatus(),
                gameName = cb.gameName,
            )
            _friends.value = friendsById.values.sortedBy { it.personaName }
            rebuildDialogs()
        }

        subscriptions += manager.subscribe(FriendsListCallback::class.java) {
            // Names/avatars/status arrive per-friend via PersonaStateCallback, nothing to do here.
        }

        subscriptions += manager.subscribe(FriendMsgCallback::class.java) { cb ->
            val text = cb.message
            if (cb.entryType == EChatEntryType.ChatMsg && text != null) {
                val friendId = cb.sender.convertToUInt64()
                val message = SteamMessage(
                    id = nextMessageId.getAndIncrement(),
                    chatPartnerSteamId64 = friendId,
                    senderSteamId64 = friendId,
                    text = text,
                    timestamp = System.currentTimeMillis(),
                    isOutgoing = false,
                )
                messagesByFriend.getOrPut(friendId) { mutableListOf() }.add(message)
                unreadCounts[friendId] = (unreadCounts[friendId] ?: 0) + 1
                rebuildDialogs()
                incomingMessages.tryEmit(message)
            }
        }

        isRunning = true
        _connectionState.value = SteamConnectionState.CONNECTING
        Thread {
            client.connect()
            while (isRunning) {
                manager.runWaitCallbacks(1000L)
            }
            subscriptions.forEach { it.close() }
        }.start()

        return loginResult.await()
    }

    override suspend fun logout() {
        steamUserHandler?.logOff()
    }

    override fun observeCurrentUser(): StateFlow<SteamUser?> = _currentUser

    override fun observeDialogs(): StateFlow<List<SteamDialog>> = _dialogs

    override fun observeFriends(): StateFlow<List<SteamUser>> = _friends

    /**
     * In-memory, this-session-only history. Steam's classic FriendMsgCallback has no persisted
     * history API wired up yet (that's requestMessageHistory - a follow-up, see section 17/21 of
     * the master prompt: offline cache is explicitly MVP-2, not MVP-1).
     */
    override suspend fun getMessageHistory(friendSteamId64: Long): List<SteamMessage> =
        messagesByFriend[friendSteamId64].orEmpty()

    override fun observeMessages(friendSteamId64: Long): Flow<SteamMessage> =
        incomingMessages.filter { it.chatPartnerSteamId64 == friendSteamId64 }

    override suspend fun sendMessage(friendSteamId64: Long, text: String) {
        val friends = steamFriendsHandler ?: error("SteamService.sendMessage called before login")
        friends.sendChatMessage(SteamID(friendSteamId64), EChatEntryType.ChatMsg, text)
        val message = SteamMessage(
            id = nextMessageId.getAndIncrement(),
            chatPartnerSteamId64 = friendSteamId64,
            senderSteamId64 = _currentUser.value?.steamId64 ?: 0L,
            text = text,
            timestamp = System.currentTimeMillis(),
            isOutgoing = true,
        )
        messagesByFriend.getOrPut(friendSteamId64) { mutableListOf() }.add(message)
        rebuildDialogs()
    }

    override suspend fun markAsRead(friendSteamId64: Long) {
        unreadCounts[friendSteamId64] = 0
        rebuildDialogs()
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
}
