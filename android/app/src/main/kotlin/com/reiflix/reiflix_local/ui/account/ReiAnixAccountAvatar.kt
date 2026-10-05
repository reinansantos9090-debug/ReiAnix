package com.reiflix.reiflix_local.ui.account

import android.graphics.BitmapFactory
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
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_AVATAR_BYTES = 4 * 1024 * 1024
private const val MAX_AVATAR_DIMENSION = 256

@Composable
fun ReiAnixAccountAvatar(
    pictureUrl: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(pictureUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(pictureUrl) {
        bitmap = loadRemoteAvatarBitmap(pictureUrl)
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
