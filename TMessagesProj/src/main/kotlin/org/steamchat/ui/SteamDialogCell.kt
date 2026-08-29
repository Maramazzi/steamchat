package org.steamchat.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.steamchat.domain.SteamDialog
import org.steamchat.domain.SteamStatus
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Same visual language as Telegram's DialogCell (Theme color keys, BackupImageView/AvatarDrawable
 * for avatars) built fresh against SteamDialog instead of forking the real 6500-line DialogCell,
 * whose delegate callbacks are typed directly with TLRPC.User/TLRPC.Chat (see migration map).
 */
class SteamDialogCell(context: Context) : FrameLayout(context) {

    private val avatarImageView = BackupImageView(context)
    private val avatarDrawable = AvatarDrawable()
    private val statusDot = View(context)
    private val nameView = TextView(context)
    private val messageView = TextView(context)
    private val timeView = TextView(context)
    private val unreadCounter = TextView(context)

    init {
        val avatarSize = 52f

        avatarImageView.setRoundRadius(dp(avatarSize / 2))
        addView(avatarImageView, LayoutHelper.createFrame(avatarSize.toInt(), avatarSize, Gravity.START or Gravity.CENTER_VERTICAL, 12f, 0f, 0f, 0f))

        val nameRow = LinearLayout(context)
        nameRow.orientation = LinearLayout.HORIZONTAL
        nameRow.gravity = Gravity.CENTER_VERTICAL

        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Theme.getColor(Theme.key_chats_onlineCircle))
        }
        nameRow.addView(statusDot, LinearLayout.LayoutParams(dp(8f), dp(8f)).apply { rightMargin = dp(6f) })

        nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        nameView.textSize = 16f
        nameView.typeface = Typeface.DEFAULT_BOLD
        nameView.maxLines = 1
        nameView.ellipsize = TextUtils.TruncateAt.END
        nameRow.addView(nameView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        messageView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
        messageView.textSize = 14f
        messageView.maxLines = 1
        messageView.ellipsize = TextUtils.TruncateAt.END
        messageView.setPadding(0, dp(4f), 0, 0)

        val textColumn = LinearLayout(context)
        textColumn.orientation = LinearLayout.VERTICAL
        textColumn.addView(nameRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        textColumn.addView(messageView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addView(
            textColumn,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.CENTER_VERTICAL, 12f + avatarSize + 12f, 0f, 60f, 0f),
        )

        timeView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
        timeView.textSize = 12f
        addView(timeView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP or Gravity.END, 0f, 14f, 12f, 0f))

        unreadCounter.setTextColor(Color.WHITE)
        unreadCounter.textSize = 12f
        unreadCounter.gravity = Gravity.CENTER
        unreadCounter.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Theme.getColor(Theme.key_chats_unreadCounter))
        }
        addView(unreadCounter, LayoutHelper.createFrame(20, 20f, Gravity.BOTTOM or Gravity.END, 0f, 0f, 12f, 14f))
    }

    fun setDialog(dialog: SteamDialog) {
        val friend = dialog.friend
        avatarDrawable.setInfo(friend.steamId64, friend.personaName, "")
        if (friend.avatarUrl != null) {
            avatarImageView.setImage(friend.avatarUrl, "50_50", avatarDrawable)
        } else {
            avatarImageView.setImageDrawable(avatarDrawable)
        }

        nameView.text = friend.personaName
        messageView.text = dialog.lastMessage?.text.orEmpty()
        statusDot.visibility = if (friend.status == SteamStatus.ONLINE) View.VISIBLE else View.GONE
        timeView.text = dialog.lastMessage?.let { timeFormat.format(Date(it.timestamp)) }.orEmpty()

        if (dialog.unreadCount > 0) {
            unreadCounter.visibility = View.VISIBLE
            unreadCounter.text = dialog.unreadCount.toString()
        } else {
            unreadCounter.visibility = View.GONE
        }
    }

    private companion object {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    }
}
