package org.steamchat.steamkit

import `in`.dragonbra.javasteam.enums.EPersonaState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.steamchat.domain.SteamStatus

class SteamMappingTest {

    @Test
    fun `persona state maps to domain status`() {
        assertEquals(SteamStatus.ONLINE, EPersonaState.Online.toSteamStatus())
        assertEquals(SteamStatus.BUSY, EPersonaState.Busy.toSteamStatus())
        assertEquals(SteamStatus.AWAY, EPersonaState.Away.toSteamStatus())
        assertEquals(SteamStatus.OFFLINE, EPersonaState.Offline.toSteamStatus())
        assertEquals(SteamStatus.OFFLINE, EPersonaState.Invisible.toSteamStatus())
        assertEquals(SteamStatus.OFFLINE, null.toSteamStatus())
    }

    @Test
    fun `avatar url is null for empty or all-zero hash`() {
        assertNull(avatarUrl(null))
        assertNull(avatarUrl(ByteArray(0)))
        assertNull(avatarUrl(ByteArray(20)))
    }

    @Test
    fun `avatar url is built from hex-encoded hash`() {
        val hash = byteArrayOf(0x42.toByte(), 0x7e.toByte(), 0xf7.toByte())
        assertEquals("https://avatars.akamai.steamstatic.com/427ef7_full.jpg", avatarUrl(hash))
    }

    @Test
    fun `game presence preserves non-app game ids and clears zero values`() {
        val shortcut = gamePresence(0, `in`.dragonbra.javasteam.types.GameID(0x200000000L), "", emptyMap())
        assertEquals(0x200000000L, (shortcut as org.steamchat.domain.SteamGamePresence.Playing).gameId)
        assertNull(shortcut.appId)

        assertEquals(
            org.steamchat.domain.SteamGamePresence.NotPlaying,
            gamePresence(0, `in`.dragonbra.javasteam.types.GameID(), "", emptyMap()),
        )
    }
}
