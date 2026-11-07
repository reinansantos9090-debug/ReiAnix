package com.reiflix.reiflix_local.data.tracemoe

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject

private const val GOOGLE_VISION_ENDPOINT = "https://vision.googleapis.com/v1/images:annotate"
private const val GOOGLE_VISION_MAX_RESPONSE_BYTES = 1024 * 1024
private const val GOOGLE_VISION_MIN_INTERVAL_MILLIS = 1_500L
private const val GOOGLE_VISION_MAX_RETRIES = 1

data class GoogleVisionHttpResponse(
    val statusCode: Int,
    val body: String,
)

fun interface GoogleVisionTransport {
    suspend fun post(
        url: String,
        body: ByteArray,
        headers: Map<String, String>,
    ): GoogleVisionHttpResponse
}

fun interface GoogleVisionTitleHintSource {
    suspend fun searchTitleHints(imageFile: File): List<String>
}

/**
 * Calls Google Cloud Vision Web Detection with the already-generated local JPEG
 * thumbnail. The video file is never read or uploaded by this client.
 *
 * Google Android API-key restrictions are reinforced with the installed package
 * name and signing-certificate SHA-1 headers. The API key is still extractable from
 * an APK; callers must also restrict it to the Vision API and monitor usage.
 */
class ReiAnixGoogleVisionWebDetectionClient(
    private val apiKey: String,
    private val androidPackageName: String,
    private val signingCertificateSha1: String,
    private val transport: GoogleVisionTransport = HttpUrlConnectionGoogleVisionTransport(),
    private val minimumRequestIntervalMillis: Long = GOOGLE_VISION_MIN_INTERVAL_MILLIS,
) : GoogleVisionTitleHintSource {
    init {
        require(apiKey.isNotBlank()) { "Google Cloud Vision API key cannot be blank." }
        require(androidPackageName.isNotBlank()) { "Android package name cannot be blank." }
        require(signingCertificateSha1.matches(Regex("(?i)^[0-9a-f]{40}$"))) {
            "Android signing certificate must be a 40-character SHA-1 fingerprint."
        }
        require(minimumRequestIntervalMillis >= 0L)
    }

    private val requestMutex = Mutex()
    private var lastRequestAtMillis = 0L

    override suspend fun searchTitleHints(imageFile: File): List<String> {
        val imageBytes = readJpeg(imageFile)
        val content = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val requestBody = JSONObject().put(
            "requests",
            JSONArray().put(
                JSONObject()
                    .put("image", JSONObject().put("content", content))
                    .put(
                        "features",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "WEB_DETECTION")
                                .put("maxResults", 10),
                        ),
                    ),
            ),
        ).toString().toByteArray(Charsets.UTF_8)
        val headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json; charset=utf-8",
            "X-Android-Package" to androidPackageName,
            "X-Android-Cert" to signingCertificateSha1.uppercase(),
            "User-Agent" to "ReiAnix-Android/1.0 (episode-thumbnail web lookup)",
        )
        return requestMutex.withLock {
            val waitMillis = (lastRequestAtMillis + minimumRequestIntervalMillis - System.currentTimeMillis())
                .coerceAtLeast(0L)
            if (waitMillis > 0L) delay(waitMillis)
            lastRequestAtMillis = System.currentTimeMillis()

            var retry = 0
            while (true) {
                try {
                    val response = transport.post(
                        url = "$GOOGLE_VISION_ENDPOINT?key=${URLEncoder.encode(apiKey, Charsets.UTF_8.name())}",
                        body = requestBody,
                        headers = headers,
                    )
                    if (response.statusCode in 200..299) {
                        return@withLock parseGoogleVisionWebDetectionHints(response.body)
                    }
                    val retryable = response.statusCode == 429 || response.statusCode in 500..599
                    if (!retryable || retry >= GOOGLE_VISION_MAX_RETRIES) {
                        throw IOException(googleVisionErrorMessage(response.statusCode, response.body))
                    }
                    delay(1_000L shl retry)
                    retry += 1
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: IOException) {
                    if (retry >= GOOGLE_VISION_MAX_RETRIES ||
                        error.message?.startsWith("Google Cloud Vision returned HTTP") == true
                    ) throw error
                    delay(1_000L shl retry)
                    retry += 1
                }
            }
            @Suppress("UNREACHABLE_CODE")
            emptyList()
        }
    }

    private suspend fun readJpeg(imageFile: File): ByteArray = withContext(Dispatchers.IO) {
        if (!imageFile.isFile || imageFile.length() !in 4L..ReiAnixTraceMoeClient.MAX_IMAGE_BYTES) {
            throw IOException("Google Web Detection requires a readable generated episode JPEG thumbnail.")
        }
        val bytes = try {
            imageFile.readBytes()
        } catch (error: IOException) {
            throw IOException("The generated episode thumbnail could not be read for web detection.", error)
        }
        if (!isJpeg(bytes)) throw IOException("Google Web Detection input must be a valid JPEG thumbnail.")
        bytes
    }

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0xff.toByte() &&
            bytes[1] == 0xd8.toByte() &&
            bytes[bytes.lastIndex - 1] == 0xff.toByte() &&
            bytes[bytes.lastIndex] == 0xd9.toByte()
}

/** Decodes only textual title hints; image URLs and arbitrary page content are not used as identity. */
internal fun parseGoogleVisionWebDetectionHints(responseBody: String): List<String> {
    val root = try {
        JSONObject(responseBody)
    } catch (error: Exception) {
        throw IOException("Google Cloud Vision returned malformed JSON.", error)
    }
    if (root.has("error")) {
        val error = root.optJSONObject("error")
        val message = error?.optString("message")?.takeIf(String::isNotBlank)
        throw IOException(message?.take(200) ?: "Google Cloud Vision returned an API error.")
    }
    val responses = root.optJSONArray("responses")
        ?: throw IOException("Google Cloud Vision response did not contain a responses array.")
    if (responses.length() == 0) return emptyList()
    val first = responses.optJSONObject(0)
        ?: throw IOException("Google Cloud Vision response item was invalid.")
    if (first.has("error")) {
        val error = first.optJSONObject("error")
        val message = error?.optString("message")?.takeIf(String::isNotBlank)
        throw IOException(message?.take(200) ?: "Google Cloud Vision could not analyze this thumbnail.")
    }
    val webDetection = first.optJSONObject("webDetection") ?: return emptyList()
    val hints = linkedSetOf<String>()
    val bestGuessLabels = webDetection.optJSONArray("bestGuessLabels") ?: JSONArray()
    for (index in 0 until bestGuessLabels.length()) {
        val label = bestGuessLabels.optJSONObject(index)?.optString("label")?.trim().orEmpty()
        if (label.isNotBlank()) hints += label.take(120)
    }
    val entities = webDetection.optJSONArray("webEntities") ?: JSONArray()
    val rankedEntities = (0 until entities.length())
        .mapNotNull { entities.optJSONObject(it) }
        .sortedByDescending { entity -> entity.optDouble("score", 0.0).takeIf { it.isFinite() } ?: 0.0 }
    for (entity in rankedEntities) {
        val description = entity.optString("description").trim()
        if (description.isNotBlank()) hints += description.take(120)
        if (hints.size >= 8) break
    }
    return hints.take(8)
}

private fun googleVisionErrorMessage(statusCode: Int, body: String): String {
    val detail = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message")
    }.getOrNull()?.takeIf(String::isNotBlank)
    return detail?.take(200) ?: "Google Cloud Vision returned HTTP $statusCode."
}

class HttpUrlConnectionGoogleVisionTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 15_000,
) : GoogleVisionTransport {
    init {
        require(connectTimeoutMillis in 1..60_000)
        require(readTimeoutMillis in 1..60_000)
    }

    override suspend fun post(
        url: String,
        body: ByteArray,
        headers: Map<String, String>,
    ): GoogleVisionHttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as? HttpURLConnection
            ?: throw IOException("Google Cloud Vision did not provide an HTTP connection.")
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) connection.disconnect()
            }
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                ?: return@withContext GoogleVisionHttpResponse(status, "")
            val response = stream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    if (total > GOOGLE_VISION_MAX_RESPONSE_BYTES) {
                        throw IOException("Google Cloud Vision response exceeded the configured size limit.")
                    }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
            GoogleVisionHttpResponse(status, response)
        } finally {
            connection.disconnect()
        }
    }
}
