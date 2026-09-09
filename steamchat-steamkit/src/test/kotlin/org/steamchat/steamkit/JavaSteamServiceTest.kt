package org.steamchat.steamkit

import `in`.dragonbra.javasteam.enums.EClientPersonaStateFlag
import `in`.dragonbra.javasteam.enums.EPersonaState
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverFriends.CMsgClientPersonaState
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.PersonaStateCallback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.steamchat.domain.SteamGamePresence
import org.steamchat.domain.SteamStatus
import org.steamchat.domain.SteamUser
import org.steamchat.service.NoOpSessionStore
import java.util.EnumSet

/**
 * mergePersona() scenarios - real PersonaStateCallback fixtures built from the actual protobuf
 * builder (verified against the JavaSteam 1.8.0 jar via javap, not guessed), not mocks. The
 * original bug: mergePersona() gated game state on GameExtraInfo (code 256), but SteamKit2's own
 * handler updates gameName/gameId/gameDataBlob under GameDataBlob (code 512) - a later callback
 * that carried Status but not GameExtraInfo left a stale "still playing" game hanging around after
 * the player actually quit. Confirmed live via javap that GameExtraInfo=256 and GameDataBlob=512
 * are genuinely different bits, not aliases.
 */
class JavaSteamServiceTest {

    private val service = JavaSteamService(NoOpSessionStore)

    private fun callback(
        flags: Set<EClientPersonaStateFlag>,
        personaState: Int = EPersonaState.Online.code(),
        gamePlayedAppId: Int = 0,
        gameId: Long = 0L,
        gameName: String = "",
        richPresence: List<Pair<String, String>> = emptyList(),
    ): PersonaStateCallback {
        val builder = CMsgClientPersonaState.Friend.newBuilder()
            .setFriendid(1L)
            .setPersonaState(personaState)
            .setGamePlayedAppId(gamePlayedAppId)
            .setGameid(gameId)
            .setGameName(gameName)
        richPresence.forEach { (k, v) ->
            builder.addRichPresence(CMsgClientPersonaState.Friend.KV.newBuilder().setKey(k).setValue(v))
        }
        return PersonaStateCallback(builder.build(), EnumSet.copyOf(flags))
    }

    @Test
    fun `online with GameDataBlob starts a Playing session`() {
        val existing = SteamUser(1L, "Player", null, SteamStatus.ONLINE, SteamGamePresence.NotPlaying)
        val cb = callback(
            flags = setOf(EClientPersonaStateFlag.Status, EClientPersonaStateFlag.GameDataBlob),
            gamePlayedAppId = 570,
            gameId = 570L,
            gameName = "Dota 2",
        )

        val result = service.mergePersona(existing, 1L, cb)

        assertEquals(SteamStatus.ONLINE, result.status)
        assertEquals(SteamGamePresence.Playing(570, 570L, "Dota 2"), result.game)
    }

    @Test
    fun `GameDataBlob switches from one game straight to another`() {
        val existing = SteamUser(1L, "Player", null, SteamStatus.ONLINE, SteamGamePresence.Playing(570, 570L, "Dota 2"))
        val cb = callback(
            flags = setOf(EClientPersonaStateFlag.GameDataBlob),
            gamePlayedAppId = 440,
            gameId = 440L,
            gameName = "Team Fortress 2",
        )

        val result = service.mergePersona(existing, 1L, cb)

        assertEquals(SteamGamePresence.Playing(440, 440L, "Team Fortress 2"), result.game)
    }

    @Test
    fun `GameDataBlob with all-zero fields clears Playing to NotPlaying`() {
        val existing = SteamUser(1L, "Player", null, SteamStatus.ONLINE, SteamGamePresence.Playing(570, 570L, "Dota 2"))
        val cb = callback(flags = setOf(EClientPersonaStateFlag.GameDataBlob))

        val result = service.mergePersona(existing, 1L, cb)

        assertEquals(SteamGamePresence.NotPlaying, result.game)
    }

    @Test
    fun `Status-only callback going offline does not touch an existing Playing game`() {
        // This is the exact shape of the original bug report: a later, narrower callback (Status
        // only, no GameDataBlob) must never be read as "stopped playing".
        val existing = SteamUser(1L, "Player", null, SteamStatus.ONLINE, SteamGamePresence.Playing(570, 570L, "Dota 2"))
        val cb = callback(flags = setOf(EClientPersonaStateFlag.Status), personaState = EPersonaState.Offline.code())

        val result = service.mergePersona(existing, 1L, cb)

        assertEquals(SteamStatus.OFFLINE, result.status)
        assertEquals(SteamGamePresence.Playing(570, 570L, "Dota 2"), result.game)
    }

    @Test
    fun `Status-only callback going online does not fabricate a game`() {
        val existing = SteamUser(1L, "Player", null, SteamStatus.OFFLINE, SteamGamePresence.NotPlaying)
        val cb = callback(flags = setOf(EClientPersonaStateFlag.Status), personaState = EPersonaState.Online.code())

        val result = service.mergePersona(existing, 1L, cb)

        assertEquals(SteamStatus.ONLINE, result.status)
        assertEquals(SteamGamePresence.NotPlaying, result.game)
    }

    @Test
    fun `an unknown game stays Unknown through a presence callback with no GameDataBlob`() {
        // Distinct from the NotPlaying case above: "we haven't been told yet" must not silently
        // become "Steam says they're not playing", and must certainly not become Playing.
        val cb = callback(
            flags = setOf(EClientPersonaStateFlag.Status, EClientPersonaStateFlag.PlayerName, EClientPersonaStateFlag.Presence),
            personaState = EPersonaState.Online.code(),
        )

        val result = service.mergePersona(null, 1L, cb)

        assertEquals(SteamStatus.ONLINE, result.status)
        assertEquals(SteamGamePresence.Unknown, result.game)
    }

    @Test
    fun `coming back from Invisible to Online keeps the game that was already running`() {
        // Mirror of the offline case: the game survived going invisible, so it must still be there
        // on the way back - a status flip in either direction is not a game-state update.
        val existing = SteamUser(1L, "Player", null, SteamStatus.OFFLINE, SteamGamePresence.Playing(570, 570L, "Dota 2"))
        val cb = callback(flags = setOf(EClientPersonaStateFlag.Status), personaState = EPersonaState.Online.code())

        val result = service.mergePersona(existing, 1L, cb)

        assertEquals(SteamStatus.ONLINE, result.status)
        assertEquals(SteamGamePresence.Playing(570, 570L, "Dota 2"), result.game)
    }

    @Test
    fun `a Status-only callback reporting Invisible leaves an existing Playing game alone`() {
        // Exercises the real EPersonaState.Invisible code end-to-end through mergePersona (not
        // just the isolated toSteamStatus() mapping test) to confirm going invisible collapses to
        // SteamStatus.OFFLINE the same way Offline itself does, and - the actual point of this
        // test - still never touches game without a GameDataBlob.
        val existing = SteamUser(1L, "Player", null, SteamStatus.ONLINE, SteamGamePresence.Playing(570, 570L, "Dota 2"))
        val cb = callback(flags = setOf(EClientPersonaStateFlag.Status), personaState = EPersonaState.Invisible.code())

        val result = service.mergePersona(existing, 1L, cb)

        assertEquals(SteamStatus.OFFLINE, result.status)
        assertEquals(SteamGamePresence.Playing(570, 570L, "Dota 2"), result.game)
    }

    @Test
    fun `RichPresence-only callback updates presence without disturbing the existing game`() {
        val existing = SteamUser(1L, "Player", null, SteamStatus.ONLINE, SteamGamePresence.Playing(570, 570L, "Dota 2"))
        val cb = callback(
            flags = setOf(EClientPersonaStateFlag.RichPresence),
            richPresence = listOf("status" to "In Menu"),
        )

        val result = service.mergePersona(existing, 1L, cb)

        assertEquals(
            SteamGamePresence.Playing(570, 570L, "Dota 2", richPresence = mapOf("status" to "In Menu")),
            result.game,
        )
    }

    @Test
    fun `Playing can carry a real appId and gameId with no name yet`() {
        val cb = callback(
            flags = setOf(EClientPersonaStateFlag.GameDataBlob),
            gamePlayedAppId = 570,
            gameId = 570L,
            gameName = "",
        )

        val result = service.mergePersona(null, 1L, cb)

        assertEquals(SteamGamePresence.Playing(570, 570L, null), result.game)
    }

    @Test
    fun `a callback carrying only an unrelated flag changes nothing`() {
        val existing = SteamUser(1L, "Player", "url", SteamStatus.ONLINE, SteamGamePresence.Playing(570, 570L, "Dota 2"))
        val cb = callback(flags = setOf(EClientPersonaStateFlag.QueryPort))

        val result = service.mergePersona(existing, 1L, cb)

        assertEquals(existing, result)
    }

    @Test
    fun `game app id comes from the dedicated gamePlayedAppId field, not a bit-extracted GameID`() {
        // A packed GameID for a non-app session (mod/shortcut/P2P file) can bit-extract to a
        // number that collides with a real Steam appid - only gamePlayedAppId is guaranteed to be
        // a genuine Steam app id (or 0). Regression check for that exact confusion.
        val cb = callback(
            flags = setOf(EClientPersonaStateFlag.GameDataBlob),
            gamePlayedAppId = 0,
            gameId = 0x0200000000001234L, // shortcut-shaped GameID: non-zero, not a plain app id
            gameName = "Some Shortcut",
        )

        val result = service.mergePersona(null, 1L, cb).game as? SteamGamePresence.Playing
            ?: error("expected Playing")

        assertEquals(null, result.appId)
        assertEquals(0x0200000000001234L, result.gameId)
    }
}
