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

    @Test
    fun `a local video note round trips through message text`() {
        val text = localVideoNoteMessageText("/data/user/0/org.steamchat/files/note.mp4", 4_250)

        assertEquals(
            SteamMessageContent.LocalVideoNote("/data/user/0/org.steamchat/files/note.mp4", 4_250),
            parseSteamMessageContent(text),
        )
    }

    @Test
    fun `a malformed local video note stays plain text`() {
        val text = "steamchat-video-note|zero|/tmp/note.mp4"

        assertEquals(SteamMessageContent.Text(text), parseSteamMessageContent(text))
    }
}
