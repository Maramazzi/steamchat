package org.steamchat.ui

import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import kotlinx.coroutines.suspendCancellableCoroutine
import org.steamchat.service.SteamGuardHandler
import kotlin.coroutines.resume

/**
 * Same prompt-a-dialog pattern proven in /spike's AndroidAuthenticator, adapted to our
 * suspend-based SteamGuardHandler instead of JavaSteam's CompletableFuture-based IAuthenticator
 * (JavaSteamService.GuardHandlerAuthenticator does that other bridge).
 */
class AndroidSteamGuardHandler(private val activity: Activity) : SteamGuardHandler {

    override suspend fun provideDeviceCode(previousWasIncorrect: Boolean): String =
        promptForCode("Steam Guard: код из приложения-аутентификатора", previousWasIncorrect)

    override suspend fun provideEmailCode(email: String?, previousWasIncorrect: Boolean): String =
        promptForCode("Steam Guard: код из письма ($email)", previousWasIncorrect)

    override suspend fun confirmViaMobileApp(): Boolean = true

    private suspend fun promptForCode(title: String, previousWasWrong: Boolean): String =
        suspendCancellableCoroutine { continuation ->
            activity.runOnUiThread {
                val input = EditText(activity)
                AlertDialog.Builder(activity)
                    .setTitle(if (previousWasWrong) "$title (предыдущий код неверный)" else title)
                    .setView(input)
                    .setCancelable(false)
                    .setPositiveButton("OK") { _, _ -> continuation.resume(input.text.toString().trim()) }
                    .show()
            }
        }
}
