package org.steamchat.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SteamWebRtcSdpTest {
    @Test
    fun `normalizes only the opus format like Steam Web`() {
        val offer = """v=0
m=audio 9 UDP/TLS/RTP/SAVPF 111 63
a=rtpmap:111 opus/48000/2
a=fmtp:111 minptime=10;useinbandfec=1
a=rtpmap:63 red/48000/2
a=fmtp:63 111/111
"""

        assertEquals(
            offer.replace(
                "a=fmtp:111 minptime=10;useinbandfec=1",
                "a=fmtp:111 minptime=10;useinbandfec=1;usedtx=1",
            ),
            normalizeSteamWebRtcOfferSdp(offer),
        )
    }

    @Test
    fun `normalizes opus independently in every media section`() {
        val offer = """v=0
m=audio 9 UDP/TLS/RTP/SAVPF 111
a=rtpmap:111 opus/48000/2
a=fmtp:111 minptime=10
m=audio 9 UDP/TLS/RTP/SAVPF 109
a=rtpmap:109 opus/48000/2
a=fmtp:109 useinbandfec=1
"""

        assertEquals(
            offer
                .replace("a=fmtp:111 minptime=10", "a=fmtp:111 minptime=10;useinbandfec=1;usedtx=1")
                .replace("a=fmtp:109 useinbandfec=1", "a=fmtp:109 minptime=10;useinbandfec=1;usedtx=1"),
            normalizeSteamWebRtcOfferSdp(offer),
        )
    }
}
