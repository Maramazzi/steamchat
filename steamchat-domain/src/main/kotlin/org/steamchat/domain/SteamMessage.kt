package org.steamchat.domain

/**
 * Steam's classic friend-message protocol (JavaSteam's FriendMsgCallback) hands back only
 * sender + entry type + text - no server-assigned message id and no server timestamp. [id] and
 * [timestamp] are therefore always assigned locally on receipt/send, not sourced from Steam.
 */
data class SteamMessage(
    val id: Long,
    val chatPartnerSteamId64: Long,
    val senderSteamId64: Long,
    val text: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
)
