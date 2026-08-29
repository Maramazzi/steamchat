package org.steamchat.steamkit

import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.steamchat.service.SteamGuardHandler
import java.util.concurrent.CompletableFuture

/**
 * Bridges our UI-facing [SteamGuardHandler] (suspend functions) to JavaSteam's [IAuthenticator]
 * (CompletableFuture-returning, called from JavaSteam's own background thread). Shape confirmed
 * against the real interface in /spike.
 */
internal class GuardHandlerAuthenticator(
    private val scope: CoroutineScope,
    private val guardHandler: SteamGuardHandler,
) : IAuthenticator {

    override fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String> =
        bridge { guardHandler.provideDeviceCode(previousCodeWasIncorrect) }

    override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean): CompletableFuture<String> =
        bridge { guardHandler.provideEmailCode(email, previousCodeWasIncorrect) }

    override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> =
        bridge { guardHandler.confirmViaMobileApp() }

    private fun <T> bridge(block: suspend () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        scope.launch {
            try {
                future.complete(block())
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        return future
    }
}
