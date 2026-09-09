package org.steamchat.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.steamchat.domain.SteamUser
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper

/** The "Друзья" tab: the full friends list, sorted the same way the client already gives it to
 *  us. Tapping a row opens that friend's profile - starting a chat instead is one tap away from
 *  there (the profile header), so this screen doesn't need to duplicate that entry point. */
class SteamFriendsFragment : SteamBaseFragment() {

    private val service = SteamServiceHolder.service
    private val scope = CoroutineScope(Dispatchers.Main)
    private val adapter = FriendsAdapter()
    private var friends: List<SteamUser> = emptyList()
    private lateinit var emptyView: TextView

    override fun createView(context: Context): View {
        // No back arrow: always a bottom-nav tab root, never pushed on top of anything.
        actionBar.setTitle("Друзья")
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })

        val recyclerView = RecyclerView(context)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        emptyView = TextView(context)
        emptyView.text = "Список друзей пуст"
        emptyView.textSize = 15f
        emptyView.gravity = Gravity.CENTER
        emptyView.setPadding(dp(32f), dp(32f), dp(32f), dp(32f))
        emptyView.setTextColor(SteamPalette.headerSubtitle)
        emptyView.visibility = View.GONE

        val root = FrameLayout(context)
        root.setBackgroundColor(SteamPalette.chatBackground)
        root.addView(recyclerView, LayoutHelper.createFrameMatchParent())
        root.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))

        fragmentView = root

        scope.launch {
            service.observeFriends().collect { list ->
                friends = list
                adapter.notifyDataSetChanged()
                emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        return root
    }

    override fun onFragmentDestroy() {
        scope.cancel()
        super.onFragmentDestroy()
    }

    private inner class FriendsAdapter : RecyclerView.Adapter<FriendsAdapter.Holder>() {
        override fun getItemCount() = friends.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val cell = FriendRowCell(parent.context)
            cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64f))
            return Holder(cell)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val user = friends[position]
            holder.cell.setUser(user)
            holder.cell.setOnClickListener { presentFragment(SteamProfileFragment(user.steamId64, user.personaName)) }
        }

        inner class Holder(val cell: FriendRowCell) : RecyclerView.ViewHolder(cell)
    }
}

private class FriendRowCell(context: Context) : FrameLayout(context) {
    private val avatarImageView = BackupImageView(context)
    private val avatarDrawable = AvatarDrawable()
    private val statusDot = View(context)
    private val nameView = TextView(context)
    private val statusView = TextView(context)

    init {
        val avatarSize = 44f
        isClickable = true
        background = SteamPalette.rowSelector()

        avatarImageView.setRoundRadius(dp(avatarSize / 2))
        addView(avatarImageView, LayoutHelper.createFrame(avatarSize.toInt(), avatarSize, Gravity.START or Gravity.CENTER_VERTICAL, 16f, 0f, 0f, 0f))

        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2f), SteamPalette.chatBackground)
        }
        addView(statusDot, LayoutHelper.createFrame(14, 14f, Gravity.START or Gravity.CENTER_VERTICAL, 16f + avatarSize - 12f, avatarSize / 2 + 6f, 0f, 0f))

        nameView.textSize = 16f
        nameView.setTextColor(SteamPalette.headerTitle)
        addView(nameView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.START or Gravity.TOP, 16f + avatarSize + 12f, 13f, 16f, 0f))

        statusView.textSize = 13f
        addView(statusView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.START or Gravity.TOP, 16f + avatarSize + 12f, 36f, 16f, 0f))
    }

    fun setUser(user: SteamUser) {
        nameView.text = user.personaName
        avatarDrawable.setInfo(user.steamId64, user.personaName, "")
        if (user.avatarUrl != null) avatarImageView.setImage(user.avatarUrl, "50_50", avatarDrawable) else avatarImageView.setImageDrawable(avatarDrawable)

        val presentation = steamStatusPresentation(user)
        statusView.text = presentation.text
        statusView.setTextColor(if (presentation.online) SteamPalette.presenceOnline else SteamPalette.headerSubtitle)
        (statusDot.background as GradientDrawable).setColor(presenceColor(user))
    }
}
