package org.steamchat.steamkit

import com.google.protobuf.UnknownFieldSet
import `in`.dragonbra.javasteam.base.ClientMsgProtobuf
import `in`.dragonbra.javasteam.base.PacketClientMsgProtobuf
import `in`.dragonbra.javasteam.enums.EMsg
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesUnifiedBaseSteamclient.NoResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VoiceCallHandlerTest {
    @Test fun `incoming notification and answer use official fixed64 fields`() {
        val voiceChatId = 123456789L
        val partner = 12345678901234567L
        var received: Pair<Long, Long>? = null
        val handler = VoiceCallHandler(onIncomingCall = { chat, caller -> received = chat to caller })
        val message = ClientMsgProtobuf<NoResponse.Builder>(NoResponse::class.java, EMsg.ServiceMethod)
        message.protoHeader.targetJobName = VoiceCallHandler.INCOMING_ONE_ON_ONE
        message.body.unknownFields = UnknownFieldSet.newBuilder()
            .addField(1, UnknownFieldSet.Field.newBuilder().addFixed64(voiceChatId).build())
            .addField(2, UnknownFieldSet.Field.newBuilder().addFixed64(partner).build())
            .build()

        handler.handleMsg(PacketClientMsgProtobuf(message.msgType, message.serialize()))

        assertEquals(voiceChatId to partner, received)
        val answer = VoiceCallHandler.answerFields(voiceChatId, partner, true)
        assertEquals(voiceChatId, answer.getField(1).fixed64List.single())
        assertEquals(partner, answer.getField(2).fixed64List.single())
        assertEquals(1L, answer.getField(3).varintList.single())
    }
}
