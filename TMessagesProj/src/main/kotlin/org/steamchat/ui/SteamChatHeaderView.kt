package org.steamchat.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.steamchat.domain.SteamUser
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper

/**
 * Compact avatar + name + presence line for the chat ActionBar:
 *
 *     ExampleUser
 *     ● В сети · играет в Dota 2
 *
 * Uses the 52dp left margin / WRAP_CONTENT width / MATCH_PARENT height convention real Telegram's
 * ChatActivity uses for its own avatarContainer (confirmed in ChatActivity.java), but is not a fork
 * of ChatAvatarContainer - that class is typed directly on TLRPC.User/TLRPC.Chat. Built from the
 * same primitives already proven in SteamDialogCell.
 */
class SteamChatHeaderView(context: Context) : LinearLayout(context) {

    private val avatarImageView = BackupImageView(context)
    private val avatarDrawable = AvatarDrawable()
    private val statusDot = View(context)
    private val nameView = TextView(context)
    private val statusView = TextView(context)
    private val richPresenceView = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        background = SteamPalette.rowSelector()

        avatarImageView.setRoundRadius(dp(AVATAR_SIZE_DP / 2))
        addView(avatarImageView, LayoutHelper.createLinear(AVATAR_SIZE_DP.toInt(), AVATAR_SIZE_DP.toInt()))

        val column = LinearLayout(context)
        column.orientation = VERTICAL
        column.gravity = Gravity.CENTER_VERTICAL

        nameView.textSize = 16f
        nameView.typeface = Typeface.DEFAULT_BOLD
        nameView.setTextColor(SteamPalette.headerTitle)
        nameView.maxLines = 1
        nameView.ellipsize = TextUtils.TruncateAt.END
        column.addView(nameView)

        // Presence dot sits inline with the status text (not on the avatar) so the line reads as
        // one statement: "● В сети · играет в Dota 2".
        val statusRow = LinearLayout(context)
        statusRow.orientation = HORIZONTAL
        statusRow.gravity = Gravity.CENTER_VERTICAL

        statusDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        statusRow.addView(statusDot, LayoutHelper.createLinear(7, 7, Gravity.CENTER_VERTICAL, 0, 0, 6, 0))

        statusView.textSize = 12f
        statusView.setTextColor(SteamPalette.headerSubtitle)
        statusView.maxLines = 1
        statusView.ellipsize = TextUtils.TruncateAt.END
        statusRow.addView(statusView)
        column.addView(statusRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 1f, 0f, 0f))

        // Second line only when Steam actually sent Rich Presence - keeps the header two lines the
        // rest of the time instead of reserving space for something usually absent.
        richPresenceView.textSize = 11f
        richPresenceView.setTextColor(SteamPalette.headerSubtitle)
        richPresenceView.maxLines = 1
        richPresenceView.ellipsize = TextUtils.TruncateAt.END
        richPresenceView.alpha = 0.8f
        richPresenceView.visibility = View.GONE
        column.addView(richPresenceView)

        // Takes the whole row left over by the avatar (weight 1) rather than WRAP_CONTENT: the
        // ActionBar measures this view before render() supplies a name, so a wrap-content column
        // would lock to the width of the status line and ellipsise the name to "Mara...".
        addView(column, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 10, 0, 0, 0))
    }

    fun render(user: SteamUser) {
        avatarDrawable.setInfo(user.steamId64, user.personaName, "")
        if (user.avatarUrl != null) {
            avatarImageView.setImage(user.avatarUrl, "50_50", avatarDrawable)
        } else {
            avatarImageView.setImageDrawable(avatarDrawable)
        }
        nameView.text = user.personaName
        statusView.text = steamHeaderStatusText(user)

        // Green in a game, Steam's light blue when merely online, grey offline - the same meaning
        // the dot carries in the real Steam client.
        (statusDot.background as GradientDrawable).setColor(presenceColor(user))

        val rich = steamRichPresenceLine(user)
        richPresenceView.text = rich.orEmpty()
        richPresenceView.visibility = if (rich != null) View.VISIBLE else View.GONE
    }

    private companion object {
        const val AVATAR_SIZE_DP = 40f
    }
}
