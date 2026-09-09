package org.steamchat.steamkit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/**
 * Bio/status text, "currently playing", level XP progress, badges, and the equipped avatar frame
 * aren't in JavaSteam's client protocol at all (confirmed while investigating badges: Player
 * service only has getGameBadgeLevels(appid), one call per game, no "list my badges"; the raw
 * level lookup - see FriendsLevelsHandler - only has accountid+level fields, no XP;
 * PersonaStateCallback only ever carries a numeric appid, not a resolved name, and has nothing at
 * all for a Points Shop avatar frame). Steam's own public profile already has all of this
 * assembled server-side, via two different endpoints:
 *
 * - `?xml=1`: a legacy but still-live structured endpoint (confirmed against a real profile). Used
 *   for the bio/status text only. It also carries an `<inGameInfo>` block, which this file used to
 *   parse into a "currently playing" - removed on purpose: a snapshot taken once when a screen
 *   opens can never be invalidated, so it outlived the game session and fought with the live
 *   client-protocol presence (SteamUser.game). Realtime game state is PersonaStateCallback's job.
 * - the badges/ page: every badge (not just the main profile page's few-item preview strip), a
 *   `profile_xp_block` with the account's real total XP, XP remaining to the next level, and a
 *   ready-made progress percentage (`style="width: NN%"`), *and* (confirmed live: the page has a
 *   `profile_small_header_avatar` with the same `profile_avatar_frame` markup as the main profile
 *   page) the account's equipped avatar frame, if any - a Points Shop cosmetic most accounts don't
 *   have. One page covers all of it, so there's no separate fetch (and no separate cache entry)
 *   just for the frame.
 * - the plain profile page (`/profiles/{id}/`): a real screenshot count, in the exact same
 *   `count_link_label`/`profile_count_link_total` widget Steam uses for badges too - confirmed
 *   live against a real profile. Not on the badges/ page (checked), so this is a third fetch, not
 *   folded into the one above.
 *
 * Fetched concurrently (all three are steamcommunity.com requests either way) rather than one
 * after another, so opening a profile costs roughly one round trip, not three back-to-back ones.
 *
 * Plain java.net throughout (steamchat-steamkit is JVM-only, no Android/OkHttp on its compile
 * classpath) and targeted regex instead of a full HTML/XML parser dependency, since only a few
 * known, stable fields are needed. Only works for public profiles - a private one just yields
 * nulls/empty here, same as any other "couldn't fetch it" case.
 */
internal suspend fun fetchWebProfile(steamId64: Long): SteamWebProfile = coroutineScope {
    // Always fetched fresh, same as statusText - XP progress only means something if it's current,
    // so there's no "skip it, we already know the badges" shortcut here the way
    // JavaSteamService.badgesCache does for the badge icons themselves (that cache is a fallback
    // for when *this* fetch fails - see getProfileStats - not a reason to skip trying).
    val badgesDeferred = async { fetchBadgesPage(steamId64) }
    val xmlDeferred = async { fetchStatusText(steamId64) }
    val screenshotCountDeferred = async { fetchScreenshotCount(steamId64) }
    val badgesPage = badgesDeferred.await()
    val statusText = xmlDeferred.await()
    val screenshotCount = screenshotCountDeferred.await()
    SteamWebProfile(
        statusText = statusText,
        badgeCount = badgesPage.count,
        badgeIconUrls = badgesPage.iconUrls,
        xpToNextLevel = badgesPage.xpToNextLevel,
        xpProgressPercent = badgesPage.xpProgressPercent,
        avatarFrameUrl = badgesPage.avatarFrameUrl,
        screenshotCount = screenshotCount,
    )
}

private suspend fun fetchBadgesPage(steamId64: Long): BadgesPage = withContext(Dispatchers.IO) {
    val html = get("https://steamcommunity.com/profiles/$steamId64/badges/") ?: return@withContext BadgesPage()
    parseBadgesPage(html)
}

private suspend fun fetchStatusText(steamId64: Long): String? = withContext(Dispatchers.IO) {
    val xml = get("https://steamcommunity.com/profiles/$steamId64/?xml=1") ?: return@withContext null
    parseProfileXml(xml)
}

private suspend fun fetchScreenshotCount(steamId64: Long): Int? = withContext(Dispatchers.IO) {
    val html = get("https://steamcommunity.com/profiles/$steamId64/") ?: return@withContext null
    parseScreenshotCount(html)
}

// Confirmed live (HTTP 429 from steamcommunity.com) that repeated fetches for the same profile
// within a short window get rate-limited - not something retrying here fixes. getProfileStats()
// caches the badges result per steamId64 so normal use (open a profile, back out, reopen later)
// doesn't refetch at all, let alone fast enough to trip this.
//
// Redirects are followed manually (instanceFollowRedirects = false, then one explicit re-request
// at the Location header) rather than left to HttpURLConnection's own auto-follow. Confirmed live
// this matters, not a style choice: these paths 302 to the account's vanity-URL equivalent
// (`/profiles/{id}/...` -> `/id/{name}/...`) for any account that has one.
// HttpURLConnection's own built-in redirect handling threw FileNotFoundException following that
// exact redirect from `/profiles/{id}/` to `/id/{name}/`, even though fetching either
// URL directly (confirmed with curl) returns 200.
//
// internal, not private: reused by SteamNameHistory.kt too - same fetch semantics (timeouts,
// User-Agent, manual redirect-following), no reason to duplicate it for a second endpoint.
internal fun get(url: String, redirectsLeft: Int = 5): String? = try {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 5000
    connection.readTimeout = 5000
    connection.instanceFollowRedirects = false
    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
    val code = connection.responseCode
    if (code in 300..399 && redirectsLeft > 0) {
        val location = connection.getHeaderField("Location")
        connection.disconnect()
        if (location != null) get(location, redirectsLeft - 1) else null
    } else {
        connection.inputStream.bufferedReader().use { it.readText() }
    }
} catch (e: Exception) {
    null
}

internal fun parseBadgesPage(html: String): BadgesPage {
    val iconUrls = parseBadgeIconUrls(html)
    val (xpToNextLevel, xpProgressPercent) = parseXpProgress(html)
    return BadgesPage(
        count = iconUrls.size.takeIf { it > 0 },
        iconUrls = iconUrls,
        xpToNextLevel = xpToNextLevel,
        xpProgressPercent = xpProgressPercent,
        avatarFrameUrl = parseAvatarFrameUrl(html),
    )
}

/**
 * `profile_xp_block_remaining` is literally "478 XP to reach Level 58" and the bar's own inline
 * style is literally `width: 20%` - both read verbatim off a real captured page rather than
 * derived from a level-up cost formula, so there's no risk of showing invented numbers (section 1
 * of this project's rules: never fabricate data Steam doesn't actually return).
 */
internal fun parseXpProgress(html: String): Pair<Int?, Int?> {
    val xpToNextLevel = Regex("""profile_xp_block_remaining">\s*([\d,]+)\s*XP""").find(html)
        ?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
    val progressPercent = Regex("""profile_xp_block_remaining_bar_progress"[^>]*style="width:\s*(\d+)%""").find(html)
        ?.groupValues?.get(1)?.toIntOrNull()
    return xpToNextLevel to progressPercent
}

/**
 * Only the bio/status text. This endpoint also carries an `<inGameInfo>` block, and it used to be
 * parsed here into a "currently playing" for the profile card - deliberately removed: a snapshot
 * fetched once when a screen opens can never be invalidated, so it kept showing a game the person
 * had already quit while the live client-protocol presence (SteamUser.game, see mergePersona) had
 * correctly moved on. Realtime game state has exactly one source now, and it isn't this page.
 *
 * Parsed defensively (everything nullable) so an unexpected shape just yields no extras instead of
 * crashing.
 */
internal fun parseProfileXml(xml: String): String? =
    Regex("""<summary><!\[CDATA\[([\s\S]*?)]]></summary>""").find(xml)
        ?.groupValues?.get(1)
        ?.replace(Regex("""<img[^>]*alt="([^"]*)"[^>]*>"""), "$1")
        ?.replace(Regex("<[^>]+>"), "")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/**
 * Each badge is a `<div id="badge_..." class="badge_row ...">` block; the icon is lazy-loaded
 * (real URL in data-delayed-image, src is a 1x1 placeholder gif, not something a plain <img src>
 * regex would catch) - confirmed against a real captured badges/ page rather than guessed. Only
 * the icon URL is needed (the profile screen shows an icon strip, not a named list any more), so
 * this just collects data-delayed-image values in document order rather than parsing full rows.
 */
internal fun parseBadgeIconUrls(html: String): List<String> =
    Regex("""<div id="badge_[^"]*"[\s\S]*?data-delayed-image="([^"]+)"""").findAll(html)
        .map { it.groupValues[1] }
        .toList()

/**
 * `profile_avatar_frame` is one optional block - most accounts don't have a frame equipped.
 * `.find()` (first match), not `.findAll()`: on the main profile page it also reappears once per
 * comment-section avatar further down (other people's frames, not the profile owner's) - not
 * actually reachable from the badges/ page this is parsed from, which only has the one, but
 * `.find()` is the correct choice regardless since the page owner's own copy is always first in
 * document order.
 *
 * Takes the `<img src>` inside the frame's `<picture>`, which is always the real animated source -
 * confirmed live: downloaded it, `file` reported "Animated PNG image data... 24 frames, play
 * indefinitely", and (also confirmed live, by inspecting the actual fcTL chunks) it's not simple
 * full-frame swaps either, several frames are small partial-region updates blended over the
 * previous ones - so SteamProfileHeaderView needs a real APNG decoder for this, not something
 * that can be approximated by grabbing individual frames as standalone images. (There's a second,
 * intentionally-unused `<source media="(prefers-reduced-motion: reduce)">` with a genuinely
 * single-frame PNG of the same art - Steam's own accessibility fallback - not used here since the
 * point is to actually animate it.)
 */
internal fun parseAvatarFrameUrl(html: String): String? {
    val block = Regex("""profile_avatar_frame"[\s\S]*?</picture>""").find(html)?.value ?: return null
    return Regex("""<img src="([^"]+)"""").find(block)?.groupValues?.get(1)
}

/**
 * Steam reuses this exact `count_link_label`/`profile_count_link_total` pair of spans for several
 * profile stats (badges, screenshots, groups, ...) - confirmed live on a real profile's screenshot
 * count specifically, not assumed from the badges one. Scoped to the block starting at the given
 * label text so it can't match some *other* stat's count that happens to render first on the page.
 */
internal fun parseCountLink(html: String, label: String): Int? =
    Regex("""count_link_label">$label</span>[\s\S]*?profile_count_link_total">\s*(\d+)\s*</span>""")
        .find(html)?.groupValues?.get(1)?.toIntOrNull()

internal fun parseScreenshotCount(html: String): Int? = parseCountLink(html, "Screenshots")

internal data class BadgesPage(
    val count: Int? = null,
    val iconUrls: List<String> = emptyList(),
    val xpToNextLevel: Int? = null,
    val xpProgressPercent: Int? = null,
    val avatarFrameUrl: String? = null,
)

internal data class SteamWebProfile(
    val statusText: String? = null,
    val badgeCount: Int? = null,
    val badgeIconUrls: List<String> = emptyList(),
    val xpToNextLevel: Int? = null,
    val xpProgressPercent: Int? = null,
    val avatarFrameUrl: String? = null,
    val screenshotCount: Int? = null,
)
