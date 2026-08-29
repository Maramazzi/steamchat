package org.steamchat.ui

import android.content.Context
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
import org.steamchat.domain.SteamDialog
import org.steamchat.service.SteamGuardHandler
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

/**
 * Stage 2 proof: real Telegram UI chrome (BaseFragment/ActionBar/Theme) driven entirely by
 * SteamService/FakeSteamService - no org.telegram.messenger.MessagesController involved.
 */
class SteamDialogsFragment : BaseFragment() {

    private val service = SteamServiceHolder.service
    private val scope = CoroutineScope(Dispatchers.Main)
    private val adapter = DialogsAdapter()
    private var dialogs: List<SteamDialog> = emptyList()

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setTitle("SteamChat (fake)")
        actionBar.setAllowOverlayTitle(true)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })

        val recyclerView = RecyclerView(context)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        val root = FrameLayout(context)
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        root.addView(recyclerView, LayoutHelper.createFrameMatchParent())

        fragmentView = root

        scope.launch {
            service.login("fake", "fake", NoOpGuardHandler)
            service.observeDialogs().collect { list ->
                dialogs = list
                adapter.notifyDataSetChanged()
            }
        }

        return root
    }

    override fun onFragmentDestroy() {
        scope.cancel()
        super.onFragmentDestroy()
    }

    private inner class DialogsAdapter : RecyclerView.Adapter<DialogViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DialogViewHolder {
            val textView = TextView(parent.context)
            textView.setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            textView.textSize = 16f
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            return DialogViewHolder(textView)
        }

        override fun onBindViewHolder(holder: DialogViewHolder, position: Int) {
            val dialog = dialogs[position]
            val unread = if (dialog.unreadCount > 0) "  [${dialog.unreadCount}]" else ""
            (holder.itemView as TextView).text =
                "${dialog.friend.personaName}$unread\n${dialog.lastMessage?.text.orEmpty()}"
            holder.itemView.setOnClickListener {
                presentFragment(SteamChatFragment(dialog.friend.steamId64, dialog.friend.personaName))
            }
        }

        override fun getItemCount(): Int = dialogs.size
    }

    private class DialogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    private object NoOpGuardHandler : SteamGuardHandler {
        override suspend fun provideDeviceCode(previousWasIncorrect: Boolean) = ""
        override suspend fun provideEmailCode(email: String?, previousWasIncorrect: Boolean) = ""
        override suspend fun confirmViaMobileApp() = true
    }
}
