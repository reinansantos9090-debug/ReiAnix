package com.reianix.reianix_local.data.personal

import android.content.Context
import com.reianix.reianix_local.data.anilist.AniListResult
import com.reianix.reianix_local.data.anilist.ReiAnixAniListClient
import com.reianix.reianix_local.data.anilist.ReiAnixAnimeCharacter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Supplemental AniList characters, stored separately from canonical metadata/artwork.
 * Reuses existing seven-day disk cache and coalesces UI + background requests for
 * the same AniList ID. Never changes the library or its metadata publication.
 */
class ReiAnixCharacterCache(
    context: Context,
    private val client: ReiAnixAniListClient,
) {
    private val folder = File(context.applicationContext.cacheDir, "characters")
    private val requestLocks = Array(32) { Mutex() }
    private val published = MutableSharedFlow<Int>(extraBufferCapacity = 64)
    val updates = published.asSharedFlow()

    suspend fun isCached(anilistId: Int): Boolean = withContext(Dispatchers.IO) {
        val file = File(folder, "$anilistId.json")
        isCharacterCacheFresh(file.lastModified(), System.currentTimeMillis()) && file.isFile
    }

    suspend fun load(anilistId: Int): AniListResult<List<ReiAnixAnimeCharacter>> {
        require(anilistId > 0)
        val lock = requestLocks[(anilistId and Int.MAX_VALUE) % requestLocks.size]
        return lock.withLock {
            val cacheFile = File(folder, "$anilistId.json")
            val cached = withContext(Dispatchers.IO) {
                if (!cacheFile.isFile ||
                    !isCharacterCacheFresh(cacheFile.lastModified(), System.currentTimeMillis())
                ) {
                    null
                } else runCatching {
                    val array = JSONArray(cacheFile.readText())
                    buildList {
                        for (i in 0 until array.length()) {
                            val item = array.optJSONObject(i) ?: continue
                            val id = item.optInt("id")
                            val name = item.optString("name")
                            if (id <= 0 || name.isBlank()) continue
                            add(ReiAnixAnimeCharacter(id, name, item.optString("role"),
                                item.optString("image").takeIf { it.startsWith("https://") }))
                        }
                    }
                }.getOrNull()
            }
            if (cached != null) return@withLock AniListResult.Success(cached)
            val response = client.getCharacters(anilistId)
            if (response is AniListResult.Success) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        folder.mkdirs()
                        val array = JSONArray()
                        response.value.forEach {
                            array.put(JSONObject().put("id", it.id).put("name", it.name)
                                .put("role", it.role).put("image", it.imageUrl ?: ""))
                        }
                        val temporary = File(folder, "$anilistId.tmp")
                        temporary.writeText(array.toString())
                        if (!temporary.renameTo(cacheFile)) {
                            cacheFile.writeText(array.toString())
                            temporary.delete()
                        }
                    }
                }
                published.tryEmit(anilistId)
            }
            response
        }
    }

    companion object {
        private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        internal fun isCharacterCacheFresh(lastModifiedMs: Long, nowMs: Long): Boolean =
            lastModifiedMs > 0L && nowMs >= lastModifiedMs &&
                nowMs - lastModifiedMs <= CACHE_TTL_MS
    }
}
