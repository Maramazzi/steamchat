package org.steamchat.domain

/** changedAt is Steam's own pre-formatted string (e.g. "17 Dec, 2025 @ 2:30am") - reused verbatim rather than re-parsed, same reasoning as the rest of this project's "don't invent data" rule. */
data class SteamNameHistoryEntry(val name: String, val changedAt: String)
