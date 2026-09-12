package org.steamchat.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import org.steamchat.domain.SteamDialog
import org.steamchat.domain.SteamStatus
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.R
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper

/**
 * A chat-list row: avatar (with a presence dot on it, not a separate element) - name - last
 * message preview - time - sent/unread state. Same visual language as Telegram's DialogCell
 * (BackupImageView/AvatarDrawable for avatars) built fresh against SteamDialog instead of forking
 * the real 6500-line DialogCell, whose delegate callbacks are typed directly with TLRPC.User/
 * TLRPC.Chat (see migration map). Lives on the SteamPalette color system, same as the rest of
 * SteamDialogsFragment - see SteamPalette.kt's class doc for why that matters.
 */
class SteamDialogCell(context: Context) : FrameLayout(context) {

    private val avatarImageView = BackupImageView(context)
    private val avatarDrawable = AvatarDrawable()
    private val onlineDot = View(context)
    private val nameView = TextView(context)
    private val gameView = TextView(context)
    private val messageView = TextView(context)
    private val timeView = TextView(context)
    private val checkView = ImageView(context)
    private val unreadCounter = TextView(context)

    init {
        // Ripple fires on ACTION_DOWN, not on release - press feedback should be instant (Apple
        // Fluid Interfaces §1: respond on pointer-down). Same helper real Telegram cells use.
        isClickable = true
        background = SteamPalette.rowSelector()

        val avatarBox = FrameLayout(context)
        avatarImageView.setRoundRadius(dp(AVATAR_SIZE / 2))
        avatarBox.addView(avatarImageView, LayoutHelper.createFrame(AVATAR_SIZE.toInt(), AVATAR_SIZE))

        onlineDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(SteamPalette.dialogOnlineDot)
            setStroke(dp(2f), SteamPalette.chatBackground)
        }
        avatarBox.addView(onlineDot, LayoutHelper.createFrame(14, 14, Gravity.BOTTOM or Gravity.END))
        addView(avatarBox, LayoutHelper.createFrame(AVATAR_SIZE.toInt(), AVATAR_SIZE, Gravity.START or Gravity.CENTER_VERTICAL, 12f, 0f, 0f, 0f))

        nameView.setTextColor(SteamPalette.headerTitle)
        nameView.textSize = 16f
        nameView.typeface = AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM)
        nameView.maxLines = 1
        nameView.ellipsize = TextUtils.TruncateAt.END

        // Same green as the avatar's own presence dot and the chat header's "играет в X" line
        // (SteamPalette.presenceInGame / visibleGame) - one meaning, one color, everywhere it shows.
        gameView.setTextColor(SteamPalette.presenceInGame)
        gameView.textSize = 13f
        gameView.maxLines = 1
        gameView.ellipsize = TextUtils.TruncateAt.END
        gameView.visibility = View.GONE

        messageView.setTextColor(SteamPalette.headerSubtitle)
        messageView.textSize = 14f
        messageView.maxLines = 1
        messageView.ellipsize = TextUtils.TruncateAt.END
        messageView.setPadding(0, dp(2f), 0, 0)

        val textColumn = LinearLayout(context)
        textColumn.orientation = LinearLayout.VERTICAL
        textColumn.addView(nameView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        textColumn.addView(gameView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        textColumn.addView(messageView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addView(
            textColumn,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.CENTER_VERTICAL, 12f + AVATAR_SIZE + 12f, 0f, 64f, 0f),
        )

        timeView.setTextColor(SteamPalette.headerSubtitle)
        timeView.textSize = 12f
        addView(timeView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP or Gravity.END, 0f, 15f, 12f, 0f))

        // Single check = "sent". Never a double check: JavaSteam's friend-message protocol carries
        // no "read" signal at all (see SteamMessageCell) - the last message's own bubble already
        // makes this call, this just mirrors it in the row.
        checkView.setImageResource(R.drawable.msg_check_s)
        checkView.setColorFilter(SteamPalette.headerSubtitle)
        addView(checkView, LayoutHelper.createFrame(14, 14f, Gravity.BOTTOM or Gravity.END, 0f, 0f, 12f, 15f))

        unreadCounter.setTextColor(Color.WHITE)
        unreadCounter.textSize = 12f
        unreadCounter.gravity = Gravity.CENTER
        unreadCounter.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(SteamPalette.accent)
        }
        addView(unreadCounter, LayoutHelper.createFrame(20, 20f, Gravity.BOTTOM or Gravity.END, 0f, 0f, 12f, 12f))

        // Hairline between rows - transparent at both edges, solid in the middle, instead of a flat
        // solid bar edge to edge.
        val divider = View(context)
        divider.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.TRANSPARENT, SteamPalette.separatorSurface, Color.TRANSPARENT),
        )
        addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1, Gravity.BOTTOM))
    }

    fun setDialog(dialog: SteamDialog, scope: CoroutineScope) {
        val friend = dialog.friend
        avatarDrawable.setInfo(friend.steamId64, friend.personaName, "")
        if (friend.avatarUrl != null) {
            avatarImageView.setImage(friend.avatarUrl, "50_50", avatarDrawable)
        } else {
            avatarImageView.setImageDrawable(avatarDrawable)
        }

        nameView.text = friend.personaName
        onlineDot.visibility = if (friend.status == SteamStatus.ONLINE) View.VISIBLE else View.GONE

        val gameLine = dialogGameLine(friend)
        gameView.text = gameLine.orEmpty()
        gameView.visibility = if (gameLine != null) View.VISIBLE else View.GONE

        val lastMessage = dialog.lastMessage
        messageView.setTextWithEmoticons(dialogPreviewText(lastMessage?.text), scope)
        timeView.text = lastMessage?.let { dialogTimeLabel(it.timestamp) }.orEmpty()

        // Bottom-right corner shows exactly one of: "we sent this" / "you haven't read this yet" /
        // nothing - never both, they describe the same last message from opposite directions.
        val outgoing = lastMessage?.isOutgoing == true
        checkView.visibility = if (outgoing) View.VISIBLE else View.GONE
        if (!outgoing && dialog.unreadCount > 0) {
            unreadCounter.visibility = View.VISIBLE
            unreadCounter.text = if (dialog.unreadCount > 99) "99+" else dialog.unreadCount.toString()
        } else {
            unreadCounter.visibility = View.GONE
        }
    }

    private companion object {
        const val AVATAR_SIZE = 56f
    }
}
