package org.steamchat.ui

import android.app.Activity
import android.os.Bundle
import org.telegram.ui.ActionBar.ActionBarLayout
import org.telegram.ui.ActionBar.INavigationLayout

/**
 * Stage 2 verification entry point only: hosts SteamDialogsFragment/SteamChatFragment on top of
 * real Telegram UI chrome without going through LaunchActivity/MessagesController at all. Not a
 * permanent part of the app - removed once real navigation integration happens.
 */
class SteamDebugActivity : Activity(), INavigationLayout.INavigationLayoutDelegate {

    private lateinit var actionBarLayout: ActionBarLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBarLayout = ActionBarLayout(this, true)
        actionBarLayout.setDelegate(this)
        setContentView(actionBarLayout)
        actionBarLayout.addFragmentToStack(SteamDialogsFragment())
        actionBarLayout.showLastFragment()
    }
}
