package org.steamchat.domain

data class SteamUser(
    val steamId64: Long,
    val personaName: String,
    val avatarUrl: String?,
    val status: SteamStatus,
    val game: SteamGamePresence = SteamGamePresence.Unknown,
) {
    /** Compatibility view for callers that only need a display title. */
    val gameName: String?
        get() = (game as? SteamGamePresence.Playing)?.name
}

sealed interface SteamGamePresence {
    data object Unknown : SteamGamePresence
    data object NotPlaying : SteamGamePresence
    data class Playing(
        val appId: Int?,
        val gameId: Long?,
        val name: String?,
        val richPresence: Map<String, String> = emptyMap(),
    ) : SteamGamePresence
}
