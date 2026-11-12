package com.reianix.reianix_local.ui.account

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.reianix.reianix_local.ui.theme.ReiAnixTokens
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_AVATAR_BYTES = 4 * 1024 * 1024
private const val MAX_AVATAR_DIMENSION = 256
private const val AVATAR_CACHE_MAX_AGE_MS = 30 * 60 * 1000L

/**
 * The settings home leaves the composition while a category is open. Its avatar must
 * survive that navigation, otherwise remember() starts at null and the gray fallback
 * flashes every time the user goes back.
 *
 * Keep only one small, decoded avatar in process memory, keyed by its exact HTTPS URL.
 * This is intentionally separate from artwork/thumbnails and never writes to disk.
 */
private object RecentAccountAvatar {
    private var url: String? = null
    private var bitmap: Bitmap? = null
    private var refreshedAtMs: Long = 0L

    @Synchronized
    fun get(requestedUrl: String): Bitmap? =
        bitmap?.takeIf { requestedUrl.isNotBlank() && requestedUrl == url }

    @Synchronized
    fun isFresh(requestedUrl: String): Boolean =
        get(requestedUrl) != null &&
            SystemClock.elapsedRealtime() - refreshedAtMs < AVATAR_CACHE_MAX_AGE_MS

    @Synchronized
    fun put(requestedUrl: String, decoded: Bitmap) {
        if (requestedUrl.isBlank()) return
        url = requestedUrl
        bitmap = decoded
        refreshedAtMs = SystemClock.elapsedRealtime()
    }
}

@Composable
fun ReiAnixAccountAvatar(
    pictureUrl: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val avatarUrl = pictureUrl.trim()
    // Read before the first frame: returning from any Settings category should draw
    // the already-loaded photo, never a temporary AccountCircle placeholder.
    var bitmap by remember(avatarUrl) { mutableStateOf(RecentAccountAvatar.get(avatarUrl)) }

    LaunchedEffect(avatarUrl) {
        // Keep showing the cached photo even when a background refresh is due.
        RecentAccountAvatar.get(avatarUrl)?.let { bitmap = it }
        if (avatarUrl.isBlank() || RecentAccountAvatar.isFresh(avatarUrl)) return@LaunchedEffect

        loadRemoteAvatarBitmap(avatarUrl)?.let { downloaded ->
            RecentAccountAvatar.put(avatarUrl, downloaded)
            bitmap = downloaded
        }
    }

    Box(
        modifier = modifier
            .size(ReiAnixTokens.Dimensions.accountAvatarSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier
                    .size(ReiAnixTokens.Dimensions.accountAvatarSize)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = Icons.Filled.AccountCircle,
                contentDescription = contentDescription,
                modifier = Modifier.size(ReiAnixTokens.Dimensions.accountAvatarSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private suspend fun loadRemoteAvatarBitmap(urlString: String): android.graphics.Bitmap? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val normalized = urlString.trim()
        if (normalized.isBlank()) return@withContext null

        val url = runCatching { URL(normalized) }.getOrNull() ?: return@withContext null
        if (url.protocol != "https") return@withContext null

        val connection = (url.openConnection() as? HttpURLConnection)
            ?: return@withContext null

        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "image/*")
            connection.setRequestProperty("User-Agent", "ReiAnix/0.2.1")

            if (connection.responseCode !in 200..299) return@withContext null
            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_AVATAR_BYTES) return@withContext null

            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(
                    if (contentLength in 1..MAX_AVATAR_BYTES.toLong()) contentLength.toInt() else 16 * 1024
                )
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_AVATAR_BYTES) return@withContext null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }

            if (bytes.isEmpty()) return@withContext null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

private fun calculateSampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (width / sample > MAX_AVATAR_DIMENSION || height / sample > MAX_AVATAR_DIMENSION) {
        sample *= 2
    }
    return sample
}
