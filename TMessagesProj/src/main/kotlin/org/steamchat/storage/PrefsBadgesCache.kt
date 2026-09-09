package org.steamchat.storage

import android.content.Context
import org.steamchat.service.BadgesCache
import org.steamchat.service.CachedBadges

/** Plain (unencrypted) SharedPreferences - badge counts/CDN icon URLs/bio text aren't sensitive. */
class PrefsBadgesCache(context: Context) : BadgesCache {

    private val prefs = context.getSharedPreferences("steamchat_badges", Context.MODE_PRIVATE)

    override fun get(steamId64: Long): CachedBadges? {
        val count = prefs.getInt(countKey(steamId64), -1).takeIf { it >= 0 }
        val icons = prefs.getString(iconsKey(steamId64), null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val avatarFrameUrl = prefs.getString(frameKey(steamId64), null)
        val statusText = prefs.getString(statusKey(steamId64), null)
        if (count == null && icons.isEmpty() && avatarFrameUrl == null && statusText == null) return null
        return CachedBadges(count, icons, avatarFrameUrl, statusText)
    }

    // Values here are already the caller's merged "fresh, else previously cached" result (see
    // JavaSteamService.getProfileStats) - written verbatim, no conditional-skip logic needed here.
    override fun put(steamId64: Long, badges: CachedBadges) {
        val editor = prefs.edit()
        val count = badges.count
        if (count != null) editor.putInt(countKey(steamId64), count) else editor.remove(countKey(steamId64))
        editor.putString(iconsKey(steamId64), badges.iconUrls.joinToString(SEPARATOR))
        if (badges.avatarFrameUrl != null) editor.putString(frameKey(steamId64), badges.avatarFrameUrl) else editor.remove(frameKey(steamId64))
        if (badges.statusText != null) editor.putString(statusKey(steamId64), badges.statusText) else editor.remove(statusKey(steamId64))
        editor.apply()
    }

    private fun countKey(steamId64: Long) = "${steamId64}_count"
    private fun iconsKey(steamId64: Long) = "${steamId64}_icons"
    private fun frameKey(steamId64: Long) = "${steamId64}_frame"
    private fun statusKey(steamId64: Long) = "${steamId64}_status"

    private companion object {
        const val SEPARATOR = "\n"
    }
}
