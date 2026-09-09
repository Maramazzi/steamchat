package org.steamchat.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import org.steamchat.domain.SteamChatGroup
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper

/** Same row grid as [SteamDialogCell] - a group is a dialog too, so it must line up on the same
 *  avatar size and text baseline, not read as a visually distinct row type. */
internal class SteamGroupDialogCell(context: Context) : FrameLayout(context) {

    private val avatar = BackupImageView(context)
    private val avatarDrawable = AvatarDrawable()
    private val name = TextView(context)
    private val preview = TextView(context)
    private val time = TextView(context)
    private val unreadDot = View(context)

    init {
        isClickable = true
        background = SteamPalette.rowSelector()

        avatar.setRoundRadius(dp(AVATAR_SIZE / 2))
        addView(avatar, LayoutHelper.createFrame(AVATAR_SIZE.toInt(), AVATAR_SIZE, Gravity.START or Gravity.CENTER_VERTICAL, 12f, 0f, 0f, 0f))

        name.setTextColor(SteamPalette.headerTitle)
        name.textSize = 16f
        name.typeface = AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END

        preview.setTextColor(SteamPalette.headerSubtitle)
        preview.textSize = 14f
        preview.maxLines = 1
        preview.ellipsize = TextUtils.TruncateAt.END
        preview.setPadding(0, dp(3f), 0, 0)

        val textColumn = LinearLayout(context)
        textColumn.orientation = LinearLayout.VERTICAL
        textColumn.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        textColumn.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(
            textColumn,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.CENTER_VERTICAL, 12f + AVATAR_SIZE + 12f, 0f, 64f, 0f),
        )

        time.setTextColor(SteamPalette.headerSubtitle)
        time.textSize = 12f
        addView(time, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP or Gravity.END, 0f, 15f, 12f, 0f))

        // Only a boolean unread signal exists per group (SteamChatGroup.hasUnread, no count) - a
        // dot rather than a number badge, same rule the row applies for any boolean-only unread.
        unreadDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(SteamPalette.accent)
        }
        addView(unreadDot, LayoutHelper.createFrame(10, 10f, Gravity.BOTTOM or Gravity.END, 0f, 0f, 17f, 17f))

        val divider = View(context)
        divider.setBackgroundColor(SteamPalette.separatorSurface)
        addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1, Gravity.BOTTOM))
    }

    fun setGroup(group: SteamChatGroup, scope: CoroutineScope) {
        val groupName = group.name.ifBlank { "Группа Steam" }
        avatarDrawable.setInfo(group.id, groupName, "")
        if (group.avatarUrl != null) avatar.setImage(group.avatarUrl, "50_50", avatarDrawable)
        else avatar.setImageDrawable(avatarDrawable)

        val default = group.channels.firstOrNull { it.id == group.defaultChannelId }
            ?: group.channels.firstOrNull()
        val latest = group.channels.maxByOrNull { it.lastMessageAt ?: 0L } ?: default
        name.text = groupName
        val previewText = latest?.lastMessage?.takeIf { it.isNotBlank() }
            ?: default?.name?.takeIf { it.isNotBlank() }?.let { "# $it" }
            ?: "Нет доступных каналов"
        preview.setTextWithEmoticons(dialogPreviewText(previewText), scope)
        time.text = latest?.lastMessageAt?.let { dialogTimeLabel(it) }.orEmpty()
        unreadDot.visibility = if (group.hasUnread) View.VISIBLE else View.GONE
    }

    private companion object {
        const val AVATAR_SIZE = 56f
    }
}
