package org.steamchat.ui

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.steamchat.service.SteamLoginResult
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.ActionBar.Theme

/**
 * Real Steam login (username/password + Steam Guard via AndroidSteamGuardHandler), backed by
 * whatever SteamServiceHolder.service currently is. On success, hands off to
 * SteamDialogsFragment - which now assumes login already happened (see SteamDialogsFragment,
 * the old hardcoded fake login call there was removed alongside this).
 */
class SteamLoginFragment : SteamBaseFragment() {

    private val service = SteamServiceHolder.service
    private val scope = CoroutineScope(Dispatchers.Main)

    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: Button
    private lateinit var statusText: TextView

    override fun createView(context: Context): View {
        actionBar.setTitle("SteamChat login")

        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        root.setPadding(dp(16f), dp(16f), dp(16f), dp(16f))

        usernameInput = EditText(context)
        usernameInput.hint = "Steam username"
        usernameInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        usernameInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))

        passwordInput = EditText(context)
        passwordInput.hint = "Steam password"
        passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        passwordInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        passwordInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))

        loginButton = Button(context)
        loginButton.text = "Login"
        loginButton.setOnClickListener { onLoginClicked() }

        statusText = TextView(context)
        statusText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        statusText.setPadding(0, dp(12f), 0, 0)

        val fieldParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val buttonParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        buttonParams.gravity = Gravity.START
        buttonParams.topMargin = dp(12f)

        root.addView(usernameInput, fieldParams)
        root.addView(passwordInput, LinearLayout.LayoutParams(fieldParams))
        root.addView(loginButton, buttonParams)
        root.addView(statusText, LinearLayout.LayoutParams(fieldParams))

        fragmentView = root
        tryResumeSession()
        return root
    }

    private fun tryResumeSession() {
        loginButton.isEnabled = false
        statusText.text = "Восстановление сессии..."
        scope.launch {
            when (val result = service.resumeSession()) {
                is SteamLoginResult.Success -> presentFragment(SteamDialogsFragment(), true)
                is SteamLoginResult.Failure -> {
                    statusText.text = ""
                    loginButton.isEnabled = true
                }
            }
        }
    }

    private fun onLoginClicked() {
        val username = usernameInput.text.toString().trim()
        val password = passwordInput.text.toString()
        val activity = getParentActivity()
        if (username.isEmpty() || password.isEmpty()) {
            statusText.text = "Введите username и password"
            return
        }
        if (activity == null) {
            statusText.text = "No parent activity"
            return
        }

        loginButton.isEnabled = false
        statusText.text = "Подключение к Steam..."

        val guardHandler = AndroidSteamGuardHandler(activity)
        scope.launch {
            when (val result = service.login(username, password, guardHandler)) {
                is SteamLoginResult.Success -> presentFragment(SteamDialogsFragment(), true)
                is SteamLoginResult.Failure -> {
                    statusText.text = "Ошибка: ${result.reason}"
                    loginButton.isEnabled = true
                }
            }
        }
    }

    override fun onFragmentDestroy() {
        scope.cancel()
        super.onFragmentDestroy()
    }
}
