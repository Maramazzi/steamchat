package org.steamchat.ui

import org.steamchat.domain.SteamMessageContent
import org.steamchat.domain.parseSteamMessageContent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Shared by [SteamDialogCell]/[SteamGroupDialogCell] - a dialog row's timestamp is time-of-day
 *  today, "Вчера" yesterday, else a short date. Same three-way split as the in-chat date chip
 *  (SteamChatFragment.dateChipLabel), just collapsed onto one line instead of a separator row. */
internal fun dialogTimeLabel(timestamp: Long): String {
    val target = Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay(target, today) -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        sameDay(target, yesterday) -> "Вчера"
        else -> SimpleDateFormat("d MMM", Locale("ru")).format(Date(timestamp))
    }
}

/** A dialog row's second line: real text as-is (shortcodes render via setTextWithEmoticons), or an
 *  honest content label for anything that isn't text - never the raw image URL. */
internal fun dialogPreviewText(text: String?): String {
    if (text.isNullOrEmpty()) return ""
    return when (parseSteamMessageContent(text)) {
        is SteamMessageContent.Image -> "Фото"
        else -> text
    }
}
