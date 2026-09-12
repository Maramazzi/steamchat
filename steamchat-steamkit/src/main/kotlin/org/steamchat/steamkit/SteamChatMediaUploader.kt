package org.steamchat.steamkit

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

internal data class SteamCommunityWebSession(
    val sessionId: String,
    val cookieHeader: String,
)

/** Steam's own three-step chat attachment upload, with no Android or JavaSteam types involved. */
internal object SteamChatMediaUploader {
    const val MAX_FILE_SIZE = 30L * 1024L * 1024L
    private const val MIME_TYPE = "video/mp4"
    private val beginUrl = URI("https://steamcommunity.com/chat/beginfileupload/?l=english")
    private val commitUrl = URI("https://steamcommunity.com/chat/commitfileupload/")

    fun uploadToFriend(session: SteamCommunityWebSession, friendSteamId64: Long, filePath: String) {
        require(friendSteamId64 > 0) { "Invalid Steam friend id" }
        upload(session, filePath, mapOf("friend_steamid" to friendSteamId64.toString(), "spoiler" to "0"))
    }

    fun uploadToGroup(
        session: SteamCommunityWebSession,
        groupId: Long,
        channelId: Long,
        filePath: String,
    ) {
        require(groupId > 0 && channelId > 0) { "Invalid Steam group channel" }
        upload(
            session,
            filePath,
            mapOf("chat_group_id" to groupId.toString(), "chat_id" to channelId.toString(), "spoiler" to "0"),
        )
    }

    private fun upload(session: SteamCommunityWebSession, filePath: String, targetFields: Map<String, String>) {
        val file = File(filePath)
        require(file.isFile) { "MP4 file does not exist" }
        require(file.extension.equals("mp4", ignoreCase = true)) { "Only MP4 files can be sent" }
        require(file.length() in 1..MAX_FILE_SIZE) { "MP4 file must be between 1 byte and 30 MiB" }

        val uploadName = "steamchat_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.mp4"
        val sha1 = file.sha1()
        val common = linkedMapOf(
            "sessionid" to session.sessionId,
            "l" to "english",
            "file_name" to uploadName,
            "file_sha" to sha1,
            "file_type" to MIME_TYPE,
            "file_image_width" to "0",
            "file_image_height" to "0",
        )
        val begin = parseBeginResponse(
            postMultipart(
                beginUrl,
                session.cookieHeader,
                linkedMapOf(
                    "sessionid" to session.sessionId,
                    "l" to "english",
                    "file_size" to file.length().toString(),
                    "file_name" to uploadName,
                    "file_sha" to sha1,
                    "file_image_width" to "0",
                    "file_image_height" to "0",
                    "file_type" to MIME_TYPE,
                ),
            ),
        )

        try {
            putFile(begin, file)
        } catch (failure: Exception) {
            runCatching { commit(session, common, targetFields, begin, success = false) }
            throw failure
        }
        commit(session, common, targetFields, begin, success = true)
    }

    private fun commit(
        session: SteamCommunityWebSession,
        common: Map<String, String>,
        targetFields: Map<String, String>,
        begin: BeginUpload,
        success: Boolean,
    ) {
        val fields = LinkedHashMap(common)
        fields["success"] = if (success) "1" else "0"
        fields["ugcid"] = begin.ugcId
        fields["timestamp"] = begin.timestamp
        fields["hmac"] = begin.hmac
        fields.putAll(targetFields)
        val response = postMultipart(commitUrl, session.cookieHeader, fields)
        check(isSuccessful(JsonParser.parseString(response).asJsonObject)) { "Steam rejected media upload commit" }
    }

    private fun putFile(begin: BeginUpload, file: File) {
        val connection = begin.uploadUrl.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            begin.headers.forEach { (name, value) ->
                if (!name.equals("Host", ignoreCase = true) &&
                    !name.equals("Content-Length", ignoreCase = true)
                ) {
                    connection.setRequestProperty(name, value)
                }
            }
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(file.length())
            connection.outputStream.use { output -> file.inputStream().use { it.copyTo(output) } }
            check(connection.responseCode in 200..299) {
                "Steam media storage upload failed: HTTP ${connection.responseCode}"
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun postMultipart(url: URI, cookieHeader: String, fields: Map<String, String>): String {
        val boundary = "----SteamChat${UUID.randomUUID().toString().replace("-", "")}"
        val body = multipartBody(boundary, fields)
        val connection = url.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Valve Steam Client")
            connection.setRequestProperty("Cookie", cookieHeader)
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            check(connection.responseCode in 200..299) {
                "Steam media request failed: HTTP ${connection.responseCode}"
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    internal fun multipartBody(boundary: String, fields: Map<String, String>): ByteArray = buildString {
        fields.forEach { (name, value) ->
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
            append(value).append("\r\n")
        }
        append("--").append(boundary).append("--\r\n")
    }.toByteArray(StandardCharsets.UTF_8)

    internal fun parseBeginResponse(json: String): BeginUpload {
        val root = JsonParser.parseString(json).asJsonObject
        check(isSuccessful(root)) { "Steam rejected media upload" }
        val result = root.requireObject("result")
        check(result.get("use_https")?.asString == "true" || result.get("use_https")?.asString == "1") {
            "Steam returned an insecure media upload URL"
        }
        val host = result.requireString("url_host")
        require(host.matches(Regex("[A-Za-z0-9.-]+(?::[0-9]+)?"))) { "Invalid Steam media upload host" }
        val path = result.requireString("url_path")
        require(path.startsWith("/")) { "Invalid Steam media upload path" }
        val uploadUrl = URI("https://$host$path")
        require(uploadUrl.scheme == "https" && uploadUrl.host != null && uploadUrl.userInfo == null) {
            "Invalid Steam media upload URL"
        }
        val headers = result.getAsJsonArray("request_headers")?.associate { header ->
            val item = header.asJsonObject
            item.requireString("name") to item.requireString("value")
        }.orEmpty()
        return BeginUpload(
            uploadUrl = uploadUrl,
            headers = headers,
            ugcId = result.requireString("ugcid"),
            timestamp = root.requireString("timestamp"),
            hmac = root.requireString("hmac"),
        )
    }

    private fun isSuccessful(json: JsonObject): Boolean =
        json.get("success")?.let { it.asString == "1" || it.asString.equals("true", ignoreCase = true) } == true

    private fun JsonObject.requireString(name: String): String =
        get(name)?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
            ?: error("Steam media response is missing $name")

    private fun JsonObject.requireObject(name: String): JsonObject =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject
            ?: error("Steam media response is missing $name")

    internal data class BeginUpload(
        val uploadUrl: URI,
        val headers: Map<String, String>,
        val ugcId: String,
        val timestamp: String,
        val hmac: String,
    )

    private fun File.sha1(): String {
        val digest = MessageDigest.getInstance("SHA-1")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
