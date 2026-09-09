package org.steamchat.domain

/**
 * An emoticon this account can actually send - CPlayer_GetEmoticonList_Request/Response (checked
 * against the javasteam jar), not the same thing as SteamEmoticons.kt's *rendering* of `:name:`
 * shortcodes already present in someone's text: this is the account's own unlockable/owned set,
 * for a picker UI. [useCount] is how many times this account has actually sent it - used to sort
 * a "most used first" picker, the same way a real emoji keyboard would.
 */
data class SteamEmoticon(
    val name: String,
    val useCount: Int,
)
