package org.steamchat.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.steamchat.domain.SteamChatChannel
import org.steamchat.domain.SteamChatGroup
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper

/**
 * Discord-style channel navigation inside SteamChat's existing visual system.
 * A 72dp group rail stays beside the selected group's text/voice sections; text opens the
 * existing renderer on the fragment stack, so Back returns here. Voice has no media transport.
 */
class SteamGroupChannelsFragment(initialGroupId: Long) : SteamBaseFragment() {
    private val service = SteamServiceHolder.service
    private val scope = CoroutineScope(Dispatchers.Main)
    private var groupsJob: Job? = null
    private var selectedGroupId = initialGroupId
    private var selectedChannelId: Long? = null
    private var groups = emptyList<SteamChatGroup>()
    private val railAdapter = GroupRailAdapter()
    private val channelsAdapter = ChannelsAdapter()
    private lateinit var rail: RecyclerView
    private lateinit var channels: RecyclerView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setTitle("Группы Steam")
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })

        rail = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = railAdapter
            itemAnimator = null
            setPadding(0, dp(8f), 0, dp(8f))
            clipToPadding = false
            setBackgroundColor(SteamPalette.inputBarBackground)
        }
        title = label(context, 24f, SteamPalette.headerTitle).apply {
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitle = label(context, 13f, SteamPalette.headerSubtitle).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(18f), dp(16f), dp(16f))
            addView(title, LinearLayout.LayoutParams(-1, -2))
            addView(subtitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
        }
        channels = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = channelsAdapter
            itemAnimator = null
            setPadding(dp(8f), 0, dp(8f), dp(16f))
            clipToPadding = false
        }
        val pane = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SteamPalette.chatBackground)
            addView(header, LinearLayout.LayoutParams(-1, -2))
            addView(View(context).apply { setBackgroundColor(SteamPalette.inputField) }, LinearLayout.LayoutParams(-1, dp(1f)))
            addView(channels, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(rail, LinearLayout.LayoutParams(dp(72f), -1))
            addView(pane, LinearLayout.LayoutParams(0, -1, 1f))
        }
        fragmentView = root
        groupsJob?.cancel()
        groupsJob = scope.launch {
            service.observeChatGroups().collect { updated ->
                val previousId = selectedGroupId
                val firstGroups = groups.isEmpty() && updated.isNotEmpty()
                groups = updated
                if (updated.isNotEmpty() && updated.none { it.id == selectedGroupId }) {
                    selectedGroupId = updated.first().id
                    selectedChannelId = null
                }
                railAdapter.notifyDataSetChanged()
                renderChannels()
                if (firstGroups) rail.scrollToPosition(groups.indexOfFirst { it.id == selectedGroupId }.coerceAtLeast(0))
                if (previousId != selectedGroupId) channels.scrollToPosition(0)
            }
        }
        renderChannels()
        rail.scrollToPosition(groups.indexOfFirst { it.id == selectedGroupId }.coerceAtLeast(0))
        return root
    }

    override fun onFragmentDestroy() {
        scope.cancel()
        super.onFragmentDestroy()
    }

    private fun selectGroup(groupId: Long) {
        if (selectedGroupId == groupId) return
        selectedGroupId = groupId
        selectedChannelId = null
        railAdapter.notifyDataSetChanged()
        renderChannels()
        channels.scrollToPosition(0)
    }

    private fun renderChannels() {
        val group = groups.firstOrNull { it.id == selectedGroupId }
        title.text = group?.name?.ifBlank { "Группа Steam" } ?: "Группы Steam"
        subtitle.text = group?.let { "${it.activeMemberCount} в сети" }
            ?: "Группы появятся после подключения к Steam"
        val rows = mutableListOf<ChannelRow>()
        if (group == null) {
            rows += ChannelRow("Нет доступных групп. Здесь появятся группы, в чаты которых вы вступили в Steam.")
        } else {
            for ((voice, heading) in listOf(false to "Текстовые каналы", true to "Голосовые каналы")) {
                rows += ChannelRow(heading, heading = true)
                val section = group.channels.filter { it.voiceAllowed == voice }
                if (section.isEmpty()) rows += ChannelRow(if (voice) "Нет голосовых каналов" else "Нет текстовых каналов")
                else rows += section.map { ChannelRow(it.name.ifBlank { "Канал" }, channel = it) }
            }
        }
        channelsAdapter.rows = rows
        channelsAdapter.notifyDataSetChanged()
    }

    private inner class GroupRailAdapter : RecyclerView.Adapter<GroupHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = GroupHolder(parent.context)
        override fun getItemCount() = groups.size
        override fun onBindViewHolder(holder: GroupHolder, position: Int) {
            val group = groups[position]
            val name = group.name.ifBlank { "Группа Steam" }
            val selected = group.id == selectedGroupId
            holder.placeholder.setInfo(group.id, name, "")
            holder.avatar.setRoundRadius(dp(if (selected) 16f else 24f))
            if (group.avatarUrl != null) holder.avatar.setImage(group.avatarUrl, "50_50", holder.placeholder)
            else holder.avatar.setImageDrawable(holder.placeholder)
            holder.marker.visibility = if (selected || group.hasUnread) View.VISIBLE else View.GONE
            holder.marker.layoutParams.height = dp(if (selected) 32f else 8f)
            holder.marker.requestLayout()
            holder.itemView.isSelected = selected
            holder.itemView.contentDescription = "$name${if (group.hasUnread) ", есть непрочитанные сообщения" else ""}"
            holder.itemView.setOnClickListener { selectGroup(group.id) }
        }
    }

    private class GroupHolder(context: Context) : RecyclerView.ViewHolder(FrameLayout(context)) {
        val avatar = BackupImageView(context)
        val placeholder = AvatarDrawable()
        val marker = View(context)
        init {
            (itemView as FrameLayout).apply {
                layoutParams = RecyclerView.LayoutParams(-1, dp(64f))
                background = SteamPalette.rowSelector()
                isFocusable = true
                addView(avatar, LayoutHelper.createFrame(48, 48, Gravity.CENTER))
                marker.background = GradientDrawable().apply {
                    cornerRadius = dp(4f).toFloat()
                    setColor(SteamPalette.headerTitle)
                }
                addView(marker, LayoutHelper.createFrame(4, 32, Gravity.START or Gravity.CENTER_VERTICAL))
            }
        }
    }

    private data class ChannelRow(val title: String, val heading: Boolean = false, val channel: SteamChatChannel? = null)

    private inner class ChannelsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var rows: List<ChannelRow> = emptyList()
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position].channel != null) 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 1) return ChannelHolder(parent.context)
            return object : RecyclerView.ViewHolder(label(parent.context, 14f, SteamPalette.headerSubtitle).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                setPadding(dp(8f), dp(20f), dp(8f), dp(10f))
            }) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            val channel = row.channel
            if (holder !is ChannelHolder || channel == null) {
                (holder.itemView as TextView).apply {
                    text = row.title
                    typeface = if (row.heading) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }
                return
            }
            holder.name.text = row.title
            holder.name.typeface = if (channel.hasUnread) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            holder.name.setTextColor(if (channel.hasUnread || channel.id == selectedChannelId) SteamPalette.headerTitle else SteamPalette.incomingMeta)
            holder.icon.setImageResource(if (channel.voiceAllowed) R.drawable.msg_call_speaker else R.drawable.menu_hashtag)
            holder.count.text = channel.voiceMemberCount.toString()
            holder.count.visibility = if (channel.voiceAllowed && channel.voiceMemberCount > 0) View.VISIBLE else View.GONE
            val selected = channel.id == selectedChannelId
            holder.itemView.isSelected = selected
            holder.itemView.setBackgroundColor(if (selected) SteamPalette.inputField else android.graphics.Color.TRANSPARENT)
            holder.itemView.contentDescription = buildString {
                append(if (channel.voiceAllowed) "Голосовой канал " else "Текстовый канал ")
                append(row.title)
                if (channel.voiceAllowed) append(", участников: ${channel.voiceMemberCount}. Голос пока не поддерживается")
                if (channel.hasUnread) append(", есть непрочитанные сообщения")
            }
            val groupId = selectedGroupId
            holder.itemView.setOnClickListener {
                if (channel.voiceAllowed) {
                    Toast.makeText(holder.itemView.context, "Голосовые каналы Steam пока не поддерживаются", Toast.LENGTH_SHORT).show()
                } else {
                    selectedChannelId = channel.id
                    notifyDataSetChanged()
                    presentFragment(SteamChatFragment.forGroup(groupId, channel.id))
                }
            }
        }
    }

    private class ChannelHolder(context: Context) : RecyclerView.ViewHolder(FrameLayout(context)) {
        val name = label(context, 17f, SteamPalette.incomingMeta)
        val icon = ImageView(context)
        val count = label(context, 12f, SteamPalette.headerSubtitle)
        init {
            (itemView as FrameLayout).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(4f) }
                minimumHeight = dp(52f)
                isFocusable = true
                foreground = SteamPalette.rowSelector()
                icon.setColorFilter(SteamPalette.incomingMeta)
                addView(icon, LayoutHelper.createFrame(24, 24f, Gravity.START or Gravity.CENTER_VERTICAL, 8f, 0f, 0f, 0f))
                name.maxLines = 1
                name.ellipsize = TextUtils.TruncateAt.END
                name.setPadding(0, dp(14f), 0, dp(14f))
                addView(name, LayoutHelper.createFrame(-1, -2f, Gravity.CENTER_VERTICAL, 42f, 0f, 36f, 0f))
                addView(count, LayoutHelper.createFrame(-2, -2f, Gravity.END or Gravity.CENTER_VERTICAL, 0f, 0f, 8f, 0f))
            }
        }
    }

    private companion object {
        fun label(context: Context, size: Float, color: Int) = TextView(context).apply {
            textSize = size
            setTextColor(color)
        }
    }
}
