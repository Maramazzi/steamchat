package org.steamchat.ui

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.steamchat.service.SteamService
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper

/**
 * Real Steam emoticon + sticker picker, backed by the account's own catalogues -
 * SteamService.getAvailableEmoticons() (CPlayer_GetEmoticonList_Request) and
 * getAvailableStickers() (legacy ClientGetEmoticonList, see StickerListHandler) - never a
 * free-typed shortcode. Picking from these lists is what guarantees the text sent is something
 * Steam's own official client actually recognises: typing an invalid or unowned name by hand
 * renders as literal text there instead of an image (observed live), since the receiving client
 * checks the name against a real catalogue rather than trying every span the way this app's own
 * *receiving* side does for emoticons (SteamEmoticons.kt).
 *
 * A sticker inserts as the literal chat text `/Sticker {name}` - confirmed against a real Steam
 * client bug report (ValveSoftware/steam-for-linux#8740) stating that exact string as the normal,
 * expected message text for sending one; there is no separate sticker field on any send-message
 * request (checked). Both sections feed the same onPickText callback, which just inserts text at
 * the caret - sending itself is the caller's existing send button, same as typed text.
 *
 * Plain platform Dialog, same choice as this file's other popups (name history, message
 * long-press) - no need for Telegram's heavier BottomSheet for a short-lived picker.
 */
internal fun showEmoticonPicker(context: Context, service: SteamService, scope: CoroutineScope, onPickText: (String) -> Unit) {
    val dialog = Dialog(context)
    val root = LinearLayout(context)
    root.orientation = LinearLayout.VERTICAL
    root.setBackgroundColor(SteamPalette.chatBackground)
    root.setPadding(dp(4f), dp(12f), dp(4f), dp(12f))

    val loadingLabel = TextView(context)
    loadingLabel.text = "Загрузка..."
    loadingLabel.setTextColor(SteamPalette.headerSubtitle)
    loadingLabel.gravity = Gravity.CENTER
    root.addView(loadingLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 160))

    val scroll = ScrollView(context)
    val content = LinearLayout(context)
    content.orientation = LinearLayout.VERTICAL
    scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    scroll.visibility = View.GONE
    root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 420))

    dialog.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    // Plain Dialog's default window chrome is the system's (often light) dialog background - this
    // content is fully custom and dark, so the window itself needs to match rather than framing it
    // in a mismatched box.
    dialog.window?.setBackgroundDrawable(ColorDrawable(SteamPalette.chatBackground))
    dialog.show()

    scope.launch {
        val emoticons = service.getAvailableEmoticons()
        val stickers = service.getAvailableStickers()
        if (emoticons.isEmpty() && stickers.isEmpty()) {
            loadingLabel.text = "Нет доступных эмодзи"
            return@launch
        }
        loadingLabel.visibility = View.GONE
        scroll.visibility = View.VISIBLE

        if (emoticons.isNotEmpty()) {
            content.addView(sectionLabel(context, "Эмодзи"))
            // Steam's own CPlayer_GetEmoticonList_Response returns Emoticon.name already wrapped
            // in colons (":steamhappy:", confirmed live - not a bare "steamhappy" as the field
            // name alone would suggest) - trim() before use so it's never double-wrapped into
            // "::steamhappy: :" and never baked verbatim, colons and all, into the CDN url.
            val emoticonNames = emoticons.map { it.name.trim(':') }
            content.addView(buildGrid(context, EMOTICON_COLUMNS, EMOTICON_CELL_DP, emoticonNames, ::emoticonUrlFor) { name ->
                onPickText(":$name: ")
                dialog.dismiss()
            })
        }
        if (stickers.isNotEmpty()) {
            content.addView(sectionLabel(context, "Стикеры"))
            content.addView(buildGrid(context, STICKER_COLUMNS, STICKER_CELL_DP, stickers.map { it.name }, ::stickerUrlFor) { name ->
                onPickText("/Sticker $name ")
                dialog.dismiss()
            })
        }
    }
}

private fun sectionLabel(context: Context, text: String): TextView = TextView(context).apply {
    this.text = text
    setTextColor(SteamPalette.headerSubtitle)
    typeface = Typeface.DEFAULT_BOLD
    textSize = 13f
    setPadding(dp(10f), dp(10f), dp(10f), dp(6f))
}

private fun buildGrid(context: Context, columns: Int, cellDp: Float, names: List<String>, imageUrl: (String) -> String, onPick: (String) -> Unit): RecyclerView =
    RecyclerView(context).apply {
        layoutManager = GridLayoutManager(context, columns)
        isNestedScrollingEnabled = false
        adapter = PickerAdapter(names, cellDp, imageUrl, onPick)
    }

private fun emoticonUrlFor(name: String) = "https://community.akamai.steamstatic.com/economy/emoticon/$name"

private fun stickerUrlFor(name: String) = "https://community.akamai.steamstatic.com/economy/sticker/$name"

private class PickerAdapter(
    private val names: List<String>,
    private val cellDp: Float,
    private val imageUrl: (String) -> String,
    private val onPick: (String) -> Unit,
) : RecyclerView.Adapter<PickerAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val imageView = BackupImageView(parent.context)
        imageView.layoutParams = RecyclerView.LayoutParams(dp(cellDp), dp(cellDp))
        return Holder(imageView)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val name = names[position]
        holder.imageView.setImage(imageUrl(name), null, ColorDrawable(SteamPalette.incomingBubble))
        holder.imageView.setOnClickListener { onPick(name) }
    }

    override fun getItemCount(): Int = names.size

    class Holder(val imageView: BackupImageView) : RecyclerView.ViewHolder(imageView)
}

private const val EMOTICON_COLUMNS = 6
private const val EMOTICON_CELL_DP = 48f
private const val STICKER_COLUMNS = 3
private const val STICKER_CELL_DP = 96f
