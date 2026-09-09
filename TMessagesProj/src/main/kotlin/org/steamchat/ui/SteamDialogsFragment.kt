package org.steamchat.ui

import android.content.Context
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.HorizontalScrollView
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
import org.steamchat.domain.SteamDialog
import org.steamchat.domain.SteamChatGroup
import org.steamchat.domain.SteamChatFolder
import org.steamchat.domain.SteamInboxEntry
import org.steamchat.domain.SteamStatus
import org.steamchat.domain.steamInbox
import org.steamchat.storage.PrefsChatFolders
import java.util.UUID
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

/** Which row of tabs is active: the two fixed built-in ones, or one of the user's own custom
 *  folders (a separate, already-shipped feature - see PrefsChatFolders). Kept apart from
 *  [SteamChatFolder] on purpose: All/Online/Groups aren't real folders a user can rename or
 *  delete, and forcing them into that model would let someone "delete" a built-in tab. */
private sealed interface SteamDialogsTab {
    data object All : SteamDialogsTab
    data object Online : SteamDialogsTab
    data object Groups : SteamDialogsTab
    data class Custom(val folder: SteamChatFolder) : SteamDialogsTab
}

/**
 * Real Telegram UI chrome (BaseFragment/ActionBar/Theme) driven entirely by SteamService - no
 * org.telegram.messenger.MessagesController involved. Assumes login already happened (see
 * SteamLoginFragment, which pushes this fragment on SteamLoginResult.Success).
 */
class SteamDialogsFragment : SteamBaseFragment() {

    private val service = SteamServiceHolder.service
    private val scope = CoroutineScope(Dispatchers.Main)
    private var collectors: Job? = null
    private val adapter = DialogsAdapter()
    private var dialogs: List<SteamDialog> = emptyList()
    private var groups: List<SteamChatGroup> = emptyList()
    private var folders = emptyList<SteamChatFolder>()
    private var selectedTab: SteamDialogsTab = SteamDialogsTab.All
    private var folderStore: PrefsChatFolders? = null
    private var accountId: Long? = null
    private var entries = emptyList<SteamInboxEntry>()
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var folderTabs: LinearLayout

    override fun createView(context: Context): View {
        // No back arrow: this fragment is always a bottom-nav tab root now (SteamDebugActivity
        // resets the stack to just this on every tab switch), never pushed on top of anything.
        actionBar.setAllowOverlayTitle(true)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> finishFragment()
                    // No dedicated search UI yet - same honest-stub pattern as the chat screen's
                    // own search icon (SteamChatFragment.MENU_SEARCH), not a silent dead button.
                    MENU_SEARCH -> Toast.makeText(context, "Поиск по чатам пока в разработке", Toast.LENGTH_SHORT).show()
                    MENU_FOLDERS -> showFolderManager(context)
                }
            }
        })
        actionBar.createMenu().apply {
            addItem(MENU_SEARCH, R.drawable.msg_search).contentDescription = "Поиск"
            addItem(MENU_FOLDERS, R.drawable.ic_ab_other).contentDescription = "Управление папками"
        }

        val brandIcon = ImageView(context)
        brandIcon.setImageResource(R.drawable.ic_steamchat_brand)
        val brandTitle = TextView(context)
        brandTitle.text = "SteamChat"
        brandTitle.textSize = 20f
        brandTitle.typeface = AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM)
        brandTitle.setTextColor(SteamPalette.headerTitle)
        val brandRow = LinearLayout(context)
        brandRow.orientation = LinearLayout.HORIZONTAL
        brandRow.gravity = Gravity.CENTER_VERTICAL
        brandRow.addView(brandIcon, LayoutHelper.createLinear(32, 32, Gravity.CENTER_VERTICAL, 0, 0, 10, 0))
        brandRow.addView(brandTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        // Same convention as SteamChatHeaderView: ActionBar is a plain FrameLayout, so a custom
        // header just gets added into it rather than going through setTitle().
        //
        // MATCH_PARENT here spans the ActionBar's *full* measured height, which on this edge-to-
        // edge layout already includes the status bar (ActionBar.onMeasure adds statusBarHeight -
        // see CLAUDE.md's edge-to-edge grabli). Telegram's own createMenu() icons know to start
        // below that reserved strip; a plain addView() doesn't, so a 0-top-margin child centers
        // itself against the status bar + content combined instead of just the visible bar,
        // landing visibly higher than the search/folders icons on the same row (confirmed by
        // uiautomator dump: brand row centered at y=105px, menu icons at y=136px, a real ~12dp
        // gap, not a rounding artifact). topMargin = statusBarHeight confines it to the same
        // visible strip the menu icons already use.
        actionBar.addView(
            brandRow,
            LayoutHelper.createFrameMarginPx(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT.toFloat(), Gravity.TOP or Gravity.LEFT, dp(20f), AndroidUtilities.statusBarHeight, 0, 0),
        )

        recyclerView = RecyclerView(context)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        emptyView = TextView(context)
        emptyView.gravity = Gravity.CENTER
        emptyView.textSize = 15f
        emptyView.setTextColor(SteamPalette.headerSubtitle)
        emptyView.setPadding(dp(32f), dp(32f), dp(32f), dp(32f))

        val content = FrameLayout(context)
        content.addView(recyclerView, LayoutHelper.createFrameMatchParent())
        content.addView(emptyView, LayoutHelper.createFrameMatchParent())

        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(SteamPalette.chatBackground)
        folderTabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(folderTabs)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42f)))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        fragmentView = root
        renderTabs()

        collectors?.cancel()
        collectors = scope.launch {
          launch {
            service.observeCurrentUser().collect { user ->
                val id = user?.steamId64
                if (accountId != id) {
                    visibleDialog?.dismiss()
                    accountId = id
                    folderStore = id?.let { PrefsChatFolders(context.applicationContext, it) }
                    folders = folderStore?.read().orEmpty()
                    selectedTab = SteamDialogsTab.All
                    renderTabs()
                    renderList()
                }
            }
          }

          launch {
            service.observeDialogs().collect { list ->
                dialogs = list
                renderList()
            }
          }
          launch {
            service.observeChatGroups().collect { list ->
                groups = list
                renderList()
            }
          }
        }

        return root
    }

    override fun onFragmentDestroy() {
        scope.cancel()
        super.onFragmentDestroy()
    }

    /** Fixed tabs first (never removable, not real SteamChatFolder rows so nothing can rename or
     *  delete them), the user's own custom folders after - two independent tab sources sharing
     *  one row, same as the mockup's "Все чаты / В сети / Группы" plus whatever the user made. */
    private fun renderTabs() {
        folderTabs.removeAllViews()
        val fixed: List<Pair<SteamDialogsTab, String>> = listOf(
            SteamDialogsTab.All to "Все чаты",
            SteamDialogsTab.Online to "В сети",
            SteamDialogsTab.Groups to "Группы",
        )
        val custom = folders.map { SteamDialogsTab.Custom(it) to it.name }
        val all = fixed + custom
        all.forEachIndexed { index, (tab, name) ->
            val selected = tab == selectedTab
            val pill = TextView(folderTabs.context).apply {
                text = name
                textSize = 13f
                typeface = AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM)
                maxLines = 1
                maxWidth = dp(200f)
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                isFocusable = true
                contentDescription = "$name${if (selected) ", выбрана" else ""}"
                setPadding(dp(14f), 0, dp(14f), 0)
                setTextColor(if (selected) android.graphics.Color.WHITE else SteamPalette.headerSubtitle)
                background = GradientDrawable().apply {
                    cornerRadius = dp(15f).toFloat()
                    setColor(if (selected) SteamPalette.accent else SteamPalette.inputField)
                }
                setOnClickListener {
                    selectedTab = tab
                    renderTabs()
                    renderList()
                    recyclerView.scrollToPosition(0)
                }
                setOnLongClickListener {
                    (tab as? SteamDialogsTab.Custom)?.let { showFolderActions(context, it.folder) } ?: showFolderManager(context)
                    true
                }
            }
            folderTabs.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30f)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(if (index == 0) 16f else 8f)
                if (index == all.lastIndex) marginEnd = dp(16f)
            })
        }
    }

    private fun saveFolders(updated: List<SteamChatFolder>) {
        folderStore?.save(updated) ?: return
        folders = updated
        val current = selectedTab
        if (current is SteamDialogsTab.Custom && folders.none { it.id == current.folder.id }) selectedTab = SteamDialogsTab.All
        renderTabs()
        renderList()
    }

    private fun showFolderManager(context: Context) {
        if (folderStore == null) return
        val snapshot = folders
        showDialog(AlertDialog.Builder(context).setTitle("Папки на этом устройстве")
            .setItems((listOf("Создать папку") + snapshot.map { it.name }).toTypedArray()) { _, which ->
                if (which == 0) editFolderName(context, null) else showFolderActions(context, snapshot[which - 1])
            }.setNegativeButton("Закрыть", null).create())
    }

    private fun showFolderActions(context: Context, folder: SteamChatFolder) {
        val owner = accountId
        showDialog(AlertDialog.Builder(context).setTitle(folder.name)
            .setItems(arrayOf("Выбрать чаты", "Переименовать", "Удалить папку")) { _, which ->
                when (which) {
                    0 -> editFolderChats(context, folder)
                    1 -> editFolderName(context, folder)
                    2 -> showDialog(AlertDialog.Builder(context).setTitle("Удалить папку «${folder.name}»?")
                        .setMessage("Чаты и группы останутся в общей ленте.")
                        .setPositiveButton("Удалить") { _, _ ->
                            if (owner == accountId) saveFolders(folders.filterNot { it.id == folder.id })
                        }
                        .setNegativeButton("Отмена", null).create())
                }
            }.create())
    }

    private fun editFolderName(context: Context, folder: SteamChatFolder?) {
        if (folderStore == null) return
        val owner = accountId
        val field = EditText(context).apply {
            setSingleLine(true)
            hint = "Название папки"
            filters = arrayOf(InputFilter.LengthFilter(32))
            setText(folder?.name.orEmpty())
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
            setPadding(dp(24f), dp(12f), dp(24f), dp(12f))
        }
        val dialog = AlertDialog.Builder(context).setTitle(if (folder == null) "Новая папка" else "Название папки")
            .setView(field).setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (owner != accountId) { dialog.dismiss(); return@setOnClickListener }
                val name = field.text.toString().trim()
                if (name.isEmpty()) { field.error = "Введите название"; return@setOnClickListener }
                val updated = folder?.copy(name = name) ?: SteamChatFolder(UUID.randomUUID().toString(), name, emptySet())
                saveFolders(if (folder == null) folders + updated else folders.map { if (it.id == folder.id) updated else it })
                dialog.dismiss()
                if (folder == null) editFolderChats(context, updated)
            }
        }
        showDialog(dialog)
    }

    private fun editFolderChats(context: Context, folder: SteamChatFolder) {
        val owner = accountId
        val available = steamInbox(dialogs, groups)
        val selected = folder.chatKeys.toMutableSet()
        showDialog(AlertDialog.Builder(context).setTitle("Чаты в «${folder.name}»")
            .setMultiChoiceItems(available.map { "${it.name}${if (it is SteamInboxEntry.Group) " · группа" else ""}" }.toTypedArray(),
                BooleanArray(available.size) { available[it].key in selected }) { _, which, checked ->
                if (checked) selected.add(available[which].key) else selected.remove(available[which].key)
            }.setPositiveButton("Сохранить") { _, _ ->
                if (owner == accountId) saveFolders(folders.map { if (it.id == folder.id) it.copy(chatKeys = selected.toSet()) else it })
            }.setNegativeButton("Отмена", null).create())
    }

    private fun assignToFolders(context: Context, entry: SteamInboxEntry) {
        if (folders.isEmpty()) { editFolderName(context, null); return }
        val owner = accountId
        val snapshot = folders
        val checked = BooleanArray(snapshot.size) { entry.key in snapshot[it].chatKeys }
        showDialog(AlertDialog.Builder(context).setTitle("Папки: ${entry.name}")
            .setMultiChoiceItems(snapshot.map { it.name }.toTypedArray(), checked) { _, which, value -> checked[which] = value }
            .setPositiveButton("Сохранить") { _, _ ->
                if (owner == accountId) saveFolders(folders.map { folder ->
                    val index = snapshot.indexOfFirst { it.id == folder.id }
                    if (index < 0) folder else folder.copy(chatKeys = if (checked[index]) folder.chatKeys + entry.key else folder.chatKeys - entry.key)
                })
            }.setNegativeButton("Отмена", null).create())
    }

    private fun renderList() {
        if (!::emptyView.isInitialized) return
        val tab = selectedTab
        val base = steamInbox(dialogs, groups, (tab as? SteamDialogsTab.Custom)?.folder)
        entries = when (tab) {
            SteamDialogsTab.Online -> base.filter { it is SteamInboxEntry.Direct && it.dialog.friend.status == SteamStatus.ONLINE }
            SteamDialogsTab.Groups -> base.filter { it is SteamInboxEntry.Group }
            else -> base
        }
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        emptyView.text = when (tab) {
            SteamDialogsTab.All -> "Здесь появятся ваши личные чаты и группы Steam"
            SteamDialogsTab.Online -> "Никого из друзей сейчас нет в сети"
            SteamDialogsTab.Groups -> "Групп пока нет"
            is SteamDialogsTab.Custom -> "В папке пока нет чатов\n\nУдерживайте название папки, чтобы выбрать чаты."
        }
    }

    private inner class DialogsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int = if (entries[position] is SteamInboxEntry.Direct) VIEW_PERSONAL else VIEW_GROUP

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val cell = if (viewType == VIEW_PERSONAL) SteamDialogCell(parent.context) else SteamGroupDialogCell(parent.context)
            cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP))
            return DialogViewHolder(cell)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val entry = entries[position]
            holder.itemView.setOnLongClickListener { assignToFolders(it.context, entry); true }
            when (entry) {
                is SteamInboxEntry.Direct -> {
                    val dialog = entry.dialog
                    (holder.itemView as SteamDialogCell).setDialog(dialog, scope)
                    holder.itemView.setOnClickListener {
                        presentFragment(SteamChatFragment(dialog.friend.steamId64, dialog.friend.personaName))
                    }
                }
                is SteamInboxEntry.Group -> {
                    val group = entry.group
                    (holder.itemView as SteamGroupDialogCell).setGroup(group, scope)
                    holder.itemView.setOnClickListener {
                        presentFragment(SteamGroupChannelsFragment(group.id))
                    }
                }
            }
        }

        override fun getItemCount(): Int = entries.size
    }

    private class DialogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    private companion object {
        const val MENU_SEARCH = 1
        const val MENU_FOLDERS = 2
        const val VIEW_PERSONAL = 0
        const val VIEW_GROUP = 1
        // Tall enough for the optional third "Играет в X" line a friend's row grows when they're
        // in a game - group rows never show that line but stay the same height for a level grid.
        const val ROW_HEIGHT_DP = 76f
    }

}
