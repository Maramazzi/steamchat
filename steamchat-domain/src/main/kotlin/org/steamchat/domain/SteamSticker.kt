package org.steamchat.domain

/**
 * A chat sticker this account owns - a Points Shop cosmetic distinct from emoticons (bigger, its
 * own asset), but with no dedicated send API: confirmed live (ValveSoftware/steam-for-linux issue
 * #8740, a Steam client bug report whose own description states normal behaviour) that a sticker
 * is sent as the literal chat text `/Sticker {name}`, resolved into the real image by whichever
 * client receives it - the same "plain text special-cased by the renderer" shape as emoticons,
 * not a protocol field (checked: no attachment/sticker field on any send-message request).
 */
data class SteamSticker(
    val name: String,
    val useCount: Int,
)
