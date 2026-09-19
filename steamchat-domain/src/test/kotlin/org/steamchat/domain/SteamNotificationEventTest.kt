package org.steamchat.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SteamNotificationEventTest {
    @Test
    fun `only incoming chat messages become notification events`() {
        val message = SteamMessage(1, 42, 42, "hello", 1000, false)
        assertEquals(SteamNotificationEvent(42, null, null, "hello"), SteamNotificationEvent.from(message))
        assertNull(SteamNotificationEvent.from(message.copy(isOutgoing = true)))

        val group = SteamGroupMessage(SteamGroupMessageId(1, 0), 7, 8, 42, "Alice", null, "hi", 1000, false)
        assertEquals(SteamNotificationEvent(7, 8, "Alice", "hi"), SteamNotificationEvent.from(group))
        assertNull(SteamNotificationEvent.from(group.copy(isOutgoing = true)))
        assertNull(SteamNotificationEvent.from(group.copy(isSystem = true)))
        assertNull(SteamNotificationEvent.from(group.copy(text = "")))
    }
}
