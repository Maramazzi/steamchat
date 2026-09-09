package org.steamchat.steamkit

import com.google.gson.JsonParser
import com.google.protobuf.ByteString
import com.google.protobuf.UnknownFieldSet
import `in`.dragonbra.javasteam.base.ClientMsgProtobuf
import `in`.dragonbra.javasteam.base.IPacketMsg
import `in`.dragonbra.javasteam.enums.EMsg
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesUnifiedBaseSteamclient.NoResponse
import `in`.dragonbra.javasteam.steam.handlers.ClientMsgHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.steamchat.domain.SteamWebRtcProbeEvent

/** Wire fields follow SteamTracking/Protobufs webui/service_webrtc.proto and service_voicechat.proto (fcbb9a107a0f).
 * NoResponse is an empty generated envelope; unknown fields preserve the exact protobuf wire.
 */
internal class WebRtcProbeHandler(
    private val ownSteamId64: Long = 0,
    private val emit: (SteamWebRtcProbeEvent) -> Unit,
) : ClientMsgHandler() {
    internal data class Call(
        val job: Long,
        val partner: Long,
        val voiceChatId: CompletableDeferred<Long>,
        var resolvedVoiceChatId: Long = 0,
        var updateJob: Long = 0,
        /** True while [job] is an AnswerOneOnOneChat rather than a RequestOneOnOneChat. */
        val answering: Boolean = false,
    )
    internal data class Connection(val ssrc: Long, val clientIp: Long, val clientPort: Long, val serverIp: Long, val serverPort: Long)
    internal data class Probe(
        val job: Long,
        val ssrc: Long,
        val answer: CompletableDeferred<String>,
        var connected: Boolean = false,
        var connection: Connection? = null,
        var call: Call? = null,
    )
    private var probe: Probe? = null

    suspend fun initiate(offer: String, browserName: String, browserVersion: String): String = withContext(Dispatchers.IO) {
        val fields = offerFields(offer, browserName, browserVersion)
        val request = ClientMsgProtobuf<NoResponse.Builder>(NoResponse::class.java, EMsg.ServiceMethodCallFromClient)
        request.sourceJobID = client.getNextJobID()
        request.protoHeader.targetJobName = INITIATE
        request.body.unknownFields = fields
        val current = synchronized(this@WebRtcProbeHandler) {
            val started = begin(request.sourceJobID.value, offerSsrc(offer))
            // Serialise sending against cancel/disconnect so a cancelled probe is never sent later.
            try {
                client.send(request)
            } catch (e: Exception) {
                probe = null
                throw e
            }
            started
        }
        try {
            withTimeout(20_000) { current.answer.await() }
        } catch (e: Exception) {
            synchronized(this@WebRtcProbeHandler) {
                if (probe === current) cancel()
            }
            throw e
        }
    }

    suspend fun requestOneOnOne(partnerSteamId64: Long): Long = withContext(Dispatchers.IO) {
        val request = ClientMsgProtobuf<NoResponse.Builder>(NoResponse::class.java, EMsg.ServiceMethodCallFromClient)
        request.sourceJobID = client.getNextJobID()
        request.protoHeader.targetJobName = REQUEST_ONE_ON_ONE
        request.body.unknownFields = oneOnOneFields(partnerSteamId64)
        val pending = synchronized(this@WebRtcProbeHandler) {
            val current = checkNotNull(probe)
            val started = beginCall(request.sourceJobID.value, partnerSteamId64)
            try {
                client.send(request)
            } catch (e: Exception) {
                current.call = null
                throw e
            }
            started
        }
        withTimeout(20_000) { pending.voiceChatId.await() }
    }

    /**
     * Accepts an incoming call **on this session**. It has to be this one and not the main CM
     * connection that received the notification: the session which answers is the one Steam counts
     * as a member of the voice chat, and only a member may send UpdateVoiceChatWebRTCData - doing
     * it from elsewhere is answered with AccessDenied (seen live). There is no separate join RPC;
     * AnswerOneOnOneChat is the join (checked against service_voicechat.proto).
     */
    suspend fun joinOneOnOne(voiceChatId: Long, partnerSteamId64: Long) = withContext(Dispatchers.IO) {
        require(voiceChatId != 0L && partnerSteamId64 > 0L) { "Invalid one-to-one call IDs" }
        val request = ClientMsgProtobuf<NoResponse.Builder>(NoResponse::class.java, EMsg.ServiceMethodCallFromClient)
        request.sourceJobID = client.getNextJobID()
        request.protoHeader.targetJobName = VoiceCallHandler.ANSWER_ONE_ON_ONE
        request.body.unknownFields = VoiceCallHandler.answerFields(voiceChatId, partnerSteamId64, true)
        synchronized(this@WebRtcProbeHandler) {
            val current = checkNotNull(probe) { "WebRTC probe is not active" }
            check(current.connected) { "WebRTC probe is not connected" }
            check(current.call == null) { "A one-to-one call is already active" }
            val resolved = CompletableDeferred<Long>().also { it.complete(voiceChatId) }
            current.call = Call(
                request.sourceJobID.value,
                partnerSteamId64,
                resolved,
                resolvedVoiceChatId = voiceChatId,
                answering = true,
            )
            client.send(request)
        }
    }

    suspend fun acknowledgeUpdate(version: Long) = withContext(Dispatchers.IO) {
        val request = ClientMsgProtobuf<NoResponse.Builder>(NoResponse::class.java, EMsg.ServiceMethodCallFromClient)
        request.sourceJobID = client.getNextJobID()
        request.protoHeader.targetJobName = ACKNOWLEDGE_UPDATED
        synchronized(this@WebRtcProbeHandler) {
            request.body.unknownFields = acknowledgeFields(
                checkNotNull(probe?.connection) { "WebRTC probe is not connected" },
                version,
            )
            client.send(request)
        }
    }

    @Synchronized
    internal fun beginCall(job: Long, partnerSteamId64: Long): Call {
        val current = checkNotNull(probe) { "WebRTC probe is not active" }
        check(current.connected) { "WebRTC probe is not connected" }
        check(current.call == null) { "A one-to-one call is already active" }
        return Call(job, partnerSteamId64, CompletableDeferred()).also { current.call = it }
    }

    @Synchronized
    internal fun begin(job: Long, ssrc: Long): Probe {
        check(probe == null) { "A WebRTC probe is already active" }
        return Probe(job, ssrc, CompletableDeferred()).also { probe = it }
    }

    @Synchronized
    fun cancel() {
        val old = probe ?: return
        old.call?.let { call ->
            runCatching {
                val request = ClientMsgProtobuf<NoResponse.Builder>(NoResponse::class.java, EMsg.ServiceMethodCallFromClient)
                request.sourceJobID = client.getNextJobID()
                request.protoHeader.targetJobName = END_ONE_ON_ONE
                request.body.unknownFields = oneOnOneFields(call.partner)
                client.send(request)
            }
        }
        probe = null
        old.answer.cancel()
        old.call?.voiceChatId?.cancel()
        emit(SteamWebRtcProbeEvent.Disconnected())
    }

    @Synchronized
    override fun handleMsg(packetMsg: IPacketMsg) {
        val current = probe ?: return
        if (!packetMsg.isProto || packetMsg.msgType !in listOf(EMsg.ServiceMethod, EMsg.ServiceMethodResponse)) return
        val message = ClientMsgProtobuf<NoResponse.Builder>(NoResponse::class.java, packetMsg)
        val fields = message.body.unknownFields
        when (packetMsg.msgType) {
            EMsg.ServiceMethodResponse -> {
                when (packetMsg.targetJobID) {
                    current.job -> {
                        val result = EResult.from(message.protoHeader.eresult)
                        val answer = fields.getField(1).lengthDelimitedList.singleOrNull()?.toStringUtf8()
                        if (result != EResult.OK || answer.isNullOrBlank()) {
                            current.answer.completeExceptionally(IllegalStateException("WebRTC initiation failed: $result"))
                        } else {
                            current.answer.complete(answer)
                        }
                    }
                    current.call?.job -> {
                        val call = current.call ?: return
                        val result = EResult.from(message.protoHeader.eresult)
                        if (call.answering) {
                            // Joined; only now is this session allowed to publish its WebRTC data.
                            if (result == EResult.OK) sendVoiceWebRtcUpdate(current, call)
                            else emit(SteamWebRtcProbeEvent.Disconnected("Steam отклонил AnswerOneOnOneChat: $result"))
                            return
                        }
                        val voiceChatId = fields.getField(1).fixed64List.singleOrNull()
                        if (result != EResult.OK || voiceChatId == null || voiceChatId == 0L) {
                            call.voiceChatId.completeExceptionally(IllegalStateException("Call invitation failed: $result"))
                        } else {
                            call.resolvedVoiceChatId = voiceChatId
                            call.voiceChatId.complete(voiceChatId)
                        }
                    }
                    current.call?.updateJob -> {
                        val call = current.call ?: return
                        if (EResult.from(message.protoHeader.eresult) == EResult.OK) {
                            val status = ClientMsgProtobuf<NoResponse.Builder>(NoResponse::class.java, EMsg.ServiceMethodCallFromClient)
                            status.sourceJobID = client.getNextJobID()
                            status.protoHeader.targetJobName = NOTIFY_VOICE_STATUS
                            status.body.unknownFields = voiceStatusFields(call.resolvedVoiceChatId, ownSteamId64)
                            try {
                                client.send(status)
                            } catch (_: Exception) {
                                emit(SteamWebRtcProbeEvent.Disconnected("не удалось отправить статус микрофона"))
                                return
                            }
                            emit(SteamWebRtcProbeEvent.OneOnOneResponse(call.resolvedVoiceChatId, call.partner, true))
                        } else {
                            emit(
                                SteamWebRtcProbeEvent.Disconnected(
                                    "Steam отклонил UpdateVoiceChatWebRTCData: ${EResult.from(message.protoHeader.eresult)}",
                                ),
                            )
                        }
                    }
                }
            }
            EMsg.ServiceMethod -> if (message.protoHeader.targetJobName == CONNECTED) {
                val values = (1..5).map { fields.getField(it).varintList.singleOrNull() ?: return }
                if (values[0] != current.ssrc || values.any { it !in 0..0xffffffffL } ||
                    values[2] !in 1..65535 || values[4] !in 1..65535) return
                current.connected = true
                current.connection = Connection(values[0], values[1], values[2], values[3], values[4])
                emit(SteamWebRtcProbeEvent.Connected(values[0], values[1], values[2].toInt(), values[3], values[4].toInt()))
            } else if (message.protoHeader.targetJobName == UPDATED && current.connected) {
                val description = fields.getField(1).lengthDelimitedList.singleOrNull()?.toStringUtf8() ?: return
                val version = fields.getField(2).varintList.singleOrNull() ?: return
                emit(SteamWebRtcProbeEvent.RemoteDescriptionUpdated(description, version, ssrcOwners(fields)))
            } else if (message.protoHeader.targetJobName == ONE_ON_ONE_RESPONSE) {
                val call = current.call ?: return
                val voiceChatId = fields.getField(1).fixed64List.singleOrNull() ?: return
                val partner = fields.getField(2).fixed64List.singleOrNull() ?: return
                val accepted = fields.getField(3).varintList.singleOrNull() ?: return
                if (partner == call.partner && voiceChatId == call.resolvedVoiceChatId && accepted in 0..1) {
                    if (accepted == 0L) {
                        emit(SteamWebRtcProbeEvent.OneOnOneResponse(voiceChatId, partner, false))
                    } else if (call.updateJob == 0L) sendVoiceWebRtcUpdate(current, call)
                }
            } else if (message.protoHeader.targetJobName == VOICE_CHAT_ENDED) {
                // Handled on this session rather than the main one: this is the connection that
                // actually joined the voice chat, so it is the one Steam is talking to about it.
                val call = current.call ?: return
                val voiceChatId = fields.getField(1).fixed64List.singleOrNull() ?: return
                if (voiceChatId == call.resolvedVoiceChatId) emit(SteamWebRtcProbeEvent.CallEnded(voiceChatId))
            } else if (message.protoHeader.targetJobName == VOICE_STATUS) {
                val call = current.call ?: return
                val voiceChatId = fields.getField(1).fixed64List.singleOrNull() ?: return
                val steamId = fields.getField(2).fixed64List.singleOrNull() ?: return
                if (voiceChatId == call.resolvedVoiceChatId && steamId == call.partner) {
                    emit(
                        SteamWebRtcProbeEvent.PartnerVoiceStatus(
                            muted = fields.getField(3).varintList.singleOrNull() == 1L,
                            hasNoMic = fields.getField(5).varintList.singleOrNull() == 1L,
                            sampleRate = fields.getField(6).varintList.singleOrNull()?.toInt() ?: 0,
                        ),
                    )
                }
            }
            else -> Unit
        }
        // ponytail: initial handshake only. Updates have no session identifier; the UI must abort
        // this diagnostic on an update, never apply possibly stale renegotiation to the new PC.
    }

    private fun sendVoiceWebRtcUpdate(current: Probe, call: Call) {
        val connection = current.connection ?: error("WebRTC probe is not connected")
        val request = ClientMsgProtobuf<NoResponse.Builder>(NoResponse::class.java, EMsg.ServiceMethodCallFromClient)
        request.sourceJobID = client.getNextJobID()
        request.protoHeader.targetJobName = UPDATE_VOICE_WEBRTC_DATA
        request.body.unknownFields = voiceWebRtcFields(call.resolvedVoiceChatId, connection)
        call.updateJob = request.sourceJobID.value
        client.send(request)
    }

    companion object {
        const val INITIATE = "WebRTCClient.InitiateWebRTCConnection#1"
        const val CONNECTED = "WebRTCClientNotifications.NotifyWebRTCSessionConnected#1"
        const val UPDATED = "WebRTCClientNotifications.NotifyWebRTCUpdateRemoteDescription#1"
        const val ACKNOWLEDGE_UPDATED = "WebRTCClient.AcknowledgeUpdatedRemoteDescription#1"
        const val REQUEST_ONE_ON_ONE = "VoiceChat.RequestOneOnOneChat#1"
        const val END_ONE_ON_ONE = "VoiceChat.EndOneOnOneChat#1"
        const val ONE_ON_ONE_RESPONSE = "VoiceChatClient.NotifyOneOnOneChatResponse#1"
        const val UPDATE_VOICE_WEBRTC_DATA = "VoiceChat.UpdateVoiceChatWebRTCData#1"
        const val NOTIFY_VOICE_STATUS = "VoiceChat.NotifyUserVoiceStatus#1"
        const val VOICE_STATUS = "VoiceChatClient.NotifyUserVoiceStatus#1"
        const val VOICE_CHAT_ENDED = "VoiceChatClient.NotifyVoiceChatEnded#1"

        fun oneOnOneFields(partnerSteamId64: Long): UnknownFieldSet {
            require(partnerSteamId64 > 0) { "Invalid partner Steam ID" }
            return UnknownFieldSet.newBuilder().addField(
                1,
                UnknownFieldSet.Field.newBuilder().addFixed64(partnerSteamId64).build(),
            ).build()
        }

        fun voiceWebRtcFields(voiceChatId: Long, connection: Connection): UnknownFieldSet {
            require(voiceChatId != 0L) { "Invalid voice chat ID" }
            return UnknownFieldSet.newBuilder().apply {
                addField(1, UnknownFieldSet.Field.newBuilder().addFixed64(voiceChatId).build())
                listOf(connection.serverIp, connection.serverPort, connection.clientIp, connection.clientPort, connection.ssrc)
                    .forEachIndexed { index, value -> addField(index + 2, UnknownFieldSet.Field.newBuilder().addVarint(value).build()) }
                addField(7, UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.copyFromUtf8("SteamChat Android WebRTC probe")).build())
                (8..11).forEach { addField(it, UnknownFieldSet.Field.newBuilder().addVarint(0).build()) }
            }.build()
        }

        fun voiceStatusFields(voiceChatId: Long, ownSteamId64: Long): UnknownFieldSet {
            require(voiceChatId != 0L && ownSteamId64 > 0) { "Invalid voice status IDs" }
            return UnknownFieldSet.newBuilder().apply {
                addField(1, UnknownFieldSet.Field.newBuilder().addFixed64(voiceChatId).build())
                addField(2, UnknownFieldSet.Field.newBuilder().addFixed64(ownSteamId64).build())
                (3..5).forEach { addField(it, UnknownFieldSet.Field.newBuilder().addVarint(0).build()) }
                addField(6, UnknownFieldSet.Field.newBuilder().addVarint(48_000).build())
                addField(7, UnknownFieldSet.Field.newBuilder().addVarint(0).build())
            }.build()
        }

        /** Repeated CSSRCToAccountIDMapping (field 3): ssrc (1) -> accountid (2), each embedded. */
        fun ssrcOwners(fields: UnknownFieldSet): Map<Long, Long> =
            fields.getField(3).lengthDelimitedList.mapNotNull { bytes ->
                runCatching {
                    val entry = UnknownFieldSet.parseFrom(bytes)
                    val ssrc = entry.getField(1).varintList.singleOrNull()
                    val account = entry.getField(2).varintList.singleOrNull()
                    if (ssrc == null || account == null) null else ssrc to account
                }.getOrNull()
            }.toMap()

        fun acknowledgeFields(connection: Connection, version: Long): UnknownFieldSet {
            require(version > 0) { "Invalid remote description version" }
            return UnknownFieldSet.newBuilder().apply {
                listOf(connection.serverIp, connection.serverPort, connection.clientIp, connection.clientPort, version)
                    .forEachIndexed { index, value -> addField(index + 1, UnknownFieldSet.Field.newBuilder().addVarint(value).build()) }
            }.build()
        }

        fun offerFields(offer: String, browserName: String, browserVersion: String): UnknownFieldSet {
            require(offer.length <= 256_000 && browserName.length in 1..128 && browserVersion.length in 1..128) {
                "Invalid WebRTC probe request size"
            }
            offerSsrc(offer)
            return UnknownFieldSet.newBuilder().apply {
                listOf(offer, browserName, browserVersion).forEachIndexed { index, value ->
                    addField(index + 1, UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.copyFromUtf8(value)).build())
                }
            }.build()
        }

        fun offerSsrc(offer: String): Long {
            val ssrc = try {
                val json = JsonParser.parseString(offer).asJsonObject
                require(json.get("type").asString == "offer")
                val sdp = json.get("sdp").asString
                var audio = false
                sdp.lineSequence().mapNotNull { line ->
                    if (line.startsWith("m=")) audio = line.startsWith("m=audio ")
                    if (audio) Regex("^a=ssrc:(\\d+) ").find(line)?.groupValues?.get(1)?.toLongOrNull() else null
                }.distinct().singleOrNull()
            } catch (_: Exception) { null }
            require(ssrc != null && ssrc in 1..0xffffffffL) { "Offer must contain exactly one audio SSRC" }
            return ssrc
        }
    }
}
