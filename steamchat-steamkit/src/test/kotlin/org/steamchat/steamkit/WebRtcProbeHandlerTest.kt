package org.steamchat.steamkit

import com.google.gson.JsonObject
import com.google.protobuf.ByteString
import com.google.protobuf.UnknownFieldSet
import `in`.dragonbra.javasteam.base.ClientMsgProtobuf
import `in`.dragonbra.javasteam.base.PacketClientMsgProtobuf
import `in`.dragonbra.javasteam.enums.EMsg
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesUnifiedBaseSteamclient.NoResponse
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.steamchat.domain.SteamWebRtcProbeEvent

class WebRtcProbeHandlerTest {
    private val offer = JsonObject().apply {
        addProperty("type", "offer")
        addProperty("sdp", "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=ssrc:4294967295 cname:test\r\na=ssrc:4294967295 msid:test audio\r\n")
    }.toString()

    @Test fun `offer wire uses three string fields and unsigned audio SSRC`() {
        val fields = WebRtcProbeHandler.offerFields(offer, "Chrome", "120.0")
        val decoded = NoResponse.parseFrom(NoResponse.newBuilder().setUnknownFields(fields).build().toByteArray()).unknownFields
        assertEquals(offer, decoded.getField(1).lengthDelimitedList.single().toStringUtf8())
        assertEquals("Chrome", decoded.getField(2).lengthDelimitedList.single().toStringUtf8())
        assertEquals("120.0", decoded.getField(3).lengthDelimitedList.single().toStringUtf8())
        assertEquals(4294967295L, WebRtcProbeHandler.offerSsrc(offer))
        assertThrows(IllegalArgumentException::class.java) { WebRtcProbeHandler.offerSsrc("{broken") }
        assertThrows(IllegalArgumentException::class.java) { WebRtcProbeHandler.offerSsrc(offer.replace("m=audio", "m=video")) }
        assertThrows(IllegalArgumentException::class.java) { WebRtcProbeHandler.offerSsrc(offer.replace("4294967295", "4294967296")) }
    }

    @Test fun `routes only current response job and current SSRC and clears cancelled probe`() = runTest {
        val events = mutableListOf<SteamWebRtcProbeEvent>()
        val handler = WebRtcProbeHandler { events += it }
        val pending = handler.begin(41, 4294967295L)
        assertThrows(IllegalStateException::class.java) { handler.begin(42, 1) }
        handler.handleMsg(response(40, 1, "answer"))
        assertFalse(pending.answer.isCompleted)
        handler.handleMsg(response(41, 1, "answer"))
        assertEquals("answer", pending.answer.await())
        handler.handleMsg(connected(1))
        assertTrue(events.isEmpty())
        handler.handleMsg(connected(4294967295L))
        assertEquals(4294967295L, (events.single() as SteamWebRtcProbeEvent.Connected).ssrc)
        handler.cancel()
        handler.handleMsg(connected(4294967295L))
        assertEquals(2, events.size)
        val next = handler.begin(42, 5)
        handler.handleMsg(response(41, 1, "stale"))
        handler.handleMsg(connected(4294967295L))
        assertFalse(next.answer.isCompleted)
        assertEquals(2, events.size)
        handler.cancel()
        assertTrue(next.answer.isCancelled)
    }

    @Test fun `server errors and empty answers fail the pending request`() = runTest {
        for ((result, answer) in listOf(15 to "", 1 to "")) {
            val handler = WebRtcProbeHandler {}
            val pending = handler.begin(7, 1)
            handler.handleMsg(response(7, result, answer))
            assertTrue(pending.answer.isCancelled)
            handler.cancel()
        }
    }

    @Test fun `renegotiation is reported only after correlated connection and never after cancellation`() {
        val events = mutableListOf<SteamWebRtcProbeEvent>()
        val handler = WebRtcProbeHandler { events += it }
        handler.begin(1, 5)
        val message = ClientMsgProtobuf<NoResponse.Builder>(NoResponse::class.java, EMsg.ServiceMethod)
        message.protoHeader.targetJobName = WebRtcProbeHandler.UPDATED
        message.body.unknownFields = UnknownFieldSet.newBuilder()
            .addField(1, UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.copyFromUtf8("update")).build())
            .addField(2, UnknownFieldSet.Field.newBuilder().addVarint(3).build()).build()
        val packet = PacketClientMsgProtobuf(message.msgType, message.serialize())
        handler.handleMsg(packet)
        assertTrue(events.isEmpty())
        handler.handleMsg(connected(5))
        handler.handleMsg(packet)
        assertEquals(SteamWebRtcProbeEvent.RemoteDescriptionUpdated("update", 3), events.last())
        handler.cancel()
        handler.handleMsg(packet)
        assertEquals(3, events.size)
    }

    @Test fun `one-to-one invitation uses fixed64 ids and routes only its response`() = runTest {
        val partner = 76561198000000001L
        val voiceChat = 123456789L
        assertEquals(partner, WebRtcProbeHandler.oneOnOneFields(partner).getField(1).fixed64List.single())
        assertThrows(IllegalArgumentException::class.java) { WebRtcProbeHandler.oneOnOneFields(0) }

        val events = mutableListOf<SteamWebRtcProbeEvent>()
        val handler = WebRtcProbeHandler { events += it }
        handler.begin(1, 5)
        handler.handleMsg(connected(5))
        val call = handler.beginCall(2, partner)
        handler.handleMsg(callResponse(2, voiceChat))
        assertEquals(voiceChat, call.voiceChatId.await())
        handler.handleMsg(oneOnOneResponse(voiceChat, partner + 1, true))
        assertEquals(1, events.size)
        handler.handleMsg(oneOnOneResponse(voiceChat, partner, false))
        assertEquals(SteamWebRtcProbeEvent.OneOnOneResponse(voiceChat, partner, false), events.last())
        handler.handleMsg(voiceStatus(voiceChat, partner, muted = true, hasNoMic = false, sampleRate = 48_000))
        assertEquals(SteamWebRtcProbeEvent.PartnerVoiceStatus(true, false, 48_000), events.last())

        // Reported only for the call actually in progress: a hangup for some other voice chat id
        // must not end this one.
        handler.handleMsg(voiceChatEnded(voiceChat + 1))
        assertEquals(SteamWebRtcProbeEvent.PartnerVoiceStatus(true, false, 48_000), events.last())
        handler.handleMsg(voiceChatEnded(voiceChat))
        assertEquals(SteamWebRtcProbeEvent.CallEnded(voiceChat), events.last())

        val connection = WebRtcProbeHandler.Connection(5, 0xffffffffL, 27020, 0x01020304, 27021)
        val fields = WebRtcProbeHandler.voiceWebRtcFields(voiceChat, connection)
        assertEquals(voiceChat, fields.getField(1).fixed64List.single())
        assertEquals(listOf(0x01020304L, 27021L, 0xffffffffL, 27020L, 5L),
            (2..6).map { fields.getField(it).varintList.single() })
        assertEquals("SteamChat Android WebRTC probe", fields.getField(7).lengthDelimitedList.single().toStringUtf8())
        assertEquals(listOf(0L, 0L, 0L, 0L), (8..11).map { fields.getField(it).varintList.single() })

        val status = WebRtcProbeHandler.voiceStatusFields(voiceChat, partner)
        assertEquals(voiceChat, status.getField(1).fixed64List.single())
        assertEquals(partner, status.getField(2).fixed64List.single())
        assertEquals(listOf(0L, 0L, 0L, 48_000L, 0L), (3..7).map { status.getField(it).varintList.single() })

        val acknowledgement = WebRtcProbeHandler.acknowledgeFields(connection, 9)
        assertEquals(listOf(0x01020304L, 27021L, 0xffffffffL, 27020L, 9L),
            (1..5).map { acknowledgement.getField(it).varintList.single() })
        assertThrows(IllegalArgumentException::class.java) { WebRtcProbeHandler.acknowledgeFields(connection, 0) }
        handler.cancel()
    }

    private fun response(job: Long, result: Int, answer: String): PacketClientMsgProtobuf {
        val message = ClientMsgProtobuf<NoResponse.Builder>(NoResponse::class.java, EMsg.ServiceMethodResponse)
        message.protoHeader.jobidTarget = job
        message.protoHeader.eresult = result
        message.body.unknownFields = UnknownFieldSet.newBuilder().addField(1,
            UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.copyFromUtf8(answer)).build()).build()
        return PacketClientMsgProtobuf(message.msgType, message.serialize())
    }

    private fun connected(ssrc: Long): PacketClientMsgProtobuf {
        val message = ClientMsgProtobuf<NoResponse.Builder>(NoResponse::class.java, EMsg.ServiceMethod)
        message.protoHeader.targetJobName = WebRtcProbeHandler.CONNECTED
        message.body.unknownFields = UnknownFieldSet.newBuilder().apply {
            listOf(ssrc, 1L, 1000L, 2L, 2000L).forEachIndexed { index, value ->
                addField(index + 1, UnknownFieldSet.Field.newBuilder().addVarint(value).build())
            }
        }.build()
        return PacketClientMsgProtobuf(message.msgType, message.serialize())
    }

    private fun callResponse(job: Long, voiceChatId: Long): PacketClientMsgProtobuf {
        val message = ClientMsgProtobuf<NoResponse.Builder>(NoResponse::class.java, EMsg.ServiceMethodResponse)
        message.protoHeader.jobidTarget = job
        message.protoHeader.eresult = 1
        message.body.unknownFields = UnknownFieldSet.newBuilder().addField(1,
            UnknownFieldSet.Field.newBuilder().addFixed64(voiceChatId).build()).build()
        return PacketClientMsgProtobuf(message.msgType, message.serialize())
    }

    private fun oneOnOneResponse(voiceChatId: Long, partner: Long, accepted: Boolean): PacketClientMsgProtobuf {
        val message = ClientMsgProtobuf<NoResponse.Builder>(NoResponse::class.java, EMsg.ServiceMethod)
        message.protoHeader.targetJobName = WebRtcProbeHandler.ONE_ON_ONE_RESPONSE
        message.body.unknownFields = UnknownFieldSet.newBuilder()
            .addField(1, UnknownFieldSet.Field.newBuilder().addFixed64(voiceChatId).build())
            .addField(2, UnknownFieldSet.Field.newBuilder().addFixed64(partner).build())
            .addField(3, UnknownFieldSet.Field.newBuilder().addVarint(if (accepted) 1 else 0).build())
            .build()
        return PacketClientMsgProtobuf(message.msgType, message.serialize())
    }

    private fun voiceChatEnded(voiceChatId: Long): PacketClientMsgProtobuf {
        val message = ClientMsgProtobuf<NoResponse.Builder>(NoResponse::class.java, EMsg.ServiceMethod)
        message.protoHeader.targetJobName = WebRtcProbeHandler.VOICE_CHAT_ENDED
        message.body.unknownFields = UnknownFieldSet.newBuilder()
            .addField(1, UnknownFieldSet.Field.newBuilder().addFixed64(voiceChatId).build()).build()
        return PacketClientMsgProtobuf(message.msgType, message.serialize())
    }

    private fun voiceStatus(voiceChatId: Long, partner: Long, muted: Boolean, hasNoMic: Boolean, sampleRate: Int): PacketClientMsgProtobuf {
        val message = ClientMsgProtobuf<NoResponse.Builder>(NoResponse::class.java, EMsg.ServiceMethod)
        message.protoHeader.targetJobName = WebRtcProbeHandler.VOICE_STATUS
        message.body.unknownFields = UnknownFieldSet.newBuilder().apply {
            addField(1, UnknownFieldSet.Field.newBuilder().addFixed64(voiceChatId).build())
            addField(2, UnknownFieldSet.Field.newBuilder().addFixed64(partner).build())
            addField(3, UnknownFieldSet.Field.newBuilder().addVarint(if (muted) 1 else 0).build())
            addField(5, UnknownFieldSet.Field.newBuilder().addVarint(if (hasNoMic) 1 else 0).build())
            addField(6, UnknownFieldSet.Field.newBuilder().addVarint(sampleRate.toLong()).build())
        }.build()
        return PacketClientMsgProtobuf(message.msgType, message.serialize())
    }
}
