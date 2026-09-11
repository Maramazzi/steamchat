package org.steamchat.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.steamchat.domain.SteamMessage
import org.steamchat.domain.SteamChatChannel
import org.steamchat.domain.SteamChatGroup
import org.steamchat.domain.SteamGroupMessage
import org.steamchat.domain.SteamMessageContent
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Stories.recorder.RoundVideoRecorder
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SteamChatFragment(
    private val friendSteamId64: Long,
    private val friendName: String,
    private val groupId: Long? = null,
    initialGroupChannelId: Long? = null,
) : SteamBaseFragment() {

    private val service = SteamServiceHolder.service
    private val scope = CoroutineScope(Dispatchers.Main)
    private val adapter = MessagesAdapter()
    private var messages: List<SteamMessage> = emptyList()
    private var rows: List<ChatRow> = emptyList()
    private var groupMessages: List<SteamGroupMessage> = emptyList()
    private var groupRows: List<GroupChatRow> = emptyList()
    private var activeGroupChannelId: Long? = initialGroupChannelId
    private var currentGroup: SteamChatGroup? = null
    private var groupMessagesJob: Job? = null
    private var groupHistoryHasMore = true
    private var loadingGroupHistory = false
    private var groupInitialPositioned = false
    private var lastAckedGroupTimestamp = 0
    private lateinit var recyclerView: RecyclerView
    private lateinit var input: EditText
    private lateinit var sendButton: ImageView
    private lateinit var attachButton: ImageView
    private lateinit var emojiButton: ImageView
    private lateinit var recordingRow: LinearLayout
    private lateinit var recordingDot: View
    private lateinit var recordingTimer: TextView
    private lateinit var recordingHint: TextView
    private lateinit var header: SteamChatHeaderView
    private lateinit var groupHeader: SteamGroupChatHeaderView
    private var voiceRecorder: MediaRecorder? = null
    private var voiceFile: File? = null
    private var voiceStartedAt = 0L
    private var roundVideoRecorder: RoundVideoRecorder? = null
    private var recordingOverlay: View? = null
    private var mediaUploadInProgress = false
    private var recordingMode = RecordingMode.VOICE
    private var recordGestureArmed = false
    private var recordGestureStarted = false
    private var recordGestureCancelled = false
    private var recordGestureDownX = 0f
    private var startRecordingRunnable: Runnable? = null
    private var pendingRoundVideoFromGesture = false
    private var recordingPulse: ObjectAnimator? = null
    private var recordingTimerRunnable: Runnable? = null
    private var activeRecordingDot: View? = null

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> finishFragment()
                    MENU_CALL -> showVoiceStatus(context)
                    MENU_SEARCH -> Toast.makeText(context, "Поиск по чату пока в разработке", Toast.LENGTH_SHORT).show()
                    MENU_MORE -> Toast.makeText(context, "Пока в разработке", Toast.LENGTH_SHORT).show()
                }
            }
        })
        // The 1:1 button currently runs the isolated Steam WebRTC call experiment.
        val menu = actionBar.createMenu()
        menu.addItem(MENU_CALL, R.drawable.msg_voice_phone)
        menu.addItem(MENU_SEARCH, R.drawable.msg_search)
        menu.addItem(MENU_MORE, R.drawable.ic_ab_other)

        // 52dp left margin clearing the back button, WRAP_CONTENT/MATCH_PARENT - same convention
        // real Telegram's ChatActivity uses for its own avatarContainer (confirmed in
        // ChatActivity.java: actionBar.addView(avatarContainer, 0, LayoutHelper.createFrame(
        // WRAP_CONTENT, MATCH_PARENT, Gravity.TOP|LEFT, 52, 0, 52, 0))), not guessed.
        // Explicit width, not WRAP_CONTENT: ActionBar measures this view once (while the name is
        // still empty, before render() arrives with real data) and its custom layout doesn't
        // re-measure a manually added child afterwards, so a WRAP_CONTENT column stayed as narrow
        // as the status line and ellipsised the name to "Mara...". Reserve the row between the
        // back button and the three menu icons instead.
        val metrics = context.resources.displayMetrics
        val screenWidthDp = metrics.widthPixels / metrics.density
        val headerWidthDp = (screenWidthDp - HEADER_LEFT_DP - MENU_RESERVE_DP).coerceAtLeast(96f)
        // topMargin = statusBarHeight (raw px, not dp): MATCH_PARENT here spans the ActionBar's
        // full measured height, which on this edge-to-edge layout already includes the status bar
        // (see CLAUDE.md's edge-to-edge grabli). Telegram's own back button/menu icons start below
        // that reserved strip; a plain addView() doesn't, so a 0-top-margin child centers itself
        // against the status bar + content combined and lands visibly higher than they do (same
        // bug confirmed live on the Chats screen's brand row via uiautomator - see
        // SteamDialogsFragment).
        if (groupId == null) {
            header = SteamChatHeaderView(context)
            header.setOnClickListener { presentFragment(SteamProfileFragment(friendSteamId64, friendName)) }
            actionBar.addView(
                header,
                0,
                LayoutHelper.createFrameMarginPx(headerWidthDp.toInt(), LayoutHelper.MATCH_PARENT.toFloat(), Gravity.TOP or Gravity.LEFT, dp(HEADER_LEFT_DP), AndroidUtilities.statusBarHeight, 0, 0),
            )
        } else {
            groupHeader = SteamGroupChatHeaderView(context)
            groupHeader.contentDescription = "Вернуться к каналам группы"
            groupHeader.setOnClickListener { finishFragment() }
            actionBar.addView(
                groupHeader,
                0,
                LayoutHelper.createFrameMarginPx(headerWidthDp.toInt(), LayoutHelper.MATCH_PARENT.toFloat(), Gravity.TOP or Gravity.LEFT, dp(HEADER_LEFT_DP), AndroidUtilities.statusBarHeight, 0, 0),
            )
        }

        val content = LinearLayout(context)
        content.orientation = LinearLayout.VERTICAL
        content.setBackgroundColor(SteamPalette.chatBackground)

        recyclerView = RecyclerView(context)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter
        recyclerView.setPadding(0, dp(8f), 0, dp(8f))
        recyclerView.clipToPadding = false
        if (groupId != null) {
            recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    if (layoutManager.findFirstVisibleItemPosition() <= 4) loadOlderGroupHistory()
                }
            })
        }

        content.addView(recyclerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(buildInputBar(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val root = FrameLayout(context)
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fragmentView = root

        if (groupId == null) {
            scope.launch {
                combine(service.observeCurrentUser(), service.observeFriends()) { current, friendsList ->
                    current?.takeIf { it.steamId64 == friendSteamId64 } ?: friendsList.find { it.steamId64 == friendSteamId64 }
                }.collect { user ->
                    if (user == null) return@collect
                    header.render(user)
                }
            }
            scope.launch {
                messages = service.getMessageHistory(friendSteamId64)
                updateRows()
                service.markAsRead(friendSteamId64)
            }
            scope.launch {
                service.observeMessages(friendSteamId64).collect { incoming ->
                    messages = messages + incoming
                    updateRows()
                }
            }
        } else {
            scope.launch {
                service.observeChatGroups().collect { groups ->
                    val group = groups.firstOrNull { it.id == groupId } ?: return@collect
                    currentGroup = group
                    val channel = group.channels.firstOrNull { it.id == activeGroupChannelId && !it.voiceAllowed }
                        ?: group.channels.firstOrNull { it.id == group.defaultChannelId && !it.voiceAllowed }
                        ?: group.channels.firstOrNull { !it.voiceAllowed }
                        ?: return@collect
                    if (channel.id != activeGroupChannelId) selectGroupChannel(channel.id)
                    groupHeader.render(group, channel)
                    if (::input.isInitialized) input.hint = "Сообщение в #${channel.name.ifBlank { "канал" }}"
                }
            }
            activeGroupChannelId?.let(::selectGroupChannel)
        }

        return root
    }

    private fun updateRows() {
        rows = buildChatRows(messages)
        adapter.notifyDataSetChanged()
        recyclerView.scrollToPosition((rows.size - 1).coerceAtLeast(0))
    }

    private fun selectGroupChannel(channelId: Long) {
        val selectedGroupId = groupId ?: return
        val channel = service.observeChatGroups().value.firstOrNull { it.id == selectedGroupId }
            ?.channels?.firstOrNull { it.id == channelId } ?: return
        if (channel.voiceAllowed) return
        if (activeGroupChannelId == channelId && groupMessagesJob != null) return
        groupMessagesJob?.cancel()
        activeGroupChannelId = channelId
        groupMessages = emptyList()
        groupRows = emptyList()
        groupHistoryHasMore = true
        loadingGroupHistory = true
        groupInitialPositioned = false
        lastAckedGroupTimestamp = 0
        adapter.notifyDataSetChanged()

        currentGroup?.channels?.firstOrNull { it.id == channelId }?.let { channel ->
            groupHeader.render(currentGroup ?: return@let, channel)
            if (::input.isInitialized) input.hint = "Сообщение в #${channel.name.ifBlank { "канал" }}"
        }

        groupMessagesJob = scope.launch {
            launch {
                service.observeGroupMessages(selectedGroupId, channelId).collect { list ->
                    val previousNewest = groupMessages.lastOrNull()?.id
                    val shouldScrollToBottom = (!groupInitialPositioned && list.isNotEmpty()) ||
                        (isNearBottom() && list.lastOrNull()?.id != previousNewest)
                    groupMessages = list
                    updateGroupRows(shouldScrollToBottom)
                    if (list.isNotEmpty()) groupInitialPositioned = true

                    val latestTimestamp = list.lastOrNull()?.id?.serverTimestamp ?: 0
                    if (latestTimestamp > lastAckedGroupTimestamp) {
                        lastAckedGroupTimestamp = latestTimestamp
                        launch { service.markGroupChannelRead(selectedGroupId, channelId) }
                    }
                }
            }
            groupHistoryHasMore = service.loadOlderGroupMessages(selectedGroupId, channelId)
            loadingGroupHistory = false
        }
    }

    private fun loadOlderGroupHistory() {
        val selectedGroupId = groupId ?: return
        val channelId = activeGroupChannelId ?: return
        if (loadingGroupHistory || !groupHistoryHasMore || groupRows.isEmpty()) return
        loadingGroupHistory = true
        scope.launch {
            groupHistoryHasMore = service.loadOlderGroupMessages(selectedGroupId, channelId)
            loadingGroupHistory = false
        }
    }

    private fun updateGroupRows(scrollToBottom: Boolean) {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
        val firstVisible = layoutManager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val anchorId = (groupRows.getOrNull(firstVisible) as? GroupChatRow.Message)?.message?.id
        val anchorOffset = layoutManager?.findViewByPosition(firstVisible)?.top ?: 0

        groupRows = buildGroupChatRows(groupMessages)
        adapter.notifyDataSetChanged()
        when {
            scrollToBottom && groupRows.isNotEmpty() -> recyclerView.scrollToPosition(groupRows.lastIndex)
            anchorId != null -> {
                val newPosition = groupRows.indexOfFirst { row ->
                    row is GroupChatRow.Message && row.message.id == anchorId
                }
                if (newPosition >= 0) layoutManager?.scrollToPositionWithOffset(newPosition, anchorOffset)
            }
        }
    }

    private fun isNearBottom(): Boolean {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return true
        return layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - 3
    }

    private fun showVoiceStatus(context: Context) {
        val group = currentGroup
        val channel = group?.channels?.firstOrNull { it.id == activeGroupChannelId }
        when {
            groupId == null -> showWebRtcProbe(context)
            group == null || channel == null || !channel.voiceAllowed ->
                Toast.makeText(context, "В этом канале голосовой чат отключён", Toast.LENGTH_SHORT).show()
            else -> showVoiceChannelDialog(context, group.id, channel)
        }
    }

    /** Opens the call screen; the screen owns the call from there, including ending it. */
    private fun showWebRtcProbe(context: Context) {
        if (Build.VERSION.SDK_INT >= 23 && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            getParentActivity()?.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
            return
        }
        presentFragment(SteamCallFragment(friendSteamId64, friendName))
    }

    override fun onRequestPermissionsResultFragment(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val activity = getParentActivity() ?: return
        when (requestCode) {
            REQUEST_MICROPHONE -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) showWebRtcProbe(activity)
                else Toast.makeText(activity, "Для голосового звонка нужен доступ к микрофону", Toast.LENGTH_SHORT).show()
            }
            REQUEST_VOICE_MESSAGE -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(activity, "Удерживайте микрофон ещё раз для записи", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "Для голосового сообщения нужен доступ к микрофону", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_ROUND_VIDEO -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    if (pendingRoundVideoFromGesture) {
                        Toast.makeText(activity, "Режим кружка: удерживайте кнопку камеры", Toast.LENGTH_SHORT).show()
                    } else {
                        showRoundVideoRecorder(activity, gestureControlled = false)
                    }
                } else {
                    Toast.makeText(activity, "Для кружка нужны камера и микрофон", Toast.LENGTH_SHORT).show()
                }
                pendingRoundVideoFromGesture = false
            }
        }
    }

    private fun showVoiceChannelDialog(context: Context, groupId: Long, channel: SteamChatChannel) {
        val container = LinearLayout(context)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(24f), dp(4f), dp(24f), dp(4f))

        val note = TextView(context)
        note.text = "Присутствие в голосовом канале без передачи звука — Steam-протокол не отдаёт аудио через этот API."
        note.textSize = 12f
        note.setTextColor(SteamPalette.headerSubtitle)
        container.addView(note)

        val membersLabel = TextView(context)
        membersLabel.text = if (channel.voiceMemberSteamIds.isEmpty()) "Сейчас никого нет в голосе" else "Загрузка..."
        membersLabel.textSize = 14f
        membersLabel.setTextColor(SteamPalette.headerTitle)
        membersLabel.setPadding(0, dp(14f), 0, 0)
        container.addView(membersLabel)

        val myId = service.observeCurrentUser().value?.steamId64
        val amInVoice = myId != null && myId in channel.voiceMemberSteamIds

        AlertDialog.Builder(context)
            .setTitle("Голосовой канал: ${channel.name}")
            .setView(container)
            .setPositiveButton(if (amInVoice) "Покинуть" else "Присоединиться") { _, _ ->
                scope.launch {
                    if (amInVoice) service.leaveChannelVoice(groupId, channel.id) else service.joinChannelVoice(groupId, channel.id)
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()

        if (channel.voiceMemberSteamIds.isNotEmpty()) {
            scope.launch {
                val resolved = service.resolveUsers(channel.voiceMemberSteamIds)
                membersLabel.text = channel.voiceMemberSteamIds.joinToString(separator = "\n") { id ->
                    "• " + (resolved.firstOrNull { it.steamId64 == id }?.personaName ?: "Steam ID $id")
                }
            }
        }
    }

    private fun buildInputBar(context: Context): View {
        val bar = LinearLayout(context)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.BOTTOM
        bar.setBackgroundColor(SteamPalette.inputBarBackground)
        bar.setPadding(dp(6f), dp(6f), dp(6f), dp(6f))

        attachButton = iconButton(context, R.drawable.msg_input_attach2) { showAttachMenu(context) }
        bar.addView(attachButton, LinearLayout.LayoutParams(dp(40f), dp(40f)))

        val pill = FrameLayout(context)
        pill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20f).toFloat()
            setColor(SteamPalette.inputField)
        }

        input = EditText(context)
        input.hint = "Сообщение"
        input.setHintTextColor(SteamPalette.inputHint)
        input.setTextColor(SteamPalette.inputText)
        input.background = null
        input.textSize = 15f
        // Grows with the text instead of scrolling a one-line field, but stops before it eats the
        // message list on a small screen.
        input.maxLines = 5
        input.isSingleLine = false
        input.setPadding(0, dp(8f), 0, dp(8f))
        pill.addView(
            input,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL
                leftMargin = dp(14f)
                rightMargin = dp(40f)
            },
        )

        recordingRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(14f), 0, dp(10f), 0)
        }
        recordingDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(RECORDING_RED)
            }
        }
        recordingTimer = TextView(context).apply {
            text = "0:00"
            textSize = 15f
            setTextColor(SteamPalette.inputText)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        recordingHint = TextView(context).apply {
            text = "← Смахните для отмены"
            textSize = 13f
            setTextColor(SteamPalette.inputHint)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        recordingRow.addView(recordingDot, LinearLayout.LayoutParams(dp(8f), dp(8f)).apply { rightMargin = dp(8f) })
        recordingRow.addView(recordingTimer, LinearLayout.LayoutParams(dp(48f), ViewGroup.LayoutParams.WRAP_CONTENT))
        recordingRow.addView(recordingHint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        pill.addView(recordingRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40f), Gravity.CENTER_VERTICAL))

        emojiButton = iconButton(context, R.drawable.msg_emoji_smiles) {
            // showEmoticonPicker's callback already hands back the fully-formatted insertable
            // text (":name: " for an emoticon, "/Sticker name " for a sticker - two different
            // shapes, decided inside the picker) - insert as-is, don't reformat it here.
            showEmoticonPicker(context, service, scope) { text ->
                val cursor = input.selectionStart.coerceAtLeast(0)
                input.text.insert(cursor, text)
            }
        }
        pill.addView(emojiButton, FrameLayout.LayoutParams(dp(36f), dp(36f)).apply { gravity = Gravity.BOTTOM or Gravity.END })

        bar.addView(
            pill,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(4f)
                rightMargin = dp(4f)
                bottomMargin = dp(2f)
            },
        )

        sendButton = ImageView(context)
        sendButton.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        sendButton.scaleType = ImageView.ScaleType.CENTER
        sendButton.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                input.setText("")
                scope.launch {
                    try {
                        val selectedGroupId = groupId
                        val channelId = activeGroupChannelId
                        if (selectedGroupId != null && channelId != null) {
                            service.sendGroupMessage(selectedGroupId, channelId, text)
                        } else {
                            service.sendMessage(friendSteamId64, text)
                        }
                    } catch (_: Exception) {
                        Toast.makeText(context, "Не удалось отправить сообщение. Попробуйте ещё раз", Toast.LENGTH_SHORT).show()
                        if (input.text.isEmpty()) input.setText(text)
                    }
                }
            }
        }
        sendButton.setOnTouchListener { _, event ->
            if (input.text.isNotBlank() || mediaUploadInProgress) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    recordGestureArmed = true
                    recordGestureStarted = false
                    recordGestureCancelled = false
                    recordGestureDownX = event.rawX
                    startRecordingRunnable = Runnable {
                        if (!recordGestureArmed) return@Runnable
                        recordGestureStarted = true
                        if (recordingMode == RecordingMode.VOICE) {
                            startVoiceRecording(context)
                        } else {
                            requestRoundVideo(context, gestureControlled = true)
                        }
                    }.also { sendButton.postDelayed(it, RECORD_HOLD_MS) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    cancelPendingRecordingStart()
                    if (recordGestureCancelled) {
                        // The move event already cancelled and cleaned up the recorder.
                    } else if (recordGestureStarted) {
                        finishSelectedRecording(send = true)
                    } else {
                        toggleRecordingMode()
                    }
                    recordGestureStarted = false
                    recordGestureCancelled = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelPendingRecordingStart()
                    if (recordGestureStarted) finishSelectedRecording(send = false)
                    recordGestureStarted = false
                    recordGestureCancelled = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val cancelDistance = (fragmentView.width * 0.35f)
                        .coerceAtMost(dp(140f).toFloat())
                        .coerceAtLeast(dp(72f).toFloat())
                    val progress = (1f + (event.rawX - recordGestureDownX) / cancelDistance).coerceIn(0f, 1f)
                    if (voiceRecorder != null) recordingHint.alpha = progress
                    if (recordGestureStarted && progress <= 0f) {
                        cancelPendingRecordingStart()
                        finishSelectedRecording(send = false)
                        recordGestureStarted = false
                        recordGestureCancelled = true
                        Toast.makeText(context, "Запись отменена", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                else -> true
            }
        }
        bar.addView(sendButton, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { bottomMargin = dp(2f) })

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateSendButton()
        })
        updateSendButton()

        return bar
    }

    /** Send is only live when there's something to send - dimmed rather than hidden, so the bar doesn't reflow. */
    private fun updateSendButton() {
        val hasText = input.text.toString().isNotBlank()
        val emptyIcon = if (recordingMode == RecordingMode.VOICE) R.drawable.input_mic else R.drawable.input_video
        sendButton.setImageResource(if (hasText) R.drawable.msg_send else emptyIcon)
        sendButton.contentDescription = when {
            hasText -> "Отправить сообщение"
            recordingMode == RecordingMode.VOICE -> "Голосовое: удерживайте для записи, коснитесь для режима кружка"
            else -> "Кружок: удерживайте для записи, коснитесь для режима голосового"
        }
        sendButton.isEnabled = !mediaUploadInProgress
        sendButton.alpha = if (mediaUploadInProgress) 0.45f else 1f
        sendButton.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (voiceRecorder != null || roundVideoRecorder != null) RECORDING_RED else SteamPalette.accent)
        }
    }

    private fun toggleRecordingMode() {
        recordingMode = if (recordingMode == RecordingMode.VOICE) RecordingMode.ROUND_VIDEO else RecordingMode.VOICE
        if (animationsEnabled()) {
            sendButton.animate().cancel()
            sendButton.scaleX = 0.72f
            sendButton.scaleY = 0.72f
            sendButton.animate().scaleX(1f).scaleY(1f).setDuration(150L).start()
        }
        updateSendButton()
        val label = if (recordingMode == RecordingMode.VOICE) "Голосовое" else "Кружок"
        Toast.makeText(sendButton.context, "$label: удерживайте кнопку для записи", Toast.LENGTH_SHORT).show()
    }

    private fun cancelPendingRecordingStart() {
        recordGestureArmed = false
        startRecordingRunnable?.let(sendButton::removeCallbacks)
        startRecordingRunnable = null
    }

    private fun finishSelectedRecording(send: Boolean) {
        if (voiceRecorder != null) {
            finishVoiceRecording(send)
        } else if (roundVideoRecorder != null) {
            finishRoundVideoRecording(send)
        }
    }

    private fun showVoiceRecordingFeedback() {
        input.visibility = View.INVISIBLE
        emojiButton.visibility = View.GONE
        attachButton.visibility = View.INVISIBLE
        recordingRow.visibility = View.VISIBLE
        recordingHint.alpha = 1f
        recordingHint.text = "← Смахните для отмены"
        recordingRow.animate().cancel()
        if (animationsEnabled()) {
            recordingRow.alpha = 0f
            recordingRow.translationX = dp(12f).toFloat()
            recordingRow.animate().alpha(1f).translationX(0f).setDuration(150L).start()
        } else {
            recordingRow.alpha = 1f
            recordingRow.translationX = 0f
        }
        startRecordingFeedback(recordingDot, recordingTimer) {
            (System.currentTimeMillis() - voiceStartedAt).coerceAtLeast(0L)
        }
    }

    private fun hideVoiceRecordingFeedback() {
        stopRecordingFeedback()
        recordingRow.visibility = View.GONE
        recordingHint.alpha = 1f
        input.visibility = View.VISIBLE
        emojiButton.visibility = View.VISIBLE
        attachButton.visibility = View.VISIBLE
    }

    private fun startRecordingFeedback(dot: View, timer: TextView, elapsed: () -> Long) {
        stopRecordingFeedback()
        activeRecordingDot = dot
        if (animationsEnabled()) {
            recordingPulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.3f, 1f).apply {
                duration = 900L
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
        val update = object : Runnable {
            override fun run() {
                timer.text = formatRecordingTime(elapsed())
                recordingTimerRunnable = this
                sendButton.postDelayed(this, 100L)
            }
        }
        recordingTimerRunnable = update
        sendButton.post(update)
    }

    private fun stopRecordingFeedback() {
        recordingPulse?.cancel()
        recordingPulse = null
        activeRecordingDot?.alpha = 1f
        activeRecordingDot = null
        recordingTimerRunnable?.let(sendButton::removeCallbacks)
        recordingTimerRunnable = null
    }

    private fun animationsEnabled(): Boolean = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()

    private fun iconButton(context: Context, iconRes: Int, onClick: () -> Unit): ImageView {
        val icon = ImageView(context)
        icon.setImageResource(iconRes)
        icon.colorFilter = PorterDuffColorFilter(SteamPalette.inputIcon, PorterDuff.Mode.SRC_IN)
        icon.scaleType = ImageView.ScaleType.CENTER
        icon.isClickable = true
        icon.background = SteamPalette.rowSelector()
        icon.setOnClickListener { onClick() }
        return icon
    }

    private fun showAttachMenu(context: Context) {
        if (mediaUploadInProgress) return
        val actions = arrayOf("Записать кружок", "Выбрать MP4")
        AlertDialog.Builder(context).setItems(actions) { _, which ->
            if (which == 0) requestRoundVideo(context) else pickMp4()
        }.show()
    }

    private fun pickMp4() {
        val activity = getParentActivity() ?: return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/mp4"
        }
        activity.startActivityForResult(intent, REQUEST_PICK_MP4)
    }

    override fun onActivityResultFragment(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_PICK_MP4 || resultCode != Activity.RESULT_OK) return
        val context = getParentActivity() ?: return
        val uri = data?.data ?: return
        scope.launch {
            val file = try {
                withContext(Dispatchers.IO) { copyMp4ToCache(context, uri) }
            } catch (_: Exception) {
                Toast.makeText(context, "Не удалось прочитать MP4 или файл больше 30 МБ", Toast.LENGTH_SHORT).show()
                return@launch
            }
            uploadMedia(file)
        }
    }

    private fun copyMp4ToCache(context: Context, uri: Uri): File {
        val target = File.createTempFile("steamchat_video_", ".mp4", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = inputStream.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= MAX_MEDIA_BYTES) { "MP4 is too large" }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("Unable to open MP4")
            check(target.length() > 0L) { "MP4 is empty" }
            return target
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    private fun startVoiceRecording(context: Context) {
        if (voiceRecorder != null || mediaUploadInProgress) return
        if (Build.VERSION.SDK_INT >= 23 && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            getParentActivity()?.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_VOICE_MESSAGE)
            return
        }
        val file = File.createTempFile("steamchat_voice_", ".mp4", context.cacheDir)
        val recorder = MediaRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioChannels(1)
            recorder.setAudioSamplingRate(44_100)
            recorder.setAudioEncodingBitRate(64_000)
            recorder.setMaxDuration(MAX_RECORDING_MS.toInt())
            recorder.setOutputFile(file.absolutePath)
            recorder.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    sendButton.post { finishVoiceRecording(send = true) }
                }
            }
            recorder.prepare()
            recorder.start()
            voiceRecorder = recorder
            voiceFile = file
            voiceStartedAt = System.currentTimeMillis()
            showVoiceRecordingFeedback()
            updateSendButton()
        } catch (_: Exception) {
            recorder.release()
            file.delete()
            Toast.makeText(context, "Не удалось начать запись", Toast.LENGTH_SHORT).show()
        }
    }

    private fun finishVoiceRecording(send: Boolean) {
        val recorder = voiceRecorder ?: return
        val file = voiceFile
        val duration = System.currentTimeMillis() - voiceStartedAt
        voiceRecorder = null
        voiceFile = null
        var valid = send && duration >= MIN_VOICE_MS
        try {
            recorder.stop()
        } catch (_: RuntimeException) {
            valid = false
        } finally {
            recorder.release()
        }
        hideVoiceRecordingFeedback()
        input.hint = chatInputHint()
        updateSendButton()
        if (valid && file != null) {
            uploadMedia(file)
        } else {
            file?.delete()
            if (send && duration < MIN_VOICE_MS) {
                getParentActivity()?.let { Toast.makeText(it, "Голосовое слишком короткое", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun requestRoundVideo(context: Context, gestureControlled: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 23) {
            val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                pendingRoundVideoFromGesture = gestureControlled
                getParentActivity()?.requestPermissions(missing.toTypedArray(), REQUEST_ROUND_VIDEO)
                return
            }
        }
        showRoundVideoRecorder(context, gestureControlled)
    }

    private fun showRoundVideoRecorder(context: Context, gestureControlled: Boolean) {
        if (isFinished || isFinishing || roundVideoRecorder != null || mediaUploadInProgress) return
        val root = fragmentView as? FrameLayout ?: return
        val overlay = FrameLayout(context).apply { setBackgroundColor(0xcc000000.toInt()) }
        val recorder = object : RoundVideoRecorder(context) {
            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                val side = cameraView.measuredWidth
                val x = (right - left - side) / 2
                val y = (bottom - top - side) / 2 - dp(24f)
                cameraView.layout(x, y, x + side, y + side)
            }
        }
        roundVideoRecorder = recorder
        recordingOverlay = overlay
        recorder.onDone { file, _, _ ->
            clearRoundVideoRecorder(root, overlay)
            uploadMedia(file)
        }
        recorder.onDestroy { clearRoundVideoRecorder(root, overlay) }
        overlay.addView(recorder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val status = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(20f).toFloat()
                setColor(0xCC16202D.toInt())
            }
            setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
        }
        val circleDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(RECORDING_RED)
            }
        }
        val circleTimer = TextView(context).apply {
            text = "0:00"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val circleHint = TextView(context).apply {
            text = if (gestureControlled) "Кружок · отпустите для отправки" else "Идёт запись кружка"
            textSize = 14f
            setTextColor(SteamPalette.headerSubtitle)
        }
        status.addView(circleDot, LinearLayout.LayoutParams(dp(9f), dp(9f)).apply { rightMargin = dp(8f) })
        status.addView(circleTimer, LinearLayout.LayoutParams(dp(48f), ViewGroup.LayoutParams.WRAP_CONTENT))
        status.addView(circleHint)
        overlay.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(28f)
        })
        if (animationsEnabled()) {
            status.alpha = 0f
            status.translationY = -dp(12f).toFloat()
            status.animate().alpha(1f).translationY(0f).setDuration(180L).start()
        }
        startRecordingFeedback(circleDot, circleTimer) { recorder.sinceRecording() }

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val cancel = TextView(context).apply {
            text = "Отмена"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(20f), dp(12f), dp(20f), dp(12f))
            setOnClickListener {
                recorder.cancel()
            }
        }
        val done = TextView(context).apply {
            text = "Отправить"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(20f), dp(12f), dp(20f), dp(12f))
            setOnClickListener { recorder.stop() }
        }
        controls.addView(cancel)
        controls.addView(ImageView(context).apply {
            setImageResource(R.drawable.camera_revert1)
            colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            contentDescription = "Сменить камеру"
            setOnClickListener { recorder.cameraView.switchCamera() }
        }, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        controls.addView(done)
        overlay.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(32f)
        })
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        updateSendButton()
    }

    private fun finishRoundVideoRecording(send: Boolean) {
        val recorder = roundVideoRecorder ?: return
        if (send) recorder.stop() else recorder.cancel()
    }

    private fun clearRoundVideoRecorder(root: FrameLayout, overlay: View) {
        if (recordingOverlay !== overlay) return
        stopRecordingFeedback()
        roundVideoRecorder = null
        recordingOverlay = null
        root.removeView(overlay)
        updateSendButton()
    }

    private fun uploadMedia(file: File) {
        val context = getParentActivity()
        if (context == null) {
            file.delete()
            return
        }
        if (mediaUploadInProgress) {
            file.delete()
            return
        }
        mediaUploadInProgress = true
        updateSendButton()
        input.hint = "Отправка MP4…"
        scope.launch {
            try {
                val selectedGroupId = groupId
                val channelId = activeGroupChannelId
                if (selectedGroupId != null) {
                    service.sendGroupMedia(selectedGroupId, checkNotNull(channelId) { "Group channel is unavailable" }, file.absolutePath)
                } else {
                    service.sendMedia(friendSteamId64, file.absolutePath)
                }
                Toast.makeText(context, "Медиа отправлено", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, "Не удалось отправить MP4", Toast.LENGTH_SHORT).show()
            } finally {
                file.delete()
                mediaUploadInProgress = false
                input.hint = chatInputHint()
                updateSendButton()
            }
        }
    }

    private fun chatInputHint(): String {
        if (groupId == null) return "Сообщение"
        val channel = activeGroupChannelId?.let { channelId ->
            currentGroup?.channels?.firstOrNull { it.id == channelId }
        }
        return "Сообщение в #${channel?.name?.ifBlank { "канал" } ?: "канал"}"
    }

    /** Long-press actions. Plain platform dialog, same choice the profile screen's name-history popup makes. */
    private fun showMessageMenu(context: Context, messageText: String, content: SteamMessageContent) {
        val url = when (content) {
            is SteamMessageContent.Image -> content.url
            is SteamMessageContent.Media -> content.url
            is SteamMessageContent.Link -> content.url
            is SteamMessageContent.Text -> null
        }
        val actions = buildList {
            add("Копировать текст" to { copyToClipboard(context, messageText) })
            if (url != null) {
                add("Открыть ссылку" to { openUrl(context, url) })
                add("Копировать ссылку" to { copyToClipboard(context, url) })
            }
            add("Поделиться" to { shareText(context, messageText) })
        }
        AlertDialog.Builder(context)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SteamChat", text))
        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    private fun openUrl(context: Context, url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Поделиться"))
    }

    override fun onPause() {
        cancelPendingRecordingStart()
        recordGestureStarted = false
        recordGestureCancelled = false
        finishVoiceRecording(send = false)
        roundVideoRecorder?.cancel()
        roundVideoRecorder = null
        (recordingOverlay?.parent as? ViewGroup)?.removeView(recordingOverlay)
        recordingOverlay = null
        if (::recyclerView.isInitialized) {
            for (index in 0 until recyclerView.childCount) {
                (recyclerView.getChildAt(index) as? SteamMessageCell)?.stopPlayback()
            }
        }
        super.onPause()
    }

    override fun onFragmentDestroy() {
        cancelPendingRecordingStart()
        finishVoiceRecording(send = false)
        roundVideoRecorder?.cancel()
        roundVideoRecorder = null
        recordingOverlay = null
        scope.cancel()
        super.onFragmentDestroy()
    }

    private inner class MessagesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int = if (groupId == null) {
            when (rows[position]) {
                is ChatRow.Message -> VIEW_TYPE_MESSAGE
                is ChatRow.DateSeparator -> VIEW_TYPE_DATE
            }
        } else {
            when (groupRows[position]) {
                is GroupChatRow.Message -> VIEW_TYPE_MESSAGE
                is GroupChatRow.DateSeparator -> VIEW_TYPE_DATE
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = if (viewType == VIEW_TYPE_MESSAGE) {
            val cell = SteamMessageCell(parent.context)
            cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            cell.onLinkTap = { url -> openUrl(parent.context, url) }
            cell.onMessageLongPress = { text, content -> showMessageMenu(parent.context, text, content) }
            RowViewHolder(cell)
        } else {
            RowViewHolder(DateSeparatorCell(parent.context))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (groupId == null) {
                when (val row = rows[position]) {
                    is ChatRow.Message ->
                        (holder.itemView as SteamMessageCell).setMessage(row.message, row.groupedWithPrevious, scope)
                    is ChatRow.DateSeparator -> (holder.itemView as DateSeparatorCell).setLabel(row.label)
                }
            } else {
                when (val row = groupRows[position]) {
                    is GroupChatRow.Message ->
                        (holder.itemView as SteamMessageCell).setGroupMessage(row.message, row.groupedWithPrevious, scope)
                    is GroupChatRow.DateSeparator -> (holder.itemView as DateSeparatorCell).setLabel(row.label)
                }
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            (holder.itemView as? SteamMessageCell)?.recycle()
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int = if (groupId == null) rows.size else groupRows.size
    }

    private class RowViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    private enum class RecordingMode { VOICE, ROUND_VIDEO }

    companion object {
        fun forGroup(groupId: Long, channelId: Long): SteamChatFragment =
            SteamChatFragment(friendSteamId64 = 0L, friendName = "", groupId = groupId, initialGroupChannelId = channelId)

        private const val MENU_CALL = 1
        private const val REQUEST_MICROPHONE = 701
        private const val REQUEST_VOICE_MESSAGE = 702
        private const val REQUEST_ROUND_VIDEO = 703
        private const val REQUEST_PICK_MP4 = 704
        private const val MENU_SEARCH = 2
        private const val MENU_MORE = 3
        private const val MAX_MEDIA_BYTES = 30L * 1024 * 1024
        private const val MAX_RECORDING_MS = 59_500L
        private const val MIN_VOICE_MS = 600L
        private const val RECORD_HOLD_MS = 150L
        private const val RECORDING_RED = 0xffe65757.toInt()

        private fun formatRecordingTime(milliseconds: Long): String {
            val seconds = (milliseconds.coerceAtLeast(0L) / 1_000L).toInt()
            return "%d:%02d".format(Locale.US, seconds / 60, seconds % 60)
        }

        /** Same 52dp back-button clearance real ChatActivity gives its own avatarContainer. */
        private const val HEADER_LEFT_DP = 52f

        /** Room kept clear for the three ActionBar icons (call/search/menu) on the right. */
        private const val MENU_RESERVE_DP = 152f
        private const val VIEW_TYPE_MESSAGE = 0
        private const val VIEW_TYPE_DATE = 1
    }
}

private sealed interface ChatRow {
    /** [groupedWithPrevious] = same author, same day, close in time - the cell tightens its top gap. */
    data class Message(val message: SteamMessage, val groupedWithPrevious: Boolean) : ChatRow
    data class DateSeparator(val label: String) : ChatRow
}

private sealed interface GroupChatRow {
    data class Message(val message: SteamGroupMessage, val groupedWithPrevious: Boolean) : GroupChatRow
    data class DateSeparator(val label: String) : GroupChatRow
}

/**
 * Flattens messages into rows, inserting a date pill whenever the calendar day changes and marking
 * runs by the same author so a burst reads as one block instead of evenly-spaced strangers. A gap
 * longer than [GROUP_WINDOW_MS] breaks the run even for the same author, so "morning" and "evening"
 * messages don't get glued together just because nobody else spoke in between.
 */
private fun buildChatRows(messages: List<SteamMessage>): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    val lastDay = Calendar.getInstance()
    val current = Calendar.getInstance()
    var haveLastDay = false
    var previous: SteamMessage? = null
    for (message in messages) {
        current.timeInMillis = message.timestamp
        val isNewDay = !haveLastDay ||
            current.get(Calendar.YEAR) != lastDay.get(Calendar.YEAR) ||
            current.get(Calendar.DAY_OF_YEAR) != lastDay.get(Calendar.DAY_OF_YEAR)
        if (isNewDay) {
            rows += ChatRow.DateSeparator(dateChipLabel(message.timestamp))
            lastDay.timeInMillis = message.timestamp
            haveLastDay = true
        }
        val prev = previous
        val grouped = !isNewDay &&
            prev != null &&
            prev.isOutgoing == message.isOutgoing &&
            message.timestamp - prev.timestamp <= GROUP_WINDOW_MS
        rows += ChatRow.Message(message, grouped)
        previous = message
    }
    return rows
}

private fun buildGroupChatRows(messages: List<SteamGroupMessage>): List<GroupChatRow> {
    val rows = mutableListOf<GroupChatRow>()
    val lastDay = Calendar.getInstance()
    val current = Calendar.getInstance()
    var haveLastDay = false
    var previous: SteamGroupMessage? = null
    for (message in messages) {
        current.timeInMillis = message.timestamp
        val isNewDay = !haveLastDay ||
            current.get(Calendar.YEAR) != lastDay.get(Calendar.YEAR) ||
            current.get(Calendar.DAY_OF_YEAR) != lastDay.get(Calendar.DAY_OF_YEAR)
        if (isNewDay) {
            rows += GroupChatRow.DateSeparator(dateChipLabel(message.timestamp))
            lastDay.timeInMillis = message.timestamp
            haveLastDay = true
        }
        val prev = previous
        val grouped = !isNewDay &&
            prev != null &&
            prev.senderSteamId64 == message.senderSteamId64 &&
            prev.isOutgoing == message.isOutgoing &&
            prev.isSystem == message.isSystem &&
            message.timestamp - prev.timestamp <= GROUP_WINDOW_MS
        rows += GroupChatRow.Message(message, grouped)
        previous = message
    }
    return rows
}

private const val GROUP_WINDOW_MS = 5 * 60 * 1000L

private fun dateChipLabel(timestamp: Long): String {
    val target = Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay(target, today) -> "Сегодня"
        sameDay(target, yesterday) -> "Вчера"
        else -> SimpleDateFormat("d MMMM", Locale("ru")).format(Date(timestamp))
    }
}

/** Rounded pill chip, centered - "Сегодня"/"Вчера"/date between messages from different days. */
private class DateSeparatorCell(context: Context) : FrameLayout(context) {
    private val label = TextView(context)

    init {
        // Small, dark and quiet: it separates days without competing with the bubbles around it.
        label.textSize = 11f
        label.setTextColor(SteamPalette.separatorText)
        label.setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
        label.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(11f).toFloat()
            setColor(SteamPalette.separatorSurface)
        }
        label.alpha = 0.95f
        addView(label, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))
        setPadding(0, dp(10f), 0, dp(6f))
    }

    fun setLabel(text: String) {
        label.text = text
    }
}
