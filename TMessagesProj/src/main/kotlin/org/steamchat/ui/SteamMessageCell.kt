package org.steamchat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import org.steamchat.domain.SteamGroupMessage
import org.steamchat.domain.SteamMessage
import org.steamchat.domain.SteamMessageContent
import org.steamchat.domain.parseSteamMessageContent
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.Emoji
import org.telegram.messenger.R
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.LayoutHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One chat message as a "Steam Modern Card": a rounded graphite/Steam-blue bubble that can hold
 * plain text, Steam `:emoticon:` images, or a real inline preview of a Steam-hosted image instead
 * of a 200-character URL (see [SteamMessageContent]).
 *
 * Built fresh against SteamMessage rather than forking Telegram's own 29500-line ChatMessageCell,
 * whose delegates are typed directly on TLRPC.User/TLRPC.Chat - but reusing its primitives
 * (BackupImageView's cached, recycle-safe image pipeline; LayoutHelper; AndroidUtilities).
 */
class SteamMessageCell(context: Context) : FrameLayout(context) {

    private val bubble = LinearLayout(context)
    private val authorAvatar = BackupImageView(context)
    private val authorAvatarDrawable = AvatarDrawable()
    private val authorView = TextView(context)
    private val imageView = BackupImageView(context)
    private val videoNoteView = SteamVideoNoteView(context)
    private val sourceLabel = TextView(context)
    private val textView = TextView(context)
    private val footer = LinearLayout(context)
    private val timeView = TextView(context)
    private val checkView = ImageView(context)

    private var messageText: String? = null
    private var content: SteamMessageContent? = null

    /** Wired by SteamChatFragment, which owns navigation/clipboard - the cell just reports intent. */
    var onLinkTap: ((String) -> Unit)? = null
    var onMessageLongPress: ((String, SteamMessageContent) -> Unit)? = null

    init {
        bubble.orientation = LinearLayout.VERTICAL
        bubble.setPadding(dp(4f), dp(4f), dp(4f), dp(4f))

        authorView.textSize = 12f
        authorView.setPadding(dp(8f), dp(4f), dp(8f), 0)
        bubble.addView(authorView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        imageView.setRoundRadius(dp(14f))
        bubble.addView(imageView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, IMAGE_HEIGHT_DP))

        bubble.addView(videoNoteView, LayoutHelper.createLinear(VIDEO_NOTE_DP, VIDEO_NOTE_DP, Gravity.CENTER_HORIZONTAL))

        // Explicit WRAP_CONTENT, not addView(child): a *vertical* LinearLayout's default params
        // are MATCH_PARENT wide, which made the bubble size to its footer (the timestamp, plus a
        // tick on outgoing) and then squeezed every message to that width - "Хихи хаха" wrapped
        // inside a 113px bubble. Horizontal LinearLayout defaults to wrap, vertical does not.
        sourceLabel.textSize = 12f
        sourceLabel.setPadding(dp(8f), dp(6f), dp(8f), 0)
        bubble.addView(sourceLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        textView.textSize = 16f
        textView.setPadding(dp(8f), dp(6f), dp(8f), 0)
        bubble.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        footer.orientation = LinearLayout.HORIZONTAL
        footer.gravity = Gravity.CENTER_VERTICAL
        timeView.textSize = 11f
        footer.addView(timeView)
        // Single check = "sent". Never a double check: JavaSteam's friend-message protocol carries
        // no "read" signal at all (FriendMsgCallback has sender/entryType/message and nothing
        // else), so a second tick would be a status the app cannot actually know.
        checkView.setImageResource(R.drawable.msg_check_s)
        footer.addView(checkView, LinearLayout.LayoutParams(dp(14f), dp(14f)).apply { leftMargin = dp(4f) })
        bubble.addView(
            footer,
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.END, 8, 2, 8, 4),
        )

        authorAvatar.setRoundRadius(dp(16f))
        authorAvatar.visibility = View.GONE
        addView(authorAvatar, LayoutHelper.createFrame(32, 32f, Gravity.START or Gravity.TOP, 0f, 10f, 0f, 0f))

        addView(bubble, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat()))

        bubble.setOnClickListener {
            val url = (content as? SteamMessageContent.Image)?.url ?: (content as? SteamMessageContent.Link)?.url
            if (url != null) onLinkTap?.invoke(url)
        }
        bubble.setOnLongClickListener {
            val current = messageText
            val currentContent = content
            if (current != null && currentContent != null) onMessageLongPress?.invoke(current, currentContent)
            true
        }
    }

    /**
     * [groupedWithPrevious] collapses the gap under a run of messages from the same author, so a
     * burst reads as one block instead of evenly-spaced strangers.
     */
    fun setMessage(message: SteamMessage, groupedWithPrevious: Boolean, scope: CoroutineScope) {
        messageText = message.text
        val parsed = parseSteamMessageContent(message.text)
        // The marker is an internal on-device format. A peer sending matching text must never make
        // us interpret their input as a local filesystem path.
        val content = if (parsed is SteamMessageContent.LocalVideoNote && !message.isOutgoing) {
            SteamMessageContent.Text(message.text)
        } else {
            parsed
        }
        this.content = content

        authorView.visibility = View.GONE
        authorAvatar.visibility = View.GONE

        setPadding(dp(10f), dp(if (groupedWithPrevious) 2f else 8f), dp(10f), dp(2f))

        val outgoing = message.isOutgoing
        val textColor = if (outgoing) SteamPalette.outgoingText else SteamPalette.incomingText
        val metaColor = if (outgoing) SteamPalette.outgoingMeta else SteamPalette.incomingMeta

        timeView.text = timeFormat.format(Date(message.timestamp))
        timeView.setTextColor(metaColor)
        footer.visibility = View.VISIBLE
        checkView.colorFilter = PorterDuffColorFilter(metaColor, PorterDuff.Mode.SRC_IN)
        checkView.visibility = if (outgoing) View.VISIBLE else View.GONE

        // Every optional view is assigned on every bind - a recycled cell must never keep the
        // previous message's picture or label (RecyclerView reuse).
        when (content) {
            is SteamMessageContent.Image -> {
                videoNoteView.clear()
                videoNoteView.visibility = View.GONE
                imageView.visibility = View.VISIBLE
                imageView.setImage(content.url, IMAGE_SIZE_HINT, ColorDrawable(SteamPalette.separatorSurface))
                sourceLabel.visibility = View.VISIBLE
                sourceLabel.text = content.sourceLabel
                sourceLabel.setTextColor(metaColor)
                textView.visibility = View.GONE
            }
            is SteamMessageContent.Link -> {
                videoNoteView.clear()
                videoNoteView.visibility = View.GONE
                imageView.visibility = View.GONE
                imageView.setImageDrawable(null)
                sourceLabel.visibility = if (content.sourceLabel != null) View.VISIBLE else View.GONE
                sourceLabel.text = content.sourceLabel.orEmpty()
                sourceLabel.setTextColor(metaColor)
                textView.visibility = View.VISIBLE
                textView.textSize = 15f
                textView.setTextColor(textColor)
                // Long URLs wrap inside the card instead of forcing horizontal overflow.
                textView.ellipsize = TextUtils.TruncateAt.MIDDLE
                textView.maxLines = 2
                textView.text = content.url
            }
            is SteamMessageContent.Text -> {
                videoNoteView.clear()
                videoNoteView.visibility = View.GONE
                imageView.visibility = View.GONE
                imageView.setImageDrawable(null)
                sourceLabel.visibility = View.GONE
                textView.visibility = View.VISIBLE
                textView.ellipsize = null
                textView.maxLines = Int.MAX_VALUE
                textView.setTextColor(textColor)
                // Emoji-only messages read bigger, same as the real client. Emoji.fullyConsists-
                // OfEmojis is Telegram's own utility (surrogate pairs make a hand-rolled check
                // unreliable). Steam :emoticon: shortcodes are a separate pass inside
                // setTextWithEmoticons, which swaps each one for its real CDN image.
                textView.textSize = if (Emoji.fullyConsistsOfEmojis(content.text)) 32f else 16f
                textView.setTextWithEmoticons(content.text, scope)
            }
            is SteamMessageContent.LocalVideoNote -> {
                imageView.visibility = View.GONE
                imageView.setImageDrawable(null)
                sourceLabel.visibility = View.GONE
                textView.visibility = View.GONE
                videoNoteView.visibility = View.VISIBLE
                videoNoteView.setVideo(content.path, content.durationMs)
                footer.visibility = View.GONE
            }
        }

        val background = GradientDrawable()
        background.cornerRadius = dp(16f).toFloat()
        background.setColor(
            when {
                content is SteamMessageContent.LocalVideoNote -> 0x00000000
                outgoing -> SteamPalette.outgoingBubble
                else -> SteamPalette.incomingBubble
            },
        )
        bubble.background = background

        val params = bubble.layoutParams as LayoutParams
        params.gravity = if (outgoing) Gravity.END else Gravity.START
        params.leftMargin = 0
        params.rightMargin = 0
        params.width = if (content is SteamMessageContent.Image) bubbleMaxWidth() else LayoutParams.WRAP_CONTENT
        bubble.layoutParams = params

        // Text bubbles stay as wide as their content but never edge-to-edge on a big phone.
        textView.maxWidth = bubbleMaxWidth() - dp(24f)
    }

    /** Same renderer for a group message, with the author's real identity on the first card in a run. */
    fun setGroupMessage(message: SteamGroupMessage, groupedWithPrevious: Boolean, scope: CoroutineScope) {
        val displayText = when {
            message.isDeleted -> "Сообщение удалено"
            message.isSystem && message.text.isBlank() -> "Системное событие"
            else -> message.text
        }
        setMessage(
            SteamMessage(
                id = (message.id.serverTimestamp.toLong() shl 32) xor (message.id.ordinal.toLong() and 0xffffffffL),
                chatPartnerSteamId64 = message.groupId,
                senderSteamId64 = message.senderSteamId64 ?: 0L,
                text = displayText,
                timestamp = message.timestamp,
                isOutgoing = message.isOutgoing,
            ),
            groupedWithPrevious,
            scope,
        )
        if (message.isOutgoing) return

        val author = message.senderName
            ?: message.senderSteamId64?.let { "Steam ID $it" }
            ?: "Steam"
        authorView.text = author
        authorView.setTextColor(SteamPalette.authorAccent)
        authorView.visibility = if (groupedWithPrevious) View.GONE else View.VISIBLE

        val params = bubble.layoutParams as LayoutParams
        params.leftMargin = dp(40f)
        bubble.layoutParams = params

        if (!groupedWithPrevious) {
            authorAvatarDrawable.setInfo(message.senderSteamId64 ?: message.groupId, author, "")
            if (message.senderAvatarUrl != null) {
                authorAvatar.setImage(message.senderAvatarUrl, "50_50", authorAvatarDrawable)
            } else {
                authorAvatar.setImageDrawable(authorAvatarDrawable)
            }
            authorAvatar.visibility = View.VISIBLE
        }
    }

    /** Keeps a bubble off the opposite margin on every screen size (portrait phones, small and large). */
    private fun bubbleMaxWidth(): Int = (resources.displayMetrics.widthPixels * 0.76f).toInt()

    private companion object {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        const val IMAGE_HEIGHT_DP = 170
        const val IMAGE_SIZE_HINT = "560_400"
        const val VIDEO_NOTE_DP = 220
    }
}

/** Small native player clipped to a circle; one tap plays/pauses and the clip loops. */
private class SteamVideoNoteView(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private val texture = TextureView(context)
    private val play = ImageView(context)
    private val duration = TextView(context)
    private val localLabel = TextView(context)
    private val clipPath = Path()
    private var path: String? = null
    private var player: MediaPlayer? = null
    private var prepared = false

    init {
        setWillNotDraw(false)
        texture.surfaceTextureListener = this
        addView(texture, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL))

        play.setImageResource(R.drawable.play_mini_video)
        play.setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        play.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x66000000)
        }
        addView(play, LayoutHelper.createFrame(56, 56, Gravity.CENTER))

        duration.textSize = 11f
        duration.setTextColor(0xFFFFFFFF.toInt())
        duration.setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
        duration.background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            setColor(0x66000000)
        }
        addView(duration, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT.toFloat(), LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0f, 0f, 0f, 10f))

        localLabel.text = "На устройстве"
        localLabel.textSize = 10f
        localLabel.setTextColor(0xFFFFFFFF.toInt())
        localLabel.setPadding(dp(7f), dp(3f), dp(7f), dp(3f))
        localLabel.background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            setColor(0x66000000)
        }
        addView(localLabel, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT.toFloat(), LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0f, 10f, 0f, 0f))

        setOnClickListener {
            if (!prepared) return@setOnClickListener
            val current = player ?: return@setOnClickListener
            if (current.isPlaying) {
                current.pause()
                play.visibility = View.VISIBLE
            } else {
                current.start()
                play.visibility = View.GONE
            }
        }
    }

    fun setVideo(path: String, durationMs: Long) {
        duration.text = "%d:%02d".format(durationMs / 60_000, durationMs / 1_000 % 60)
        if (this.path == path && player != null) return
        this.path = path
        releasePlayer()
        scaleX = 0.78f
        scaleY = 0.78f
        alpha = 0f
        animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260).start()
        if (texture.isAvailable) preparePlayer()
    }

    fun clear() {
        path = null
        releasePlayer()
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        clipPath.rewind()
        clipPath.addCircle(width / 2f, height / 2f, minOf(width, height) / 2f, Path.Direction.CW)
        canvas.clipPath(clipPath)
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    private fun preparePlayer() {
        val videoPath = path ?: return
        val surfaceTexture = texture.surfaceTexture ?: return
        val mediaPlayer = MediaPlayer()
        try {
            mediaPlayer.setDataSource(videoPath)
            val surface = Surface(surfaceTexture)
            mediaPlayer.setSurface(surface)
            surface.release()
            mediaPlayer.isLooping = true
            mediaPlayer.setOnPreparedListener {
                prepared = true
                it.seekTo(1)
                play.visibility = View.VISIBLE
            }
            mediaPlayer.setOnCompletionListener { play.visibility = View.VISIBLE }
            mediaPlayer.setOnErrorListener { _, _, _ ->
                play.visibility = View.VISIBLE
                true
            }
            player = mediaPlayer
            mediaPlayer.prepareAsync()
        } catch (_: Exception) {
            mediaPlayer.release()
        }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        prepared = false
        play.visibility = View.VISIBLE
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = preparePlayer()
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        releasePlayer()
        return true
    }

    override fun onDetachedFromWindow() {
        releasePlayer()
        super.onDetachedFromWindow()
    }
}
