package org.steamchat.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SteamMessageContentTest {

    @Test
    fun `plain text stays text`() {
        assertEquals(SteamMessageContent.Text("го в катку"), parseSteamMessageContent("го в катку"))
    }

    @Test
    fun `a steamusercontent url becomes an image card`() {
        val url = "https://images.steamusercontent.com/ugc/2298183338292/ABCDEF/"

        assertEquals(SteamMessageContent.Image(url, "Steam Community"), parseSteamMessageContent(url))
    }

    @Test
    fun `a chat ugc url becomes media but lookalikes do not`() {
        val url = "https://steamusercontent-a.akamaihd.net/ugc/123/ABC/"

        assertEquals(SteamMessageContent.Media(url), parseSteamMessageContent(url))
        assertEquals(
            SteamMessageContent.Media("https://edge.steamusercontent-a.akamaihd.net/ugc/123/ABC/"),
            parseSteamMessageContent("https://edge.steamusercontent-a.akamaihd.net/ugc/123/ABC/"),
        )
        assertEquals(
            SteamMessageContent.Link("https://notsteamusercontent-a.akamaihd.net/ugc/123/ABC/", null),
            parseSteamMessageContent("https://notsteamusercontent-a.akamaihd.net/ugc/123/ABC/"),
        )
    }

    @Test
    fun `an audio-only mp4 is a voice message despite its video mime type`() {
        assertEquals(
            SteamMediaKind.VOICE,
            classifySteamMedia("video/mp4", hasVideo = false, hasAudio = true),
        )
    }

    @Test
    fun `an mp4 with a video track is a round video`() {
        assertEquals(
            SteamMediaKind.ROUND_VIDEO,
            classifySteamMedia("video/mp4", hasVideo = true, hasAudio = true),
        )
    }

    @Test
    fun `video mime without readable tracks stays unknown`() {
        assertEquals(
            SteamMediaKind.UNKNOWN,
            classifySteamMedia("video/mp4", hasVideo = false, hasAudio = false),
        )
    }

    @Test
    fun `surrounding whitespace still yields a card`() {
        val url = "https://images.steamusercontent.com/ugc/1/A/"

        assertEquals(SteamMessageContent.Image(url, "Steam Community"), parseSteamMessageContent("  $url\n"))
    }

    @Test
    fun `a url inside a sentence stays text so the message isn't lost`() {
        val text = "смотри https://images.steamusercontent.com/ugc/1/A/ красота"

        assertEquals(SteamMessageContent.Text(text), parseSteamMessageContent(text))
    }

    @Test
    fun `community and store links are labelled but not previewed as images`() {
        assertEquals(
            SteamMessageContent.Link("https://steamcommunity.com/id/example-user/", "Steam Community"),
            parseSteamMessageContent("https://steamcommunity.com/id/example-user/"),
        )
        assertEquals(
            SteamMessageContent.Link("https://store.steampowered.com/app/570/", "Steam Store"),
            parseSteamMessageContent("https://store.steampowered.com/app/570/"),
        )
    }

    @Test
    fun `a non-steam url is a plain unlabelled link`() {
        assertEquals(
            SteamMessageContent.Link("https://example.com/x", null),
            parseSteamMessageContent("https://example.com/x"),
        )
    }

    @Test
    fun `a lookalike host cannot pose as steam`() {
        // Host matching is exact-or-subdomain: neither a query string nor a prefixed domain
        // may turn someone else's server into a "Steam Community" image card.
        assertEquals(
            SteamMessageContent.Link("https://evil.com/?x=images.steamusercontent.com", null),
            parseSteamMessageContent("https://evil.com/?x=images.steamusercontent.com"),
        )
        assertEquals(
            SteamMessageContent.Link("https://notsteamusercontent.com/ugc/1/", null),
            parseSteamMessageContent("https://notsteamusercontent.com/ugc/1/"),
        )
    }

    @Test
    fun `a subdomain of a steam host is still steam`() {
        val url = "https://images.steamusercontent.com/ugc/1/A/"

        assertEquals(SteamMessageContent.Image(url, "Steam Community"), parseSteamMessageContent(url))
    }
}
