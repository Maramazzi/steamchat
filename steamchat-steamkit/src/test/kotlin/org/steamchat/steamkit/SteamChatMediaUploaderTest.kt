package org.steamchat.steamkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class SteamChatMediaUploaderTest {
    @Test
    fun `begin response accepts only an HTTPS signed upload`() {
        val begin = SteamChatMediaUploader.parseBeginResponse(
            """
            {
              "success": 1,
              "timestamp": "123",
              "hmac": "signed",
              "result": {
                "use_https": true,
                "url_host": "uploads.steamusercontent.com",
                "url_path": "/ugc/42",
                "ugcid": "42",
                "request_headers": [
                  {"name": "x-amz-acl", "value": "private"},
                  {"name": "Content-Length", "value": "9"}
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals(URI("https://uploads.steamusercontent.com/ugc/42"), begin.uploadUrl)
        assertEquals("private", begin.headers["x-amz-acl"])
        assertEquals("42", begin.ugcId)

        assertThrows(IllegalStateException::class.java) {
            SteamChatMediaUploader.parseBeginResponse(
                """{"success":1,"timestamp":"1","hmac":"x","result":{"use_https":false,"url_host":"example.com","url_path":"/x","ugcid":"1"}}""",
            )
        }
    }

    @Test
    fun `multipart carries every field and closes the boundary`() {
        val body = SteamChatMediaUploader.multipartBody(
            "boundary",
            linkedMapOf("sessionid" to "abc", "file_type" to "video/mp4"),
        ).toString(Charsets.UTF_8)

        assertTrue(body.contains("name=\"sessionid\"\r\n\r\nabc\r\n"))
        assertTrue(body.contains("name=\"file_type\"\r\n\r\nvideo/mp4\r\n"))
        assertTrue(body.endsWith("--boundary--\r\n"))
    }
}
