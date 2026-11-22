package com.reianix.reianix_local.data.personal

import android.content.Context
import android.util.LruCache
import com.reianix.reianix_local.data.anilist.AniListResult
import com.reianix.reianix_local.data.anilist.ReiAnixAniListClient
import com.reianix.reianix_local.data.anilist.ReiAnixAnimeCharacter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Optional character-only cache. Never touched by artwork, scanner or metadata bootstrap. */
class ReiAnixCharacterCache(
    context: Context,
    private val client: ReiAnixAniListClient,
) {
    private data class CachedCharacters(
        val characters: List<ReiAnixAnimeCharacter>,
        val storedAt: Long,
    )

    private val folder = File(context.applicationContext.cacheDir, "characters")
    private val memory = LruCache<Int, CachedCharacters>(64)

    fun peek(anilistId: Int): List<ReiAnixAnimeCharacter>? =
        memory.get(anilistId)
            ?.takeIf { System.currentTimeMillis() - it.storedAt <= CACHE_TTL_MS }
            ?.characters

    suspend fun load(anilistId: Int): AniListResult<List<ReiAnixAnimeCharacter>> {
        require(anilistId > 0)
        peek(anilistId)?.let { return AniListResult.Success(it) }
        val cacheFile = File(folder, "$anilistId.json")
        val cached = withContext(Dispatchers.IO) {
            val storedAt = cacheFile.lastModified()
            if (!cacheFile.isFile || System.currentTimeMillis() - storedAt > CACHE_TTL_MS) {
                null
            } else runCatching {
                val array = JSONArray(cacheFile.readText())
                val characters = buildList {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val id = item.optInt("id")
                        val name = item.optString("name")
                        if (id <= 0 || name.isBlank()) continue
                        add(ReiAnixAnimeCharacter(id, name, item.optString("role"),
                            item.optString("image").takeIf { it.startsWith("https://") }))
                    }
                }
                CachedCharacters(characters, storedAt)
            }.getOrNull()
        }
        if (cached != null) {
            memory.put(anilistId, cached)
            return AniListResult.Success(cached.characters)
        }
        val response = client.getCharacters(anilistId)
        if (response is AniListResult.Success) {
            memory.put(anilistId, CachedCharacters(response.value, System.currentTimeMillis()))
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
        }
        return response
    }

    companion object { private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000 }
}
