package org.steamchat.ui

import org.telegram.ui.ActionBar.BaseFragment

/**
 * Every org.steamchat.ui screen extends this instead of BaseFragment directly, so
 * SteamDebugActivity can reliably learn "a fragment transition has truly finished" - including
 * the deferred removal of whatever was pushed off, which real Telegram animates away and then
 * removes via View.removeViewInLayout() specifically so that removal does NOT trigger a fresh
 * layout pass. That made a passive ViewTreeObserver.OnGlobalLayoutListener miss it: it read the
 * fragment stack mid-transition (still holding both the old and new fragment) and never got a
 * second chance to correct itself once the old one actually left - the concrete symptom was the
 * bottom nav bar staying permanently hidden after the very first login → chat list transition,
 * even though the chat list is a bottom-nav root. onBecomeFullyVisible() is the one hook
 * ActionBarLayout itself calls at that exact point (see its own presentFragment* internals), so
 * hooking it here - once, centrally - is what actually closes the gap, not another guess at
 * which View callback fires "late enough".
 */
abstract class SteamBaseFragment : BaseFragment() {
    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        (getParentActivity() as? SteamDebugActivity)?.onFragmentBecameFullyVisible()
    }
}
