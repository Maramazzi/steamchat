package org.steamchat.ui

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

internal enum class SteamNavTab(val icon: Int, val label: String) {
    CHATS(org.telegram.messenger.R.drawable.msg_discussion, "Чаты"),
    FRIENDS(org.telegram.messenger.R.drawable.msg_groups, "Друзья"),
    SETTINGS(org.telegram.messenger.R.drawable.msg_settings, "Настройки"),
    PROFILE(org.telegram.messenger.R.drawable.msg_contacts, "Профиль"),
}

/**
 * The app's persistent tab bar, shown only while a tab's own root screen is on top of the stack
 * (SteamDebugActivity hides it the moment anything is pushed on top - a chat, a profile, the call
 * screen - since those already anchor their own content to the bottom of the screen).
 */
internal class SteamBottomNavView(context: Context) : LinearLayout(context) {

    var onTabSelected: ((SteamNavTab) -> Unit)? = null
    private var selected = SteamNavTab.CHATS
    private val tabViews = mutableMapOf<SteamNavTab, Triple<ImageView, TextView, TextView>>()

    init {
        orientation = HORIZONTAL
        setBackgroundColor(SteamPalette.chatBackground)
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        SteamNavTab.entries.forEach { tab -> row.addView(buildTab(context, tab), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f)) }
        addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        this.orientation = VERTICAL
        applySelection()
    }

    fun setUnreadCount(count: Int) {
        val (_, _, badge) = tabViews[SteamNavTab.CHATS] ?: return
        badge.visibility = if (count > 0) View.VISIBLE else View.GONE
        badge.text = if (count > 99) "99+" else count.toString()
    }

    private fun buildTab(context: Context, tab: SteamNavTab): View {
        val column = LinearLayout(context)
        column.orientation = VERTICAL
        column.gravity = Gravity.CENTER_HORIZONTAL
        column.isFocusable = true
        column.background = SteamPalette.rowSelector()
        column.setPadding(0, dp(8f), 0, dp(8f))
        column.contentDescription = tab.label
        column.setOnClickListener {
            if (selected == tab) return@setOnClickListener
            selected = tab
            applySelection()
            onTabSelected?.invoke(tab)
        }

        val iconBox = FrameLayout(context)
        val icon = ImageView(context).apply { setImageResource(tab.icon) }
        iconBox.addView(icon, LayoutHelper.createFrame(24, 24, Gravity.CENTER))
        val badge = TextView(context).apply {
            setBackgroundColor(SteamPalette.accent)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 10f
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(SteamPalette.accent) }
        }
        iconBox.addView(badge, LayoutHelper.createFrame(16, 16f, Gravity.TOP or Gravity.END, 0f, -2f, -6f, 0f))
        column.addView(iconBox, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        val label = TextView(context).apply {
            text = tab.label
            textSize = 11f
            setPadding(0, dp(2f), 0, 0)
        }
        column.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        tabViews[tab] = Triple(icon, label, badge)
        return column
    }

    private fun applySelection() {
        tabViews.forEach { (tab, views) ->
            val (icon, label, _) = views
            val color = if (tab == selected) SteamPalette.accent else SteamPalette.headerSubtitle
            icon.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            label.setTextColor(color)
        }
    }
}
