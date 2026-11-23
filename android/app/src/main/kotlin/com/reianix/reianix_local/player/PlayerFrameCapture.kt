package com.reianix.reianix_local.player

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Captures the actual local video frame, never a screenshot of the controls.
 * Decoding and writing are strictly opt-in IO work, not part of playback.
 */
internal object PlayerFrameCapture {
    suspend fun capture(context: Context, source: Uri, positionMs: Long): File? =
        withContext(Dispatchers.IO) {
            if (source.scheme !in setOf("content", "file")) return@withContext null
            val retriever = MediaMetadataRetriever()
            var frame: Bitmap? = null
            try {
                if (source.scheme == "file") retriever.setDataSource(requireNotNull(source.path))
                else retriever.setDataSource(context.applicationContext, source)
                val micros = positionMs.coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L
                frame = if (Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(
                        micros, MediaMetadataRetriever.OPTION_CLOSEST, 1920, 1080,
                    )
                } else {
                    retriever.getFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST)
                }
                val bitmap = frame ?: return@withContext null
                val scale = minOf(1f, 1920f / bitmap.width, 1080f / bitmap.height)
                val export = if (scale < 1f) Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                ) else bitmap
                val temporary = File(context.cacheDir, "reianix-frame-${UUID.randomUUID()}.jpg")
                try {
                    FileOutputStream(temporary).use { stream ->
                        check(export.compress(Bitmap.CompressFormat.JPEG, 92, stream))
                        stream.flush()
                    }
                    temporary
                } catch (error: Exception) {
                    temporary.delete()
                    null
                } finally {
                    if (export !== bitmap) export.recycle()
                }
            } catch (_: Exception) {
                null
            } finally {
                frame?.recycle()
                retriever.release()
            }
        }

    suspend fun saveToGallery(context: Context, source: File, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < 29) return@withContext false
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ReiAnix")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val destination = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false
            try {
                val written = resolver.openOutputStream(destination)?.use { output ->
                    source.inputStream().use { it.copyTo(output) }
                    true
                } == true
                check(written)
                val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                check(resolver.update(destination, ready, null, null) > 0)
                true
            } catch (_: Exception) {
                resolver.delete(destination, null, null)
                false
            }
        }
}
