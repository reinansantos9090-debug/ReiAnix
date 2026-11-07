package com.reiflix.reiflix_local.data.tracemoe

import java.util.Base64
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReiAnixGoogleVisionWebDetectionClientTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun extractsBestGuessAndWebEntityTitleHintsFromTheUploadedThumbnail() = runBlocking {
        val image = jpeg()
        var requestUrl = ""
        var requestHeaders = emptyMap<String, String>()
        var requestBody = ByteArray(0)
        val transport = GoogleVisionTransport { url, body, headers ->
            requestUrl = url
            requestHeaders = headers
            requestBody = body
            GoogleVisionHttpResponse(
                200,
                """{"responses":[{"webDetection":{"bestGuessLabels":[{"label":"Frieren: Beyond Journey's End"}],"webEntities":[{"description":"Frieren","score":0.91},{"description":"Anime","score":0.22}]}}]}""",
            )
        }
        val client = client(transport)

        val hints = client.searchTitleHints(image)

        assertEquals(listOf("Frieren: Beyond Journey's End", "Frieren", "Anime"), hints)
        assertTrue(requestUrl.startsWith("https://vision.googleapis.com/v1/images:annotate?key="))
        assertEquals("com.example.reianix", requestHeaders["X-Android-Package"])
        assertEquals("0123456789012345678901234567890123456789", requestHeaders["X-Android-Cert"])
        val body = String(requestBody, Charsets.UTF_8)
        val submittedThumbnail = org.json.JSONObject(body)
            .getJSONArray("requests")
            .getJSONObject(0)
            .getJSONObject("image")
            .getString("content")
        assertTrue(
            "The request must contain the complete original thumbnail",
            Base64.getDecoder().decode(submittedThumbnail).contentEquals(image.readBytes()),
        )
        assertTrue(body.contains("WEB_DETECTION"))
        assertFalse(body.contains("video"))
    }

    @Test
    fun rejectsMalformedVisionResponsesAndApiErrors() {
        assertThrows(IOException::class.java) { parseGoogleVisionWebDetectionHints("not-json") }
        assertThrows(IOException::class.java) {
            parseGoogleVisionWebDetectionHints("""{"error":{"message":"API disabled"}}""")
        }
    }

    @Test
    fun missingWebDetectionIsACompletedNoHintResponse() {
        assertTrue(parseGoogleVisionWebDetectionHints("""{"responses":[{}]}""").isEmpty())
    }

    @Test
    fun rejectsNonJpegInputBeforeCallingTheProvider() {
        val image = File(temporaryFolder.root, "not-image.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        var calls = 0
        val client = client(GoogleVisionTransport { _, _, _ ->
            calls++
            GoogleVisionHttpResponse(200, """{"responses":[{}]}""")
        })

        assertThrows(IOException::class.java) {
            runBlocking { client.searchTitleHints(image) }
        }
        assertEquals(0, calls)
    }

    private fun client(transport: GoogleVisionTransport) = ReiAnixGoogleVisionWebDetectionClient(
        apiKey = "test-key-not-real",
        androidPackageName = "com.example.reianix",
        signingCertificateSha1 = "0123456789012345678901234567890123456789",
        transport = transport,
        minimumRequestIntervalMillis = 0L,
    )

    private fun jpeg(): File = File(temporaryFolder.root, "thumbnail.jpg").apply {
        writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x11, 0x22, 0xff.toByte(), 0xd9.toByte()))
    }
}
