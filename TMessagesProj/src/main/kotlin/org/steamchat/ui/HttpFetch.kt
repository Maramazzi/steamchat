package org.steamchat.ui

import java.net.HttpURLConnection
import java.net.URI

/**
 * Same manual-redirect-following as steamchat-steamkit's SteamWebProfile.get() - that module is
 * JVM-only (no Android on its compile classpath) and this one needs raw bytes (images), not text,
 * so it's a small copy here rather than a cross-module dependency for one shared helper.
 */
internal fun fetchBytes(url: String, redirectsLeft: Int = 5): ByteArray? = try {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 5000
    connection.readTimeout = 5000
    connection.instanceFollowRedirects = false
    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
    val code = connection.responseCode
    if (code in 300..399 && redirectsLeft > 0) {
        val location = connection.getHeaderField("Location")
        connection.disconnect()
        if (location != null) fetchBytes(location, redirectsLeft - 1) else null
    } else {
        connection.inputStream.use { it.readBytes() }
    }
} catch (e: Exception) {
    null
}
