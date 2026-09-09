package org.steamchat.domain

/**
 * Extra profile-screen stats fetched on demand when a profile opens - level works for any
 * account, groupsCount only for the logged-in one (Steam's client protocol has no call for
 * another account's group list). Null means "not fetched yet or unavailable", not zero.
 *
 * statusText/badgeCount/badgeIconUrls/xpToNextLevel/xpProgressPercent come from the public
 * steamcommunity.com pages, not the Steam client protocol (JavaSteam's level lookup only ever
 * returns the level number, and there's no badge-list or bio-text call at all) - so they're only
 * as reliable as those pages are reachable and public. null/empty just means "couldn't scrape it"
 * (private profile, network hiccup, markup change), not "definitely has none".
 *
 * xpToNextLevel/xpProgressPercent are read verbatim off Steam's own badges page (which computes
 * and prints them itself) - never derived from a level-up cost formula on our side, so there's no
 * risk of these being invented numbers.
 *
 * avatarFrameUrl is a Points Shop cosmetic (an animated avatar frame) equipped on the profile -
 * most accounts don't have one. Comes off the same badges page fetch as badgeCount/badgeIconUrls,
 * so it rides along with that same persistent fallback cache (see JavaSteamService.badgesCache) -
 * not a separate cache entry. statusText (a different, independent fetch - see SteamWebProfile)
 * has its own fallback slot in that same cache: reported live that the bio card went missing "not
 * always", traced to that fetch having no fallback at all before, unlike badges.
 *
 * There is deliberately no "currently playing" field here. It used to exist (scraped from ?xml=1)
 * and fed the profile's "Сейчас играет" card, which made the web snapshot a second source of truth
 * for game state next to SteamUser.game: the snapshot is taken once when the screen opens and can
 * never be invalidated, so quitting a game cleared the header (client protocol) while this card
 * kept showing the old game. Realtime game presence comes from PersonaStateCallback/GameDataBlob
 * only - see SteamGamePresence.
 *
 * screenshotCount is real (Steam's own `count_link_label`/`profile_count_link_total` widget on the
 * plain profile page, same one badges/groups/etc. use), not a placeholder - shown even though the
 * screenshots *list* itself isn't built yet, same as gamesCount existed before the games screen
 * did. Fetch-fresh-or-nothing, no fallback cache yet - add one later if it turns out flaky the same
 * way statusText did, no need to build it speculatively now.
 */
data class SteamProfileStats(
    val level: Int? = null,
    val groupsCount: Int? = null,
    val statusText: String? = null,
    val badgeCount: Int? = null,
    val badgeIconUrls: List<String> = emptyList(),
    val xpToNextLevel: Int? = null,
    val xpProgressPercent: Int? = null,
    val avatarFrameUrl: String? = null,
    val screenshotCount: Int? = null,
)
