package org.steamchat.steamkit

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.steamchat.domain.SteamNameHistoryEntry

/**
 * Not in JavaSteam's client protocol at all - this is the same JSON endpoint the real
 * steamcommunity.com profile page's own alias-history popup calls (`namehistory_link` /
 * `ShowAliasPopup` in the page's own markup/JS), confirmed live: fetched it directly for a real
 * account, got back a plain JSON array, most-recent-first, no auth needed:
 * `[{"newname":"ExampleUser","timechanged":"17 Dec, 2025 @ 2:30am"}, ...]`.
 */
private const val ALIASES_URL = "https://steamcommunity.com/profiles/%d/ajaxaliases/"

internal suspend fun fetchNameHistory(steamId64: Long): List<SteamNameHistoryEntry> = withContext(Dispatchers.IO) {
    val json = get(ALIASES_URL.format(steamId64)) ?: return@withContext emptyList()
    parseNameHistory(json)
}

internal fun parseNameHistory(json: String): List<SteamNameHistoryEntry> = try {
    val entries: List<AliasEntry> = Gson().fromJson(json, object : TypeToken<List<AliasEntry>>() {}.type)
    entries.map { SteamNameHistoryEntry(it.newname, it.timechanged) }
} catch (e: Exception) {
    emptyList()
}

/** Field names match the JSON keys exactly - Gson maps them by reflection, no @SerializedName needed. */
private data class AliasEntry(val newname: String, val timechanged: String)
