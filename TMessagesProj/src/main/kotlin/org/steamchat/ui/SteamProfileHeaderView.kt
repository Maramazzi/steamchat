package org.steamchat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.github.penfeizhou.animation.apng.APNGDrawable
import com.github.penfeizhou.animation.loader.ByteBufferLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.steamchat.domain.SteamUser
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper
import java.nio.ByteBuffer

/**
 * Avatar+status dot, name, "playing X"/online/offline status and a copyable Steam ID row - the
 * part of a profile screen that's identical whether it's a friend's profile or your own. Same
 * compact-custom-view pattern as SteamDialogCell/SteamMessageCell: reuse Theme/BackupImageView/
 * AvatarDrawable instead of forking Telegram's own (much larger) ProfileActivity.
 *
 * Layout matches the product mockup: a large rounded-square avatar on the left, name/status/ID
 * stacked to its right rather than centred under it.
 */
class SteamProfileHeaderView(context: Context) : LinearLayout(context) {

    private val avatarDrawable = AvatarDrawable()
    private val avatarImageView = BackupImageView(context)
    private val avatarFrameView = ImageView(context)
    private val avatarBox = FrameLayout(context)
    private val statusDot = View(context)
    private val nameView = TextView(context)
    private val nameHistoryArrow = TextView(context)
    private val statusView = TextView(context)
    private val idText = TextView(context)

    /** Wired by SteamProfileFragment, which owns the SteamService/coroutine scope this view doesn't have. */
    var onNameHistoryClick: ((steamId64: Long) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16f), dp(24f), dp(16f), 0)

        val avatarSize = AVATAR_SIZE_DP
        avatarImageView.setRoundRadius(dp(30f))
        avatarBox.addView(avatarImageView, LayoutHelper.createFrame(avatarSize.toInt(), avatarSize.toInt(), Gravity.CENTER))

        // Sized/shown only once applyDecorations() confirms an equipped frame - most accounts
        // don't have one, and avatarBox only grows past avatarSize when one's present. A plain
        // ImageView, not BackupImageView: this hosts a real animated Drawable (APNGDrawable) we
        // decode ourselves, not a CDN url through Telegram's own image pipeline, and a plain
        // ImageView is guaranteed (standard Android Drawable.Callback wiring) to actually redraw
        // on every animation frame - not something worth risking on BackupImageView's own custom
        // draw path, which is built for static/CDN images.
        avatarFrameView.visibility = View.GONE
        avatarBox.addView(avatarFrameView, LayoutHelper.createFrame(avatarSize.toInt(), avatarSize.toInt(), Gravity.CENTER))

        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Theme.getColor(Theme.key_chats_onlineCircle))
        }
        avatarBox.addView(statusDot, LayoutHelper.createFrame(20, 20f, Gravity.BOTTOM or Gravity.END, 0f, 0f, 2f, 2f))
        addView(avatarBox, LayoutHelper.createLinear(avatarSize.toInt(), avatarSize.toInt()))

        val column = LinearLayout(context)
        column.orientation = VERTICAL
        column.gravity = Gravity.START

        val nameRow = LinearLayout(context)
        nameRow.orientation = LinearLayout.HORIZONTAL
        nameRow.gravity = Gravity.CENTER_VERTICAL

        nameView.textSize = 26f
        nameView.typeface = Typeface.DEFAULT_BOLD
        nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        nameRow.addView(nameView)

        // Same "small arrow next to the name opens alias history" affordance as the real Steam
        // profile page (namehistory_link there). Text glyph, not a drawable resource - matches
        // this file's own chevron precedent (SteamProfileFragment.buildRow's "›") rather than
        // pulling in an icon just for a tiny dropdown arrow.
        nameHistoryArrow.text = "▾"
        nameHistoryArrow.textSize = 18f
        nameHistoryArrow.isClickable = true
        nameHistoryArrow.background = SteamPalette.rowSelector()
        nameHistoryArrow.setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
        nameHistoryArrow.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
        nameHistoryArrow.setOnClickListener {
            val steamId64 = idText.tag as? Long ?: return@setOnClickListener
            onNameHistoryClick?.invoke(steamId64)
        }
        nameRow.addView(nameHistoryArrow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 2, 0, 0, 0))

        column.addView(nameRow)

        statusView.textSize = 15f
        statusView.setPadding(0, dp(4f), 0, 0)
        column.addView(statusView)

        val idRow = LinearLayout(context)
        idRow.orientation = LinearLayout.HORIZONTAL
        idRow.gravity = Gravity.CENTER_VERTICAL
        idRow.isClickable = true
        idRow.background = SteamPalette.rowSelector()
        idRow.setPadding(0, dp(8f), dp(8f), dp(8f))

        idText.textSize = 13f
        idText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
        idRow.addView(idText)

        val copyIcon = android.widget.ImageView(context)
        copyIcon.setImageResource(R.drawable.mini_inline_copy_16)
        copyIcon.colorFilter = android.graphics.PorterDuffColorFilter(
            Theme.getColor(Theme.key_windowBackgroundWhiteBlueText),
            android.graphics.PorterDuff.Mode.SRC_IN,
        )
        idRow.addView(copyIcon, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 6, 0, 0, 0))

        column.addView(idRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))

        addView(column, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 16, 0, 0, 0))

        idRow.setOnClickListener {
            val steamId64 = idText.tag as? Long ?: return@setOnClickListener
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Steam ID", steamId64.toString()))
            Toast.makeText(context, "Steam ID скопирован", Toast.LENGTH_SHORT).show()
        }
    }

    fun render(user: SteamUser) {
        avatarDrawable.setInfo(user.steamId64, user.personaName, "")
        if (user.avatarUrl != null) {
            avatarImageView.setImage(user.avatarUrl, "100_100", avatarDrawable)
        } else {
            avatarImageView.setImageDrawable(avatarDrawable)
        }
        nameView.text = user.personaName
        idText.text = "Steam ID: ${user.steamId64}"
        idText.tag = user.steamId64

        val presentation = steamStatusPresentation(user)
        statusDot.visibility = if (presentation.online) View.VISIBLE else View.GONE
        statusView.text = presentation.text
        statusView.setTextColor(
            if (presentation.online) Theme.getColor(Theme.key_chats_onlineCircle) else Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2),
        )
    }

    /**
     * Equipped avatar frame (Points Shop cosmetic, most accounts don't have one) - arrives later
     * than render() since it comes from getProfileStats() (a web scrape, see SteamWebProfile), not
     * the client-protocol SteamUser. A real, live-confirmed animated PNG (24 frames, partial-region
     * compositing between frames - not simple full-frame swaps), which Android's own ImageDecoder
     * can't play at all (no APNG support, confirmed - see SteamWebProfile.parseAvatarFrameUrl), so
     * this decodes it via com.github.penfeizhou.android.animation:apng (pure Kotlin/Java, no native
     * code, minSdk 21 - chosen over hand-rolling an APNG frame compositor, which the real asset's
     * partial-region frames make a genuine correctness risk, not "a few lines").
     */
    suspend fun applyDecorations(avatarFrameUrl: String?) {
        if (avatarFrameUrl == null) return
        val drawable = fetchAnimatedFrame(avatarFrameUrl) ?: return

        // Frame art is a bigger canvas than the avatar itself (confirmed: downloaded a real
        // frame, 224x224 vs the avatar's own 184x184) so it overhangs the avatar's edges -
        // avatarBox has to grow to fit that overhang, or the frame gets clipped to avatarSize.
        // Left at avatarSize (no frame equipped, or the fetch failed) this is a no-op, the layout
        // stays exactly as it was before this feature existed.
        val frameSize = (AVATAR_SIZE_DP * FRAME_OVERHANG_RATIO).toInt()
        val inset = (frameSize - AVATAR_SIZE_DP.toInt()) / 2f
        avatarBox.layoutParams = LayoutHelper.createLinear(frameSize, frameSize)
        avatarFrameView.layoutParams = LayoutHelper.createFrame(frameSize, frameSize, Gravity.CENTER)
        // Keep the status dot hugging the actual avatar photo's corner, not the wider frame's.
        statusDot.layoutParams = LayoutHelper.createFrame(20, 20f, Gravity.BOTTOM or Gravity.END, 0f, 0f, 2f + inset, 2f + inset)
        avatarFrameView.setImageDrawable(drawable)
        avatarFrameView.visibility = View.VISIBLE
        avatarBox.requestLayout()
    }

    private suspend fun fetchAnimatedFrame(url: String): APNGDrawable? = withContext(Dispatchers.IO) {
        val bytes = fetchBytes(url) ?: return@withContext null
        try {
            APNGDrawable(ByteArrayApngLoader(bytes))
        } catch (e: Exception) {
            null
        }
    }

    private class ByteArrayApngLoader(private val bytes: ByteArray) : ByteBufferLoader() {
        override fun getByteBuffer(): ByteBuffer = ByteBuffer.wrap(bytes)
    }

    companion object {
        const val AVATAR_SIZE_DP = 156f

        // Measured, not guessed: a real equipped frame downloaded from steamcommunity.com is a
        // 224x224 canvas around a 184x184 avatar.
        private const val FRAME_OVERHANG_RATIO = 224f / 184f
    }
}
