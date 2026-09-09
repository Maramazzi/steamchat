package org.steamchat.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.steamchat.domain.SteamGame
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper

/** One row in SteamGamesFragment's list: icon, name, total playtime. */
class SteamGameCell(context: Context) : LinearLayout(context) {

    private val iconView = BackupImageView(context)
    private val nameView = TextView(context)
    private val playtimeView = TextView(context)
    private val placeholder = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(6f).toFloat()
        setColor(Theme.getColor(Theme.key_windowBackgroundGray))
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        background = SteamPalette.rowSelector()
        setPadding(dp(16f), dp(10f), dp(16f), dp(10f))

        val iconSize = 40f
        iconView.setRoundRadius(dp(6f))
        addView(iconView, LayoutHelper.createLinear(iconSize.toInt(), iconSize.toInt()))

        val textColumn = LinearLayout(context)
        textColumn.orientation = VERTICAL

        nameView.textSize = 15f
        nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        nameView.maxLines = 1
        nameView.ellipsize = TextUtils.TruncateAt.END
        textColumn.addView(nameView)

        playtimeView.textSize = 13f
        playtimeView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
        playtimeView.setPadding(0, dp(2f), 0, 0)
        textColumn.addView(playtimeView)

        addView(textColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12, 0, 0, 0))
    }

    fun setGame(game: SteamGame) {
        nameView.text = game.name
        playtimeView.text = formatPlaytime(game.playtimeMinutesForever)
        if (game.iconUrl != null) {
            iconView.setImage("https://media.steampowered.com/steamcommunity/public/images/apps/${game.appId}/${game.iconUrl}.jpg", "40_40", placeholder)
        } else {
            iconView.setImageDrawable(placeholder)
        }
    }

    private fun formatPlaytime(minutes: Int): String {
        val hours = minutes / 60
        return when {
            hours >= 1 -> "$hours ч."
            minutes > 0 -> "$minutes мин."
            else -> "Не запускалась"
        }
    }
}
