package org.steamchat.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.steamchat.domain.SteamMessage
import org.steamchat.service.MessageHistoryStore
import java.io.File

/** Private chat history excluded from Android backup; no credentials are stored here. */
class SqliteMessageHistoryStore(context: Context) : MessageHistoryStore {
    private val file = File(context.noBackupFilesDir, "steamchat_history.sqlite")
    private val database by lazy {
        SQLiteDatabase.openOrCreateDatabase(file, null).apply {
            execSQL("CREATE TABLE IF NOT EXISTS messages (account INTEGER NOT NULL, friend INTEGER NOT NULL, sender INTEGER NOT NULL, timestamp INTEGER NOT NULL, text TEXT NOT NULL, outgoing INTEGER NOT NULL, PRIMARY KEY(account, friend, sender, timestamp, text))")
        }
    }

    @Synchronized override fun load(accountId: Long, friendId: Long): List<SteamMessage> {
        return database.rawQuery("SELECT rowid, sender, text, timestamp, outgoing FROM messages WHERE account=? AND friend=? ORDER BY timestamp DESC, rowid DESC LIMIT 1000", arrayOf(accountId.toString(), friendId.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(SteamMessage(cursor.getLong(0), friendId, cursor.getLong(1), cursor.getString(2), cursor.getLong(3), cursor.getInt(4) != 0))
            }.reversed()
        }
    }

    @Synchronized override fun merge(accountId: Long, friendId: Long, messages: List<SteamMessage>) {
        val db = database
        db.beginTransaction()
        try {
            messages.forEach { message ->
                val values = ContentValues().apply {
                    put("account", accountId); put("friend", friendId); put("sender", message.senderSteamId64)
                    put("timestamp", message.timestamp); put("text", message.text); put("outgoing", if (message.isOutgoing) 1 else 0)
                }
                db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
