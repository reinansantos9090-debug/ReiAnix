package com.reianix.reianix_local.player

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Read-only, IO-only requests using the established AniList association, not filename search. */
internal class AniSkipSegments(context: Context) {
    private val directory = File(context.cacheDir, "player-skip-v1")

    fun fetch(anilistId: Long, episode: Int, season: Int, durationMs: Long): List<PlayerSkipSegment> {
        if (anilistId <= 0 || episode <= 0 || durationMs <= 0) return emptyList()
        val primary = fetchAniSkip(anilistId, episode, durationMs)
        val kinds = primary.mapTo(mutableSetOf()) { it.kind }
        if (season <= 0 || ("op" in kinds && "ed" in kinds)) return primary

        // IntroDB only fills missing types. The mapping supplies the service's
        // season/episode numbers, which can differ from AniList numbering.
        return primary + fetchIntroDb(anilistId, episode, season, durationMs)
            .filter { it.kind !in kinds }
    }

    private fun fetchAniSkip(anilistId: Long, episode: Int, durationMs: Long): List<PlayerSkipSegment> {
        val file = File(directory, "$anilistId-$episode-$durationMs.json")
        readCached(file)?.let { return parse(it, durationMs) }
        return try {
            val body = JSONObject().put("query", "query { Media(id: $anilistId, type: ANIME) { idMal } }")
            val mal = request("https://graphql.anilist.co", body.toString())
                .optJSONObject("data")?.optJSONObject("Media")?.optLong("idMal") ?: 0L
            if (mal <= 0L) return emptyList()
            val seconds = String.format(Locale.US, "%.3f", durationMs / 1000.0)
            val json = request("https://api.aniskip.com/v2/skip-times/$mal/$episode?types=op&types=ed&types=recap&episodeLength=$seconds")
            val segments = parse(json, durationMs)
            if (segments.isNotEmpty()) writeCached(file, json)
            segments
        } catch (_: Exception) { emptyList() }
    }

    fun parse(json: JSONObject, duration: Long): List<PlayerSkipSegment> {
        if (!json.optBoolean("found")) return emptyList()
        val results = json.optJSONArray("results") ?: return emptyList()
        return buildList {
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val interval = item.optJSONObject("interval") ?: continue
                val start = interval.optDouble("startTime")
                val end = interval.optDouble("endTime")
                val length = item.optDouble("episodeLength")
                val kind = item.optString("skipType")
                if (!start.isFinite() || !end.isFinite() || !length.isFinite() || length <= 0 ||
                    abs(length * 1000 - duration) > 3_000) continue
                val a = (start * 1000).roundToLong()
                val b = (end * 1000).roundToLong()
                if (PlayerEnhancementPolicy.validSegment(kind, a, b, duration)) add(PlayerSkipSegment(kind, a, b))
            }
        }.distinct()
    }

    private fun fetchIntroDb(
        anilistId: Long,
        episode: Int,
        season: Int,
        durationMs: Long,
    ): List<PlayerSkipSegment> {
        val file = File(directory, "introdb-$anilistId-$season-$episode-$durationMs.json")
        readCached(file)?.let { return parseIntroDb(it, durationMs) }

        return runCatching {
            val response = request(
                "https://api.ani.zip/mappings?anilist_id=$anilistId",
                maxChars = 2 * 1024 * 1024,
            )
            val mappings = response.optJSONObject("mappings") ?: return emptyList()
            val imdbId = mappings.optString("imdb_id")
            if (mappings.optLong("anilist_id") != anilistId || !IMDB_ID.matches(imdbId)) return emptyList()

            val mapped = response.optJSONObject("episodes")?.optJSONObject(episode.toString())
                ?: return emptyList()
            val mappedSeason = mapped.optInt("seasonNumber")
            val mappedEpisode = mapped.optInt("episodeNumber")
            val runtimeMinutes = mapped.optInt("runtime")
            if (mappedSeason <= 0 || mappedEpisode <= 0 || runtimeMinutes <= 0 ||
                (season != 1 && season != mappedSeason) ||
                abs(runtimeMinutes * 60_000L - durationMs) > 60_000L
            ) return emptyList()

            val json = request(
                "https://api.introdb.app/segments?imdb_id=$imdbId&season=$mappedSeason&episode=$mappedEpisode",
            )
            if (json.optString("imdb_id") != imdbId || json.optInt("season") != mappedSeason ||
                json.optInt("episode") != mappedEpisode || json.optBoolean("is_movie")
            ) return emptyList()

            parseIntroDb(json, durationMs).also { writeCached(file, json) }
        }.getOrDefault(emptyList())
    }

    internal fun parseIntroDb(json: JSONObject, durationMs: Long): List<PlayerSkipSegment> =
        INTRO_DB_SEGMENTS.mapNotNull { (field, kind) ->
            val segment = json.optJSONObject(field) ?: return@mapNotNull null
            val start = segment.optLong("start_ms", -1L)
            val end = segment.optLong("end_ms", -1L)
            if (PlayerEnhancementPolicy.validSegment(kind, start, end, durationMs)) {
                PlayerSkipSegment(kind, start, end)
            } else {
                null
            }
        }

    private fun readCached(file: File): JSONObject? = synchronized(CACHE_LOCK) {
        runCatching {
            val cache = AtomicFile(file)
            if (file.isFile && file.lastModified() > System.currentTimeMillis() - CACHE_LIFETIME_MS) {
                JSONObject(cache.readFully().toString(Charsets.UTF_8))
            } else {
                null
            }
        }.getOrNull()
    }

    private fun writeCached(file: File, json: JSONObject) {
        synchronized(CACHE_LOCK) {
            runCatching {
                val cache = AtomicFile(file)
                val stream = cache.startWrite()
                try {
                    stream.write(json.toString().toByteArray(Charsets.UTF_8))
                    cache.finishWrite(stream)
                } catch (error: Exception) {
                    cache.failWrite(stream)
                    throw error
                }
                directory.listFiles()?.filter { it.extension == "json" }
                    ?.sortedByDescending { it.lastModified() }?.drop(512)?.forEach { it.delete() }
            }
        }
    }

    private fun request(url: String, body: String? = null, maxChars: Int = 128 * 1024): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5_000
            c.readTimeout = 5_000
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            check(c.responseCode == 200)
            return c.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                val data = StringBuilder()
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    data.append(buffer, 0, n)
                    check(data.length <= maxChars)
                }
                JSONObject(data.toString())
            }
        } finally { c.disconnect() }
    }

    private companion object {
        const val CACHE_LIFETIME_MS = 604_800_000L
        val CACHE_LOCK = Any()
        val IMDB_ID = Regex("tt[0-9]{7,10}")
        val INTRO_DB_SEGMENTS = listOf("intro" to "op", "outro" to "ed", "recap" to "recap")
    }
}
