package org.steamchat.domain

/**
 * What a message body actually is, so the chat cell can render a Steam link as a card instead of
 * dumping a 200-character URL into a bubble. Pure text analysis, no network and no Android - lives
 * here (not in the UI module) so it's unit-testable like the rest of the domain.
 *
 * [SteamMessage] itself is untouched: this is a *view* of its text, derived on demand. Steam's
 * chat protocol has no attachment/media field at all (checked: CChatRoom_SendChatMessage_Request
 * carries only a string, and classic friend messages likewise), so a shared image genuinely *is*
 * a URL in the text - recognising it is the only way to show a picture.
 */
sealed interface SteamMessageContent {

    /** Ordinary message text (may still contain `:emoticon:` shortcodes - that's a separate pass). */
    data class Text(val text: String) : SteamMessageContent

    /** A Steam-hosted image the cell can load and show inline. [sourceLabel] captions the card. */
    data class Image(val url: String, val sourceLabel: String) : SteamMessageContent

    /** A Steam chat upload whose extension is hidden by the UGC CDN (voice note or video). */
    data class Media(val url: String) : SteamMessageContent

    /** A link worth labelling but not previewing as a picture. [sourceLabel] is null for non-Steam hosts. */
    data class Link(val url: String, val sourceLabel: String?) : SteamMessageContent
}

enum class SteamMediaKind { IMAGE, VOICE, ROUND_VIDEO, UNKNOWN }

/** Steam labels both voice notes and round videos as video/mp4, so tracks win over MIME. */
fun classifySteamMedia(contentType: String?, hasVideo: Boolean, hasAudio: Boolean): SteamMediaKind = when {
    contentType?.startsWith("image/") == true -> SteamMediaKind.IMAGE
    hasVideo -> SteamMediaKind.ROUND_VIDEO
    hasAudio -> SteamMediaKind.VOICE
    contentType?.startsWith("audio/") == true -> SteamMediaKind.VOICE
    else -> SteamMediaKind.UNKNOWN
}

private val URL_PATTERN = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

// Steam serves user-uploaded chat images/screenshots from these hosts. Checked as a host suffix,
// not a substring of the whole URL, so "evil.com/?x=steamusercontent.com" can't pose as Steam.
private val IMAGE_HOSTS = listOf("steamusercontent.com", "steamuserimages-a.akamaihd.net")

// Chat MP4 uploads use this distinct CDN host and extensionless /ugc/... URLs. Keep it separate
// from IMAGE_HOSTS: the cell has to inspect the container before choosing voice/video rendering.
private const val MEDIA_HOST = "steamusercontent-a.akamaihd.net"

private val LABELLED_HOSTS = mapOf(
    "steamcommunity.com" to "Steam Community",
    "store.steampowered.com" to "Steam Store",
    "steampowered.com" to "Steam",
)

/**
 * Only a message that is *nothing but* a single URL becomes a card. A URL mixed into a sentence
 * stays [Text]: replacing a whole message with a picture because it happened to mention a link
 * would lose what the person actually wrote, and Steam's own client behaves the same way.
 */
fun parseSteamMessageContent(text: String): SteamMessageContent {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return SteamMessageContent.Text(text)

    val match = URL_PATTERN.matchEntire(trimmed) ?: return SteamMessageContent.Text(text)
    val url = match.value
    val host = hostOf(url) ?: return SteamMessageContent.Text(text)

    if (host.hostMatches(MEDIA_HOST)) return SteamMessageContent.Media(url)
    IMAGE_HOSTS.firstOrNull { host.hostMatches(it) }?.let {
        return SteamMessageContent.Image(url, "Steam Community")
    }
    LABELLED_HOSTS.entries.firstOrNull { host.hostMatches(it.key) }?.let {
        return SteamMessageContent.Link(url, it.value)
    }
    return SteamMessageContent.Link(url, null)
}

/** Exact host or a subdomain of it - never a mere substring (see IMAGE_HOSTS). */
private fun String.hostMatches(host: String): Boolean = this == host || endsWith(".$host")

private fun hostOf(url: String): String? = Regex("""^https?://([^/?#]+)""", RegexOption.IGNORE_CASE)
    .find(url)
    ?.groupValues?.get(1)
    ?.substringAfter('@')
    ?.substringBefore(':')
    ?.lowercase()
    ?.takeIf { it.isNotEmpty() }
