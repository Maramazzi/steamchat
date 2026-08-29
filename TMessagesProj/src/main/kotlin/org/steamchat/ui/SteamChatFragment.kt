package org.steamchat.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.steamchat.domain.SteamMessage
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme

class SteamChatFragment(
    private val friendSteamId64: Long,
    private val friendName: String,
) : BaseFragment() {

    private val service = SteamServiceHolder.service
    private val scope = CoroutineScope(Dispatchers.Main)
    private val adapter = MessagesAdapter()
    private var messages: List<SteamMessage> = emptyList()
    private lateinit var recyclerView: RecyclerView
    private lateinit var input: EditText

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setTitle(friendName)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })

        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))

        recyclerView = RecyclerView(context)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        input = EditText(context)
        input.hint = "Message"
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))

        val sendButton = TextView(context)
        sendButton.text = "Send"
        sendButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        sendButton.setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        sendButton.isClickable = true
        sendButton.background = Theme.getSelectorDrawable(true)
        sendButton.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                input.setText("")
                scope.launch { service.sendMessage(friendSteamId64, text) }
            }
        }

        val inputRow = LinearLayout(context)
        inputRow.orientation = LinearLayout.HORIZONTAL
        inputRow.gravity = Gravity.CENTER_VERTICAL
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(sendButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(recyclerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(inputRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fragmentView = root

        scope.launch {
            messages = service.getMessageHistory(friendSteamId64)
            adapter.notifyDataSetChanged()
            recyclerView.scrollToPosition((messages.size - 1).coerceAtLeast(0))
            service.markAsRead(friendSteamId64)
        }
        scope.launch {
            service.observeMessages(friendSteamId64).collect { incoming ->
                messages = messages + incoming
                adapter.notifyItemInserted(messages.size - 1)
                recyclerView.scrollToPosition(messages.size - 1)
            }
        }

        return root
    }

    override fun onFragmentDestroy() {
        scope.cancel()
        super.onFragmentDestroy()
    }

    private inner class MessagesAdapter : RecyclerView.Adapter<MessageViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
            val cell = SteamMessageCell(parent.context)
            cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return MessageViewHolder(cell)
        }

        override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
            (holder.itemView as SteamMessageCell).setMessage(messages[position])
        }

        override fun getItemCount(): Int = messages.size
    }

    private class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}
