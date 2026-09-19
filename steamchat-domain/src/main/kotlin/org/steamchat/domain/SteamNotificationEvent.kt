package org.steamchat.domain

/** A live incoming chat message, never history, local echo, or a system event. */
data class SteamNotificationEvent(
    val chatId: Long,
    val channelId: Long?,
    val senderName: String?,
    val text: String,
) {
    companion object {
        fun from(message: SteamMessage): SteamNotificationEvent? =
            if (!message.isOutgoing && message.text.isNotBlank())
                SteamNotificationEvent(message.chatPartnerSteamId64, null, null, message.text)
            else null

        fun from(message: SteamGroupMessage): SteamNotificationEvent? =
            if (!message.isOutgoing && !message.isSystem && !message.isDeleted && message.text.isNotBlank())
                SteamNotificationEvent(message.groupId, message.channelId, message.senderName, message.text)
            else null
    }
}
