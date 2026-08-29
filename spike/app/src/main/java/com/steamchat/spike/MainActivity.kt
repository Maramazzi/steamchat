package com.steamchat.spike

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import `in`.dragonbra.javasteam.enums.EChatEntryType
import `in`.dragonbra.javasteam.enums.EPersonaState
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.FriendMsgCallback
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.FriendsListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.PersonaStateCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.AccountInfoCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.types.SteamID
import `in`.dragonbra.javasteam.util.log.LogListener
import `in`.dragonbra.javasteam.util.log.LogManager

/**
 * Spike goal (no more, no less): prove that JavaSteam - a JVM port of SteamKit2 - can log into Steam,
 * modern-auth with Steam Guard, list friends and send/receive a real-time chat message, all from a
 * single Android process with no separate backend server. See SteamChat_master_prompt.md section 6.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var sendButton: Button

    private var steamFriends: SteamFriends? = null
    private var isRunning = false
    private var storedGuardData: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        LogManager.addListener(object : LogListener {
            override fun onLog(clazz: Class<*>, message: String?, throwable: Throwable?) {
                log("[${clazz.simpleName}] $message${throwable?.let { " | " + it.message } ?: ""}")
            }

            override fun onError(clazz: Class<*>, message: String?, throwable: Throwable?) {
                log("[ERR ${clazz.simpleName}] $message${throwable?.let { " | " + it.message } ?: ""}")
            }
        })

        val usernameInput = findViewById<EditText>(R.id.usernameInput)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)
        val loginButton = findViewById<Button>(R.id.loginButton)
        val targetSteamIdInput = findViewById<EditText>(R.id.targetSteamIdInput)
        val messageInput = findViewById<EditText>(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        logView = findViewById(R.id.logView)

        loginButton.setOnClickListener {
            val user = usernameInput.text.toString().trim()
            val pass = passwordInput.text.toString()
            if (user.isEmpty() || pass.isEmpty()) {
                log("Введите username и password")
            } else {
                loginButton.isEnabled = false
                startSteamSession(user, pass)
            }
        }

        sendButton.setOnClickListener {
            val targetId = targetSteamIdInput.text.toString().trim().toLongOrNull()
            val text = messageInput.text.toString()
            val friends = steamFriends
            if (targetId == null || text.isEmpty() || friends == null) {
                log("Укажите SteamID64 друга (из лога ниже) и текст сообщения")
            } else {
                Thread {
                    friends.sendChatMessage(SteamID(targetId), EChatEntryType.ChatMsg, text)
                    log("-> отправлено [$targetId]: $text")
                }.start()
            }
        }
    }

    private fun log(message: String) {
        runOnUiThread { logView.append("\n$message") }
    }

    private fun startSteamSession(user: String, pass: String) {
        Thread {
            val client = SteamClient()
            val manager = CallbackManager(client)
            val steamUser = client.getHandler(SteamUser::class.java)!!
            val friends = client.getHandler(SteamFriends::class.java)!!
            steamFriends = friends

            val authenticator = AndroidAuthenticator(this, ::log)
            val subscriptions = mutableListOf<AutoCloseable>()

            subscriptions += manager.subscribe(ConnectedCallback::class.java) {
                log("Connected to Steam. Авторизация $user...")
                try {
                    val authDetails = AuthSessionDetails()
                    authDetails.username = user
                    authDetails.password = pass
                    authDetails.persistentSession = false
                    authDetails.guardData = storedGuardData
                    authDetails.authenticator = authenticator

                    val authSession = client.authentication.beginAuthSessionViaCredentials(authDetails).get()
                    val pollResponse = authSession.pollingWaitForResult().get()

                    if (pollResponse.newGuardData != null) {
                        storedGuardData = pollResponse.newGuardData
                    }

                    val details = LogOnDetails()
                    details.username = pollResponse.accountName
                    details.accessToken = pollResponse.refreshToken
                    details.loginID = 149

                    steamUser.logOn(details)
                } catch (e: Exception) {
                    log("Ошибка авторизации: ${e.message}")
                    steamUser.logOff()
                }
            }

            subscriptions += manager.subscribe(DisconnectedCallback::class.java) { cb ->
                log("Disconnected. userInitiated=${cb.isUserInitiated}")
                if (cb.isUserInitiated) {
                    isRunning = false
                } else {
                    Thread.sleep(2000L)
                    client.connect()
                }
            }

            subscriptions += manager.subscribe(LoggedOnCallback::class.java) { cb ->
                if (cb.result != EResult.OK) {
                    log("Не удалось войти: ${cb.result}")
                    isRunning = false
                } else {
                    log("Успешный вход в Steam!")
                }
            }

            subscriptions += manager.subscribe(LoggedOffCallback::class.java) { cb ->
                log("Logged off: ${cb.result}")
                isRunning = false
            }

            subscriptions += manager.subscribe(AccountInfoCallback::class.java) {
                friends.setPersonaState(EPersonaState.Online)
                runOnUiThread { sendButton.isEnabled = true }
            }

            subscriptions += manager.subscribe(FriendsListCallback::class.java) { cb ->
                log("Друзей: ${cb.friendList.size}")
                for (friend in cb.friendList) {
                    log("  friend steamId64=${friend.steamID.convertToUInt64()}")
                }
            }

            subscriptions += manager.subscribe(PersonaStateCallback::class.java) { cb ->
                log("Статус: ${cb.playerName} | ${cb.personaState}")
            }

            subscriptions += manager.subscribe(FriendMsgCallback::class.java) { cb ->
                if (cb.entryType == EChatEntryType.ChatMsg && cb.message != null) {
                    log("<- [${cb.sender.convertToUInt64()}]: ${cb.message}")
                }
            }

            isRunning = true
            log("Подключение к Steam...")
            client.connect()

            while (isRunning) {
                manager.runWaitCallbacks(1000L)
            }

            for (subscription in subscriptions) {
                subscription.close()
            }
        }.start()
    }
}
