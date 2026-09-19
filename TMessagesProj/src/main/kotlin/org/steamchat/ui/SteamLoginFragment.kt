package org.steamchat.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.steamchat.service.SteamLoginResult
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.R

/**
 * Real Steam login (username/password + Steam Guard via AndroidSteamGuardHandler), backed by
 * whatever SteamServiceHolder.service currently is. On success, hands off to
 * SteamDialogsFragment - which now assumes login already happened (see SteamDialogsFragment,
 * the old hardcoded fake login call there was removed alongside this).
 *
 * Full-bleed dark screen matching a user-provided mockup, not a titled form: the action bar stays
 * (so the status-bar inset machinery in SteamDebugActivity keeps working, see CLAUDE.md's status
 * bar gotcha) but is made invisible - no title, no back button, background matching the screen.
 */
class SteamLoginFragment : SteamBaseFragment() {

    private val service = SteamServiceHolder.service
    private val scope = CoroutineScope(Dispatchers.Main)

    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var eyeIcon: ImageView
    private lateinit var loginButton: TextView
    private lateinit var statusText: TextView

    override fun createView(context: Context): View {
        actionBar.setTitle(null)
        actionBar.setCastShadows(false)
        actionBar.setBackgroundColor(SteamPalette.chatBackground)

        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(SteamPalette.chatBackground)
        root.setPadding(dp(24f), dp(40f), dp(24f), dp(40f))

        val title = TextView(context).apply {
            text = "SteamChatX"
            textSize = 34f
            typeface = AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM)
            setTextColor(SteamPalette.headerTitle)
        }
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val subtitle = TextView(context).apply {
            text = "Вход в аккаунт"
            textSize = 14f
            setTextColor(SteamPalette.headerSubtitle)
            setPadding(0, dp(4f), 0, 0)
        }
        root.addView(subtitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        usernameInput = underlineField(context, "Steam username")
        root.addView(usernameInput, fieldParams().apply { topMargin = dp(48f) })

        val passwordRow = FrameLayout(context)
        passwordInput = underlineField(context, "Steam password").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(0, dp(16f), dp(32f), dp(16f))
        }
        eyeIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_steamchat_eye)
            alpha = 0.6f
            setOnClickListener { togglePasswordVisibility() }
        }
        passwordRow.addView(passwordInput, LayoutParamsMatchWrap())
        passwordRow.addView(eyeIcon, FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.CENTER_VERTICAL or Gravity.END).apply { bottomMargin = dp(16f) })
        root.addView(passwordRow, fieldParams().apply { topMargin = dp(36f) })

        loginButton = TextView(context).apply {
            text = "LOGIN"
            textSize = 15f
            gravity = Gravity.CENTER
            letterSpacing = 0.05f
            typeface = AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM)
            setTextColor(SteamPalette.headerTitle)
            background = SteamPalette.rowSelector().let { selector ->
                android.graphics.drawable.LayerDrawable(arrayOf(loginButtonBackground(), selector))
            }
            setOnClickListener { onLoginClicked() }
        }
        root.addView(loginButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52f)).apply { topMargin = dp(32f) })

        statusText = TextView(context).apply {
            textSize = 13f
            setTextColor(SteamPalette.headerSubtitle)
            setPadding(0, dp(12f), 0, 0)
        }
        root.addView(statusText, fieldParams())

        root.addView(View(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = TextView(context).apply {
            text = "FRIENDS\nGAMES\nCOMMUNITY\nALWAYS TOGETHER"
            textSize = 14f
            letterSpacing = 0.12f
            setLineSpacing(dp(4f).toFloat(), 1f)
            setTextColor(SteamPalette.headerSubtitle)
            alpha = 0.7f
        }
        root.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val dash = View(context).apply { setBackgroundColor(SteamPalette.headerSubtitle) }
        root.addView(dash, LinearLayout.LayoutParams(dp(24f), dp(2f)).apply { topMargin = dp(12f) })

        fragmentView = root
        tryResumeSession()
        return root
    }

    private fun LayoutParamsMatchWrap() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun fieldParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun underlineField(context: Context, hintText: String) = EditText(context).apply {
        hint = hintText
        textSize = 15f
        setTextColor(SteamPalette.headerTitle)
        setHintTextColor(SteamPalette.headerSubtitle)
        // Keep the platform's own Material underline instead of hand-rolling a bottom-border
        // drawable - just retint it to match the screen.
        backgroundTintList = ColorStateList.valueOf(SteamPalette.headerSubtitle)
        setPadding(0, dp(16f), 0, dp(16f))
    }

    private fun loginButtonBackground() = GradientDrawable().apply {
        cornerRadius = dp(9f).toFloat()
        setColor(SteamPalette.inputField)
    }

    private fun setLoginEnabled(enabled: Boolean) {
        loginButton.isEnabled = enabled
        loginButton.alpha = if (enabled) 1f else 0.5f
    }

    private fun togglePasswordVisibility() {
        val visible = passwordInput.inputType and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != 0
        passwordInput.inputType = InputType.TYPE_CLASS_TEXT or
            if (visible) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        eyeIcon.alpha = if (visible) 0.6f else 1f
        passwordInput.setSelection(passwordInput.text.length)
    }

    private fun tryResumeSession() {
        setLoginEnabled(false)
        statusText.text = "Восстановление сессии..."
        scope.launch {
            when (val result = service.resumeSession()) {
                is SteamLoginResult.Success -> {
                    (getParentActivity() as? SteamDebugActivity)?.onSteamLogin()
                    presentFragment(SteamDialogsFragment(), true)
                }
                is SteamLoginResult.Failure -> {
                    statusText.text = ""
                    setLoginEnabled(true)
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

        setLoginEnabled(false)
        statusText.text = "Подключение к Steam..."

        val guardHandler = AndroidSteamGuardHandler(activity)
        scope.launch {
            when (val result = service.login(username, password, guardHandler)) {
                is SteamLoginResult.Success -> {
                    (getParentActivity() as? SteamDebugActivity)?.onSteamLogin()
                    presentFragment(SteamDialogsFragment(), true)
                }
                is SteamLoginResult.Failure -> {
                    statusText.text = "Ошибка: ${result.reason}"
                    setLoginEnabled(true)
                }
            }
        }
    }

    override fun onFragmentDestroy() {
        scope.cancel()
        super.onFragmentDestroy()
    }
}
