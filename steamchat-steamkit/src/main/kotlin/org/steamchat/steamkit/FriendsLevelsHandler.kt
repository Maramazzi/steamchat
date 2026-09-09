package org.steamchat.steamkit

import `in`.dragonbra.javasteam.base.ClientMsgProtobuf
import `in`.dragonbra.javasteam.base.IPacketMsg
import `in`.dragonbra.javasteam.enums.EMsg
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver2.CMsgClientFSGetFriendsSteamLevels
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver2.CMsgClientFSGetFriendsSteamLevelsResponse
import `in`.dragonbra.javasteam.steam.handlers.ClientMsgHandler
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackMsg
import `in`.dragonbra.javasteam.types.SteamID

/**
 * ClientFSGetFriendsSteamLevels has no handler/callback in JavaSteam 1.8.0 - unlike every other
 * Steam call this codebase makes (SteamFriends/SteamUser/SteamUnifiedMessages), it's a bare
 * ClientMsgProtobuf with nobody listening for the response. This is the minimum needed to send it
 * and turn the reply into a callback through the same manager.subscribe() path as everything else.
 */
internal class FriendsSteamLevelsCallback(packetMsg: IPacketMsg) : CallbackMsg() {
    val levelByAccountId: Map<Int, Int>

    init {
        val msg = ClientMsgProtobuf<CMsgClientFSGetFriendsSteamLevelsResponse.Builder>(CMsgClientFSGetFriendsSteamLevelsResponse::class.java, packetMsg)
        levelByAccountId = msg.body.friendsList.associate { it.accountid to it.level }
    }
}

internal class FriendsLevelsHandler : ClientMsgHandler() {

    override fun handleMsg(packetMsg: IPacketMsg) {
        if (packetMsg.msgType == EMsg.ClientFSGetFriendsSteamLevelsResponse) {
            client.postCallback(FriendsSteamLevelsCallback(packetMsg))
        }
    }

    fun requestLevels(steamId64s: List<Long>) {
        val msg = ClientMsgProtobuf<CMsgClientFSGetFriendsSteamLevels.Builder>(CMsgClientFSGetFriendsSteamLevels::class.java, EMsg.ClientFSGetFriendsSteamLevels)
        msg.body.addAllAccountids(steamId64s.map { SteamID(it).accountID.toInt() })
        client.send(msg)
    }
}
