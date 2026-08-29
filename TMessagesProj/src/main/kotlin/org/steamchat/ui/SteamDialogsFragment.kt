package org.steamchat.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.steamchat.domain.SteamDialog
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

/**
 * Real Telegram UI chrome (BaseFragment/ActionBar/Theme) driven entirely by SteamService - no
 * org.telegram.messenger.MessagesController involved. Assumes login already happened (see
 * SteamLoginFragment, which pushes this fragment on SteamLoginResult.Success).
 */
class SteamDialogsFragment : BaseFragment() {

    private val service = SteamServiceHolder.service
    private val scope = CoroutineScope(Dispatchers.Main)
    private val adapter = DialogsAdapter()
    private var dialogs: List<SteamDialog> = emptyList()

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setTitle("SteamChat")
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
            val cell = SteamDialogCell(parent.context)
            cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72f))
            return DialogViewHolder(cell)
        }

        override fun onBindViewHolder(holder: DialogViewHolder, position: Int) {
            val dialog = dialogs[position]
            (holder.itemView as SteamDialogCell).setDialog(dialog)
            holder.itemView.setOnClickListener {
                presentFragment(SteamChatFragment(dialog.friend.steamId64, dialog.friend.personaName))
            }
        }

        override fun getItemCount(): Int = dialogs.size
    }

    private class DialogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}
