package org.steamchat.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import android.view.View
import android.widget.LinearLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.Toast
import androidx.core.view.ViewCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.steamchat.domain.SteamIncomingVoiceCall
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.ActionBarLayout
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.INavigationLayout
import org.telegram.ui.ActionBar.Theme

/**
 * Stage 2 verification entry point only: hosts SteamDialogsFragment/SteamChatFragment on top of
 * real Telegram UI chrome without going through LaunchActivity/MessagesController at all. Not a
 * permanent part of the app - removed once real navigation integration happens.
 */
class SteamDebugActivity : Activity(), INavigationLayout.INavigationLayoutDelegate {

    private lateinit var actionBarLayout: ActionBarLayout
    private lateinit var bottomNav: SteamBottomNavView
    private val service = SteamServiceHolder.service
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var incomingDialog: AlertDialog? = null
    private var pendingPermissionCall: SteamIncomingVoiceCall? = null
    private var bottomInset = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SteamChat is dark-first by design. Our cells already read every color through
        // Theme.getColor(), so switching the active theme repaints all of them - no per-view
        // color overrides needed. "Dark Blue" is Telegram's own dark navy palette.
        Theme.getTheme(DARK_THEME_NAME)?.let { Theme.applyTheme(it, false, true) }

        // Normally LaunchActivity does this before any dialogs/chat UI exists. We skip
        // LaunchActivity entirely (see class doc), so without this every shared Paint these
        // resources own - e.g. Theme.avatar_backgroundPaint used by AvatarDrawable.draw() - stays
        // null: every avatar draw throws NPE, which aborts that frame's whole draw pass and
        // renders as a solid black screen. Same pattern BubbleActivity/ExternalActionActivity/
        // PopupNotificationActivity use for standalone Activities that skip LaunchActivity too.
        Theme.createDialogsResources(this)
        Theme.createChatResources(this, false)

        // main=false: skips ActionBarLayout's BottomSheetTabs setup, which hard-depends on the
        // static LaunchActivity.instance singleton that we never create (see setFragmentStack).
        actionBarLayout = ActionBarLayout(this, false)
        actionBarLayout.setFragmentStack(ArrayList())
        actionBarLayout.setDelegate(this)

        bottomNav = SteamBottomNavView(this)
        bottomNav.onTabSelected = { tab -> switchToTab(tab) }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(actionBarLayout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomNav, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        // LaunchActivity normally fills these in from DrawerLayoutContainer's inset callback, and
        // we skip LaunchActivity entirely (see class doc). Left at 0, ActionBar.onMeasure adds no
        // room for the status bar - its height is actionBarHeight + statusBarHeight only when that
        // field is real - so the system clock and icons sit on top of our own header. targetSdk 36
        // means edge-to-edge is enforced, so nothing else pads the window for us either.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val system = AndroidUtilities.getDefaultWindowInsets(insets, false)
            if (AndroidUtilities.statusBarHeight != system.top || AndroidUtilities.navigationBarHeight != system.bottom) {
                AndroidUtilities.statusBarHeight = system.top
                AndroidUtilities.navigationBarHeight = system.bottom
                view.requestLayout()
            }
            // Whichever of the two sits at the true bottom of the screen right now (bottomNav on
            // a tab root, actionBarLayout everywhere else) gets the gesture-bar clearance -
            // updateNavState() re-applies this on every stack change, not just on inset changes.
            bottomInset = system.bottom
            updateNavState()
            insets
        }
        ViewCompat.requestApplyInsets(root)
        // Visibility is inferred structurally (visible only when the stack is exactly one
        // fragment deep - a tab root with nothing pushed on top of it), not a guess:
        // switchToTab() always resets to depth 1. The real trigger for re-checking that is
        // SteamBaseFragment.onBecomeFullyVisible -> onFragmentBecameFullyVisible() below,
        // fired once a transition (including its animation) has actually finished. This listener
        // is a cheap secondary net for anything else that reshapes the layout (e.g. inset changes)
        // without going through a fragment transition at all.
        actionBarLayout.viewTreeObserver.addOnGlobalLayoutListener { updateNavState() }
        actionBarLayout.addFragmentToStack(SteamLoginFragment())
        actionBarLayout.showLastFragment()
        scope.launch {
            service.incomingVoiceCall.collect { call ->
                if (call == null) incomingDialog?.dismiss() else showIncomingCall(call)
            }
        }
        scope.launch {
            service.observeDialogs().collect { dialogs ->
                bottomNav.setUnreadCount(dialogs.sumOf { it.unreadCount })
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                OnBackInvokedCallback { actionBarLayout.onBackPressed() },
            )
        }
    }

    @Deprecated("Legacy back handling for Android 12 and earlier")
    override fun onBackPressed() {
        actionBarLayout.onBackPressed()
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

    override fun onResume() {
        super.onResume()
        actionBarLayout.onResume()
    }

    override fun onPause() {
        super.onPause()
        actionBarLayout.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_INCOMING_MICROPHONE) {
            val call = pendingPermissionCall ?: return
            pendingPermissionCall = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                acceptIncomingCall(call)
            } else {
                scope.launch { service.answerIncomingVoiceCall(call, false) }
                Toast.makeText(this, "Для звонка нужен доступ к микрофону", Toast.LENGTH_SHORT).show()
            }
            return
        }
        actionBarLayout.fragmentStack.lastOrNull()?.onRequestPermissionsResultFragment(requestCode, permissions, grantResults)
    }

    @Deprecated("Activity result API is dictated by BaseFragment")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        actionBarLayout.fragmentStack.lastOrNull()?.onActivityResultFragment(requestCode, resultCode, data)
    }

    // Fragments only get onFragmentDestroy() (which runs their scope.cancel()) through
    // ActionBarLayout's own fragment-removal paths - the Activity being destroyed isn't one of
    // them, so without this every fragment leaks: its coroutine scope stays subscribed to
    // SteamServiceHolder.service forever, holding the dead fragment, RecyclerView and Activity.
    // removeAllFragments() calls onPause()+onFragmentDestroy() on every fragment in the stack.
    override fun onDestroy() {
        incomingDialog?.dismiss()
        scope.cancel()
        actionBarLayout.removeAllFragments()
        super.onDestroy()
    }

    /** Tapping a tab always resets to a fresh root fragment - simpler than four independent
     *  back-stacks, and correct enough: nothing here is expensive to rebuild on re-entry. */
    private fun switchToTab(tab: SteamNavTab) {
        val fragment: BaseFragment = when (tab) {
            SteamNavTab.CHATS -> SteamDialogsFragment()
            SteamNavTab.FRIENDS -> SteamFriendsFragment()
            SteamNavTab.SETTINGS -> SteamSettingsFragment()
            SteamNavTab.PROFILE -> {
                val me = service.observeCurrentUser().value ?: return
                SteamProfileFragment(me.steamId64, me.personaName)
            }
        }
        actionBarLayout.removeAllFragments()
        actionBarLayout.addFragmentToStack(fragment)
        actionBarLayout.showLastFragment()
        updateNavState()
    }

    /** Bottom nav is visible only on a bare tab root (stack depth 1, and not the pre-login
     *  screen) - see the addOnGlobalLayoutListener comment in onCreate for why depth is the
     *  signal, not fragment identity. Gesture-bar clearance follows whichever of the two views is
     *  currently the true bottom of the screen. */
    private fun updateNavState() {
        val stack = actionBarLayout.fragmentStack
        val isRoot = stack.size <= 1 && stack.lastOrNull() !is SteamLoginFragment
        bottomNav.visibility = if (isRoot) View.VISIBLE else View.GONE
        bottomNav.setPadding(0, 0, 0, if (isRoot) bottomInset else 0)
        actionBarLayout.setPadding(0, 0, 0, if (isRoot) 0 else bottomInset)
    }

    /**
     * Called by [SteamBaseFragment.onBecomeFullyVisible] - the reliable "a transition truly
     * finished" signal every one of our fragments now forwards here. The addOnGlobalLayoutListener
     * in onCreate is not enough by itself: real Telegram animates a pushed-off fragment out and
     * only then removes it via View.removeViewInLayout(), which deliberately does not trigger a
     * fresh layout pass - so that listener can read the stack mid-transition (both fragments still
     * present) and never gets a second chance once the old one actually leaves. Concrete symptom
     * this fixed: the bottom nav stayed hidden forever after the very first login → chat list
     * transition, because that first (and only) layout-listener firing saw stack size 2
     * (SteamLoginFragment not gone yet), not the 1 it settled on moments later.
     */
    fun onFragmentBecameFullyVisible() = updateNavState()

    private fun showIncomingCall(call: SteamIncomingVoiceCall) {
        if (incomingDialog != null) return
        val name = service.observeFriends().value.firstOrNull { it.steamId64 == call.partnerSteamId64 }
            ?.personaName ?: call.partnerSteamId64.toString()
        var answered = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("Входящий звонок")
            .setMessage("Звонит $name")
            .setPositiveButton("Принять", null)
            .setNegativeButton("Отклонить") { _, _ ->
                answered = true
                scope.launch { service.answerIncomingVoiceCall(call, false) }
            }
            .create()
        incomingDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                answered = true
                dialog.dismiss()
                if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingPermissionCall = call
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_INCOMING_MICROPHONE)
                } else {
                    acceptIncomingCall(call)
                }
            }
        }
        dialog.setOnCancelListener {
            if (!answered) scope.launch { service.answerIncomingVoiceCall(call, false) }
        }
        dialog.setOnDismissListener { incomingDialog = null }
        dialog.show()
    }

    private fun acceptIncomingCall(call: SteamIncomingVoiceCall) {
        val name = service.observeFriends().value.firstOrNull { it.steamId64 == call.partnerSteamId64 }
            ?.personaName ?: call.partnerSteamId64.toString()
        // The same screen an outgoing call uses: it answers the call itself and owns it from there,
        // so an accepted call looks and behaves identically whichever side started it.
        actionBarLayout.presentFragment(SteamCallFragment(call.partnerSteamId64, name, call.voiceChatId))
    }

    private companion object {
        const val DARK_THEME_NAME = "Dark Blue"
        const val REQUEST_INCOMING_MICROPHONE = 0x531
    }
}
