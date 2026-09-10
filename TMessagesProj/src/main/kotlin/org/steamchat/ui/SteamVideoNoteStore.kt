package org.steamchat.ui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.steamchat.domain.SteamMessage
import org.steamchat.domain.localVideoNoteMessageText
import java.io.File

/** Device-local storage for video notes: Steam friend chat has no media payload field. */
internal object SteamVideoNoteStore {
    private const val PREFS = "steam_video_notes"

    fun load(context: Context, partnerSteamId64: Long, senderSteamId64: Long): List<SteamMessage> {
        val items = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(partnerSteamId64.toString(), "[]"))
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val path = item.optString("path")
                val duration = item.optLong("duration")
                val timestamp = item.optLong("timestamp")
                if (duration <= 0 || timestamp <= 0 || !File(path).isFile) continue
                add(
                    SteamMessage(
                        id = -timestamp,
                        chatPartnerSteamId64 = partnerSteamId64,
                        senderSteamId64 = senderSteamId64,
                        text = localVideoNoteMessageText(path, duration),
                        timestamp = timestamp,
                        isOutgoing = true,
                    ),
                )
            }
        }
    }

    suspend fun save(
        context: Context,
        partnerSteamId64: Long,
        senderSteamId64: Long,
        source: File,
        durationMs: Long,
    ): SteamMessage = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val directory = File(context.filesDir, "video_notes").apply { mkdirs() }
        val target = File(directory, "${partnerSteamId64}_$timestamp.mp4")
        source.copyTo(target, overwrite = true)
        source.delete()

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val items = JSONArray(prefs.getString(partnerSteamId64.toString(), "[]"))
        items.put(JSONObject().put("path", target.absolutePath).put("duration", durationMs).put("timestamp", timestamp))
        prefs.edit().putString(partnerSteamId64.toString(), items.toString()).apply()

        SteamMessage(
            id = -timestamp,
            chatPartnerSteamId64 = partnerSteamId64,
            senderSteamId64 = senderSteamId64,
            text = localVideoNoteMessageText(target.absolutePath, durationMs),
            timestamp = timestamp,
            isOutgoing = true,
        )
    }
}
