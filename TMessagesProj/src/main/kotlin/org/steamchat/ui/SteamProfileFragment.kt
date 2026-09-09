package org.steamchat.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.steamchat.domain.SteamNameHistoryEntry
import org.steamchat.domain.SteamUser
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper

/**
 * A profile screen - the logged-in account's own or a friend's, same layout either way (the user
 * explicitly wants this consistent). Opened from SteamChatFragment's header tap, or from the
 * profile icon in SteamDialogsFragment's ActionBar for the own account. isOwn (computed once,
 * synchronously, from observeCurrentUser().value at construction time - the account is always
 * already logged in by the time any profile screen can open) gates the rows Steam's client
 * protocol only has data for about the local account: friend count, group count, settings.
 *
 * Level/groups come from the Steam client protocol; statusText/badgeCount/badgeIconUrls/
 * xpToNextLevel/xpProgressPercent come from scraping public steamcommunity.com pages instead (see
 * SteamWebProfile in steamchat-steamkit) - JavaSteam has no badge-list, bio-text, or XP-progress
 * call at all.
 */
class SteamProfileFragment(
    private val steamId64: Long,
    private val initialName: String,
) : SteamBaseFragment() {

    private val service = SteamServiceHolder.service
    private val scope = CoroutineScope(Dispatchers.Main)
    private val isOwn = steamId64 == SteamServiceHolder.service.observeCurrentUser().value?.steamId64

    private lateinit var header: SteamProfileHeaderView
    private lateinit var statsCard: LinearLayout
    private lateinit var levelValueView: TextView
    private lateinit var levelProgressBar: ProgressBar
    private lateinit var levelProgressText: TextView
    private lateinit var badgesCountView: TextView
    private lateinit var badgesIconsRow: LinearLayout
    private lateinit var aboutCard: LinearLayout
    private lateinit var aboutTextView: TextView
    private lateinit var playingCard: LinearLayout
    private lateinit var playingIconView: BackupImageView
    private lateinit var playingGameView: TextView
    private lateinit var gamesValueView: TextView
    private lateinit var screenshotsValueView: TextView
    private var groupsValueView: TextView? = null

    override fun createView(context: Context): View {
        // No back arrow when this is the bottom-nav "Профиль" tab (a bare root, stack depth 1) -
        // tapping it there would pop the only fragment left and finish() the Activity (see
        // SteamDebugActivity.needCloseLastFragment). Pushed on top of a chat/friends list to view
        // someone else's profile, the stack is deeper and the back arrow is correct as normal.
        if (parentLayout?.fragmentStack?.size != 1) actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setTitle(initialName)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> finishFragment()
                    MENU_SETTINGS -> Toast.makeText(context, "Настройки скоро появятся", Toast.LENGTH_SHORT).show()
                }
            }
        })
        if (isOwn) {
            actionBar.createMenu().addItem(MENU_SETTINGS, R.drawable.msg_settings)
        }

        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))

        header = SteamProfileHeaderView(context)
        header.onNameHistoryClick = { id ->
            scope.launch {
                val history = service.getNameHistory(id)
                showNameHistoryDialog(context, history)
            }
        }
        root.addView(header)

        // Уровень and Значки as two halves of one card (matching feedback: same height, no
        // separate boxes) with a hairline vertical divider between them, instead of two cards.
        statsCard = LinearLayout(context)
        statsCard.orientation = LinearLayout.HORIZONTAL
        statsCard.background = cardBackground()
        statsCard.visibility = View.GONE

        val levelColumn = LinearLayout(context)
        levelColumn.orientation = LinearLayout.VERTICAL
        levelColumn.setPadding(dp(16f), dp(12f), dp(16f), dp(12f))

        val levelLabel = TextView(context)
        levelLabel.text = "Уровень"
        levelLabel.textSize = 13f
        levelLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
        levelColumn.addView(levelLabel)

        levelValueView = TextView(context)
        levelValueView.textSize = 30f
        levelValueView.typeface = Typeface.DEFAULT_BOLD
        levelValueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        levelColumn.addView(levelValueView)

        levelProgressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
        levelProgressBar.max = 100
        levelProgressBar.visibility = View.GONE
        levelColumn.addView(levelProgressBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 6, 0f, 0, 10, 0, 0))

        levelProgressText = TextView(context)
        levelProgressText.textSize = 12f
        levelProgressText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
        levelProgressText.setPadding(0, dp(4f), 0, 0)
        levelProgressText.visibility = View.GONE
        levelColumn.addView(levelProgressText)

        statsCard.addView(levelColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        val divider = View(context)
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider))
        statsCard.addView(divider, LayoutHelper.createLinear(1, LayoutHelper.MATCH_PARENT))

        val badgesColumn = LinearLayout(context)
        badgesColumn.orientation = LinearLayout.VERTICAL
        badgesColumn.setPadding(dp(16f), dp(12f), dp(16f), dp(12f))

        val badgesLabelRow = LinearLayout(context)
        badgesLabelRow.orientation = LinearLayout.HORIZONTAL
        badgesLabelRow.gravity = Gravity.CENTER_VERTICAL

        val badgesLabel = TextView(context)
        badgesLabel.text = "Значки"
        badgesLabel.textSize = 13f
        badgesLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
        badgesLabelRow.addView(badgesLabel)

        badgesCountView = TextView(context)
        badgesCountView.textSize = 13f
        badgesCountView.typeface = Typeface.DEFAULT_BOLD
        badgesCountView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        badgesCountView.setPadding(dp(6f), 0, 0, 0)
        badgesLabelRow.addView(badgesCountView)

        badgesColumn.addView(badgesLabelRow)

        val badgesScroll = HorizontalScrollView(context)
        badgesScroll.isHorizontalScrollBarEnabled = false
        badgesIconsRow = LinearLayout(context)
        badgesIconsRow.orientation = LinearLayout.HORIZONTAL
        badgesScroll.addView(badgesIconsRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        badgesColumn.addView(badgesScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 10, 0, 0))

        statsCard.addView(badgesColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        root.addView(statsCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16f, 20f, 16f, 0f))

        aboutCard = LinearLayout(context)
        aboutCard.orientation = LinearLayout.VERTICAL
        aboutCard.background = cardBackground()
        aboutCard.setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        aboutCard.visibility = View.GONE

        val aboutLabel = TextView(context)
        aboutLabel.text = "О себе"
        aboutLabel.textSize = 13f
        aboutLabel.typeface = Typeface.DEFAULT_BOLD
        aboutLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText))
        aboutCard.addView(aboutLabel)

        aboutTextView = TextView(context)
        aboutTextView.textSize = 14f
        aboutTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        aboutTextView.setPadding(0, dp(4f), 0, 0)
        aboutCard.addView(aboutTextView)

        root.addView(aboutCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16f, 12f, 16f, 0f))

        playingCard = LinearLayout(context)
        playingCard.orientation = LinearLayout.HORIZONTAL
        playingCard.gravity = Gravity.CENTER_VERTICAL
        playingCard.background = cardBackground()
        playingCard.setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        playingCard.visibility = View.GONE

        playingIconView = BackupImageView(context)
        playingIconView.setRoundRadius(dp(8f))
        playingCard.addView(playingIconView, LayoutHelper.createLinear(48, 48))

        val playingTextColumn = LinearLayout(context)
        playingTextColumn.orientation = LinearLayout.VERTICAL

        val playingLabel = TextView(context)
        playingLabel.text = "Сейчас играет"
        playingLabel.textSize = 13f
        playingLabel.typeface = Typeface.DEFAULT_BOLD
        playingLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText))
        playingTextColumn.addView(playingLabel)

        playingGameView = TextView(context)
        playingGameView.textSize = 17f
        playingGameView.typeface = Typeface.DEFAULT_BOLD
        playingGameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        playingGameView.setPadding(0, dp(2f), 0, 0)
        playingTextColumn.addView(playingGameView)

        playingCard.addView(playingTextColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12, 0, 0, 0))

        root.addView(playingCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16f, 12f, 16f, 0f))

        fun openSteamCommunity(path: String) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://steamcommunity.com/profiles/$steamId64$path")))
        }

        // Placeholder rows (Инвентарь/Скриншоты/Видео/Мастерская) - matching sections exist on
        // the real Steam profile screen but aren't built here yet. Same "tell the user, don't
        // pretend it works" pattern as the settings gear (MENU_SETTINGS below).
        fun notBuiltYet() {
            Toast.makeText(context, "Пока в разработке", Toast.LENGTH_SHORT).show()
        }

        // Друзья/Игры/Скриншоты as an icon+label+number tile row, matching the user's reference
        // screenshot of the real Steam app - replaces the old plain-text Игры/Друзья rows (their
        // numbers now live here instead). Друзья stays isOwn-only (same client-protocol limit as
        // before, just restyled); "—" for a friend's profile rather than hiding the tile, same
        // placeholder convention already used for groupsValueView below.
        val statsTilesCard = LinearLayout(context)
        statsTilesCard.orientation = LinearLayout.HORIZONTAL
        statsTilesCard.background = cardBackground()

        val (friendsTile, friendsValue) = buildStatTile(context, R.drawable.outline_groups_24, "Друзья", clickable = false, onClick = null)
        friendsValue.text = if (isOwn) service.observeFriends().value.size.toString() else "—"
        statsTilesCard.addView(friendsTile, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
        statsTilesCard.addView(tileDivider(context), LayoutHelper.createLinear(1, LayoutHelper.MATCH_PARENT))

        val (gamesTile, gamesValue) = buildStatTile(context, R.drawable.dialog_media_game_20, "Игры", clickable = true) {
            presentFragment(SteamGamesFragment(steamId64, initialName))
        }
        gamesValueView = gamesValue
        statsTilesCard.addView(gamesTile, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
        statsTilesCard.addView(tileDivider(context), LayoutHelper.createLinear(1, LayoutHelper.MATCH_PARENT))

        val (screenshotsTile, screenshotsValue) = buildStatTile(context, R.drawable.msg_camera, "Скриншоты", clickable = true) { notBuiltYet() }
        screenshotsValueView = screenshotsValue
        statsTilesCard.addView(screenshotsTile, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        root.addView(statsTilesCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16f, 20f, 16f, 0f))

        val profileLinkLabel = if (isOwn) "Мой профиль Steam" else "Профиль в Steam"
        val (steamProfileRow, _) = buildRow(context, null, profileLinkLabel, null, clickable = true) { openSteamCommunity("") }
        root.addView(steamProfileRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 12f, 0f, 0f))

        if (isOwn) {
            val (groupsRow, groupsValue) = buildRow(context, null, "Группы", "…", clickable = true) { openSteamCommunity("/groups") }
            groupsValueView = groupsValue
            root.addView(groupsRow)
        }

        // Инвентарь/Скриншоты/Видео/Мастерская grouped into one card with hairline dividers,
        // matching the reference screenshot - not four loose rows like Профиль/Группы above.
        val placeholderCard = LinearLayout(context)
        placeholderCard.orientation = LinearLayout.VERTICAL
        placeholderCard.background = cardBackground()

        val (inventoryRow, _) = buildRow(context, R.drawable.chats_archive_box, "Инвентарь", null, clickable = true) { notBuiltYet() }
        placeholderCard.addView(inventoryRow)
        placeholderCard.addView(rowDivider(context))

        val (placeholderScreenshotsRow, _) = buildRow(context, R.drawable.msg_camera, "Скриншоты", null, clickable = true) { notBuiltYet() }
        placeholderCard.addView(placeholderScreenshotsRow)
        placeholderCard.addView(rowDivider(context))

        val (videoRow, _) = buildRow(context, R.drawable.ic_action_play, "Видео", null, clickable = true) { notBuiltYet() }
        placeholderCard.addView(videoRow)
        placeholderCard.addView(rowDivider(context))

        val (workshopRow, _) = buildRow(context, R.drawable.msg_settings, "Мастерская", null, clickable = true) { notBuiltYet() }
        placeholderCard.addView(workshopRow)

        root.addView(placeholderCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16f, 12f, 16f, 0f))

        val scroll = ScrollView(context)
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fragmentView = scroll

        scope.launch {
            combine(service.observeCurrentUser(), service.observeFriends()) { current, friendsList ->
                current?.takeIf { it.steamId64 == steamId64 } ?: friendsList.find { it.steamId64 == steamId64 }
            }.collect { user ->
                if (user == null) return@collect
                header.render(user)
                actionBar.setTitle(user.personaName)
                renderPlayingCard(user)
            }
        }
        scope.launch {
            val stats = service.getProfileStats(steamId64)
            val level = stats.level
            if (level != null) {
                statsCard.visibility = View.VISIBLE
                levelValueView.text = level.toString()
                val xpToNextLevel = stats.xpToNextLevel
                val xpProgressPercent = stats.xpProgressPercent
                if (xpToNextLevel != null && xpProgressPercent != null) {
                    levelProgressBar.visibility = View.VISIBLE
                    levelProgressBar.progressDrawable = levelProgressDrawable(SteamLevelColors.forLevel(level))
                    levelProgressBar.progress = xpProgressPercent
                    levelProgressText.visibility = View.VISIBLE
                    levelProgressText.text = "$xpToNextLevel XP до след. уровня"
                }
            }
            groupsValueView?.text = stats.groupsCount?.toString() ?: "—"
            screenshotsValueView.text = stats.screenshotCount?.toString() ?: "—"

            val statusText = stats.statusText
            if (statusText != null) {
                aboutCard.visibility = View.VISIBLE
                aboutTextView.setTextWithEmoticons(statusText, scope)
            }

            val badgeCount = stats.badgeCount
            if (badgeCount != null) {
                statsCard.visibility = View.VISIBLE
                badgesCountView.text = badgeCount.toString()
                stats.badgeIconUrls.forEach { url ->
                    val icon = BackupImageView(context)
                    icon.setRoundRadius(dp(9f))
                    icon.setImage(url, "48_48", cardBackground())
                    badgesIconsRow.addView(icon, LayoutHelper.createLinear(48, 48, 0, 0, 0, 8, 0))
                }
            }

            header.applyDecorations(stats.avatarFrameUrl)
        }
        scope.launch {
            gamesValueView.text = service.getOwnedGames(steamId64).size.toString()
        }

        return scroll
    }

    /**
     * Driven by the live client-protocol presence (SteamUser.game), not the web profile scrape -
     * re-runs on every PersonaStateCallback, so quitting a game clears this card by itself. It
     * used to read SteamProfileStats.currentGame (?xml=1), which is a one-shot snapshot taken when
     * the screen opened, with no way to ever be invalidated: the header would correctly stop
     * saying "Играет" while this card kept showing the game the person had already quit. Two
     * sources for one fact, and the stale one won on screen - that was the bug.
     */
    private fun renderPlayingCard(user: SteamUser) {
        val playing = visibleGame(user)
        if (playing == null) {
            playingCard.visibility = View.GONE
            return
        }
        playingCard.visibility = View.VISIBLE
        playingGameView.text = playing.name ?: "В игре"
        val iconUrl = steamGameHeaderUrl(playing.appId)
        if (iconUrl != null) {
            playingIconView.setImage(iconUrl, "48_48", cardBackground())
        } else {
            playingIconView.setImageDrawable(cardBackground())
        }
    }

    /** Same "▾ next to the name" -> popup pattern as the real Steam profile page's alias history. Plain platform AlertDialog (same choice as AndroidSteamGuardHandler) - it already picks up this Activity's dark theme, no need for Telegram's own themed dialog for a simple read-only list. */
    private fun showNameHistoryDialog(context: Context, history: List<SteamNameHistoryEntry>) {
        if (history.isEmpty()) {
            Toast.makeText(context, "История ников недоступна", Toast.LENGTH_SHORT).show()
            return
        }
        val lines = history.map { "${it.name}  —  ${it.changedAt}" }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("История ников")
            .setItems(lines, null)
            .setPositiveButton("Закрыть", null)
            .show()
    }

    private fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12f).toFloat()
        setColor(Theme.getColor(Theme.key_windowBackgroundGray))
    }

    /** Rounded track + rounded, bracket-coloured fill clipped to ProgressBar's own progress/max ratio. */
    private fun levelProgressDrawable(color: Int): LayerDrawable {
        val track = GradientDrawable().apply {
            cornerRadius = dp(3f).toFloat()
            setColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        }
        val fillShape = GradientDrawable().apply {
            cornerRadius = dp(3f).toFloat()
            setColor(color)
        }
        val clippedFill = ClipDrawable(fillShape, Gravity.START, ClipDrawable.HORIZONTAL)
        val layers = LayerDrawable(arrayOf(track, clippedFill))
        layers.setId(0, android.R.id.background)
        layers.setId(1, android.R.id.progress)
        return layers
    }

    /** Icon + label + trailing count above a card of icon + label + chevron rows - matching a user-provided reference screenshot of the real Steam app. */
    private fun buildStatTile(context: Context, iconRes: Int, label: String, clickable: Boolean, onClick: (() -> Unit)?): Pair<LinearLayout, TextView> {
        val tile = LinearLayout(context)
        tile.orientation = LinearLayout.VERTICAL
        tile.gravity = Gravity.CENTER_HORIZONTAL
        tile.setPadding(dp(8f), dp(14f), dp(8f), dp(14f))
        if (clickable) {
            tile.isClickable = true
            // false = transparent-at-rest ripple, not an opaque key_windowBackgroundWhite fill -
            // this tile sits on statsTilesCard's own cardBackground() gray, not the plain root.
            tile.background = SteamPalette.rowSelector()
            tile.setOnClickListener { onClick?.invoke() }
        }

        val icon = ImageView(context)
        icon.setImageResource(iconRes)
        icon.colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), PorterDuff.Mode.SRC_IN)
        tile.addView(icon, LayoutHelper.createLinear(24, 24, Gravity.CENTER_HORIZONTAL))

        val labelView = TextView(context)
        labelView.text = label
        labelView.textSize = 12f
        labelView.gravity = Gravity.CENTER_HORIZONTAL
        labelView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
        labelView.setPadding(0, dp(6f), 0, 0)
        tile.addView(labelView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))

        val valueView = TextView(context)
        valueView.text = "…"
        valueView.textSize = 17f
        valueView.typeface = Typeface.DEFAULT_BOLD
        valueView.gravity = Gravity.CENTER_HORIZONTAL
        valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        valueView.setPadding(0, dp(2f), 0, 0)
        tile.addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))

        return tile to valueView
    }

    private fun tileDivider(context: Context): View {
        val divider = View(context)
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider))
        return divider
    }

    private fun rowDivider(context: Context): View {
        val divider = View(context)
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider))
        return divider.apply { layoutParams = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1) }
    }

    private fun buildRow(context: Context, iconRes: Int?, label: String, value: String?, clickable: Boolean, onClick: (() -> Unit)?): Pair<LinearLayout, TextView?> {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        if (clickable) {
            row.isClickable = true
            // false = transparent-at-rest ripple: some callers put rows on the plain root
            // background, others (the placeholder card) on cardBackground()'s gray - transparent
            // shows through correctly either way, unlike the opaque windowBackgroundWhite fill
            // getSelectorDrawable(true) paints (mismatched the gray card - see buildStatTile).
            row.background = SteamPalette.rowSelector()
            row.setOnClickListener { onClick?.invoke() }
        }

        if (iconRes != null) {
            val icon = ImageView(context)
            icon.setImageResource(iconRes)
            icon.colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), PorterDuff.Mode.SRC_IN)
            row.addView(icon, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 0, 0, 16, 0))
        }

        val labelView = TextView(context)
        labelView.text = label
        labelView.textSize = 15f
        labelView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        row.addView(labelView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        var valueView: TextView? = null
        if (value != null) {
            valueView = TextView(context)
            valueView.text = value
            valueView.textSize = 15f
            valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
            row.addView(valueView)
        }
        if (clickable) {
            val chevron = TextView(context)
            chevron.text = "›"
            chevron.textSize = 18f
            chevron.setPadding(dp(8f), 0, 0, 0)
            chevron.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
            row.addView(chevron)
        }
        return row to valueView
    }

    override fun onFragmentDestroy() {
        scope.cancel()
        super.onFragmentDestroy()
    }

    private companion object {
        const val MENU_SETTINGS = 1
    }
}
