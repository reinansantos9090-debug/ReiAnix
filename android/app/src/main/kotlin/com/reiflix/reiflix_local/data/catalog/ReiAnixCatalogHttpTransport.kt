package com.reiflix.reiflix_local.data.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ReiAnixCatalogHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String>,
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

fun interface ReiAnixCatalogGetTransport {
    suspend fun get(url: String, accept: String): ReiAnixCatalogHttpResponse
}

/** Bounded GET-only transport shared by public catalog providers. */
class HttpUrlConnectionReiAnixCatalogGetTransport(
    private val connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val maxResponseBytes: Int = MAX_RESPONSE_BYTES,
) : ReiAnixCatalogGetTransport {
    init {
        require(connectTimeoutMillis in 1..60_000)
        require(readTimeoutMillis in 1..60_000)
        require(maxResponseBytes in 1_024..MAX_RESPONSE_BYTES)
    }

    override suspend fun get(url: String, accept: String): ReiAnixCatalogHttpResponse =
        withContext(Dispatchers.IO) {
            val parsed = URL(url)
            require(parsed.protocol.equals("https", ignoreCase = true)) {
                "External catalog endpoints must use HTTPS."
            }
            require(parsed.host.isNotBlank() && parsed.userInfo == null) {
                "External catalog endpoint is invalid."
            }
            val connection = parsed.openConnection() as? HttpURLConnection
                ?: throw IOException("External catalog did not provide an HTTP connection.")
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Accept", accept)
                connection.setRequestProperty("User-Agent", "ReiAnix-Android/1.0 (public catalog reader)")
                connection.connect()
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        if (total > maxResponseBytes) {
                            throw IOException("External catalog response exceeded the configured size limit.")
                        }
                        output.write(buffer, 0, read)
                    }
                    output.toString(Charsets.UTF_8.name())
                }.orEmpty()
                val selectedHeaders = linkedMapOf<String, String>()
                listOf("Retry-After", "Cache-Control", "Expires", "Last-Modified", "ETag", "Content-Type")
                    .forEach { name -> connection.getHeaderField(name)?.let { selectedHeaders[name] = it } }
                ReiAnixCatalogHttpResponse(status, body, selectedHeaders)
            } finally {
                connection.disconnect()
            }
        }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000
        const val MAX_RESPONSE_BYTES = 6 * 1024 * 1024
    }
}

internal fun ReiAnixCatalogHttpResponse.cacheExpiryMillis(
    nowMillis: Long,
    fallbackTtlMillis: Long,
): Long {
    val cacheControl = header("Cache-Control").orEmpty()
    Regex("(?:^|,)\\s*max-age\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)
        .find(cacheControl)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { seconds ->
            if (seconds <= Long.MAX_VALUE / 1_000L) {
                return safeAddMillis(nowMillis, seconds * 1_000L)
            }
        }
    header("Expires")?.let { raw ->
        val formatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("GMT")
        }
        runCatching { formatter.parse(raw)?.time }.getOrNull()?.let { expiry ->
            return expiry.coerceAtLeast(nowMillis)
        }
    }
    return safeAddMillis(nowMillis, fallbackTtlMillis)
}

private fun safeAddMillis(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
