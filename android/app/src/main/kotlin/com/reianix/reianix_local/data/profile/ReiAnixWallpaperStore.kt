package com.reianix.reianix_local.data.profile

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Independent local image crop settings, used for wallpaper and profile banner. */
data class ReiAnixWallpaper(
    val imageName: String? = null,
    val zoom: Float = 1f,
    val positionX: Float = 0.5f,
    val positionY: Float = 0.5f,
) {
    fun normalized() = copy(
        zoom = zoom.finiteIn(1f, 3f, 1f),
        positionX = positionX.finiteIn(0f, 1f, 0.5f),
        positionY = positionY.finiteIn(0f, 1f, 0.5f),
    )
}

private fun Float.finiteIn(min: Float, max: Float, fallback: Float) =
    if (isFinite()) coerceIn(min, max) else fallback

class ReiAnixWallpaperStore private constructor(context: Context, private val isBanner: Boolean = false) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(if (isBanner) "reianix_banner_v2" else "reianix_wallpaper_v1", Context.MODE_PRIVATE)
    private val directory = File(app.filesDir, if (isBanner) "profile_banner" else "app_wallpaper")
    private val mutex = Mutex()
    private val current = MutableStateFlow(read())
    val state = current.asStateFlow()

    fun file(name: String?): File? = name?.takeIf { it.matches(Regex("[a-f0-9-]+[.](jpg|webp|png)")) }
        ?.let { File(directory, it) }

    private fun read() = ReiAnixWallpaper(
        imageName = prefs.getString("image", null),
        zoom = prefs.getFloat("zoom", 1f),
        positionX = prefs.getFloat("x", 0.5f),
        positionY = prefs.getFloat("y", 0.5f),
    ).normalized()

    suspend fun importWallpaper(uri: Uri): String = importPersonalImage(app, directory, uri, 1600)

    internal suspend fun importPreset(art: PersonalArt): String = withContext(Dispatchers.IO) {
        require(art.folder == if (isBanner) "banners" else "wallpapers")
        check(directory.isDirectory || directory.mkdirs())
        val name = UUID.randomUUID().toString() + "." + art.extension
        val target = File(directory, name)
        try {
            ReiAnixArtPack.open(app, art).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            name
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    suspend fun discard(name: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (name != current.value.imageName) file(name)?.delete()
        }
    }

    suspend fun save(wallpaper: ReiAnixWallpaper) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val clean = wallpaper.normalized()
            require(clean.imageName == null || file(clean.imageName)?.isFile == true) { "Imagem indisponível." }
            val previous = current.value
            check(prefs.edit().putString("image", clean.imageName)
                .putFloat("zoom", clean.zoom).putFloat("x", clean.positionX).putFloat("y", clean.positionY)
                .remove("dim").remove("blur").commit()) {
                "Não foi possível salvar o papel de parede."
            }
            current.value = clean
            if (previous.imageName != clean.imageName) file(previous.imageName)?.delete()
        }
    }

    companion object {
        @Volatile private var instance: ReiAnixWallpaperStore? = null
        @Volatile private var bannerInstance: ReiAnixWallpaperStore? = null
        fun banner(context: Context): ReiAnixWallpaperStore {
            val appContext = context.applicationContext
            return bannerInstance?.takeIf { it.app === appContext } ?: synchronized(this) {
                bannerInstance?.takeIf { it.app === appContext }
                    ?: ReiAnixWallpaperStore(appContext, true).also { bannerInstance = it }
            }
        }
        fun from(context: Context): ReiAnixWallpaperStore {
            val appContext = context.applicationContext
            return instance?.takeIf { it.app === appContext } ?: synchronized(this) {
                instance?.takeIf { it.app === appContext }
                    ?: ReiAnixWallpaperStore(appContext).also { instance = it }
            }
        }
    }
}
