package org.steamchat.storage

import android.content.Context
import org.steamchat.domain.SteamChatFolder

/** Device-only folder preferences, isolated by signed-in Steam account. */
class PrefsChatFolders(context: Context, accountId: Long) {
    private val prefs = context.getSharedPreferences("steamchat_folders_$accountId", Context.MODE_PRIVATE)

    fun read(): List<SteamChatFolder> = prefs.getString("order", "").orEmpty()
        .split(',').filter { it.isNotEmpty() }.mapNotNull { id ->
            val name = prefs.getString("name_$id", null) ?: return@mapNotNull null
            SteamChatFolder(id, name, prefs.getStringSet("chats_$id", emptySet()).orEmpty().toSet())
        }

    fun save(folders: List<SteamChatFolder>) {
        val editor = prefs.edit()
        val ids = folders.map { it.id }.toSet()
        read().filter { it.id !in ids }.forEach {
            editor.remove("name_${it.id}").remove("chats_${it.id}")
        }
        folders.forEach {
            editor.putString("name_${it.id}", it.name).putStringSet("chats_${it.id}", it.chatKeys.toSet())
        }
        editor.putString("order", folders.joinToString(",") { it.id }).apply()
    }
}
