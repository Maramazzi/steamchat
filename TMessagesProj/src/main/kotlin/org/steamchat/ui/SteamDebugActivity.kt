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
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.steamchat.domain.SteamIncomingVoiceCall
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
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
    private var answeringCallId: Long? = null
    private var bottomInset = 0
    private var resumed = false

    fun onSteamLogout() {
        actionBarLayout.removeAllFragments()
        actionBarLayout.addFragmentToStack(SteamLoginFragment())
        actionBarLayout.showLastFragment()
    }

    fun onSteamLogin() {
        val background = Intent(this, SteamNotificationService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(background) else startService(background)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("notifications_requested", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notifications_requested", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0x532)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        answerNotificationCall()
        openNotificationChat()
    }

    private fun openNotificationChat() {
        if (!intent.hasExtra("steam_chat_id") || service.observeCurrentUser().value == null ||
            actionBarLayout.fragmentStack.lastOrNull() is SteamLoginFragment) return
        val id = intent.getLongExtra("steam_chat_id", 0)
        val channel = if (intent.hasExtra("steam_channel_id")) intent.getLongExtra("steam_channel_id", 0) else null
        intent.removeExtra("steam_chat_id")
        intent.removeExtra("steam_channel_id")
        switchToTab(SteamNavTab.CHATS)
        val name = service.observeFriends().value.firstOrNull { it.steamId64 == id }?.personaName ?: id.toString()
        actionBarLayout.presentFragment(if (channel == null) SteamChatFragment(id, name) else SteamChatFragment.forGroup(id, channel))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Install the bundled Somnolent variant before any themed views or paints are created.
        // getAssetFile only compares file sizes and can reuse an older palette after an update.
        val themeFile = File(cacheDir, "somnolent_github.attheme")
        assets.open("somnolent_github.attheme").use { source ->
            themeFile.outputStream().use { source.copyTo(it) }
        }
        Theme.applyThemeFile(themeFile, "Somnolent GitHub", null, false)

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
                if (call == null) {
                    answeringCallId = null
                    incomingDialog?.dismiss()
                } else if (resumed) {
                    if (!answerNotificationCall()) showIncomingCall(call)
                }
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
        resumed = true
        ApplicationLoader.mainInterfacePaused = false
        actionBarLayout.onResume()
        updateVisibleChat()
        service.incomingVoiceCall.value?.let { call ->
            if (!answerNotificationCall()) showIncomingCall(call)
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        SteamNotificationService.visibleChat = null
        ApplicationLoader.mainInterfacePaused = true
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
    fun onFragmentBecameFullyVisible() {
        updateNavState()
        updateVisibleChat()
        openNotificationChat()
        if (!answerNotificationCall() && resumed) service.incomingVoiceCall.value?.let(::showIncomingCall)
    }

    private fun answerNotificationCall(): Boolean {
        if (!resumed || !intent.hasExtra(SteamNotificationService.ANSWER_CALL) ||
            actionBarLayout.fragmentStack.lastOrNull() is SteamLoginFragment) return false
        val id = intent.getLongExtra(SteamNotificationService.ANSWER_CALL, 0)
        intent.removeExtra(SteamNotificationService.ANSWER_CALL)
        val call = service.incomingVoiceCall.value?.takeIf { it.voiceChatId == id } ?: return false
        incomingDialog?.dismiss()
        requestAcceptIncomingCall(call)
        return true
    }

    private fun requestAcceptIncomingCall(call: SteamIncomingVoiceCall) {
        answeringCallId = call.voiceChatId
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(SteamNotificationService.CALL_NOTIFICATION_ID)
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingPermissionCall = call
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_INCOMING_MICROPHONE)
        } else acceptIncomingCall(call)
    }

    private fun declineIncomingCall(call: SteamIncomingVoiceCall) {
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(SteamNotificationService.CALL_NOTIFICATION_ID)
        scope.launch { service.answerIncomingVoiceCall(call, false) }
    }

    private fun updateVisibleChat() {
        SteamNotificationService.visibleChat = if (resumed)
            (actionBarLayout.fragmentStack.lastOrNull() as? SteamChatFragment)?.notificationChat() else null
        SteamNotificationService.visibleChat?.let { (chat, channel) ->
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel("$chat:$channel", 2)
        }
    }

    private fun showIncomingCall(call: SteamIncomingVoiceCall) {
        if (incomingDialog != null || answeringCallId == call.voiceChatId ||
            actionBarLayout.fragmentStack.lastOrNull() is SteamLoginFragment) return
        val name = service.observeFriends().value.firstOrNull { it.steamId64 == call.partnerSteamId64 }
            ?.personaName ?: call.partnerSteamId64.toString()
        var answered = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("Входящий звонок")
            .setMessage("Звонит $name")
            .setPositiveButton("Принять", null)
            .setNegativeButton("Отклонить") { _, _ ->
                answered = true
                declineIncomingCall(call)
            }
            .create()
        incomingDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                answered = true
                dialog.dismiss()
                requestAcceptIncomingCall(call)
            }
        }
        dialog.setOnCancelListener {
            if (!answered) declineIncomingCall(call)
        }
        dialog.setOnDismissListener { incomingDialog = null }
        dialog.show()
    }

    private fun acceptIncomingCall(call: SteamIncomingVoiceCall) {
        if (service.incomingVoiceCall.value != call) return
        val name = service.observeFriends().value.firstOrNull { it.steamId64 == call.partnerSteamId64 }
            ?.personaName ?: call.partnerSteamId64.toString()
        // The same screen an outgoing call uses: it answers the call itself and owns it from there,
        // so an accepted call looks and behaves identically whichever side started it.
        actionBarLayout.presentFragment(SteamCallFragment(call.partnerSteamId64, name, call.voiceChatId))
    }

    private companion object {
        const val REQUEST_INCOMING_MICROPHONE = 0x531
    }
}
