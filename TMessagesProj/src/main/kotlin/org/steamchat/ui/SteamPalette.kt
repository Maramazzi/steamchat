package org.steamchat.ui

import android.graphics.Color
import android.graphics.drawable.Drawable
import org.steamchat.domain.SteamStatus
import org.steamchat.domain.SteamUser
import org.telegram.ui.ActionBar.Theme

/**
 * TWO SEPARATE COLOR SYSTEMS COEXIST IN org.steamchat.ui ON PURPOSE - this object is one of them.
 * Every screen belongs to exactly one; nothing on a screen should read from the other one.
 *
 * 1. **SteamPalette (this file)** - Steam's own client colours (#1B2838 background / #2A475E
 *    panel / #66C0F4 accent, not invented shades), hardcoded because these screens are meant to
 *    look like Steam, not like Telegram. Screens on this system: [SteamDialogsFragment] (chat
 *    list), [SteamChatFragment] (a chat), [SteamCallFragment] (the call screen), the bottom nav
 *    ([SteamBottomNavView]), [SteamFriendsFragment].
 * 2. **`Theme.getColor(Theme.key_*)`** - real Telegram theming, repaints automatically if the
 *    active theme ever changes. Screens on this system: [SteamProfileFragment] +
 *    [SteamProfileHeaderView], [SteamGamesFragment] + [SteamGameCell], [SteamSettingsFragment],
 *    [SteamGroupChannelsFragment]. A full SteamPalette-style replacement was tried here once
 *    (`SteamColors.kt`) and reverted on request - kept on Theme.getColor() deliberately, not an
 *    oversight.
 *
 * **Building something new:** pick whichever system the screen it lives on already uses - never
 * mix both on one screen. If the screen doesn't exist yet, default to `Theme.getColor()` (that's
 * the rest of the app, and what real Telegram infrastructure like `BaseFragment`/`ActionBar`
 * already assumes); reach for SteamPalette only when the screen is deliberately meant to look
 * like Steam's own client chrome, not Telegram's.
 *
 * **The recurring bug this causes:** a cell built for one system silently keeps working - until
 * its screen is repainted onto the other system, and whatever it hardcoded (or whatever
 * `Theme.getSelectorDrawable(true)` happens to paint) stops matching. It has already happened
 * twice (the profile screen's stat tiles, then [SteamDialogCell] when the chat list moved onto
 * SteamPalette) - see CLAUDE.md section 4. [rowSelector] exists specifically to make the second
 * half of that bug (the selector-drawable footgun) structurally impossible going forward.
 */
internal object SteamPalette {

    // ---- Backgrounds --------------------------------------------------------------------------
    /** Screen background - the deep graphite-blue Steam uses behind its own content. */
    val chatBackground = Color.parseColor("#16202D")
    /** Date pill / dividers - barely there, sitting on the background rather than competing. */
    val separatorSurface = Color.parseColor("#1E2B3A")

    // ---- Text & accent --------------------------------------------------------------------------
    val headerTitle = Color.parseColor("#E6EEF6")
    val headerSubtitle = Color.parseColor("#8FA6BC")
    val authorAccent = Color.parseColor("#66C0F4")
    /** Send button, active tab pills/icons, and the header accent - the one "brand" blue. */
    val accent = Color.parseColor("#1F6FA8")
    val accentDisabled = Color.parseColor("#2A3A4B")

    // ---- Chat bubbles ---------------------------------------------------------------------------
    /** Incoming bubble: Steam's panel colour, a touch lifted off the background. */
    val incomingBubble = Color.parseColor("#25384D")
    /** Outgoing bubble: Steam blue, dark enough to keep white text readable. */
    val outgoingBubble = Color.parseColor("#1F6FA8")
    val incomingText = Color.parseColor("#DCE7F1")
    val outgoingText = Color.WHITE
    /** Timestamps/labels inside a bubble: same hue as its text but stepped back. */
    val incomingMeta = Color.parseColor("#8FA6BC")
    val outgoingMeta = Color.parseColor("#BBDCF2")
    val separatorText = Color.parseColor("#8FA6BC")

    // ---- Input bar ------------------------------------------------------------------------------
    val inputBarBackground = Color.parseColor("#131C27")
    val inputField = Color.parseColor("#22303F")
    val inputHint = Color.parseColor("#7E93A8")
    val inputText = Color.parseColor("#E6EEF6")
    val inputIcon = Color.parseColor("#8FA6BC")

    // ---- Call screen ----------------------------------------------------------------------------
    /** Darker than the chat, so the avatar and the red hangup carry the screen. */
    val callBackground = Color.parseColor("#0B111B")
    val callPanel = Color.parseColor("#131C29")
    val callControl = Color.parseColor("#1E2836")
    val callHandle = Color.parseColor("#33445A")
    val callHangup = Color.parseColor("#E5484D")
    val callRingStart = Color.parseColor("#7A5CFF")
    val callRingEnd = Color.parseColor("#4AA8FF")

    // ---- Presence -------------------------------------------------------------------------------
    /**
     * Matches how Steam itself colours friends: green while in a game, light blue when merely
     * online, grey when offline - so the dot carries the same meaning it does in the real client
     * rather than being "green = any activity".
     */
    val presenceInGame = Color.parseColor("#90BA3C")
    val presenceOnline = Color.parseColor("#57CBDE")
    val presenceOffline = Color.parseColor("#5C6D7E")
    /** Dialog list's on-avatar online dot: boolean-only (that screen doesn't show in-game nuance,
     *  see CLAUDE.md section "19. НЕ ДУБЛИРОВАТЬ FRIENDS SCREEN") so it always uses the same
     *  Steam-like green as [presenceInGame] rather than the 3-way [presenceColor] split. */
    val dialogOnlineDot = presenceInGame

    /**
     * Row/cell press feedback - call this instead of `Theme.getSelectorDrawable()` directly.
     * Always transparent at rest: `getSelectorDrawable(true)` paints an opaque
     * `key_windowBackgroundWhite` fill, a Theme color that only happens to look right when it
     * matches whatever surface the cell sits on - which silently breaks the moment that screen or
     * its container is repainted (see the class doc). `false` shows the real surface through
     * unconditionally and is correct everywhere, on either color system, so there is never a
     * per-cell judgment call to get wrong again.
     */
    fun rowSelector(): Drawable = Theme.getSelectorDrawable(false)
}

/** Dot colour for a user's current Steam presence - see [SteamPalette.presenceInGame]. */
internal fun presenceColor(user: SteamUser): Int = when {
    visibleGame(user) != null -> SteamPalette.presenceInGame
    user.status == SteamStatus.ONLINE -> SteamPalette.presenceOnline
    else -> SteamPalette.presenceOffline
}
