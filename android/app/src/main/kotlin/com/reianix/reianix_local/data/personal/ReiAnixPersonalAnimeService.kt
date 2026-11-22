package com.reianix.reianix_local.data.personal

import android.util.LruCache
import com.reianix.reianix_local.data.sqlite.ReiAnixAnimeRecord
import com.reianix.reianix_local.data.sqlite.ReiAnixSqliteLibraryRepository
import com.reianix.reianix_local.data.sqlite.ReiAnixSqliteSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Locale

/**
 * User-owned local state: entirely separate from AniList's public score, automatic
 * metadata, image matching and scan-owned SQLite columns. No extra database.
 */
enum class ReiAnixWatchStatus(val label: String) {
    PLANNING("Quero assistir"),
    WATCHING("Assistindo"),
    COMPLETED("Concluído"),
    PAUSED("Pausado"),
    DROPPED("Abandonado"),
}

data class ReiAnixPersonalAnimeState(
    val status: ReiAnixWatchStatus? = null,
    /** 0..100, with each unit representing 0.1 points on a 0..10 scale. */
    val ratingTenths: Int? = null,
)

data class ReiAnixLocalRecommendation(
    val animeId: Long,
    val title: String,
    val reason: String,
    val posterPath: String? = null,
)

class ReiAnixPersonalAnimeService(
    private val settings: ReiAnixSqliteSettingsRepository,
    private val library: ReiAnixSqliteLibraryRepository,
) {
    suspend fun load(animeId: Long): ReiAnixPersonalAnimeState {
        require(animeId > 0L)
        val status = settings.getPreference(statusKey(animeId))?.value
            ?.let { value -> ReiAnixWatchStatus.entries.firstOrNull { it.name == value } }
        val rating = settings.getPreference(ratingKey(animeId))?.value
            ?.toIntOrNull()?.takeIf { it in 0..100 }
        return ReiAnixPersonalAnimeState(status, rating)
    }

    suspend fun setStatus(animeId: Long, status: ReiAnixWatchStatus?): ReiAnixPersonalAnimeState {
        require(animeId > 0L)
        if (status == null) settings.removePreference(statusKey(animeId))
        else settings.setPreference(statusKey(animeId), status.name)
        return load(animeId)
    }

    suspend fun setRating(animeId: Long, ratingTenths: Int?): ReiAnixPersonalAnimeState {
        require(animeId > 0L)
        require(ratingTenths == null || ratingTenths in 0..100)
        if (ratingTenths == null) settings.removePreference(ratingKey(animeId))
        else settings.setPreference(ratingKey(animeId), ratingTenths.toString())
        return load(animeId)
    }

    // Keep recent results across Details entries, including successful empty lists.
    private val recentRecommendations = LruCache<Pair<Long, Int>, List<ReiAnixLocalRecommendation>>(32)

    internal fun cachedRecommendations(animeId: Long, limit: Int): List<ReiAnixLocalRecommendation>? =
        recentRecommendations.get(animeId to limit)

    /** Details-only local recommendations; ranking runs off the UI thread. */
    suspend fun recommendLocal(animeId: Long, limit: Int = 6): List<ReiAnixLocalRecommendation> {
        require(animeId > 0L)
        require(limit in 1..12)
        val all = withContext(Dispatchers.IO) {
            buildList {
                var offset = 0
                while (true) {
                    val page = library.listAnime(limit = 200, offset = offset)
                    addAll(page)
                    offset += page.size
                    if (page.size < 200) break
                }
            }
        }
        val allPrefs = settings.allPreferences()
        val ranked = withContext(Dispatchers.Default) {
        val current = all.firstOrNull { it.id == animeId } ?: return@withContext emptyList<ReiAnixLocalRecommendation>()
        val favoriteGenres = all.asSequence().filter {
            it.favorite == true || (allPrefs[ratingKey(it.id)]?.toIntOrNull() ?: 0) >= 75
        }.flatMap { genres(it.genresJson).asSequence() }.groupingBy { it }.eachCount()
        val seedGenres = genres(current.genresJson)
        all.asSequence()
            .filter { it.id != animeId && it.status != "UNRELEASED" }
            .filter { allPrefs[statusKey(it.id)] !in setOf("COMPLETED", "DROPPED") }
            .map { candidate ->
                val common = genres(candidate.genresJson).intersect(seedGenres)
                val taste = genres(candidate.genresJson).sumOf { favoriteGenres[it] ?: 0 }
                val value = common.size * 20 + taste * 3 +
                    ((candidate.score ?: 0).coerceIn(0, 100) / 20)
                Triple(candidate, common, value)
            }
            .filter { it.third > 0 }
            .sortedWith(compareByDescending<Triple<ReiAnixAnimeRecord, Set<String>, Int>> { it.third }
                .thenBy { it.first.title.lowercase(Locale.ROOT) })
            .take(limit)
            .map { (anime, common, _) ->
                ReiAnixLocalRecommendation(
                    animeId = anime.id,
                    title = anime.title,
                    reason = common.take(2).joinToString(", ")
                        .ifBlank { "Combina com seus animes favoritos" },
                    posterPath = anime.coverCache?.takeIf { it.isNotBlank() },
                )
            }.toList()
        }
        // Only the bounded result set is inspected for persisted local artwork.
        // Do not perform any cover downloads or metadata searches here.
        val recommendations = withContext(Dispatchers.IO) {
            ranked.map { suggestion ->
                val stored = library.artworkForAnime(suggestion.animeId)
                    .firstOrNull { it.artworkType == "poster" && !it.localPath.isNullOrBlank() && java.io.File(it.localPath).isFile }
                    ?.localPath
                suggestion.copy(posterPath = stored ?: suggestion.posterPath)
            }
        }
        recentRecommendations.put(animeId to limit, recommendations)
        return recommendations
    }

    private fun genres(raw: String?): Set<String> = runCatching {
        val array = JSONArray(raw ?: "[]")
        buildSet {
            for (i in 0 until array.length()) {
                val genre = array.optString(i).trim().lowercase(Locale.ROOT)
                if (genre.isNotBlank()) add(genre)
            }
        }
    }.getOrDefault(emptySet())

    companion object {
        fun statusKey(id: Long): String = "reianix.personal.status.$id"
        fun ratingKey(id: Long): String = "reianix.personal.rating.$id"
    }
}
