package com.reianix.reianix_local.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest

/** All operations run on the existing preview IO worker. */
internal class PersistentSeekPreviewCache(context: Context, uri: Uri, duration: Long) {
    private val root = File(context.cacheDir, "seek-preview-v2")
    private val folder = runCatching {
        val signature = when (uri.scheme) {
            "file" -> File(requireNotNull(uri.path)).let {
                if (it.isFile) "${it.length()}:${it.lastModified()}" else null
            }
            "content" -> {
                fun value(column: String): Long? = runCatching {
                    context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use {
                        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
                    }
                }.getOrNull()
                val size = value(OpenableColumns.SIZE)
                val modified = value("last_modified") ?: value("date_modified")
                if (size != null && size > 0 && modified != null && modified > 0) "$size:$modified" else null
            }
            else -> null
        } ?: return@runCatching null
        val key = MessageDigest.getInstance("SHA-256")
            .digest("$uri:$signature:$duration".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        File(root, key)
    }.getOrNull()

    fun availableTimes(): List<Long> = synchronized(lock) {
        folder?.listFiles()?.filter { it.extension == "jpg" }
            ?.mapNotNull { it.nameWithoutExtension.toLongOrNull() }?.take(48).orEmpty()
    }

    fun read(time: Long): Bitmap? = synchronized(lock) {
        val dir = folder ?: return@synchronized null
        val f = File(dir, "$time.jpg")
        if (!f.isFile || f.length() !in 1L..256_000L) return@synchronized null
        runCatching { BitmapFactory.decodeFile(f.absolutePath)?.also { f.setLastModified(System.currentTimeMillis()) } }.getOrNull()
    }

    fun put(time: Long, bitmap: Bitmap) = synchronized(lock) {
        val dir = folder ?: return@synchronized
        runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return@runCatching
            val f = File(dir, "$time.jpg")
            val tmp = File(dir, "$time.tmp")
            try {
                val ok = tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                if (ok && tmp.length() in 1L..256_000L) tmp.renameTo(f)
            } finally { tmp.delete() }
            dir.listFiles()?.filter { it.extension == "jpg" }?.sortedByDescending { it.lastModified() }
                ?.drop(48)?.forEach { it.delete() }
            val all = root.listFiles()?.flatMap { it.listFiles()?.filter { f -> f.extension == "jpg" }.orEmpty() }
                .orEmpty().sortedByDescending { it.lastModified() }
            var total = 0L
            for (entry in all) {
                total += entry.length()
                if (total > 64L * 1024 * 1024) entry.delete()
            }
            root.listFiles()?.filter { it.isDirectory && it.list()?.isEmpty() == true }?.forEach { it.delete() }
        }
        Unit
    }
    companion object { private val lock = Any() }
}
