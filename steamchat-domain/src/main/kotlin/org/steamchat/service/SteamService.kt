package org.steamchat.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.steamchat.domain.SteamDialog
import org.steamchat.domain.SteamEmoticon
import org.steamchat.domain.SteamSticker
import org.steamchat.domain.SteamChatGroup
import org.steamchat.domain.SteamGame
import org.steamchat.domain.SteamGroupMessage
import org.steamchat.domain.SteamIncomingVoiceCall
import org.steamchat.domain.SteamMessage
import org.steamchat.domain.SteamNameHistoryEntry
import org.steamchat.domain.SteamNotificationEvent
import org.steamchat.domain.SteamProfileStats
import org.steamchat.domain.SteamUser

/**
 * UI-facing abstraction over the Steam backend (section 7/9 of the master prompt). No SteamKit2/
 * JavaSteam/protobuf/callback types may cross this boundary - only domain models from
 * [org.steamchat.domain] and plain Kotlin types.
 */
interface SteamService {

    /** Starts the diagnostic WebRTC server connection. */
    suspend fun initiateWebRtcProbe(offerJson: String, browserName: String, browserVersion: String): String
    /** Sends a real one-to-one call invitation over the active diagnostic connection. */
    suspend fun requestOneOnOneWebRtcProbe(partnerSteamId64: Long): Long
    /** Joins an already accepted incoming one-to-one call over the active WebRTC connection. */
    suspend fun joinOneOnOneWebRtcProbe(voiceChatId: Long, partnerSteamId64: Long)
    /** Confirms that a server-pushed WebRTC offer was applied locally. */
    suspend fun acknowledgeWebRtcProbeUpdate(version: Long)
    fun observeWebRtcProbeEvents(): Flow<org.steamchat.domain.SteamWebRtcProbeEvent>
    fun cancelWebRtcProbe()

    val connectionState: StateFlow<SteamConnectionState>

    val incomingVoiceCall: StateFlow<SteamIncomingVoiceCall?>
    suspend fun answerIncomingVoiceCall(call: SteamIncomingVoiceCall, accepted: Boolean): Boolean

    suspend fun login(username: String, password: String, guardHandler: SteamGuardHandler): SteamLoginResult

    /** Tries to resume a previously saved session (see [SessionStore]) with no user interaction. */
    suspend fun resumeSession(): SteamLoginResult

    suspend fun logout()

    fun observeCurrentUser(): StateFlow<SteamUser?>

    fun observeDialogs(): StateFlow<List<SteamDialog>>

    fun observeFriends(): StateFlow<List<SteamUser>>

    /** Existing Steam Chat groups and their current channel summaries. */
    fun observeChatGroups(): StateFlow<List<SteamChatGroup>>

    /**
     * Fetched fresh each call, for any account (self or friend) - see [SteamProfileStats].
     * groupsCount is only ever populated when steamId64 is the logged-in account.
     */
    suspend fun getProfileStats(steamId64: Long): SteamProfileStats

    /** Owned-games list for any account; empty if the account's games are private or unset. */
    suspend fun getOwnedGames(steamId64: Long): List<SteamGame>

    /** Past personas, most recent first, same as Steam's own name-history popup. Fetched lazily (only when the user opens it), not part of getProfileStats. */
    suspend fun getNameHistory(steamId64: Long): List<SteamNameHistoryEntry>

    /** This account's own sendable emoticons (CPlayer_GetEmoticonList_Request) - for a picker, not text rendering (see SteamEmoticons.kt in the UI module for that). Empty if unavailable, never fabricated. */
    suspend fun getAvailableEmoticons(): List<SteamEmoticon>

    /** This account's own real stickers (legacy ClientGetEmoticonList/ClientEmoticonList - see StickerListHandler). Sent as chat text "/Sticker {name}", not a protocol field - see SteamSticker. */
    suspend fun getAvailableStickers(): List<SteamSticker>

    suspend fun getMessageHistory(friendSteamId64: Long): List<SteamMessage>

    fun observeMessages(friendSteamId64: Long): Flow<SteamMessage>

    fun observeMessageHistory(friendSteamId64: Long): StateFlow<List<SteamMessage>>

    /** Live incoming messages across direct chats and group channels; excludes history and local echoes. */
    fun observeNotificationEvents(): Flow<SteamNotificationEvent>

    suspend fun sendMessage(friendSteamId64: Long, text: String)

    /** Uploads an MP4 attachment; Steam publishes it to the chat when the upload is committed. */
    suspend fun sendMedia(friendSteamId64: Long, filePath: String)

    suspend fun markAsRead(friendSteamId64: Long)

    /** Live, atomically merged history for one Steam group channel. */
    fun observeGroupMessages(groupId: Long, channelId: Long): StateFlow<List<SteamGroupMessage>>

    /** Loads the newest page first, then successively older pages. Returns whether more remain. */
    suspend fun loadOlderGroupMessages(groupId: Long, channelId: Long): Boolean

    suspend fun sendGroupMessage(groupId: Long, channelId: Long, text: String)

    /** Group-channel counterpart of [sendMedia]. */
    suspend fun sendGroupMedia(groupId: Long, channelId: Long, filePath: String)

    suspend fun markGroupChannelRead(groupId: Long, channelId: Long)

    /**
     * Marks this account present in the channel's voice chat (ChatRoom.joinVoiceChat) - real Steam
     * protocol call, but presence/signalling only: there is no audio transport anywhere in
     * JavaSteam for this (checked; see CLAUDE.md). Returns whether the server accepted it.
     */
    suspend fun joinChannelVoice(groupId: Long, channelId: Long): Boolean

    suspend fun leaveChannelVoice(groupId: Long, channelId: Long)

    /** Best-effort display info (name/avatar) for arbitrary Steam IDs - e.g. voice-channel members, who aren't necessarily friends. Silently drops any id that can't be resolved in time rather than guessing a name. */
    suspend fun resolveUsers(steamId64s: List<Long>): List<SteamUser>

    /**
     * EXPERIMENTAL - sends the real Steam "may I call you" handshake (CMsgClientVoiceCallPreAuthorize)
     * to a friend. It reaches their actual Steam client, but there is no WebRTC/SDP/ICE negotiation
     * message anywhere in JavaSteam (checked; see CLAUDE.md), so this can never become a connected,
     * audible call by itself - it is a live probe of what Steam's servers actually do with this
     * signal, not a finished calling feature. Returns Steam's own EResult name, or null on
     * timeout/no session.
     */
    suspend fun sendCallSignal(friendSteamId64: Long, hangup: Boolean): String?
}

enum class SteamConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

sealed interface SteamLoginResult {
    data object Success : SteamLoginResult
    data class Failure(val reason: String) : SteamLoginResult
}

/**
 * UI-side hook for Steam Guard prompts. Shape mirrors JavaSteam's IAuthenticator (confirmed
 * working end-to-end in /spike) without leaking that type into the domain layer.
 */
interface SteamGuardHandler {
    suspend fun provideDeviceCode(previousWasIncorrect: Boolean): String
    suspend fun provideEmailCode(email: String?, previousWasIncorrect: Boolean): String
    suspend fun confirmViaMobileApp(): Boolean
}
