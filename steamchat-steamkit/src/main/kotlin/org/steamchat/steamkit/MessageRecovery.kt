package org.steamchat.steamkit

import org.steamchat.domain.SteamMessage

internal class ReconnectBackoff {
    private var delay = 2000L
    fun nextDelay(): Long = delay.also { delay = (delay * 2).coerceAtMost(60000L) }
    fun reset() { delay = 2000L }
}

internal fun mergeMessageHistory(existing: List<SteamMessage>, incoming: List<SteamMessage>): List<SteamMessage> =
    // ponytail: render the newest 1000 messages; add disk pagination for older local history.
    (existing + incoming).distinctBy { Triple(it.senderSteamId64, it.timestamp, it.text) }
        .sortedBy { it.timestamp }.takeLast(1000)
