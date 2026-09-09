package org.steamchat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.steamchat.domain.SteamUser
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper
import java.util.Locale

/**
 * The voice call screen: avatar, who and for how long, and the three things you actually do during
 * a call. Built as a fragment on the same primitives as the rest of org.steamchat.ui rather than
 * forking Telegram's VoIPFragment, which is written against its own TLRPC call objects.
 *
 * Leaving this screen ends the call - there is no minimised-call surface to hand it over to, so
 * pretending it survives in the background would be a lie. [SteamWebRtcProbe] is released by the
 * scope dying with the fragment.
 */
class SteamCallFragment(
    private val partnerSteamId64: Long,
    private val partnerName: String,
    private val incomingVoiceChatId: Long? = null,
) : SteamBaseFragment() {

    private val service = SteamServiceHolder.service
    private val scope = CoroutineScope(Dispatchers.Main)
    private val avatarDrawable = AvatarDrawable()

    private lateinit var avatarImage: BackupImageView
    private lateinit var statusDot: View
    private lateinit var presenceView: TextView
    private lateinit var stateView: TextView
    private lateinit var microphoneIcon: ImageView
    private lateinit var microphoneLabel: TextView

    private var callJob: Job? = null
    private var connectedAt = 0L
    private var lastReport = "Соединение…"
    private val tick = object : Runnable {
        override fun run() {
            if (connectedAt == 0L) return
            val seconds = (System.currentTimeMillis() - connectedAt) / 1000L
            stateView.text = String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)
            AndroidUtilities.runOnUIThread(this, 1000L)
        }
    }

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setCastShadows(false)
        actionBar.setBackgroundColor(SteamPalette.callBackground)
        actionBar.setItemsColor(SteamPalette.headerTitle, false)
        actionBar.createMenu().addItem(MENU_DETAILS, R.drawable.ic_ab_other)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> finishFragment()
                    // The diagnostics that found the silent-audio bug stay reachable, just out of
                    // the way: a call screen is no place for RTP counters, but losing them would
                    // mean rebuilding them the next time audio misbehaves.
                    MENU_DETAILS -> AlertDialog.Builder(context)
                        .setTitle("Состояние звонка")
                        .setMessage(lastReport)
                        .setPositiveButton("Закрыть", null)
                        .show()
                }
            }
        })

        val root = FrameLayout(context)
        root.setBackgroundColor(SteamPalette.callBackground)

        root.addView(buildIdentity(context), LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0f, 96f, 0f, 0f,
        ))
        root.addView(buildControls(context), LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM,
        ))

        fragmentView = root
        observePartner()
        startCall(context)
        return root
    }

    private fun buildIdentity(context: Context): View {
        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER_HORIZONTAL

        val avatarBox = FrameLayout(context)
        avatarBox.addView(RingView(context), LayoutHelper.createFrame(AVATAR_BOX_DP, AVATAR_BOX_DP.toFloat()))
        avatarImage = BackupImageView(context)
        avatarImage.setRoundRadius(dp(AVATAR_DP / 2f))
        avatarBox.addView(avatarImage, LayoutHelper.createFrame(AVATAR_DP, AVATAR_DP, Gravity.CENTER))
        statusDot = View(context)
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(SteamPalette.presenceOnline)
            setStroke(dp(3f), SteamPalette.callBackground)
        }
        statusDot.visibility = View.GONE
        avatarBox.addView(statusDot, LayoutHelper.createFrame(22, 22f, Gravity.BOTTOM or Gravity.END, 0f, 0f, 12f, 12f))
        column.addView(avatarBox, LayoutHelper.createLinear(AVATAR_BOX_DP, AVATAR_BOX_DP))

        val nameView = TextView(context)
        nameView.text = partnerName
        nameView.textSize = 26f
        nameView.setTextColor(SteamPalette.headerTitle)
        nameView.typeface = AndroidUtilities.bold()
        column.addView(nameView, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 20, 0, 0,
        ))

        presenceView = TextView(context)
        presenceView.textSize = 15f
        presenceView.setTextColor(SteamPalette.presenceOnline)
        column.addView(presenceView, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 6, 0, 0,
        ))

        val kindView = TextView(context)
        kindView.text = "Голосовой звонок"
        kindView.textSize = 14f
        kindView.setTextColor(SteamPalette.headerSubtitle)
        column.addView(kindView, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 26, 0, 0,
        ))

        stateView = TextView(context)
        stateView.text = lastReport
        stateView.textSize = 14f
        stateView.setTextColor(SteamPalette.headerSubtitle)
        column.addView(stateView, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0,
        ))
        return column
    }

    private fun buildControls(context: Context): View {
        val panel = LinearLayout(context)
        panel.orientation = LinearLayout.VERTICAL
        panel.gravity = Gravity.CENTER_HORIZONTAL
        panel.background = GradientDrawable().apply {
            setColor(SteamPalette.callPanel)
            cornerRadii = floatArrayOf(
                dp(24f).toFloat(), dp(24f).toFloat(), dp(24f).toFloat(), dp(24f).toFloat(), 0f, 0f, 0f, 0f,
            )
        }
        panel.setPadding(dp(16f), dp(10f), dp(16f), dp(28f))

        val handle = View(context)
        handle.background = GradientDrawable().apply {
            cornerRadius = dp(2f).toFloat()
            setColor(SteamPalette.callHandle)
        }
        panel.addView(handle, LayoutHelper.createLinear(36, 4, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 18))

        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        val microphone = buildControl(context, R.drawable.msg_voice_muted, "Выкл. звук") { toggleMicrophone(context) }
        microphoneIcon = microphone.first
        microphoneLabel = microphone.second
        row.addView(microphone.third, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14, 0,
        ))
        row.addView(
            buildControl(context, R.drawable.calls_speaker, "Динамик") { SteamWebRtcProbe.toggleSpeaker() }.third,
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 14, 0),
        )
        row.addView(
            buildControl(context, R.drawable.msg_voicechat2, "Чат") { openChat() }.third,
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0, 0),
        )
        panel.addView(row, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        val hangup = ImageView(context)
        hangup.setImageResource(R.drawable.calls_decline)
        hangup.scaleType = ImageView.ScaleType.CENTER
        hangup.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(SteamPalette.callHangup)
        }
        hangup.setOnClickListener { finishFragment() }
        panel.addView(hangup, LayoutHelper.createLinear(HANGUP_DP, HANGUP_DP, Gravity.CENTER_HORIZONTAL, 0, 24, 0, 0))
        return panel
    }

    /** Icon, label and the column holding them - the caller keeps the first two to retitle a toggle. */
    private fun buildControl(
        context: Context,
        icon: Int,
        label: String,
        onClick: () -> Unit,
    ): Triple<ImageView, TextView, View> {
        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER_HORIZONTAL

        val button = ImageView(context)
        button.setImageResource(icon)
        button.scaleType = ImageView.ScaleType.CENTER
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(SteamPalette.callControl)
        }
        button.setOnClickListener { onClick() }
        column.addView(button, LayoutHelper.createLinear(CONTROL_DP, CONTROL_DP))

        val caption = TextView(context)
        caption.text = label
        caption.textSize = 12f
        caption.setTextColor(SteamPalette.headerSubtitle)
        column.addView(caption, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0,
        ))
        return Triple(button, caption, column)
    }

    private fun toggleMicrophone(context: Context) {
        val enabled = SteamWebRtcProbe.toggleMicrophone()
        if (enabled == null) return
        // The icon shows what the button does next, so it matches its own caption.
        microphoneIcon.setImageResource(if (enabled) R.drawable.msg_voice_muted else R.drawable.msg_voice_unmuted)
        microphoneLabel.text = if (enabled) "Выкл. звук" else "Вкл. звук"
    }

    /** The call keeps running: this screen stays in the stack underneath the chat. */
    private fun openChat() = presentFragment(SteamChatFragment(partnerSteamId64, partnerName))

    private fun observePartner() {
        scope.launch {
            service.observeFriends().collect { friends ->
                val partner = friends.firstOrNull { it.steamId64 == partnerSteamId64 } ?: return@collect
                applyPartner(partner)
            }
        }
    }

    private fun applyPartner(partner: SteamUser) {
        val presentation = steamStatusPresentation(partner)
        presenceView.text = presentation.text
        presenceView.setTextColor(if (presentation.online) SteamPalette.presenceOnline else SteamPalette.headerSubtitle)
        statusDot.visibility = if (presentation.online) View.VISIBLE else View.GONE
        avatarDrawable.setInfo(partner.steamId64, partner.personaName, "")
        if (partner.avatarUrl != null) {
            avatarImage.setImage(partner.avatarUrl, "200_200", avatarDrawable)
        } else {
            avatarImage.setImageDrawable(avatarDrawable)
        }
    }

    private fun startCall(context: Context) {
        callJob = scope.launch {
            try {
                val outcome = SteamWebRtcProbe.run(
                    context,
                    service,
                    partnerSteamId64,
                    incomingVoiceChatId,
                    onConnected = {
                        connectedAt = System.currentTimeMillis()
                        AndroidUtilities.runOnUIThread(tick)
                    },
                ) { report ->
                    lastReport = report
                    // Before the call connects this line is the only progress the user has; after
                    // it, the clock owns it and the detail moves behind the menu.
                    if (connectedAt == 0L) stateView.text = report.substringBefore('\n')
                }
                finishAfterCall(outcome)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finishAfterCall(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * The call is over for real by the time this runs (the probe released everything on its way
     * out). The screen lingers just long enough to say why, instead of vanishing mid-sentence or
     * pretending a finished call is still running.
     */
    private fun finishAfterCall(message: String) {
        connectedAt = 0L
        AndroidUtilities.cancelRunOnUIThread(tick)
        lastReport = message
        stateView.text = message.substringBefore('\n')
        scope.launch {
            delay(1_500)
            finishFragment()
        }
    }

    override fun onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(tick)
        callJob?.cancel()
        scope.cancel()
        super.onFragmentDestroy()
    }

    /** The two-tone ring around the avatar, drawn rather than shipped as nine more PNG densities. */
    private class RingView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(3f).toFloat()
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            paint.shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                SteamPalette.callRingStart, SteamPalette.callRingEnd, Shader.TileMode.CLAMP,
            )
        }

        override fun onDraw(canvas: Canvas) {
            val inset = paint.strokeWidth / 2f
            canvas.drawOval(inset, inset, width - inset, height - inset, paint)
        }
    }

    private companion object {
        const val MENU_DETAILS = 1
        const val AVATAR_DP = 140
        const val AVATAR_BOX_DP = 156
        const val CONTROL_DP = 64
        const val HANGUP_DP = 68
    }
}
