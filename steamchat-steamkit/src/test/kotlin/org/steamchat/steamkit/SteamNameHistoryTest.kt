package org.steamchat.steamkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SteamNameHistoryTest {

    @Test
    fun `parseNameHistory reads name and timestamp pairs, most recent first, from a real captured response`() {
        // Real response, fetched live from a real account's ajaxaliases endpoint - not guessed shape.
        val json = """
            [{"newname":"ExampleUser","timechanged":"17 Dec, 2025 @ 2:30am"},{"newname":"PreviousName","timechanged":"13 Dec, 2025 @ 10:47am"},{"newname":"OlderName","timechanged":"27 Jul, 2025 @ 4:33pm"}]
        """.trimIndent()

        val history = parseNameHistory(json)

        assertEquals(
            listOf(
                "ExampleUser" to "17 Dec, 2025 @ 2:30am",
                "PreviousName" to "13 Dec, 2025 @ 10:47am",
                "OlderName" to "27 Jul, 2025 @ 4:33pm",
            ),
            history.map { it.name to it.changedAt },
        )
    }

    @Test
    fun `parseNameHistory unescapes quotes in a persona name instead of breaking on them`() {
        // The whole reason this uses Gson instead of a regex: a hand-rolled "([^"]+)" match would
        // truncate at the embedded quote instead of reading the full name.
        val json = """[{"newname":"Weird \"Quoted\" Name","timechanged":"1 Jan, 2026 @ 12:00am"}]"""

        val history = parseNameHistory(json)

        assertEquals("Weird \"Quoted\" Name", history.single().name)
    }

    @Test
    fun `parseNameHistory on malformed input yields an empty list, not a crash`() {
        assertEquals(emptyList<Any>(), parseNameHistory("not json"))
    }
}
