# SteamChat code review

Scope: SteamChat-authored code only — `steamchat-domain/src/`, `steamchat-steamkit/src/`,
`TMessagesProj/src/main/kotlin/org/steamchat/`. Upstream `org/telegram/**` was read only where it
was needed to ground a claim about our code.

**Re-verified against current code on 2026-09-20.** The original 24 findings below are from the
first pass (`steamchat-steamkit` was ~350 lines then; it's ~1500 now). Every row was re-checked
by reading the actual current file/line, not by memory or by trusting the old text — the
**Status** column and note reflect what's really there today. Findings marked *Fixed* were closed
incidentally by later feature work, not by working through this list top to bottom; several fix
comments in the code (`JavaSteamService.kt`) explicitly reference "the High-severity finding this
replaces", so the connection is real, not assumed.

## Findings

| # | Severity | Status | File:line | Finding |
|---|----------|--------|-----------|---------|
| 1 | High | ✅ Fixed | `steamchat-steamkit/.../JavaSteamService.kt` (`login()`, ~214-400) | Was: a rejected logon never stopped the pump thread, so it retried known-bad credentials forever. Now: `isRunning`/`subscriptions` are local to one `login()` attempt (`val isRunning = AtomicBoolean(true)`, ~line 260) instead of shared instance fields, and every callback checks identity against a `@Volatile activeSteamClient` (line 138) before acting — a dead attempt's pump can no longer touch the current client's state. |
| 2 | High | ✅ Fixed | `steamchat-steamkit/.../JavaSteamService.kt:214` | Was: unguarded re-entrant `login()` could build a second live `SteamClient`. Now: the whole function is `loginMutex.withLock { ... }` (line 163 declares `private val loginMutex = Mutex()`), serialising every call. |
| 3 | High | ✅ Fixed | `TMessagesProj/.../ui/SteamDebugActivity.kt:228` | Was: `onFragmentDestroy()` (and its `scope.cancel()`) never ran because `needCloseLastFragment()` called `finish()` instead of letting the stack pop. Now: `onDestroy()` is overridden and calls `actionBarLayout.removeAllFragments()` before `super.onDestroy()`, with a comment explaining exactly why that's the only path that reaches `onFragmentDestroy()`. |
| 4 | High | ✅ Fixed | `steamchat-steamkit/.../JavaSteamService.kt:936` | Was: `getMessageHistory` returned the live mutable list, aliased by the UI while a callback thread appended to it. Now: `messagesByFriend[friendSteamId64]?.toList().orEmpty()` — a defensive copy. |
| 5 | High | ✅ Fixed | `steamchat-steamkit/.../JavaSteamService.kt:1028-1053` | Was: `sendMessage` never emitted to `incomingMessages`, so sent messages didn't appear until the fragment reopened. Now: it builds the outgoing `SteamMessage`, merges it into history, and calls `incomingMessages.tryEmit(message)` at the end. |
| 6 | High | ✅ Fixed | `steamchat-steamkit/.../JavaSteamService.kt:255-270` | Was: the plaintext password and an Activity-holding guard handler stayed captured in a long-lived callback closure for the process lifetime. Now explicit comment + code: `pendingPassword`/`pendingAuthenticator` are `var`s nulled out once the one-time credential attempt resolves, so neither survives in the closure afterward. |
| 7 | Medium | ✅ Fixed | `steamchat-steamkit/.../JavaSteamService.kt:614-641` | Was: `logout()` only cleared the session store, leaving friends/messages/unread/history-loaded state from the previous account visible. Now `logout()` resets `_currentUser`, `_friends`, `_dialogs`, `friendsById`, `messagesByFriend`, `historyLoaded`, `diskHistoryLoaded`, chat groups and disconnects the client. (It now has a real caller too — `SteamSettingsFragment`'s "Выйти" button — so this is no longer dead code either.) |
| 8 | Medium | ✅ Fixed | `steamchat-steamkit/.../JavaSteamService.kt:138-143` | Was: cross-thread fields (`cachedLogOnDetails` etc.) were plain non-volatile `var`s. Now `activeSteamClient` and `webRtcProbeSession` are `@Volatile`, and the mutex from #2 removes the concurrent-access window that made the rest of the plain vars dangerous. |
| 9 | Medium | ✅ Fixed | `steamchat-steamkit/.../JavaSteamService.kt:955`, `:959` | Was: `historyLoaded.add(id)` happened before the fetch could fail, permanently poisoning retries on a timeout. Now the id is only added on the success path and explicitly `.remove()`d on both `CancellationException` (line 955) and plain failure (line 959). |
| 10 | Medium | ❌ Still open | `steamchat-steamkit/.../MessageRecovery.kt:13` | Unchanged: `mergeMessageHistory` dedupes on `Triple(senderSteamId64, timestamp, text)`. History messages carry the server timestamp, live messages carry the local clock (documented in `SteamMessage.kt`), so a message received live and later re-fetched via history still can't match this key — same double-render risk as originally found. Now centralised in one function (`MessageRecovery.kt`) instead of inline, which at least makes it a one-place fix. |
| 11 | Medium | ❌ Still open | `steamchat-steamkit/.../JavaSteamService.kt:553`, `:1053`, `:1274` | Unchanged: `incomingMessages.tryEmit(...)` / `notificationEvents.tryEmit(...)` still ignore the boolean return. The flows are still declared with the default `BufferOverflow.SUSPEND` (`extraBufferCapacity = 64`, lines 206-207), so a slow/leaked subscriber can still silently drop a live message with zero signal. |
| 12 | Medium | ✅ Fixed | `steamchat-steamkit/.../JavaSteamService.kt:261` | Was: the shared `subscriptions` list was mutated from two different threads with no synchronisation. Now it's a `val` local to one `login()` attempt (see #1) — there is no second attempt to race with while the mutex from #2 is held, and the old attempt's subscriptions were already closed and discarded before a new one can start. |
| 13 | Medium | ❌ Still open | `steamchat-steamkit/.../JavaSteamService.kt:601`; `TMessagesProj/.../storage/EncryptedSessionStore.kt:35` | Unchanged: `resumeSession()` calls `sessionStore.load()` with no `withContext(Dispatchers.IO)`, and it's invoked from `SteamLoginFragment` on `Dispatchers.Main`. Every other JavaSteam RPC in the file now correctly wraps itself in `withContext(Dispatchers.IO)` (grep shows 17 call sites) — this is the one gap, and it's the one that runs on literally every cold start. |
| 14 | Medium | ❌ Still open | `TMessagesProj/.../ui/AndroidSteamGuardHandler.kt` (whole file, 37 lines) | Unchanged: `suspendCancellableCoroutine` with no `invokeOnCancellation`, `setCancelable(false)` with only a positive button, unguarded `.show()`. Same hang/leak/`BadTokenException` risk as originally found. |
| 15 | Medium | ✅ Fixed | `TMessagesProj/.../ui/SteamDebugActivity.kt:160-167` | Was: no `onBackPressed()` override, so back finished the whole Activity. Now both `onBackPressed()` (delegating to `actionBarLayout.onBackPressed()`) and an `OnBackInvokedCallback` (for gesture nav) are wired. `android:configChanges` on the manifest entry was not re-checked this pass. |
| 16 | Medium | ✅ Fixed | `steamchat-steamkit/.../MessageRecovery.kt:5-9` | Was: a fixed 2s `Thread.sleep` on the callback thread, no backoff, no cap. Now a small `ReconnectBackoff` class (`nextDelay()` doubles from 2s up to a 60s cap, `reset()` on success) used from the reconnect path. |
| 17 | Low | ❌ Still open | `steamchat-steamkit/.../JavaSteamService.kt:551`; `TMessagesProj/.../ui/SteamChatFragment.kt:226` | Unchanged: `unreadCounts[friendId]` is still incremented unconditionally for every incoming message. `markAsRead` is still called once (line 226, on chat open), not on each subsequent message while the chat stays open — the badge can still tick up while the user is looking at that exact chat. |
| 18 | Low | ❌ Still open | `steamchat-steamkit/.../JavaSteamService.kt:1419` | Unchanged: `.sortedByDescending { it.lastMessage?.timestamp ?: 0L }` with no tie-break. Message-less dialogs (fresh account, or after #7's logout-then-relogin reset) still order by hash-map iteration, not name. |
| 19 | Low | ❌ Still open | `TMessagesProj/.../ui/SteamDialogsFragment.kt:395` | Unchanged: `adapter.notifyDataSetChanged()` on every `observeDialogs()` emission. No `DiffUtil`. |
| 20 | Low | ❌ Still open | `TMessagesProj/.../ui/SteamDialogCell.kt:44` (colors baked in `init`) vs `:123` (`setDialog`, never re-reads them) | Unchanged: same asymmetry as originally found — `SteamMessageCell` re-reads `Theme.getColor()` per bind, `SteamDialogCell` still doesn't. Still latent only because nothing switches the theme at runtime. |
| 21 | Low | ⚠️ No longer true | `steamchat-domain/.../SteamService.kt` | The original claim was "`logout()`/`observeFriends()`/`observeCurrentUser()` have zero callers." That's now false — the app grew a real logout button, and `observeFriends()` is read from at least 7 places (`SteamCallFragment`, `SteamChatFragment`, `SteamDebugActivity` ×3, `SteamFriendsFragment`, `SteamNotificationService`, `SteamProfileFragment`). This finding should be dropped, not fixed — the "problem" was overtaken by the app actually using its own API surface. |
| 22 | Low | 🟡 Partially fixed | `steamchat-steamkit/.../JavaSteamService.kt:1489`; `steamchat-domain/.../FakeSteamService.kt:110`; `TMessagesProj/.../ui/SteamDialogRowFormat.kt:13` | The `timeFormat` duplication is fully gone — both cells now call a single `dialogTimeLabel()` in `SteamDialogRowFormat.kt`. The guard-handler duplication is down from three copies to two: `JavaSteamService` has one `private object NoOpSteamGuardHandler`, but `FakeSteamService.resumeSession()` still builds its own anonymous no-op inline instead of sharing one from `steamchat-domain`. |
| 23 | Low | ❌ Still open | `TMessagesProj/.../ui/SteamChatFragment.kt:75`, `SteamDialogsFragment.kt:61`, `SteamLoginFragment.kt:37` | Unchanged: all three are still plain `CoroutineScope(Dispatchers.Main)`, not `SupervisorJob() + Dispatchers.Main`. `JavaSteamService`'s own `serviceScope` does use a supervisor job — the UI-side fragments still don't. |
| 24 | Low | ✅ Fixed | `steamchat-steamkit/.../JavaSteamService.kt` (`login()`) | Superseded along with #1/#2/#12: the pump thread's lifetime is now scoped to one `login()` attempt behind the mutex, so there's no longer a long-lived orphan thread that outlives the state meant to control it. (It's still an anonymous `Thread` with no name — the specific "name it for ANR traces" suggestion was not applied.) |

**Score: 12 fixed, 9 still open, 1 partially fixed, 1 invalidated by later feature work — of 24.**
All 6 original High findings are fixed; 3 of 6 Medium and 5 of 8 Low are still open.

## Verified clean (original pass, not re-checked 2026-09-20)

Checked deliberately and found genuinely fine at the time of the first review — listed so these
were not blindly re-reviewed this pass, not because they're guaranteed unchanged since:

- **Boundary discipline.** `steamchat-domain` importing only `kotlinx.coroutines`/`org.steamchat.domain`, zero Android/JavaSteam types across `SteamService`.
- **`SteamMapping.kt`** — status/avatar mapping edge cases, covered by `SteamMappingTest.kt`.
- **No credential ever reaches a log** — grepped for `Log.`/`println`/`FileLog`, zero hits.
- **`EncryptedSessionStore`** — correct Keystore scheme, no context leak, not swept into Android auto-backup.
- **History round-trip bounded by `withTimeoutOrNull`**, no deadlock.
- **`AtomicLong nextMessageId` / `ConcurrentHashMap` map objects** — correct for their job.
- **`SteamUser.logOff()`** disassembly-verified to set `isUserInitiated` correctly.
- **Domain models** — immutable, thread-safe by construction.
- **`FakeSteamService`** matched its own spec and tests.
- **Gradle wiring** — no missing-transitive dependency problem between the two Steam modules.

## Open items worth doing next

In rough order of how much they'd actually bite in practice:

1. **#14** — the Steam Guard dialog can't be cancelled and can crash on a dead Activity. Real user-facing risk, small fix (`invokeOnCancellation`, a negative button, an `isFinishing` guard).
2. **#13** — Keystore + disk I/O on the main thread on every cold start. One `withContext(Dispatchers.IO)` around the `load()` call in `resumeSession()`.
3. **#11** — silent message drop under load. Either log the `tryEmit` failure or switch to `BufferOverflow.DROP_OLDEST` so it's an explicit policy instead of an accident.
4. **#23** — swap `CoroutineScope(Dispatchers.Main)` for `CoroutineScope(SupervisorJob() + Dispatchers.Main)` in the three fragments; matches what `JavaSteamService` already does.
5. **#10, #17, #18, #19, #20** — all cosmetic/latent, safe to batch whenever someone's already touching that file.
6. **#22** — give `FakeSteamService` the shared `NoOpSteamGuardHandler` instead of its own copy (needs the type to move from `steamchat-steamkit` into `steamchat-domain` first, since `FakeSteamService` can't depend on the steamkit module).
