package com.reianix.reianix_local.data.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal const val DEFAULT_PROFILE_AVATAR_ID = "bolt"

/** User-owned local profile, entirely independent of catalog metadata and external accounts. */
/** Retired frame styles must not reappear after an upgrade, even in saved profiles. */
internal fun normalizedProfileFrame(frame: String): String =
    if (frame == "headset" || frame == "cat_ears" || frame == "accent") "none" else frame

data class ReiAnixProfile(
    val name: String = "",
    val bio: String = "",
    val avatar: String = DEFAULT_PROFILE_AVATAR_ID,
    val photo: String? = null,
    val frame: String = "none",
    val color: String = "blue",
    val featuredAnimeId: Long? = null,
) {
    val displayName get() = name.ifBlank { "Nome do usuário" }
    val displayBio get() = bio.ifBlank { "Explorando o mundo dos animes." }
}

class ReiAnixProfileStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("reianix_local_profile_v1", Context.MODE_PRIVATE)
    private val directory = File(app.filesDir, "local_profile")
    private val mutex = Mutex()
    private val current = MutableStateFlow(read())
    val state = current.asStateFlow()

    /** Remove the retired banner reference; never reuse it as a wallpaper without consent. */
    internal suspend fun removeLegacyBanner() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val obsolete = prefs.getString("banner", null)
            if (obsolete != null && prefs.edit().remove("banner").commit() && obsolete != current.value.photo) {
                image(obsolete)?.delete()
            }
        }
    }

    private fun read() = ReiAnixProfile(
        name = prefs.getString("name", "").orEmpty(),
        bio = prefs.getString("bio", "").orEmpty(),
        avatar = prefs.getString("avatar", DEFAULT_PROFILE_AVATAR_ID) ?: DEFAULT_PROFILE_AVATAR_ID,
        photo = prefs.getString("photo", null),
        frame = normalizedProfileFrame(prefs.getString("frame", "none").orEmpty()),
        color = prefs.getString("color", "blue").orEmpty(),
        featuredAnimeId = prefs.getLong("featured", 0L).takeIf { it > 0L },
    )

    fun image(name: String?): File? =
        name?.takeIf { it.matches(Regex("[a-f0-9-]+[.]jpg")) }
            ?.let { File(directory, it) }

    suspend fun importPhoto(uri: Uri): String = importPersonalImage(app, directory, uri, 512)

    // Work on a private, bounded copy. Never modify the user's gallery or active avatar.
    private val photoDraftDirectory = File(app.cacheDir, "profile_photo_drafts")
    internal suspend fun importPhotoDraft(uri: Uri): String =
        importPersonalImage(app, photoDraftDirectory, uri, 2048)

    internal fun photoDraftFile(name: String): File? =
        name.takeIf { it.matches(Regex("[a-f0-9-]+[.]jpg")) }
            ?.let { File(photoDraftDirectory, it) }

    internal suspend fun discardPhotoDraft(name: String) = withContext(Dispatchers.IO) {
        photoDraftFile(name)?.delete()
        Unit
    }

    internal suspend fun importCroppedPhoto(name: String, left: Int, top: Int, size: Int): String =
        withContext(Dispatchers.IO) {
            val source = requireNotNull(photoDraftFile(name))
            val bitmap = BitmapFactory.decodeFile(source.absolutePath) ?: error("Foto indisponível.")
            try {
                require(size > 0 && left >= 0 && top >= 0 &&
                    size <= bitmap.width && size <= bitmap.height &&
                    left <= bitmap.width - size && top <= bitmap.height - size)
                check(directory.isDirectory || directory.mkdirs())
                val outputName = UUID.randomUUID().toString() + ".jpg"
                val target = File(directory, outputName)
                val cropped = Bitmap.createBitmap(bitmap, left, top, size, size)
                try {
                    val scaled = if (size > 512) Bitmap.createScaledBitmap(cropped, 512, 512, true) else cropped
                    try {
                        target.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
                    } finally {
                        if (scaled !== cropped) scaled.recycle()
                    }
                    outputName
                } catch (e: Exception) {
                    target.delete()
                    throw e
                } finally {
                    if (cropped !== bitmap) cropped.recycle()
                }
            } finally {
                bitmap.recycle()
            }
        }

    /** Only temporary selections can be discarded; never delete the active photo. */
    suspend fun discard(name: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val selected = current.value
            if (name != selected.photo) image(name)?.delete()
        }
    }

    suspend fun save(profile: ReiAnixProfile) = update { profile }

    /** Transform the latest profile under the same lock as persistence, so
     * autosaving identity cannot overwrite an independently changed field. */
    suspend fun update(change: (ReiAnixProfile) -> ReiAnixProfile) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val profile = change(current.value)
            val clean = profile.copy(
                name = profile.name.trim().take(40),
                bio = profile.bio.trim().take(160),
                frame = normalizedProfileFrame(profile.frame),
            )
            require(clean.photo == null || image(clean.photo)?.isFile == true) { "Foto indisponível." }
            val old = current.value
            val obsolete = prefs.getString("banner", null)
            check(prefs.edit().putString("name", clean.name).putString("bio", clean.bio)
                .putString("avatar", clean.avatar).putString("photo", clean.photo)
                .remove("banner").putString("frame", clean.frame)
                .putString("color", clean.color).putLong("featured", clean.featuredAnimeId ?: 0L)
                .commit()) { "Não foi possível salvar o perfil." }
            current.value = clean
            if (old.photo != clean.photo) image(old.photo)?.delete()
            if (obsolete != clean.photo && obsolete != old.photo) image(obsolete)?.delete()
        }
    }

    companion object {
        @Volatile private var instance: ReiAnixProfileStore? = null
        fun from(context: Context): ReiAnixProfileStore = instance ?: synchronized(this) {
            instance ?: ReiAnixProfileStore(context).also { instance = it }
        }
    }
}

internal suspend fun importPersonalImage(app: Context, directory: File, uri: Uri, limit: Int): String = withContext(Dispatchers.IO) {
    val bitmap = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
            val largest = maxOf(info.size.width, info.size.height)
            require(largest > 0) { "Imagem inválida." }
            if (largest > limit) decoder.setTargetSize(
                (info.size.width.toLong() * limit / largest).toInt().coerceAtLeast(1),
                (info.size.height.toLong() * limit / largest).toInt().coerceAtLeast(1),
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Imagem inválida." }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > limit * 2) inSampleSize *= 2
        }
        app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Não foi possível abrir a imagem.")
    }
    check(directory.isDirectory || directory.mkdirs()) { "Não foi possível criar a pasta do perfil." }
    val name = UUID.randomUUID().toString() + ".jpg"
    val target = File(directory, name)
    try {
        val largest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (largest > limit) Bitmap.createScaledBitmap(bitmap,
            (bitmap.width.toLong() * limit / largest).toInt().coerceAtLeast(1),
            (bitmap.height.toLong() * limit / largest).toInt().coerceAtLeast(1), true) else bitmap
        try {
            target.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        name
    } catch (e: Exception) {
        target.delete()
        throw e
    } finally {
        bitmap.recycle()
    }
}

