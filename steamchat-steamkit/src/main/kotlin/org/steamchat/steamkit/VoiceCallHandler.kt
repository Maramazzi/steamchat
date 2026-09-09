package org.steamchat.steamkit

import `in`.dragonbra.javasteam.base.ClientMsgProtobuf
import `in`.dragonbra.javasteam.base.IPacketMsg
import `in`.dragonbra.javasteam.enums.EMsg
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver2.CMsgClientVoiceCallPreAuthorize
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver2.CMsgClientVoiceCallPreAuthorizeResponse
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesUnifiedBaseSteamclient.NoResponse
import `in`.dragonbra.javasteam.steam.handlers.ClientMsgHandler
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackMsg
import com.google.protobuf.UnknownFieldSet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference

/**
 * ClientVoiceCallPreAuthorize(Response) has no handler/callback in JavaSteam 1.8.0 - same gap
 * FriendsLevelsHandler/StickerListHandler already fill for their own bare ClientMsgProtobuf pairs.
 *
 * This legacy pre-authorize pair is inert. Real calls use unified VoiceChat service methods, which
 * this handler also decodes manually because JavaSteam 1.8.0 has no generated VoiceChat service.
 */
internal class VoiceCallPreAuthorizeCallback(packetMsg: IPacketMsg) : CallbackMsg() {
    val result: EResult
    val callerSteamId64: Long
    val receiverSteamId64: Long

    init {
        val msg = ClientMsgProtobuf<CMsgClientVoiceCallPreAuthorizeResponse.Builder>(CMsgClientVoiceCallPreAuthorizeResponse::class.java, packetMsg)
        result = EResult.from(msg.body.eresult)
        callerSteamId64 = msg.body.callerSteamid
        receiverSteamId64 = msg.body.receiverSteamid
    }
}

internal class VoiceCallHandler(
    private val onIncomingCall: (voiceChatId: Long, partnerSteamId64: Long) -> Unit = { _, _ -> },
    private val onCallEnded: (voiceChatId: Long) -> Unit = {},
) : ClientMsgHandler() {

    private data class PendingAnswer(val job: Long, val result: CompletableDeferred<Boolean>)
    private val pendingAnswer = AtomicReference<PendingAnswer?>()

    override fun handleMsg(packetMsg: IPacketMsg) {
        when {
            packetMsg.msgType == EMsg.ClientVoiceCallPreAuthorizeResponse ->
                client.postCallback(VoiceCallPreAuthorizeCallback(packetMsg))
            packetMsg.isProto && packetMsg.msgType == EMsg.ServiceMethod -> {
                val message = ClientMsgProtobuf<NoResponse.Builder>(NoResponse::class.java, packetMsg)
                val fields = message.body.unknownFields
                when (message.protoHeader.targetJobName) {
                    INCOMING_ONE_ON_ONE -> {
                        val voiceChatId = fields.getField(1).fixed64List.singleOrNull() ?: return
                        val partner = fields.getField(2).fixed64List.singleOrNull() ?: return
                        if (voiceChatId != 0L && partner > 0L) onIncomingCall(voiceChatId, partner)
                    }
                    VOICE_CHAT_ENDED -> fields.getField(1).fixed64List.singleOrNull()?.let(onCallEnded)
                }
            }
            packetMsg.isProto && packetMsg.msgType == EMsg.ServiceMethodResponse -> {
                val pending = pendingAnswer.get() ?: return
                if (packetMsg.targetJobID != pending.job) return
                val message = ClientMsgProtobuf<NoResponse.Builder>(NoResponse::class.java, packetMsg)
                pending.result.complete(EResult.from(message.protoHeader.eresult) == EResult.OK)
            }
        }
    }

    suspend fun answerOneOnOne(voiceChatId: Long, partnerSteamId64: Long, accepted: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val request = ClientMsgProtobuf<NoResponse.Builder>(NoResponse::class.java, EMsg.ServiceMethodCallFromClient)
            request.sourceJobID = client.getNextJobID()
            request.protoHeader.targetJobName = ANSWER_ONE_ON_ONE
            request.body.unknownFields = answerFields(voiceChatId, partnerSteamId64, accepted)
            val pending = PendingAnswer(request.sourceJobID.value, CompletableDeferred())
            check(pendingAnswer.compareAndSet(null, pending)) { "Another incoming call answer is pending" }
            try {
                client.send(request)
                withTimeout(20_000) { pending.result.await() }
            } finally {
                pendingAnswer.compareAndSet(pending, null)
            }
        }

    /** [hangup] false = request/ring, true = end - same message toggles both, per its own field name. */
    fun sendPreAuthorize(callerSteamId64: Long, receiverSteamId64: Long, hangup: Boolean) {
        val msg = ClientMsgProtobuf<CMsgClientVoiceCallPreAuthorize.Builder>(CMsgClientVoiceCallPreAuthorize::class.java, EMsg.ClientVoiceCallPreAuthorize)
        msg.body.callerSteamid = callerSteamId64
        msg.body.receiverSteamid = receiverSteamId64
        msg.body.hangup = hangup
        client.send(msg)
    }

    companion object {
        const val INCOMING_ONE_ON_ONE = "VoiceChatClient.NotifyOneOnOneChatRequested#1"
        const val VOICE_CHAT_ENDED = "VoiceChatClient.NotifyVoiceChatEnded#1"
        const val ANSWER_ONE_ON_ONE = "VoiceChat.AnswerOneOnOneChat#1"

        fun answerFields(voiceChatId: Long, partnerSteamId64: Long, accepted: Boolean): UnknownFieldSet {
            require(voiceChatId != 0L && partnerSteamId64 > 0L) { "Invalid one-to-one call IDs" }
            return UnknownFieldSet.newBuilder().apply {
                addField(1, UnknownFieldSet.Field.newBuilder().addFixed64(voiceChatId).build())
                addField(2, UnknownFieldSet.Field.newBuilder().addFixed64(partnerSteamId64).build())
                addField(3, UnknownFieldSet.Field.newBuilder().addVarint(if (accepted) 1 else 0).build())
            }.build()
        }
    }
}
