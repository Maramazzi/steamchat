package org.steamchat.ui

import android.graphics.drawable.Drawable
import org.steamchat.domain.SteamStatus
import org.steamchat.domain.SteamUser
import org.telegram.ui.ActionBar.Theme

/** Semantic names for SteamChat views, backed by the same theme as profiles and dialogs. */
internal object SteamPalette {

    // ---- Backgrounds --------------------------------------------------------------------------
    /** Main dark canvas. */
    val chatBackground get() = Theme.getColor(Theme.key_windowBackgroundGray)
    /** Date pill / dividers - barely there, sitting on the background rather than competing. */
    val separatorSurface get() = Theme.getColor(Theme.key_chat_inBubble)

    // ---- Text & accent --------------------------------------------------------------------------
    val headerTitle get() = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText)
    val headerSubtitle get() = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText)
    val authorAccent get() = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText)
    /** Send button, active tabs, and header accent. */
    val accent get() = Theme.getColor(Theme.key_chats_actionBackground)
    val accentDisabled get() = Theme.getColor(Theme.key_chats_unreadCounterMuted)

    // ---- Chat bubbles ---------------------------------------------------------------------------
    /** Incoming cards sit slightly above the canvas. */
    val incomingBubble get() = Theme.getColor(Theme.key_chat_inBubble)
    /** Outgoing cards use a lighter graphite surface. */
    val outgoingBubble get() = Theme.getColor(Theme.key_chat_outBubble)
    val incomingText get() = Theme.getColor(Theme.key_chat_messageTextIn)
    val outgoingText get() = Theme.getColor(Theme.key_chat_messageTextOut)
    /** Timestamps/labels inside a bubble: same hue as its text but stepped back. */
    val incomingMeta get() = Theme.getColor(Theme.key_chat_inTimeText)
    val outgoingMeta get() = Theme.getColor(Theme.key_chat_outTimeText)
    val separatorText get() = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText)

    // ---- Input bar ------------------------------------------------------------------------------
    val inputBarBackground get() = Theme.getColor(Theme.key_chat_messagePanelBackground)
    val inputField get() = Theme.getColor(Theme.key_chat_inBubble)
    val inputHint get() = Theme.getColor(Theme.key_chat_messagePanelHint)
    val inputText get() = Theme.getColor(Theme.key_chat_messagePanelText)
    val inputIcon get() = Theme.getColor(Theme.key_chat_messagePanelIcons)

    // ---- Call screen ----------------------------------------------------------------------------
    /** The avatar and red hangup remain the focus of the call screen. */
    val callBackground get() = Theme.getColor(Theme.key_windowBackgroundGray)
    val callPanel get() = Theme.getColor(Theme.key_dialogBackground)
    val callControl get() = Theme.getColor(Theme.key_chat_inBubble)
    val callHandle get() = Theme.getColor(Theme.key_divider)
    val callHangup get() = Theme.getColor(Theme.key_text_RedRegular)
    val callRingStart get() = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText)
    val callRingEnd get() = Theme.getColor(Theme.key_chats_actionBackground)

    // ---- Presence -------------------------------------------------------------------------------
    /**
     * Matches how Steam itself colours friends: green while in a game, light blue when merely
     * online, grey when offline - so the dot carries the same meaning it does in the real client
     * rather than being "green = any activity".
     */
    val presenceInGame get() = Theme.getColor(Theme.key_windowBackgroundWhiteGreenText)
    val presenceOnline get() = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText)
    val presenceOffline get() = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText)
    /** Dialog list's on-avatar online dot: boolean-only (that screen doesn't show in-game nuance,
     *  see CLAUDE.md section "19. НЕ ДУБЛИРОВАТЬ FRIENDS SCREEN") so it always uses the same
     *  Steam-like green as [presenceInGame] rather than the 3-way [presenceColor] split. */
    val dialogOnlineDot get() = Theme.getColor(Theme.key_chats_onlineCircle)

    /** Transparent ripple preserves the background of cards and rows. */
    fun rowSelector(): Drawable = Theme.getSelectorDrawable(false)
}

/** Dot colour for a user's current Steam presence - see [SteamPalette.presenceInGame]. */
internal fun presenceColor(user: SteamUser): Int = when {
    visibleGame(user) != null -> SteamPalette.presenceInGame
    user.status == SteamStatus.ONLINE -> SteamPalette.presenceOnline
    else -> SteamPalette.presenceOffline
}
