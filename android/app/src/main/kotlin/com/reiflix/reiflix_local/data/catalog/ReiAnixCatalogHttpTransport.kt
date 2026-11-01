package com.reiflix.reiflix_local.data.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
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
            var currentUri = runCatching { URI(url) }.getOrElse {
                throw IOException("External catalog endpoint is not a valid URI.", it)
            }
            requireSafeCatalogEndpoint(currentUri)
            var redirects = 0
            var finalResponse: ReiAnixCatalogHttpResponse? = null

            while (finalResponse == null) {
                val connection = currentUri.toURL().openConnection() as? HttpURLConnection
                    ?: throw IOException("External catalog did not provide an HTTP connection.")
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = connectTimeoutMillis
                    connection.readTimeout = readTimeoutMillis
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Accept", accept)
                    connection.setRequestProperty("User-Agent", "ReiAnix-Android/1.0 (public catalog reader)")
                    connection.connect()
                    val status = connection.responseCode
                    val selectedHeaders = linkedMapOf<String, String>()
                    listOf("Retry-After", "Cache-Control", "Expires", "Last-Modified", "ETag", "Content-Type", "Location")
                        .forEach { name -> connection.getHeaderField(name)?.let { selectedHeaders[name] = it } }

                    val location = selectedHeaders["Location"]
                    if (status in REDIRECT_STATUSES && !location.isNullOrBlank()) {
                        if (redirects >= MAX_REDIRECTS) {
                            throw IOException("External catalog exceeded the redirect limit.")
                        }
                        currentUri = resolveSafeCatalogRedirect(currentUri, location)
                        redirects++
                    } else {
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
                        finalResponse = ReiAnixCatalogHttpResponse(status, body, selectedHeaders)
                    }
                } finally {
                    connection.disconnect()
                }
            }
            finalResponse ?: throw IOException("External catalog returned no HTTP response.")
        }

    private fun requireSafeCatalogEndpoint(uri: URI) {
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrBlank() ||
            uri.userInfo != null
        ) {
            throw IOException("External catalog endpoints and redirects must use valid HTTPS URLs without user info.")
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000
        const val MAX_RESPONSE_BYTES = 6 * 1024 * 1024
        const val MAX_REDIRECTS = 3
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }
}

/** Resolves a redirect only when its target is a valid HTTPS URI. */
internal fun resolveSafeCatalogRedirect(current: URI, location: String): URI {
    val resolved = try {
        current.resolve(location).normalize()
    } catch (error: Exception) {
        throw IOException("External catalog redirect location is invalid.", error)
    }
    if (!resolved.scheme.equals("https", ignoreCase = true) ||
        resolved.host.isNullOrBlank() ||
        resolved.userInfo != null
    ) {
        throw IOException("External catalog redirects must remain on valid HTTPS URLs without user info.")
    }
    return resolved
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
