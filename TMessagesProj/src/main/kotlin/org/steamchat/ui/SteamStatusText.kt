package org.steamchat.ui

import org.steamchat.domain.SteamGamePresence
import org.steamchat.domain.SteamStatus
import org.steamchat.domain.SteamUser

/** Shared by SteamProfileHeaderView (big) and SteamChatHeaderView (compact) - one status line, one place to fix it. */
internal data class SteamStatusPresentation(val text: String, val online: Boolean)

/**
 * The one place that decides whether a game may be *shown*, so the offline rule can't drift
 * between the profile header, the chat header and the "Сейчас играет" card.
 *
 * mergePersona() deliberately keeps game=Playing(...) around through an offline-without-
 * GameDataBlob transition (see JavaSteamService) - that's correct internal state, not a stale
 * bug, but it means presentation has to gate on status itself, not just game's type. Otherwise
 * someone who went offline mid-session would keep showing as "Играет: X" until the next
 * GameDataBlob callback happened to arrive - possibly never, if they don't switch games again
 * before logging back in.
 */
internal fun visibleGame(user: SteamUser): SteamGamePresence.Playing? =
    (user.game as? SteamGamePresence.Playing)?.takeIf { user.status != SteamStatus.OFFLINE }

internal fun steamStatusPresentation(user: SteamUser): SteamStatusPresentation {
    val playing = visibleGame(user)
    val online = playing != null || user.status == SteamStatus.ONLINE
    val text = when {
        playing != null -> playing.name?.let { "Играет: $it" } ?: "Играет"
        user.status == SteamStatus.ONLINE -> "В сети"
        else -> "Не в сети"
    }
    return SteamStatusPresentation(text, online)
}

/**
 * Header line: presence first, game after it - "В сети · играет в Dota 2". A Playing state with no
 * resolved name still says "играет" rather than inventing a title, and Unknown never claims the
 * person isn't playing: it just shows presence, since "we haven't been told" is not "not playing".
 */
internal fun steamHeaderStatusText(user: SteamUser): String {
    val presence = if (user.status == SteamStatus.ONLINE) "В сети" else "Не в сети"
    val playing = visibleGame(user) ?: return presence
    val game = playing.name?.let { "играет в $it" } ?: "играет"
    return "$presence · $game"
}

/** Chat row's optional third line - same "is playing" gate as [visibleGame], but standing alone
 *  (no "В сети ·" prefix: the row's own second line already carries the last message, not
 *  presence text, so there's nothing to prefix it onto). */
internal fun dialogGameLine(user: SteamUser): String? {
    val playing = visibleGame(user) ?: return null
    return playing.name?.let { "Играет в $it" } ?: "Играет"
}

/** Rich Presence detail ("Ranked Match") under the status line, when Steam actually sent one. */
internal fun steamRichPresenceLine(user: SteamUser): String? {
    val playing = visibleGame(user) ?: return null
    return playing.richPresence["status"]?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Steam CDN header image for an app - same URL shape the web profile scraper builds from an
 * appid, stable for any real Steam app. Null for a mod/shortcut/P2P-file session, where
 * gamePresence() leaves appId null precisely because there's no real app behind it (see
 * JavaSteamService.mergePersona) - a made-up URL there would render someone else's game art.
 */
internal fun steamGameHeaderUrl(appId: Int?): String? =
    appId?.let { "https://cdn.akamai.steamstatic.com/steam/apps/$it/header.jpg" }
