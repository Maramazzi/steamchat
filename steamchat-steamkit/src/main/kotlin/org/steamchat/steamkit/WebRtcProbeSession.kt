package org.steamchat.steamkit

import `in`.dragonbra.javasteam.base.ClientMsgProtobuf
import `in`.dragonbra.javasteam.enums.EMsg
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.generated.MsgClientLogon
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverLogin.CMsgClientLogon
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.steamchat.domain.SteamWebRtcProbeEvent
import java.util.concurrent.atomic.AtomicBoolean

internal data class WebRtcWebLogon(val accountName: String, val steamId: Long, val token: String)

/** Short-lived web-authenticated CM connection used only by the transport probe. */
internal class WebRtcProbeSession(
    private val logon: WebRtcWebLogon,
    emit: (SteamWebRtcProbeEvent) -> Unit,
) {
    private val client = SteamClient()
    private val manager = CallbackManager(client)
    private val handler = WebRtcProbeHandler(logon.steamId, emit).also(client::addHandler)
    private val running = AtomicBoolean(true)
    private val loggedOn = CompletableDeferred<Unit>()
    private val subscriptions = listOf(
        manager.subscribe(ConnectedCallback::class.java) { sendWebLogon() },
        manager.subscribe(LoggedOnCallback::class.java) {
            if (it.result == EResult.OK) loggedOn.complete(Unit)
            else loggedOn.completeExceptionally(IllegalStateException("Steam web logon failed: ${it.result}"))
        },
        manager.subscribe(DisconnectedCallback::class.java) {
            if (running.get() && !loggedOn.completeExceptionally(IllegalStateException("Steam web session disconnected"))) {
                handler.cancel()
            }
        },
    )
    private val pump = Thread {
        try {
            client.connect()
            while (running.get()) manager.runWaitCallbacks(500L)
        } finally {
            subscriptions.forEach { it.close() }
        }
    }.apply { name = "Steam-WebRTC-Probe"; start() }

    init {
        checkNotNull(client.getHandler(SteamUser::class.java))
    }

    suspend fun initiate(offer: String, browserName: String, browserVersion: String): String = try {
        withTimeout(20_000) { loggedOn.await() }
        handler.initiate(offer, browserName, browserVersion)
    } catch (e: Exception) {
        close()
        throw e
    }

    suspend fun requestOneOnOne(partnerSteamId64: Long): Long = handler.requestOneOnOne(partnerSteamId64)

    suspend fun joinOneOnOne(voiceChatId: Long, partnerSteamId64: Long) =
        handler.joinOneOnOne(voiceChatId, partnerSteamId64)

    suspend fun acknowledgeUpdate(version: Long) = handler.acknowledgeUpdate(version)

    fun close() {
        if (!running.getAndSet(false)) return
        handler.cancel()
        client.disconnect()
    }

    private fun sendWebLogon() {
        val message = ClientMsgProtobuf<CMsgClientLogon.Builder>(CMsgClientLogon::class.java, EMsg.ClientLogon)
        message.protoHeader.steamid = logon.steamId
        message.body.accountName = logon.accountName
        message.body.protocolVersion = MsgClientLogon.CurrentProtocol
        message.body.qosLevel = 2
        message.body.clientOsType = -700
        message.body.uiMode = 3
        message.body.chatMode = 2
        message.body.webLogonNonce = logon.token
        message.body.clientInstanceId = System.currentTimeMillis()
        client.send(message)
    }
}
