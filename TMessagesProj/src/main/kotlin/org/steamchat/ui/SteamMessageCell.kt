package org.steamchat.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import org.steamchat.domain.SteamMessage
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.ActionBar.Theme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Same visual language as Telegram's ChatMessageCell (Theme bubble/text/time color keys) built
 * fresh against SteamMessage instead of forking the real 29500-line ChatMessageCell, whose
 * delegate callbacks are typed directly with TLRPC.User/TLRPC.Chat (see migration map).
 */
class SteamMessageCell(context: Context) : FrameLayout(context) {

    private val bubble = FrameLayout(context)
    private val textView = TextView(context)
    private val timeView = TextView(context)

    init {
        textView.textSize = 16f
        timeView.textSize = 11f

        bubble.addView(
            textView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = dp(12f)
                topMargin = dp(8f)
                rightMargin = dp(12f)
                bottomMargin = dp(20f)
            },
        )
        bubble.addView(
            timeView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = dp(10f)
                bottomMargin = dp(6f)
            },
        )

        addView(bubble, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
    }

    fun setMessage(message: SteamMessage) {
        textView.text = message.text
        timeView.text = timeFormat.format(Date(message.timestamp))

        val background = GradientDrawable()
        background.cornerRadius = dp(14f).toFloat()

        val bubbleParams = bubble.layoutParams as FrameLayout.LayoutParams
        if (message.isOutgoing) {
            background.setColor(Theme.getColor(Theme.key_chat_outBubble))
            textView.setTextColor(Theme.getColor(Theme.key_chat_messageTextOut))
            timeView.setTextColor(Theme.getColor(Theme.key_chat_outTimeText))
            bubbleParams.gravity = Gravity.END
        } else {
            background.setColor(Theme.getColor(Theme.key_chat_inBubble))
            textView.setTextColor(Theme.getColor(Theme.key_chat_messageTextIn))
            timeView.setTextColor(Theme.getColor(Theme.key_chat_inTimeText))
            bubbleParams.gravity = Gravity.START
        }
        bubble.background = background
        bubble.layoutParams = bubbleParams
    }

    private companion object {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    }
}
