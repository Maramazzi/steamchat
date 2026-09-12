package org.steamchat.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.Surface
import android.text.TextUtils
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.steamchat.domain.SteamGroupMessage
import org.steamchat.domain.SteamMessage
import org.steamchat.domain.SteamMessageContent
import org.steamchat.domain.SteamMediaKind
import org.steamchat.domain.classifySteamMedia
import org.steamchat.domain.parseSteamMessageContent
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.Emoji
import org.telegram.messenger.R
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.LayoutHelper
import java.text.SimpleDateFormat
import java.net.HttpURLConnection
import java.net.URL
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
    private val mediaContainer = FrameLayout(context)
    private val mediaThumbnail = BackupImageView(context)
    private val mediaVideo = TextureView(context)
    private val mediaProgress = ProgressBar(context)
    private val mediaPlay = ImageView(context)
    private val audioRow = LinearLayout(context)
    private val audioPlay = ImageView(context)
    private val audioProgress = SeekBar(context)
    private val audioElapsedTime = TextView(context)
    private val audioTotalTime = TextView(context)
    private val sourceLabel = TextView(context)
    private val textView = TextView(context)
    private val footer = LinearLayout(context)
    private val timeView = TextView(context)
    private val checkView = ImageView(context)

    private var messageText: String? = null
    private var content: SteamMessageContent? = null
    private var mediaJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoPlayer: MediaPlayer? = null
    private var playerPrepared = false
    private var videoPrepared = false
    private var pendingVideoUrl: String? = null
    private var mediaUrl: String? = null
    private var bindToken = 0
    private val audioProgressRunnable = Runnable { updateAudioProgress() }

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

        mediaThumbnail.setRoundRadius(dp(VIDEO_CORNER_DP))
        mediaContainer.addView(mediaThumbnail, LayoutHelper.createFrame(VIDEO_SIZE_DP, VIDEO_SIZE_DP.toFloat()))
        mediaVideo.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, dp(VIDEO_CORNER_DP).toFloat())
        }
        mediaVideo.clipToOutline = true
        mediaThumbnail.outlineProvider = mediaVideo.outlineProvider
        mediaThumbnail.clipToOutline = true
        mediaVideo.visibility = View.GONE
        mediaContainer.addView(mediaVideo, LayoutHelper.createFrame(VIDEO_SIZE_DP, VIDEO_SIZE_DP.toFloat()))
        mediaProgress.visibility = View.GONE
        mediaContainer.addView(mediaProgress, LayoutHelper.createFrame(48, 48, Gravity.CENTER))
        mediaPlay.setImageResource(R.drawable.msg_round_play_m)
        mediaPlay.setColorFilter(SteamPalette.incomingText)
        mediaPlay.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x99000000.toInt())
        }
        mediaPlay.setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        mediaContainer.addView(mediaPlay, LayoutHelper.createFrame(52, 52, Gravity.CENTER))
        bubble.addView(mediaContainer, LayoutHelper.createLinear(VIDEO_SIZE_DP, VIDEO_SIZE_DP))

        audioRow.gravity = Gravity.CENTER_VERTICAL
        audioRow.minimumWidth = dp(230f)
        audioRow.setPadding(dp(6f), dp(7f), dp(12f), dp(4f))
        audioPlay.setImageResource(R.drawable.msg_round_play_m)
        audioPlay.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x22ffffff)
        }
        audioPlay.setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        audioPlay.contentDescription = "Воспроизвести голосовое сообщение"
        audioRow.addView(audioPlay, LinearLayout.LayoutParams(dp(48f), dp(48f)))

        val audioDetails = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), 0, 0, 0)
        }
        audioProgress.max = AUDIO_PROGRESS_MAX
        audioProgress.progress = 0
        audioProgress.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        audioDetails.addView(audioProgress, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val timeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        audioElapsedTime.textSize = 12f
        audioElapsedTime.text = "0:00"
        timeRow.addView(audioElapsedTime, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        timeRow.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        audioTotalTime.textSize = 12f
        audioTotalTime.text = "0:00"
        timeRow.addView(audioTotalTime, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        audioDetails.addView(timeRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2f)
        })
        audioRow.addView(audioDetails, LinearLayout.LayoutParams(dp(180f), LayoutParams.WRAP_CONTENT))
        bubble.addView(audioRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        audioPlay.setOnClickListener { toggleAudio() }
        audioProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = runCatching { mediaPlayer?.duration }.getOrNull()?.coerceAtLeast(0) ?: return
                val position = progress.toLong() * duration / AUDIO_PROGRESS_MAX
                audioElapsedTime.text = formatDuration(position)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                audioProgress.removeCallbacks(audioProgressRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val player = mediaPlayer
                val duration = if (playerPrepared) runCatching { player?.duration }.getOrNull()?.coerceAtLeast(0) else null
                if (player == null || duration == null) {
                    audioProgress.progress = 0
                    return
                }
                val position = (seekBar.progress.toLong() * duration / AUDIO_PROGRESS_MAX).toInt()
                runCatching { player.seekTo(position) }
                updateAudioProgress()
            }
        })
        mediaContainer.setOnClickListener { toggleVideo() }
        mediaVideo.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                pendingVideoUrl?.let { startVideo(it, surfaceTexture) }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stopVideoPlayback()
                return true
            }
        }

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
            val url = when (val current = content) {
                is SteamMessageContent.Image -> current.url
                is SteamMessageContent.Link -> current.url
                is SteamMessageContent.Media, is SteamMessageContent.Text, null -> null
            }
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
        releaseMedia()
        bindToken++
        messageText = message.text
        val content = parseSteamMessageContent(message.text)
        this.content = content

        authorView.visibility = View.GONE
        authorAvatar.visibility = View.GONE

        setPadding(dp(10f), dp(if (groupedWithPrevious) 2f else 8f), dp(10f), dp(2f))

        val outgoing = message.isOutgoing
        val textColor = if (outgoing) SteamPalette.outgoingText else SteamPalette.incomingText
        val metaColor = if (outgoing) SteamPalette.outgoingMeta else SteamPalette.incomingMeta

        timeView.text = timeFormat.format(Date(message.timestamp))
        timeView.setTextColor(metaColor)
        checkView.colorFilter = PorterDuffColorFilter(metaColor, PorterDuff.Mode.SRC_IN)
        checkView.visibility = if (outgoing) View.VISIBLE else View.GONE

        // Every optional view is assigned on every bind - a recycled cell must never keep the
        // previous message's picture or label (RecyclerView reuse).
        when (content) {
            is SteamMessageContent.Image -> {
                hideMediaViews()
                imageView.visibility = View.VISIBLE
                imageView.setImage(content.url, IMAGE_SIZE_HINT, ColorDrawable(SteamPalette.separatorSurface))
                sourceLabel.visibility = View.VISIBLE
                sourceLabel.text = content.sourceLabel
                sourceLabel.setTextColor(metaColor)
                textView.visibility = View.GONE
            }
            is SteamMessageContent.Media -> {
                imageView.visibility = View.GONE
                imageView.setImageDrawable(null)
                sourceLabel.visibility = View.GONE
                textView.visibility = View.GONE
                showMedia(content.url, scope, bindToken, outgoing, textColor, metaColor)
            }
            is SteamMessageContent.Link -> {
                hideMediaViews()
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
                hideMediaViews()
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
        }

        setBubbleBackground(outgoing)

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

    /** Called by the RecyclerView adapter before a cell leaves the screen. */
    fun recycle() {
        bindToken++
        releaseMedia()
        mediaThumbnail.setImageDrawable(null)
        imageView.setImageDrawable(null)
    }

    private fun showMedia(
        url: String,
        scope: CoroutineScope,
        token: Int,
        outgoing: Boolean,
        textColor: Int,
        metaColor: Int,
    ) {
        mediaUrl = url
        mediaContainer.visibility = View.GONE
        audioRow.visibility = View.GONE
        imageView.visibility = View.GONE
        sourceLabel.visibility = View.VISIBLE
        sourceLabel.text = "Загрузка медиа…"
        sourceLabel.setTextColor(metaColor)
        mediaThumbnail.setImageDrawable(null)
        mediaThumbnail.background = ColorDrawable(SteamPalette.separatorSurface)
        mediaVideo.visibility = View.GONE
        mediaProgress.visibility = View.GONE
        mediaPlay.setColorFilter(textColor)
        mediaPlay.setImageResource(R.drawable.msg_round_play_m)
        mediaPlay.visibility = View.VISIBLE
        mediaPlay.alpha = 0.55f
        audioPlay.setColorFilter(textColor)
        audioProgress.progressTintList = ColorStateList.valueOf(textColor)
        audioProgress.progressBackgroundTintList = ColorStateList.valueOf(metaColor)
        audioProgress.thumbTintList = ColorStateList.valueOf(textColor)
        audioProgress.progress = 0
        audioElapsedTime.setTextColor(metaColor)
        audioTotalTime.setTextColor(metaColor)
        audioElapsedTime.text = "0:00"
        audioTotalTime.text = "0:00"
        textView.visibility = View.GONE

        mediaJob = scope.launch {
            val info = withContext(Dispatchers.IO) { readMediaInfo(url) }
            if (token != bindToken || mediaUrl != url) return@launch
            mediaPlay.alpha = 1f
            when (info.kind) {
                SteamMediaKind.IMAGE -> {
                    stopVideoPlayback()
                    mediaContainer.visibility = View.GONE
                    textView.visibility = View.GONE
                    imageView.visibility = View.VISIBLE
                    imageView.setImage(url, IMAGE_SIZE_HINT, ColorDrawable(SteamPalette.separatorSurface))
                    sourceLabel.visibility = View.VISIBLE
                    sourceLabel.text = "Steam Community"
                    sourceLabel.setTextColor(metaColor)
                    (bubble.layoutParams as LayoutParams).also {
                        it.width = bubbleMaxWidth()
                        bubble.layoutParams = it
                    }
                }
                SteamMediaKind.VOICE -> {
                    stopVideoPlayback()
                    mediaContainer.visibility = View.GONE
                    sourceLabel.visibility = View.GONE
                    textView.visibility = View.GONE
                    audioRow.visibility = View.VISIBLE
                    audioTotalTime.text = formatDuration(info.durationMs)
                    setBubbleBackground(outgoing)
                }
                SteamMediaKind.ROUND_VIDEO -> {
                    textView.visibility = View.GONE
                    mediaContainer.visibility = View.VISIBLE
                    sourceLabel.visibility = View.GONE
                    info.frame?.let(mediaThumbnail::setImageBitmap)
                    bubble.background = null
                }
                SteamMediaKind.UNKNOWN -> {
                    sourceLabel.text = "Медиа"
                }
            }
        }
    }

    private fun hideMediaViews() {
        mediaUrl = null
        stopVideoPlayback()
        mediaContainer.visibility = View.GONE
        audioRow.visibility = View.GONE
        mediaThumbnail.setImageDrawable(null)
    }

    private fun toggleAudio() {
        val url = mediaUrl ?: return
        val token = bindToken
        val player = mediaPlayer
        if (playerPrepared && player != null) {
            runCatching {
                if (player.isPlaying) {
                    player.pause()
                    audioPlay.setImageResource(R.drawable.msg_round_play_m)
                    audioPlay.contentDescription = "Воспроизвести голосовое сообщение"
                    audioProgress.removeCallbacks(audioProgressRunnable)
                    updateAudioProgress()
                } else {
                    player.start()
                    audioPlay.setImageResource(R.drawable.msg_round_pause_m)
                    audioPlay.contentDescription = "Приостановить голосовое сообщение"
                    updateAudioProgress()
                }
            }.onFailure {
                playbackFailed(url, token, player)
            }
            return
        }
        releasePlayer()
        audioPlay.isEnabled = false
        val newPlayer = MediaPlayer()
        mediaPlayer = newPlayer
        runCatching {
            newPlayer.apply {
            setDataSource(url)
            setOnPreparedListener {
                if (mediaPlayer !== it || token != bindToken || mediaUrl != url) return@setOnPreparedListener
                playerPrepared = true
                audioPlay.isEnabled = true
                audioTotalTime.text = formatDuration(it.duration.toLong())
                it.start()
                audioPlay.setImageResource(R.drawable.msg_round_pause_m)
                audioPlay.contentDescription = "Приостановить голосовое сообщение"
                updateAudioProgress()
            }
            setOnCompletionListener {
                if (mediaPlayer !== it || token != bindToken || mediaUrl != url) return@setOnCompletionListener
                audioProgress.removeCallbacks(audioProgressRunnable)
                runCatching { it.seekTo(0) }
                audioPlay.setImageResource(R.drawable.msg_round_play_m)
                audioPlay.contentDescription = "Воспроизвести голосовое сообщение"
                audioProgress.progress = 0
                audioElapsedTime.text = "0:00"
                audioTotalTime.text = formatDuration(it.duration.toLong())
            }
            setOnErrorListener { failed, _, _ ->
                playbackFailed(url, token, failed)
                true
            }
            prepareAsync()
            }
        }.onFailure {
            playbackFailed(url, token, newPlayer)
        }
    }

    private fun playbackFailed(url: String, token: Int, player: MediaPlayer) {
        if (mediaPlayer !== player || token != bindToken || mediaUrl != url) return
        releasePlayer()
        showPlaybackError()
    }

    private fun updateAudioProgress() {
        val player = mediaPlayer ?: return
        if (!playerPrepared) return
        val duration = runCatching { player.duration }.getOrDefault(0).coerceAtLeast(0)
        val position = runCatching { player.currentPosition }.getOrDefault(0).coerceAtLeast(0)
        audioProgress.progress = if (duration == 0) 0 else (position.toLong() * AUDIO_PROGRESS_MAX / duration).toInt()
        audioElapsedTime.text = formatDuration(position.toLong())
        audioTotalTime.text = formatDuration(duration.toLong())
        if (runCatching { player.isPlaying }.getOrDefault(false)) {
            audioProgress.postDelayed(audioProgressRunnable, AUDIO_PROGRESS_TICK_MS)
        }
    }

    private fun setBubbleBackground(outgoing: Boolean) {
        bubble.background = GradientDrawable().apply {
            cornerRadius = dp(16f).toFloat()
            setColor(if (outgoing) SteamPalette.outgoingBubble else SteamPalette.incomingBubble)
        }
    }

    private fun toggleVideo() {
        val url = mediaUrl ?: return
        val player = videoPlayer
        if (videoPrepared && player != null) {
            runCatching {
                if (player.isPlaying) {
                    player.pause()
                    mediaPlay.setImageResource(R.drawable.msg_round_play_m)
                    mediaPlay.visibility = View.VISIBLE
                } else {
                    player.start()
                    mediaPlay.visibility = View.GONE
                }
            }.onFailure { videoPlaybackFailed(url, bindToken, player) }
            return
        }

        releasePlayer()
        stopVideoPlayback()
        pendingVideoUrl = url
        mediaVideo.visibility = View.VISIBLE
        mediaPlay.visibility = View.GONE
        mediaProgress.visibility = View.VISIBLE
        mediaVideo.surfaceTexture?.let { startVideo(url, it) }
    }

    private fun startVideo(url: String, surfaceTexture: SurfaceTexture) {
        if (pendingVideoUrl != url || mediaUrl != url || videoPlayer != null) return
        val token = bindToken
        val surface = Surface(surfaceTexture)
        val player = MediaPlayer()
        videoPlayer = player
        runCatching {
            player.apply {
                setDataSource(url)
                setSurface(surface)
                isLooping = true
                setOnPreparedListener {
                    if (videoPlayer !== it || token != bindToken || mediaUrl != url) return@setOnPreparedListener
                    videoPrepared = true
                    pendingVideoUrl = null
                    it.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                    mediaProgress.visibility = View.GONE
                    mediaPlay.visibility = View.GONE
                    it.start()
                }
                setOnErrorListener { failed, _, _ ->
                    videoPlaybackFailed(url, token, failed)
                    true
                }
                prepareAsync()
            }
        }.onFailure { videoPlaybackFailed(url, token, player) }
        surface.release()
    }

    private fun videoPlaybackFailed(url: String, token: Int, player: MediaPlayer) {
        if (videoPlayer !== player || token != bindToken || mediaUrl != url) return
        stopVideoPlayback()
        showPlaybackError()
    }

    private fun showPlaybackError() =
        Toast.makeText(context, "Не удалось воспроизвести медиа", Toast.LENGTH_SHORT).show()

    private fun releaseMedia() {
        mediaJob?.cancel()
        mediaJob = null
        stopPlayback()
    }

    fun stopPlayback() {
        releasePlayer()
        stopVideoPlayback()
    }

    override fun onDetachedFromWindow() {
        stopPlayback()
        super.onDetachedFromWindow()
    }

    private fun releasePlayer() {
        audioProgress.removeCallbacks(audioProgressRunnable)
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        playerPrepared = false
        audioPlay.isEnabled = true
        audioPlay.setImageResource(R.drawable.msg_round_play_m)
        audioPlay.contentDescription = "Воспроизвести голосовое сообщение"
        audioProgress.progress = 0
    }

    private fun stopVideoPlayback() {
        releaseVideoPlayer()
        pendingVideoUrl = null
        videoPrepared = false
        mediaVideo.visibility = View.GONE
        mediaProgress.visibility = View.GONE
        mediaPlay.setImageResource(R.drawable.msg_round_play_m)
        mediaPlay.visibility = View.VISIBLE
    }

    private fun releaseVideoPlayer() {
        runCatching { videoPlayer?.release() }
        videoPlayer = null
    }

    private data class MediaInfo(val kind: SteamMediaKind, val durationMs: Long = 0L, val frame: Bitmap? = null)

    private companion object {
        fun readMediaInfo(url: String): MediaInfo {
            val contentType = readContentType(url)
            if (contentType?.startsWith("image/") == true) return MediaInfo(SteamMediaKind.IMAGE)

            val metadata = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(url, emptyMap())
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                    val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                    MediaInfo(
                        classifySteamMedia(contentType, hasVideo, hasAudio),
                        duration,
                        if (hasVideo) retriever.getFrameAtTime(0) else null,
                    )
            } finally {
                retriever.release()
            }
            }.getOrNull()
            return metadata ?: MediaInfo(classifySteamMedia(contentType, hasVideo = false, hasAudio = false))
        }

        private fun readContentType(url: String): String? = runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "HEAD"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                if (connection.responseCode !in 200..299) return@runCatching null
                connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.US)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

        fun formatDuration(milliseconds: Long): String {
            val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
            return "%d:%02d".format(Locale.US, totalSeconds / 60, totalSeconds % 60)
        }

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        const val IMAGE_HEIGHT_DP = 170
        const val IMAGE_SIZE_HINT = "560_400"
        const val VIDEO_SIZE_DP = 180
        const val VIDEO_CORNER_DP = 24f
        const val AUDIO_PROGRESS_MAX = 1_000
        const val AUDIO_PROGRESS_TICK_MS = 200L
    }
}
