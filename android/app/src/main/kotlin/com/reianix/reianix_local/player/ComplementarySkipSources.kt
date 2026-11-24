package com.reianix.reianix_local.player

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

internal enum class SkipProvider { ANISKIP, INTRODB, SKIPDB }
internal data class SkipEvidence(val provider: SkipProvider, val segment: PlayerSkipSegment, val durationMatched: Boolean)
internal data class SkipProviderResult(val evidence: List<SkipEvidence> = emptyList(), val absentKinds: Set<String> = emptySet())

/** Runtime display only. No combined/derived timestamp database, uploads or votes. */
internal object SkipConsensus {
    fun choose(results: List<SkipProviderResult>, duration: Long): List<PlayerSkipSegment> {
        val absent = results.flatMap { it.absentKinds }.toSet()
        val candidates = results.flatMap { it.evidence }
            .filter { PlayerEnhancementPolicy.plausibleRemoteSegment(it.segment, duration) }
        return listOf("op", "ed").mapNotNull { kind ->
            if (kind in absent) return@mapNotNull null
            val evidence = candidates.filter { it.segment.kind == kind }.distinct()
            if (evidence.isEmpty() || evidence.groupBy { it.provider }.values.any { it.size > 1 }) return@mapNotNull null
            val first = evidence.first().segment
            // Any credible disagreement is a veto, not a majority vote.
            if (evidence.any { !agree(first, it.segment) } ||
                evidence.any { a -> evidence.any { b -> !agree(a.segment, b.segment) } }) return@mapNotNull null
            // IntroDB does not describe the video cut, so never trust it alone.
            if (evidence.none { it.durationMatched }) return@mapNotNull null
            // With agreement, use the intersection to avoid extending a skip into dialogue.
            val start = evidence.maxOf { it.segment.startMs }
            val end = evidence.minOf { it.segment.endMs }
            PlayerSkipSegment(kind, start, end).takeIf { PlayerEnhancementPolicy.plausibleRemoteSegment(it, duration) }
        }
    }

    fun agree(a: PlayerSkipSegment, b: PlayerSkipSegment): Boolean =
        a.kind == b.kind && abs(a.startMs - b.startMs) <= 3_000L && abs(a.endMs - b.endMs) <= 3_000L

    fun addLocal(remote: List<PlayerSkipSegment>, local: List<PlayerSkipSegment>,
                 results: List<SkipProviderResult>, duration: Long): List<PlayerSkipSegment> {
        val blocked = results.flatMap { it.absentKinds }.toSet()
        return PlayerEnhancementPolicy.trustedRemoteSegments(remote + local.filter { candidate ->
            candidate.kind !in blocked && remote.none { it.kind == candidate.kind } &&
                results.flatMap { it.evidence }.filter { it.segment.kind == candidate.kind }
                    .all { agree(it.segment, candidate) }
        }, duration)
    }
}

internal data class SkipImdbEpisode(val id: String, val season: Int, val episode: Int)

/** Conservative, exact-ID-only bridge. AniMap's title crosswalk does not prove
 * episode offsets; only an unambiguous first-season TV entry is eligible.
 */
internal class ComplementarySkipSources {
    suspend fun fetch(anilistId: Long, season: Int, episode: Int, total: Int?, duration: Long): List<SkipProviderResult> {
        if (season != 1 || episode <= 0 || (total != null && total > 0 && episode > total)) return emptyList()
        val mapping = get("https://animap.id/api/map/anilist/$anilistId") ?: return emptyList()
        val id = exactImdb(mapping, anilistId) ?: return emptyList()
        val reverse = get("https://animap.id/api/map/imdb/$id") ?: return emptyList()
        if (exactImdb(reverse, anilistId) != id) return emptyList()
        val key = SkipImdbEpisode(id, 1, episode)
        val query = "imdb_id=$id&season=1&episode=$episode"
        val seconds = String.format(Locale.US, "%.3f", duration / 1000.0)
        return coroutineScope {
            val intro = async { get("https://api.introdb.app/segments?$query")?.let { parseIntro(it, key, duration) } }
            val skip = async { get("https://api.skipdb.tv/api/segments?$query&duration=$seconds&adjust=none")?.let { parseSkip(it, key, duration) } }
            listOfNotNull(intro.await(), skip.await())
        }
    }

    private suspend fun get(url: String): JSONObject? {
        coroutineContext.ensureActive()
        val host = URL(url).host
        val now = SystemClock.elapsedRealtime()
        synchronized(LOCK) {
            if ((backoff[host] ?: 0L) > now) return null
            cache[url]?.takeIf { now - it.first < 600_000L }?.let { return JSONObject(it.second) }
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 3500
            conn.readTimeout = 3500
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            val status = conn.responseCode
            if (status == 429) {
                val retry = conn.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(60L, 3600L) ?: 120L
                synchronized(LOCK) { backoff[host] = SystemClock.elapsedRealtime() + retry * 1000L }
            }
            if (status != 200) return null
            val raw = conn.inputStream.bufferedReader().use { reader ->
                val out = StringBuilder()
                val buf = CharArray(4096)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = reader.read(buf)
                    if (n < 0) break
                    out.append(buf, 0, n)
                    if (out.length > 128 * 1024) return null
                }
                out.toString()
            }
            val parsed = JSONObject(raw)
            synchronized(LOCK) {
                cache[url] = SystemClock.elapsedRealtime() to raw
                while (cache.size > 64) cache.remove(cache.keys.first())
            }
            return parsed
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return null }
        finally { conn.disconnect() }
    }

    companion object {
        private val LOCK = Any()
        private val cache = linkedMapOf<String, Pair<Long, String>>()
        private val backoff = mutableMapOf<String, Long>()
        private val imdbPattern = Regex("tt[0-9]{7,10}")
        private fun ids(json: JSONObject, key: String): List<String> {
            val a = json.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).map { a.optString(it) }.distinct()
        }

        fun exactImdb(json: JSONObject, anilistId: Long): String? {
            if (ids(json, "anilist_id") != listOf(anilistId.toString()) ||
                ids(json, "mal_id").singleOrNull()?.toLongOrNull()?.let { it > 0 } != true) return null
            return ids(json, "imdb_id").singleOrNull()?.takeIf { imdbPattern.matches(it) }
        }

        private fun identity(json: JSONObject, key: SkipImdbEpisode): Boolean =
            json.optString("imdb_id") == key.id && json.optInt("season", -1) == key.season &&
                json.optInt("episode", -1) == key.episode

        fun parseIntro(json: JSONObject, key: SkipImdbEpisode, duration: Long): SkipProviderResult {
            if (!identity(json, key) || json.optString("media_type") != "tv" || json.optBoolean("is_movie")) return SkipProviderResult()
            val absent = buildSet {
                if (json.optBoolean("no_intro")) add("op")
                if (json.optBoolean("no_outro")) add("ed")
            }
            val evidence = listOf("intro" to "op", "outro" to "ed").mapNotNull { (field, kind) ->
                val item = json.optJSONObject(field) ?: return@mapNotNull null
                val confidence = item.optDouble("confidence")
                if (!confidence.isFinite() || confidence !in 0.70..1.0 || item.optInt("submission_count", 0) < 1) return@mapNotNull null
                val segment = PlayerSkipSegment(kind, item.optLong("start_ms", -1), item.optLong("end_ms", -1))
                if (!PlayerEnhancementPolicy.plausibleRemoteSegment(segment, duration)) return@mapNotNull null
                SkipEvidence(SkipProvider.INTRODB, segment, false)
            }
            return SkipProviderResult(evidence, absent)
        }

        fun parseSkip(json: JSONObject, key: SkipImdbEpisode, duration: Long): SkipProviderResult {
            if (!identity(json, key)) return SkipProviderResult()
            val segments = json.optJSONObject("segments") ?: return SkipProviderResult()
            return SkipProviderResult(listOf("intro" to "op", "outro" to "ed").mapNotNull { (field, kind) ->
                val item = segments.optJSONObject(field) ?: return@mapNotNull null
                val confidence = item.optDouble("confidence")
                if (item.optString("match") != "exact" || item.optBoolean("adjusted") ||
                    !confidence.isFinite() || confidence !in 0.85..1.0 ||
                    abs(item.optLong("offset_ms", Long.MAX_VALUE).toDouble()) > 2000) return@mapNotNull null
                val segment = PlayerSkipSegment(kind, item.optLong("start_ms", -1), item.optLong("end_ms", -1))
                if (!PlayerEnhancementPolicy.plausibleRemoteSegment(segment, duration)) return@mapNotNull null
                SkipEvidence(SkipProvider.SKIPDB, segment, true)
            })
        }
    }
}
