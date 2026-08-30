package org.steamchat.ui

import android.app.Activity
import android.os.Bundle
import org.telegram.ui.ActionBar.ActionBarLayout
import org.telegram.ui.ActionBar.INavigationLayout
import org.telegram.ui.ActionBar.Theme

/**
 * Stage 2 verification entry point only: hosts SteamDialogsFragment/SteamChatFragment on top of
 * real Telegram UI chrome without going through LaunchActivity/MessagesController at all. Not a
 * permanent part of the app - removed once real navigation integration happens.
 */
class SteamDebugActivity : Activity(), INavigationLayout.INavigationLayoutDelegate {

    private lateinit var actionBarLayout: ActionBarLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SteamChat is dark-first by design. Our cells already read every color through
        // Theme.getColor(), so switching the active theme repaints all of them - no per-view
        // color overrides needed. "Dark Blue" is Telegram's own dark navy palette.
        Theme.getTheme(DARK_THEME_NAME)?.let { Theme.applyTheme(it, false, true) }

        // main=false: skips ActionBarLayout's BottomSheetTabs setup, which hard-depends on the
        // static LaunchActivity.instance singleton that we never create (see setFragmentStack).
        actionBarLayout = ActionBarLayout(this, false)
        actionBarLayout.setFragmentStack(ArrayList())
        actionBarLayout.setDelegate(this)
        setContentView(actionBarLayout)
        actionBarLayout.addFragmentToStack(SteamLoginFragment())
        actionBarLayout.showLastFragment()
    }

    // Without this, closing the only remaining fragment (e.g. SteamDialogsFragment after
    // SteamLoginFragment was removed via removeLast=true on login) leaves ActionBarLayout with an
    // empty fragment stack and nothing to draw - a blank white screen with no way back. Finish the
    // Activity ourselves instead and block ActionBarLayout's own pop-to-empty.
    override fun needCloseLastFragment(layout: INavigationLayout): Boolean {
        if (layout.fragmentStack.size <= 1) {
            finish()
            return false
        }
        return true
    }

    private companion object {
        const val DARK_THEME_NAME = "Dark Blue"
    }
}
