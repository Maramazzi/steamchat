package org.steamchat.steamkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Fixtures below are trimmed, otherwise-untouched excerpts of real steamcommunity.com responses
 * (the badges/ page, ?xml=1, and the plain profile page) captured while building these parsers -
 * real markup, not a guessed shape.
 */
class SteamWebProfileTest {

    @Test
    fun `parseBadgeIconUrls reads each row's lazy-loaded icon url, in order, not bleeding into the next row`() {
        val html = """
            <script type="text/javascript">${'$'}J( function() { LoadImageGroupOnScroll( 'badge_badge_1', 'badge_images_badge_1' ); } );</script>				<div id="badge_badge_1" data-panel="{&quot;clickOnActivate&quot;:&quot;firstChild&quot;}" role="button" class="badge_row is_link">
					<a class="badge_row_overlay" href="https://steamcommunity.com/id/example-user/badges/1"></a>
					<div class="badge_row_inner">
						<div class="badge_content">
							<div class="badge_current">
								<div class="badge_info">
									<div class="badge_info_image">
										<img src="https://community.fastly.steamstatic.com/public/shared/images/trans.gif" data-delayed-image-group="badge_images_badge_1" data-delayed-image="https://community.fastly.steamstatic.com/public/images/badges/02_years/steamyears7_80.png" class="badge_icon">
									</div>
									<div class="badge_info_description">
										<div class="badge_info_title">Years of Service</div>
									</div>
								</div>
							</div>
						</div>
					</div>
				</div>
							<script type="text/javascript">${'$'}J( function() { LoadImageGroupOnScroll( 'badge_badge_13', 'badge_images_badge_13' ); } );</script>				<div id="badge_badge_13" data-panel="{&quot;clickOnActivate&quot;:&quot;firstChild&quot;}" role="button" class="badge_row is_link">
					<a class="badge_row_overlay" href="https://steamcommunity.com/id/example-user/badges/13"></a>
					<div class="badge_row_inner">
						<div class="badge_content">
							<div class="badge_current">
								<div class="badge_info">
									<div class="badge_info_image">
										<img src="https://community.fastly.steamstatic.com/public/shared/images/trans.gif" data-delayed-image-group="badge_images_badge_13" data-delayed-image="https://community.fastly.steamstatic.com/public/images/badges/13_gamecollector/50_80.png?v=4" class="badge_icon">
									</div>
									<div class="badge_info_description">
										<div class="badge_info_title">Collection Agent</div>
									</div>
								</div>
							</div>
						</div>
					</div>
				</div>
        """.trimIndent()

        assertEquals(
            listOf(
                "https://community.fastly.steamstatic.com/public/images/badges/02_years/steamyears7_80.png",
                "https://community.fastly.steamstatic.com/public/images/badges/13_gamecollector/50_80.png?v=4",
            ),
            parseBadgeIconUrls(html),
        )
    }

    @Test
    fun `parseXpProgress reads the remaining-xp text and the bar's own width percentage`() {
        // Real markup: Steam computes and prints the percentage itself - not derived from a
        // level-up cost formula on our side, so there's no risk of showing invented numbers.
        val html = """
            <div class="profile_xp_block">
                <div class="profile_xp_block_left">
                    <span class="profile_xp_block_level">Level <span class="friendPlayerLevel lvl_50"><span class="friendPlayerLevelNum">57</span></span></span>
                    <span class="profile_xp_block_xp">XP 19,322</span>
                </div>
                <div class="profile_xp_block_mid">
                    <div class="profile_xp_block_remaining">478 XP to reach Level 58</div>
                    <div class="profile_xp_block_remaining_bar">
                        <div class="profile_xp_block_remaining_bar_progress" style="width: 20%"></div>
                    </div>
                </div>
            </div>
        """.trimIndent()

        val (xpToNextLevel, progressPercent) = parseXpProgress(html)

        assertEquals(478, xpToNextLevel)
        assertEquals(20, progressPercent)
    }

    @Test
    fun `parseBadgesPage on a page with no badges yields null count, empty icons, no xp progress, no frame`() {
        val page = parseBadgesPage("<html><body>private profile, nothing here</body></html>")

        assertNull(page.count)
        assertEquals(emptyList<String>(), page.iconUrls)
        assertNull(page.xpToNextLevel)
        assertNull(page.xpProgressPercent)
        assertNull(page.avatarFrameUrl)
    }

    @Test
    fun `parseProfileXml extracts status text and strips the emoticon image to its alt text`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?><profile>
                <steamID64>12345678901234567</steamID64>
                <onlineState>online</onlineState>
                <stateMessage><![CDATA[Online]]></stateMessage>
                <summary><![CDATA[планы не по плану и только так <img src="https://community.akamai.steamstatic.com/economy/emoticon/crtstressed" alt=":crtstressed:" class="emoticon">]]></summary>
            </profile>
        """.trimIndent()

        assertEquals("планы не по плану и только так :crtstressed:", parseProfileXml(xml))
    }

    @Test
    fun `parseProfileXml ignores the in-game block - realtime game state is the client protocol's job`() {
        // Regression guard for the two-sources-of-truth bug: this endpoint does carry a game name,
        // and parsing it back into the profile card is exactly what made a quit game linger on
        // screen (the snapshot can't be invalidated). Realtime presence is SteamUser.game only.
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?><profile>
                <steamID64>12345678901234567</steamID64>
                <onlineState>in-game</onlineState>
                <stateMessage><![CDATA[In-Game]]></stateMessage>
                <inGameInfo>
                    <gameName><![CDATA[Dota 2]]></gameName>
                    <gameLink><![CDATA[http://steamcommunity.com/app/570]]></gameLink>
                </inGameInfo>
            </profile>
        """.trimIndent()

        assertNull(parseProfileXml(xml))
    }

    @Test
    fun `parseAvatarFrameUrl takes the real animated source, not the reduced-motion static one, and stops at the frame's own closing picture tag`() {
        // Real markup: the frame's own <picture> is immediately followed by a second, unrelated
        // <picture> for the actual avatar photo - a non-greedy match has to stop at the first
        // </picture> or it'd swallow the avatar photo's srcset as if it were part of the frame.
        // The <img src> always matches the second <source> (no media query) - the genuinely
        // animated file, confirmed live by downloading it and checking with `file` - not the
        // first <source>, which is Steam's own single-frame reduced-motion fallback.
        val html = """
            <div class="playerAvatar profile_header_size online" data-miniprofile="1024100458">
                <div class="playerAvatarAutoSizeInner">
                    <div class="profile_avatar_frame">
                        <picture>
                            <source media="(prefers-reduced-motion: reduce)" srcset="https://shared.akamai.steamstatic.com/community_assets/images/items/1210230/1aa6c11f7af1af68afd17b60a2a558e427a4c687.png"></source>
                            <source srcset="https://shared.akamai.steamstatic.com/community_assets/images/items/1210230/918d6cbab2b4a4cdc9776f39fdfe64932a809d81.png"></source>
                            <img src="https://shared.akamai.steamstatic.com/community_assets/images/items/1210230/918d6cbab2b4a4cdc9776f39fdfe64932a809d81.png">
                        </picture>
                    </div>
                    <picture>
                        <source media="(prefers-reduced-motion: reduce)" srcset="https://avatars.akamai.steamstatic.com/70a1113be78b1b2693550ff42bb4de0d1dc11ef0_full.jpg"></source>
                        <img srcset="https://avatars.akamai.steamstatic.com/70a1113be78b1b2693550ff42bb4de0d1dc11ef0_full.jpg" >
                    </picture>
                </div>
            </div>
        """.trimIndent()

        assertEquals(
            "https://shared.akamai.steamstatic.com/community_assets/images/items/1210230/918d6cbab2b4a4cdc9776f39fdfe64932a809d81.png",
            parseAvatarFrameUrl(html),
        )
    }

    @Test
    fun `parseScreenshotCount reads the real count, not some other stat's count that happens to render first`() {
        // Real markup: Steam reuses this exact count_link_label/profile_count_link_total pair for
        // several stats - Badges included, and it comes first on the page - so the label has to be
        // matched, not just "the first profile_count_link_total on the page".
        val html = """
            <div class="profile_count_link_preview_ctn" role="button" >
                <a href="https://steamcommunity.com/id/example-user/badges/">
                    <span class="count_link_label">Badges</span>&nbsp;
                    <span class="profile_count_link_total">
                        77
                    </span>
                </a>
            </div>
            <div data-panel="{&quot;focusable&quot;:true,&quot;clickOnActivate&quot;:true}" role="button" class="profile_count_link ellipsis" >
                <a href="https://steamcommunity.com/id/example-user/screenshots/">
                    <span class="count_link_label">Screenshots</span>&nbsp;
                    <span class="profile_count_link_total">
                        36
                    </span>
                </a>
            </div>
        """.trimIndent()

        assertEquals(36, parseScreenshotCount(html))
    }

    @Test
    fun `parseScreenshotCount on a page without that stat yields null`() {
        assertNull(parseScreenshotCount("<html><body>private profile, nothing here</body></html>"))
    }
}
