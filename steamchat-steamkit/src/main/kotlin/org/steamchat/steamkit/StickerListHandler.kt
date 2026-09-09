package org.steamchat.steamkit

import `in`.dragonbra.javasteam.base.ClientMsgProtobuf
import `in`.dragonbra.javasteam.base.IPacketMsg
import `in`.dragonbra.javasteam.enums.EMsg
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverFriends.CMsgClientEmoticonList
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverFriends.CMsgClientGetEmoticonList
import `in`.dragonbra.javasteam.steam.handlers.ClientMsgHandler
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackMsg
import org.steamchat.domain.SteamSticker

/**
 * ClientGetEmoticonList/ClientEmoticonList has no handler/callback in JavaSteam 1.8.0 - same gap
 * FriendsLevelsHandler already fills for ClientFSGetFriendsSteamLevels, a bare ClientMsgProtobuf
 * pair nobody wraps. Note this is a *different* message from CPlayer_GetEmoticonList_Request (the
 * modern unified-service call the emoticon picker uses): that one's response has no stickers field
 * at all - stickers only exist on this legacy CMsgClientEmoticonList.
 */
internal class StickerListCallback(packetMsg: IPacketMsg) : CallbackMsg() {
    val stickers: List<SteamSticker>

    init {
        val msg = ClientMsgProtobuf<CMsgClientEmoticonList.Builder>(CMsgClientEmoticonList::class.java, packetMsg)
        stickers = msg.body.stickersList.map { SteamSticker(it.name, it.useCount) }
    }
}

internal class StickerListHandler : ClientMsgHandler() {

    override fun handleMsg(packetMsg: IPacketMsg) {
        if (packetMsg.msgType == EMsg.ClientEmoticonList) {
            client.postCallback(StickerListCallback(packetMsg))
        }
    }

    fun requestStickerList() {
        val msg = ClientMsgProtobuf<CMsgClientGetEmoticonList.Builder>(CMsgClientGetEmoticonList::class.java, EMsg.ClientGetEmoticonList)
        client.send(msg)
    }
}
