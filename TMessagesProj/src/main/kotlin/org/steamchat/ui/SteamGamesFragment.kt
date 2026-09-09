package org.steamchat.ui

import android.content.Context
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
import org.steamchat.domain.SteamGame
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

/** Native owned-games list, sorted by playtime - opened by tapping "Игры" on a profile screen. */
class SteamGamesFragment(
    private val steamId64: Long,
    private val ownerName: String,
) : SteamBaseFragment() {

    private val service = SteamServiceHolder.service
    private val scope = CoroutineScope(Dispatchers.Main)
    private val adapter = GamesAdapter()
    private var games: List<SteamGame> = emptyList()

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setTitle("Игры — $ownerName")
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })

        val recyclerView = RecyclerView(context)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        val emptyView = TextView(context)
        emptyView.text = "Список игр пуст или скрыт настройками приватности"
        emptyView.textSize = 15f
        emptyView.gravity = Gravity.CENTER
        emptyView.setPadding(dp(32f), dp(32f), dp(32f), dp(32f))
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
        emptyView.visibility = View.GONE

        val root = FrameLayout(context)
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        root.addView(recyclerView, LayoutHelper.createFrameMatchParent())
        root.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))

        fragmentView = root

        scope.launch {
            games = service.getOwnedGames(steamId64)
            adapter.notifyDataSetChanged()
            emptyView.visibility = if (games.isEmpty()) View.VISIBLE else View.GONE
        }

        return root
    }

    override fun onFragmentDestroy() {
        scope.cancel()
        super.onFragmentDestroy()
    }

    private inner class GamesAdapter : RecyclerView.Adapter<GameViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
            val cell = SteamGameCell(parent.context)
            cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return GameViewHolder(cell)
        }

        override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
            (holder.itemView as SteamGameCell).setGame(games[position])
        }

        override fun getItemCount(): Int = games.size
    }

    private class GameViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}
