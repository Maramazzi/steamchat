package org.steamchat.ui

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.steamchat.domain.SteamChatChannel
import org.steamchat.domain.SteamChatGroup
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView

internal class SteamGroupChatHeaderView(context: Context) : LinearLayout(context) {

    private val avatar = BackupImageView(context)
    private val avatarDrawable = AvatarDrawable()
    private val title = TextView(context)
    private val subtitle = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true

        avatar.setRoundRadius(dp(18f))
        addView(avatar, LayoutParams(dp(36f), dp(36f)).apply { rightMargin = dp(10f) })

        title.setTextColor(SteamPalette.headerTitle)
        title.textSize = 16f
        title.typeface = Typeface.DEFAULT_BOLD
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END

        subtitle.setTextColor(SteamPalette.headerSubtitle)
        subtitle.textSize = 12f
        subtitle.maxLines = 1
        subtitle.ellipsize = TextUtils.TruncateAt.END

        val labels = LinearLayout(context)
        labels.orientation = VERTICAL
        labels.gravity = Gravity.CENTER_VERTICAL
        labels.addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        labels.addView(subtitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(labels, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    fun render(group: SteamChatGroup, channel: SteamChatChannel) {
        val groupName = group.name.ifBlank { "Группа Steam" }
        avatarDrawable.setInfo(group.id, groupName, "")
        if (group.avatarUrl != null) {
            avatar.setImage(group.avatarUrl, "50_50", avatarDrawable)
        } else {
            avatar.setImageDrawable(avatarDrawable)
        }
        title.text = groupName
        val channelName = channel.name.ifBlank { "Канал" }
        subtitle.text = if (channel.voiceAllowed) {
            "# $channelName · в голосе ${channel.voiceMemberCount}"
        } else {
            "# $channelName"
        }
        contentDescription = "$groupName, канал $channelName. Вернуться к каналам группы"
    }
}
