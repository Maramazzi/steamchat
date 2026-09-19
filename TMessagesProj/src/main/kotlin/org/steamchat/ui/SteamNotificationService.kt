package org.steamchat.ui

import android.app.NotificationChannel
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.steamchat.domain.SteamNotificationEvent
import org.steamchat.domain.SteamIncomingVoiceCall
import org.steamchat.service.SteamLoginResult
import org.telegram.messenger.R

/** Keeps Steam's live connection available while the UI is closed. */
class SteamNotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val service get() = SteamServiceHolder.service
    private lateinit var manager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CONNECTION, "Соединение со Steam", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(MESSAGES, "Сообщения Steam", NotificationManager.IMPORTANCE_HIGH))
            manager.createNotificationChannel(NotificationChannel(CALLS, "Входящие звонки Steam", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            })
        }
        startForeground(1, connectionNotification("Подключение к Steam"))
        scope.launch {
            service.connectionState.collect { state ->
                System.out.println("Steam connection: $state")
                val text = when (state) {
                    org.steamchat.service.SteamConnectionState.CONNECTED -> "Получение сообщений в фоне"
                    org.steamchat.service.SteamConnectionState.RECONNECTING -> "Связь потеряна · переподключение"
                    org.steamchat.service.SteamConnectionState.CONNECTING -> "Подключение к Steam"
                    org.steamchat.service.SteamConnectionState.DISCONNECTED -> "Нет соединения со Steam"
                }
                manager.notify(1, connectionNotification(text))
            }
        }
        scope.launch {
            service.observeNotificationEvents().collect { event ->
                if (visibleChat == (event.chatId to event.channelId)) return@collect
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return@collect
                val group = service.observeChatGroups().value.firstOrNull { it.id == event.chatId }
                val title = if (event.channelId == null) {
                    service.observeFriends().value.firstOrNull { it.steamId64 == event.chatId }?.personaName
                        ?: event.chatId.toString()
                } else {
                    val channel = group?.channels?.firstOrNull { it.id == event.channelId }?.name
                    listOfNotNull(group?.name, channel).joinToString(" · ").ifBlank { "Групповой чат" }
                }
                val text = if (event.channelId != null && event.senderName != null) "${event.senderName}: ${event.text}" else event.text
                manager.notify("${event.chatId}:${event.channelId}", 2,
                    NotificationCompat.Builder(this@SteamNotificationService, MESSAGES)
                        .setSmallIcon(R.drawable.notification)
                        .setContentTitle(title).setContentText(text)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                        .setContentIntent(openApp(event)).setAutoCancel(true)
                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setDefaults(NotificationCompat.DEFAULT_ALL).build())
            }
        }
        scope.launch {
            if (service.resumeSession() !is SteamLoginResult.Success) stopSelf()
        }
        scope.launch {
            service.incomingVoiceCall.collect { call ->
                if (call == null) manager.cancel(CALL_NOTIFICATION_ID) else notifyCall(call)
            }
        }
    }

    private fun notifyCall(call: SteamIncomingVoiceCall) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val name = service.observeFriends().value.firstOrNull { it.steamId64 == call.partnerSteamId64 }?.personaName
            ?: call.partnerSteamId64.toString()
        val answerIntent = Intent(this, SteamDebugActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(ANSWER_CALL, call.voiceChatId)
            .setData(android.net.Uri.parse("steamchat://call/${call.voiceChatId}"))
        val answer = PendingIntent.getActivity(this, 3, answerIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val decline = PendingIntent.getService(this, 4, Intent(this, SteamNotificationService::class.java)
            .setAction(DECLINE_CALL).putExtra(ANSWER_CALL, call.voiceChatId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(CALL_NOTIFICATION_ID, NotificationCompat.Builder(this, CALLS)
            .setSmallIcon(R.drawable.notification).setContentTitle(name).setContentText("Входящий звонок")
            .setContentIntent(answer).setOngoing(true).setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(androidx.core.app.Person.Builder().setName(name).build(), decline, answer))
            .setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build().apply { flags = flags or Notification.FLAG_INSISTENT })
    }

    private fun connectionNotification(text: String): Notification = NotificationCompat.Builder(this, CONNECTION)
        .setSmallIcon(R.drawable.notification).setContentTitle("SteamChatX").setContentText(text)
        .setContentIntent(openApp(null)).setOngoing(true).setSilent(true).build()

    private fun openApp(event: SteamNotificationEvent?): PendingIntent {
        val intent = Intent(this, SteamDebugActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (event != null) {
            intent.putExtra("steam_chat_id", event.chatId)
            event.channelId?.let { intent.putExtra("steam_channel_id", it) }
            intent.data = android.net.Uri.parse("steamchat://chat/${event.chatId}/${event.channelId ?: 0}")
        }
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DECLINE_CALL) {
            service.incomingVoiceCall.value?.takeIf { it.voiceChatId == intent.getLongExtra(ANSWER_CALL, 0) }?.let { call ->
                manager.cancel(CALL_NOTIFICATION_ID)
                scope.launch { service.answerIncomingVoiceCall(call, false) }
            }
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        manager.cancel(CALL_NOTIFICATION_ID)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CONNECTION = "steam_connection"
        private const val MESSAGES = "steam_messages"
        private const val CALLS = "steam_calls"
        private const val DECLINE_CALL = "org.steamchat.DECLINE_CALL"
        const val ANSWER_CALL = "steam_answer_call"
        const val CALL_NOTIFICATION_ID = 3
        var visibleChat: Pair<Long, Long?>? = null
    }
}
