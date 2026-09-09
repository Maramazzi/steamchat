package org.steamchat.steamkit

import org.steamchat.domain.SteamNameHistoryEntry

/**
 * The public-steamcommunity.com/CDN half of this module - a snapshot/fallback data source, never
 * realtime presence (see JavaSteamService/PersonaStateCallback for that; mergePersona() is the
 * only thing allowed to change SteamUser.game). Formalizes a seam that already existed as two
 * plain top-level functions (fetchWebProfile/fetchNameHistory in SteamWebProfile.kt and
 * SteamNameHistory.kt) into an interface JavaSteamService depends on by constructor injection,
 * instead of calling them directly - so a test can substitute a fake without touching any of the
 * CM-connection/callback code. internal: SteamWebProfile itself never crosses the SteamService
 * boundary into domain/UI.
 */
internal interface SteamWebDataSource {
    suspend fun fetchProfile(steamId64: Long): SteamWebProfile
    suspend fun fetchNameHistory(steamId64: Long): List<SteamNameHistoryEntry>
}

internal object RealSteamWebDataSource : SteamWebDataSource {
    override suspend fun fetchProfile(steamId64: Long): SteamWebProfile = fetchWebProfile(steamId64)

    // Package-qualified: an unqualified call here would resolve to this very member (same name),
    // not the top-level function, and recurse forever.
    override suspend fun fetchNameHistory(steamId64: Long): List<SteamNameHistoryEntry> =
        org.steamchat.steamkit.fetchNameHistory(steamId64)
}
