package org.steamchat.domain

data class SteamIncomingVoiceCall(val voiceChatId: Long, val partnerSteamId64: Long)

sealed interface SteamWebRtcProbeEvent {
    data class Connected(
        val ssrc: Long,
        val clientIp: Long,
        val clientPort: Int,
        val serverIp: Long,
        val serverPort: Int,
    ) : SteamWebRtcProbeEvent
    data class OneOnOneResponse(val voiceChatId: Long, val partnerSteamId64: Long, val accepted: Boolean) : SteamWebRtcProbeEvent
    /**
     * [ssrcOwners] maps each stream SSRC to the account that owns it (`ssrcs_to_accountids` in
     * CWebRTC_WebRTCUpdateRemoteDescription_Notification) - the only way to tell whose stream is
     * whose when a call carries several. [version] is 0 for a description returned in a reply
     * rather than pushed, which therefore needs no acknowledgement.
     */
    data class RemoteDescriptionUpdated(
        val descriptionJson: String,
        val version: Long,
        val ssrcOwners: Map<Long, Long> = emptyMap(),
    ) : SteamWebRtcProbeEvent
    data class PartnerVoiceStatus(
        val muted: Boolean,
        val hasNoMic: Boolean,
        val sampleRate: Int,
    ) : SteamWebRtcProbeEvent
    /**
     * `VoiceChatClient.NotifyVoiceChatEnded` - a real hangup that ends the call for both sides.
     * Checked live rather than assumed: the first guess was that a Steam voice chat behaves like a
     * Discord room and outlives whoever leaves, but when one side ends the call it ends for
     * everyone, so the local session must stop with it.
     */
    data class CallEnded(val voiceChatId: Long) : SteamWebRtcProbeEvent
    /**
     * [reason] carries what Steam actually said when it is known - reporting a bare "connection
     * lost" for a server that answered with a concrete EResult hides the only useful fact there is.
     */
    data class Disconnected(val reason: String? = null) : SteamWebRtcProbeEvent
}

fun normalizeSteamWebRtcOfferSdp(sdp: String): String {
    val sections = sdp.split(Regex("(?=m=)"))
    return sections.joinToString("") { section ->
        val opusPayload = Regex("(?m)^a=rtpmap:(\\d+)\\s+opus(?:/|\\s)", RegexOption.IGNORE_CASE)
            .find(section)?.groupValues?.get(1) ?: return@joinToString section
        Regex("(?m)^a=fmtp:${Regex.escape(opusPayload)}\\s+[^\\r\\n]*")
            .replaceFirst(section, "a=fmtp:$opusPayload minptime=10;useinbandfec=1;usedtx=1")
    }
}
