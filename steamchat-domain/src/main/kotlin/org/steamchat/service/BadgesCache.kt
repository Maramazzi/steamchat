package org.steamchat.service

data class CachedBadges(
    val count: Int? = null,
    val iconUrls: List<String> = emptyList(),
    val avatarFrameUrl: String? = null,
    val statusText: String? = null,
)

/**
 * Persists scraped badge counts/icons/avatar-frame-url/bio-text across app restarts, keyed by
 * steamId64 - the source (steamcommunity.com's HTML/XML pages) rate-limits repeated fetches of the
 * same profile (confirmed live: HTTP 429 after heavy testing against one account), and an
 * in-memory-only cache loses everything on every restart, right when testing/normal use would
 * otherwise retry and risk tripping the limit again. Once a piece of data is read successfully,
 * there's no reason to ever ask again - a badge count, an equipped avatar frame, or a bio don't
 * change often enough to be worth refreshing.
 *
 * count/iconUrls/avatarFrameUrl come from the badges/ page fetch; statusText comes from a
 * *different*, independent fetch (?xml=1 - see SteamWebProfile) that can succeed or fail on its
 * own. Callers (JavaSteamService.getProfileStats) merge "this fetch's fresh value, else whatever
 * was cached before" per field *before* calling put() - by the time put() is called, every field
 * already holds the best currently-known value, so put() just writes it verbatim, never
 * conditionally. Getting this merge wrong (e.g. gating the whole entry on one fetch's success)
 * would let a failed xml fetch silently blank out a perfectly good cached bio, or vice versa -
 * this is why the two field groups are tracked independently rather than as one all-or-nothing
 * cache hit/miss.
 */
interface BadgesCache {
    fun get(steamId64: Long): CachedBadges?
    fun put(steamId64: Long, badges: CachedBadges)
}

/** No-op store for backends (e.g. FakeSteamService) that don't need persistence. */
object NoOpBadgesCache : BadgesCache {
    override fun get(steamId64: Long): CachedBadges? = null
    override fun put(steamId64: Long, badges: CachedBadges) {}
}
