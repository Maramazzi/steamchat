package com.steamchat.spike

import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import java.util.concurrent.CompletableFuture

/**
 * Steam Guard prompts arrive on JavaSteam's background thread; dialogs must be built on the UI thread.
 */
class AndroidAuthenticator(
    private val activity: Activity,
    private val log: (String) -> Unit,
) : IAuthenticator {

    override fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String> =
        promptForCode("Steam Guard: код из приложения-аутентификатора", previousCodeWasIncorrect)

    override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean): CompletableFuture<String> =
        promptForCode("Steam Guard: код из письма ($email)", previousCodeWasIncorrect)

    override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> {
        log("Подтвердите вход через приложение Steam на телефоне...")
        return CompletableFuture.completedFuture(true)
    }

    private fun promptForCode(title: String, previousWasWrong: Boolean): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        activity.runOnUiThread {
            val input = EditText(activity)
            AlertDialog.Builder(activity)
                .setTitle(if (previousWasWrong) "$title (предыдущий код неверный)" else title)
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ -> future.complete(input.text.toString().trim()) }
                .show()
        }
        return future
    }
}
