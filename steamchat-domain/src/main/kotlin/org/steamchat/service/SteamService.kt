package org.steamchat.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.steamchat.domain.SteamDialog
import org.steamchat.domain.SteamMessage
import org.steamchat.domain.SteamUser

/**
 * UI-facing abstraction over the Steam backend (section 7/9 of the master prompt). No SteamKit2/
 * JavaSteam/protobuf/callback types may cross this boundary - only domain models from
 * [org.steamchat.domain] and plain Kotlin types.
 */
interface SteamService {

    val connectionState: StateFlow<SteamConnectionState>

    suspend fun login(username: String, password: String, guardHandler: SteamGuardHandler): SteamLoginResult

    /** Tries to resume a previously saved session (see [SessionStore]) with no user interaction. */
    suspend fun resumeSession(): SteamLoginResult

    suspend fun logout()

    fun observeCurrentUser(): StateFlow<SteamUser?>

    fun observeDialogs(): StateFlow<List<SteamDialog>>

    fun observeFriends(): StateFlow<List<SteamUser>>

    suspend fun getMessageHistory(friendSteamId64: Long): List<SteamMessage>

    fun observeMessages(friendSteamId64: Long): Flow<SteamMessage>

    suspend fun sendMessage(friendSteamId64: Long, text: String)

    suspend fun markAsRead(friendSteamId64: Long)
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
