package org.steamchat.ui

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.text.Spannable
import android.text.style.ImageSpan
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.telegram.messenger.AndroidUtilities.dp

// Accepts a real colon or U+02D0 MODIFIER LETTER TRIANGULAR COLON (ː) as the delimiter - confirmed
// live: a real chat message's text had ːsteamhappyː with that lookalike character instead of ':',
// which silently failed to match a plain-':' pattern (Android soft keyboards offer it as a
// long-press alternate on the ':' key, easy to select by accident since it's visually identical at
// normal text size). Steam's own shortcodes always use a real colon; this is purely about being
// forgiving of what a phone keyboard actually produces.
private val EMOTICON_PATTERN = Regex("""[:ː]([a-zA-Z0-9_]+)[:ː]""")

/**
 * `:name:` shortcodes (chat messages, profile bio - same syntax both places, confirmed live in
 * real bio text like ":crtstressed:") resolve to a real image at a fixed, unauthenticated CDN URL
 * - confirmed live against several real emoticons, not guessed:
 * `https://community.akamai.steamstatic.com/economy/emoticon/{name}`. No JavaSteam call needed
 * (CPlayer_GetEmoticonList_Request returns the *account's own unlocked* emoticons for a picker
 * UI - a different concern from rendering shortcodes already present in text someone wrote).
 *
 * Sets the plain text immediately (so a message never sits blank while a tiny image loads, and
 * offline/slow-network still shows the raw `:name:` text same as it always did), then swaps each
 * match for the real image as its fetch completes - the same "text first, then the emoji" behavior
 * the real Steam client has. tag holds the exact text this call rendered, checked again once each
 * fetch finishes: if a RecyclerView cell got rebound to different text in the meantime, the stale
 * result is just dropped instead of corrupting the new text.
 */
internal fun TextView.setTextWithEmoticons(text: String, scope: CoroutineScope) {
    tag = text
    setText(text, TextView.BufferType.SPANNABLE)
    val matches = EMOTICON_PATTERN.findAll(text).toList()
    if (matches.isEmpty()) return

    val size = dp(20f)
    matches.forEach { match ->
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { fetchBytes(emoticonUrl(match.groupValues[1])) } ?: return@launch
            val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } ?: return@launch
            if (tag != text) return@launch
            // Bounds start slightly below the baseline so a square emoticon sits centred against
            // the text rather than hanging off it - ALIGN_BASELINE with a plain 0..size box would
            // push the glyph up and stretch the line height.
            val descent = (size * 0.18f).toInt()
            val drawable = BitmapDrawable(resources, bitmap).apply { setBounds(0, -descent, size, size - descent) }
            (getText() as? Spannable)?.setSpan(
                ImageSpan(drawable, ImageSpan.ALIGN_BASELINE),
                match.range.first,
                match.range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            requestLayout()
            invalidate()
        }
    }
}

private fun emoticonUrl(name: String): String = "https://community.akamai.steamstatic.com/economy/emoticon/$name"
