package org.steamchat.ui

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

/** "Настройки" tab. `logout()` is real (SteamService already has it); everything else here is an
 *  honest "Пока в разработке" stub, same pattern as the placeholder rows on the profile screen -
 *  not a silent dead button. */
class SteamSettingsFragment : SteamBaseFragment() {

    private val service = SteamServiceHolder.service
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun createView(context: Context): View {
        // No back arrow: always a bottom-nav tab root, never pushed on top of anything.
        actionBar.setTitle("Настройки")
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.addView(buildRow(context, R.drawable.msg_theme, "Тема") { stub(context) })
        column.addView(buildRow(context, R.drawable.msg_notifications, "Уведомления") { stub(context) })
        column.addView(buildRow(context, R.drawable.msg_info, "О приложении") { stub(context) })
        column.addView(View(context).apply { setBackgroundColor(Theme.getColor(Theme.key_divider)) },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 16, 8, 16, 8))
        column.addView(buildRow(context, R.drawable.msg_leave, "Выйти из аккаунта") { confirmLogout(context) })

        val scrollView = ScrollView(context)
        scrollView.addView(column, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP))

        val root = FrameLayout(context)
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        root.addView(scrollView, LayoutHelper.createFrameMatchParent())
        fragmentView = root
        return root
    }

    private fun stub(context: Context) = Toast.makeText(context, "Пока в разработке", Toast.LENGTH_SHORT).show()

    private fun confirmLogout(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("Выйти из аккаунта?")
            .setMessage("Понадобится войти заново.")
            .setPositiveButton("Выйти") { _, _ -> scope.launch { service.logout() } }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun buildRow(context: Context, iconRes: Int, label: String, onClick: () -> Unit): View {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.isClickable = true
        row.background = SteamPalette.rowSelector()
        row.setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        row.setOnClickListener { onClick() }

        val icon = ImageView(context)
        icon.setImageResource(iconRes)
        icon.colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), PorterDuff.Mode.SRC_IN)
        row.addView(icon, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 0, 0, 16, 0))

        val labelView = TextView(context)
        labelView.text = label
        labelView.textSize = 15f
        labelView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        row.addView(labelView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    override fun onFragmentDestroy() {
        scope.cancel()
        super.onFragmentDestroy()
    }
}
