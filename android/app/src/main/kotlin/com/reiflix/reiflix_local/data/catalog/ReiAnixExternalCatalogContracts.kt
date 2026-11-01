package com.reiflix.reiflix_local.data.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONException
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

enum class ReiAnixCatalogSource(
    val key: String,
    val providesMetadata: Boolean,
    val providesArtwork: Boolean,
    val resolvesIdentifiers: Boolean = false,
) {
    ANILIST("anilist", true, true),
    KITSU("kitsu", true, true),
    @Deprecated("The public Jikan API was discontinued; retained for legacy stored references only.")
    JIKAN("jikan", false, false),
    TENRAI("tenrai", true, true),
    ANIPUB("anipub", true, true),
    ANIMAP("animap", false, false, resolvesIdentifiers = true);

    companion object {
        fun fromKey(value: String): ReiAnixCatalogSource? =
            entries.firstOrNull { it.key == value.trim().lowercase(Locale.ROOT) }
    }
}

data class ReiAnixExternalCatalogIds(
    val aniListId: Long? = null,
    val myAnimeListId: Long? = null,
    val kitsuId: String? = null,
    val aniDbId: Long? = null,
    val additionalIds: Map<String, String> = emptyMap(),
) {
    init {
        require(aniListId == null || aniListId > 0L)
        require(myAnimeListId == null || myAnimeListId > 0L)
        require(aniDbId == null || aniDbId > 0L)
        require(kitsuId == null || kitsuId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        additionalIds.forEach { (catalog, id) ->
            require(catalog.matches(Regex("[a-z][a-z0-9_.-]{0,31}")))
            require(id == id.trim() && id.isNotEmpty() && id.length <= 128)
        }
    }

    val hasAnyId: Boolean
        get() = aniListId != null || myAnimeListId != null || kitsuId != null ||
            aniDbId != null || additionalIds.isNotEmpty()

    fun idFor(source: ReiAnixCatalogSource): String? = when (source) {
        ReiAnixCatalogSource.ANILIST -> aniListId?.toString()
        ReiAnixCatalogSource.JIKAN, ReiAnixCatalogSource.TENRAI -> myAnimeListId?.toString()
        ReiAnixCatalogSource.KITSU -> kitsuId
        ReiAnixCatalogSource.ANIPUB -> additionalIds[source.key]
        ReiAnixCatalogSource.ANIMAP -> null
    }

    fun mergeVerified(other: ReiAnixExternalCatalogIds): ReiAnixExternalCatalogIds {
        fun <T> merge(name: String, current: T?, incoming: T?): T? {
            require(current == null || incoming == null || current == incoming) {
                "Conflicting verified $name identifiers."
            }
            return current ?: incoming
        }
        val additional = additionalIds.toMutableMap()
        other.additionalIds.forEach { (catalog, id) ->
            val previous = additional[catalog]
            require(previous == null || previous == id) { "Conflicting verified identifier for $catalog." }
            additional[catalog] = id
        }
        return ReiAnixExternalCatalogIds(
            aniListId = merge("AniList", aniListId, other.aniListId),
            myAnimeListId = merge("MyAnimeList", myAnimeListId, other.myAnimeListId),
            kitsuId = merge("Kitsu", kitsuId, other.kitsuId),
            aniDbId = merge("AniDB", aniDbId, other.aniDbId),
            additionalIds = additional,
        )
    }
}

enum class ReiAnixCatalogMediaKind { SERIES, MOVIE, OVA, ONA, SPECIAL, MUSIC, UNKNOWN }

enum class ReiAnixCatalogArtworkType(val key: String) {
    POSTER("poster"),
    BACKDROP("backdrop"),
    BANNER("banner"),
}

/** Provider image field retained without guessing a canonical meaning. */
data class ReiAnixCatalogArtworkSourceField(
    val originalField: String,
    val url: String,
    val variant: String? = null,
) {
    init {
        require(originalField.isNotBlank())
        requireValidArtworkUrl(url)
        require(variant == null || variant.isNotBlank())
    }
}

/**
 * Fully typed provider candidate. Dimensions may produce a warning, never a type conversion.
 * The local anime row ID is attached only after the external identity is verified.
 */
data class ReiAnixCatalogArtworkCandidate(
    val source: ReiAnixCatalogSource,
    val externalRecordId: String,
    val type: ReiAnixCatalogArtworkType,
    val url: String,
    val originalField: String,
    val variant: String? = null,
    val priority: Int = 400,
    val entityType: String = "anime",
    val width: Int? = null,
    val height: Int? = null,
    /** Local SQLite owner, set only after the external identity has passed verification. */
    val localAnimeId: Long? = null,
) {
    init {
        require(source.providesArtwork && !source.resolvesIdentifiers) {
            "Identifier-only sources cannot provide artwork."
        }
        require(externalRecordId.isNotBlank() && externalRecordId.length <= 128)
        require(entityType == "anime")
        require(originalField.isNotBlank())
        require(variant == null || variant.isNotBlank())
        require(priority >= 0)
        require(width == null || width > 0)
        require(height == null || height > 0)
        require(localAnimeId == null || localAnimeId > 0L)
        requireValidArtworkUrl(url)
    }

    /** Provider/reference/field tuple remains stable through internal conversion. */
    val sourceReference: String
        get() = "${source.key}:$externalRecordId:$originalField"

    /** A geometry discrepancy is observable but cannot rewrite [type]. */
    val orientationWarning: String?
        get() = width?.let { w -> height?.let { h -> reiAnixArtworkAspectRatioWarning(type, w, h) } }
}

internal fun reiAnixArtworkAspectRatioWarning(
    type: ReiAnixCatalogArtworkType,
    width: Int,
    height: Int,
): String? {
    if (width <= 0 || height <= 0) return null
    val ratio = width.toDouble() / height.toDouble()
    return when (type) {
        ReiAnixCatalogArtworkType.POSTER -> if (ratio > 1.15) "poster_has_landscape_ratio" else null
        ReiAnixCatalogArtworkType.BACKDROP, ReiAnixCatalogArtworkType.BANNER ->
            if (ratio < 0.90) "horizontal_artwork_has_portrait_ratio" else null
    }
}

private fun requireValidArtworkUrl(url: String) {
    val uri = runCatching { java.net.URL(url) }.getOrNull()
    require(
        uri != null &&
            (uri.protocol.equals("https", true) || uri.protocol.equals("http", true)) &&
            uri.host.isNotBlank() && uri.userInfo == null &&
            url.none { it.isWhitespace() || it.code == 0 }
    ) { "Artwork URL must be a valid HTTP(S) URL without user info." }
}

/** Remote DTO only. It cannot mutate local metadata, episode identity or playback state. */
data class ReiAnixCatalogAnime(
    val source: ReiAnixCatalogSource,
    val ids: ReiAnixExternalCatalogIds,
    val title: String,
    val alternativeTitles: Set<String> = emptySet(),
    val synopsis: String? = null,
    /** Canonical Poster URL. Kept under its historic property name for source compatibility. */
    val coverUrl: String? = null,
    /** Populated only when the provider explicitly documents a Backdrop field. */
    val backdropUrl: String? = null,
    val releaseYear: Int? = null,
    val format: String? = null,
    val mediaKind: ReiAnixCatalogMediaKind? = null,
    val season: String? = null,
    val seasonNumber: Int? = null,
    val episodes: Int? = null,
    val durationMinutes: Int? = null,
    val status: String? = null,
    val averageScore: Int? = null,
    val contentRating: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    /** Canonical Banner URL; it is never copied into the legacy Backdrop field. */
    val bannerUrl: String? = null,
    val coverImageField: String = "coverImage",
    val coverVariant: String? = null,
    val backdropImageField: String = "backdropImage",
    val backdropVariant: String? = null,
    val bannerImageField: String = "bannerImage",
    val bannerVariant: String? = null,
    /** Raw provider fields lacking enough evidence for a canonical artwork type. */
    val unclassifiedArtworkFields: List<ReiAnixCatalogArtworkSourceField> = emptyList(),
) {
    init {
        require(!source.resolvesIdentifiers)
        require(title.isNotBlank())
        require(releaseYear == null || releaseYear in 1900..2200)
        require(seasonNumber == null || seasonNumber > 0)
        require(episodes == null || episodes >= 0)
        require(durationMinutes == null || durationMinutes > 0)
        require(averageScore == null || averageScore in 0..100)
        coverUrl?.let { ReiAnixCatalogArtworkCandidate(source, ids.idFor(source).orEmpty(),
            ReiAnixCatalogArtworkType.POSTER, it, coverImageField, coverVariant) }
        backdropUrl?.let { ReiAnixCatalogArtworkCandidate(source, ids.idFor(source).orEmpty(),
            ReiAnixCatalogArtworkType.BACKDROP, it, backdropImageField, backdropVariant) }
        bannerUrl?.let { ReiAnixCatalogArtworkCandidate(source, ids.idFor(source).orEmpty(),
            ReiAnixCatalogArtworkType.BANNER, it, bannerImageField, bannerVariant) }
    }

    fun artworkCandidates(): List<ReiAnixCatalogArtworkCandidate> = buildList {
        val externalId = ids.idFor(source) ?: return@buildList
        coverUrl?.let { add(ReiAnixCatalogArtworkCandidate(source, externalId,
            ReiAnixCatalogArtworkType.POSTER, it, coverImageField, coverVariant)) }
        backdropUrl?.let { add(ReiAnixCatalogArtworkCandidate(source, externalId,
            ReiAnixCatalogArtworkType.BACKDROP, it, backdropImageField, backdropVariant)) }
        bannerUrl?.let { add(ReiAnixCatalogArtworkCandidate(source, externalId,
            ReiAnixCatalogArtworkType.BANNER, it, bannerImageField, bannerVariant)) }
    }
}

sealed interface ReiAnixCatalogError {
    data class Http(val statusCode: Int) : ReiAnixCatalogError
    data class RateLimited(val retryAfterMillis: Long?) : ReiAnixCatalogError
    data class Server(val statusCode: Int) : ReiAnixCatalogError
    data class Network(val description: String) : ReiAnixCatalogError
    data class InvalidRequest(val description: String) : ReiAnixCatalogError
    data class InvalidResponse(val description: String) : ReiAnixCatalogError
    data object TimedOut : ReiAnixCatalogError
    data object Unavailable : ReiAnixCatalogError
}

sealed interface ReiAnixCatalogResult<out T> {
    data class Success<T>(
        val value: T,
        val fetchedAtMillis: Long = System.currentTimeMillis(),
        val expiresAtMillis: Long? = null,
    ) : ReiAnixCatalogResult<T> {
        init {
            require(fetchedAtMillis >= 0L)
            require(expiresAtMillis == null || expiresAtMillis >= fetchedAtMillis)
        }
    }
    data class Failure(val error: ReiAnixCatalogError) : ReiAnixCatalogResult<Nothing>
}

interface ReiAnixAnimeCatalogProvider {
    val source: ReiAnixCatalogSource
    suspend fun searchAnime(query: String, limit: Int = 10): ReiAnixCatalogResult<List<ReiAnixCatalogAnime>>
    suspend fun getAnime(externalId: String): ReiAnixCatalogResult<ReiAnixCatalogAnime?>
}

/**
 * AniMap crosswalk data. The metadata fields exist only to prove record identity;
 * they are never merged into the local anime or used as artwork.
 */
data class ReiAnixCatalogIdentifierMapping(
    val ids: ReiAnixExternalCatalogIds,
    val title: String,
    val alternativeTitles: Set<String> = emptySet(),
    val releaseYear: Int?,
    val format: String?,
    val mediaKind: ReiAnixCatalogMediaKind?,
    /** AniMap's calendar quarter (WINTER/SPRING/SUMMER/FALL), not a sequel number. */
    val season: String? = null,
    val episodes: Int? = null,
) {
    init {
        require(title.isNotBlank())
        require(releaseYear == null || releaseYear in 1900..2200)
        require(episodes == null || episodes >= 0)
    }
}

/** Created only after the AniMap record passes the same strict local identity gates. */
class ReiAnixCatalogIdMappingMatch internal constructor(
    val localAnimeId: Long,
    val externalIds: ReiAnixExternalCatalogIds,
    internal val localIdentityFingerprint: String,
    val matchedTitle: String,
) {
    init {
        require(localAnimeId > 0L)
        require(externalIds.hasAnyId)
        require(matchedTitle.isNotBlank())
    }
}

interface ReiAnixCatalogIdResolver {
    val source: ReiAnixCatalogSource
        get() = ReiAnixCatalogSource.ANIMAP
    suspend fun resolveIds(ids: ReiAnixExternalCatalogIds): ReiAnixCatalogResult<ReiAnixCatalogIdentifierMapping?>
}

/**
 * Registry applies one shared coordinator to all registered providers and to the AniMap resolver.
 * The coordinator is injected/owned here so screens cannot accidentally create independent limits.
 */
class ReiAnixCatalogProviderRegistry(
    providers: Collection<ReiAnixAnimeCatalogProvider>,
    private val requestCoordinator: ReiAnixExternalRequestCoordinator = ReiAnixExternalRequestCoordinator(),
) {
    private data class CachedResolverSuccess(
        val result: ReiAnixCatalogResult.Success<ReiAnixCatalogIdentifierMapping?>,
        val expiresAtMillis: Long,
    )

    private class CoordinatedProvider(
        private val delegate: ReiAnixAnimeCatalogProvider,
        private val coordinator: ReiAnixExternalRequestCoordinator,
    ) : ReiAnixAnimeCatalogProvider by delegate {
        private data class CachedSuccess(
            val result: ReiAnixCatalogResult.Success<*>,
            val expiresAtMillis: Long,
        )

        private val cache = java.util.concurrent.ConcurrentHashMap<String, CachedSuccess>()

        private suspend fun <T> executeCached(
            key: String,
            fallbackTtlMillis: Long,
            operation: suspend () -> ReiAnixCatalogResult<T>,
        ): ReiAnixCatalogResult<T> = coordinator.execute(delegate.source, key) {
            val now = System.currentTimeMillis()
            val cached = cache[key]
            if (cached != null && cached.expiresAtMillis > now) {
                @Suppress("UNCHECKED_CAST")
                cached.result as ReiAnixCatalogResult<T>
            } else {
                if (cached != null) cache.remove(key, cached)
                val result = operation()
                when (result) {
                    is ReiAnixCatalogResult.Success -> {
                        val expiry = result.expiresAtMillis ?: safeCatalogCacheExpiry(
                            result.fetchedAtMillis,
                            fallbackTtlMillis,
                        )
                        if (expiry > now) cache[key] = CachedSuccess(result, expiry)
                    }
                    is ReiAnixCatalogResult.Failure -> Unit
                }
                result
            }
        }

        override suspend fun searchAnime(
            query: String,
            limit: Int,
        ): ReiAnixCatalogResult<List<ReiAnixCatalogAnime>> {
            val normalized = query.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
            if (normalized.isEmpty()) return ReiAnixCatalogResult.Success(emptyList())
            val key = "search:$limit:$normalized"
            return executeCached(key, DEFAULT_SEARCH_CACHE_TTL_MILLIS) {
                delegate.searchAnime(query, limit)
            }
        }

        override suspend fun getAnime(externalId: String): ReiAnixCatalogResult<ReiAnixCatalogAnime?> {
            val id = externalId.trim()
            require(id.isNotEmpty()) { "External catalog ID cannot be blank." }
            return executeCached("id:$id", DEFAULT_DETAIL_CACHE_TTL_MILLIS) {
                delegate.getAnime(id)
            }
        }
    }

    private companion object {
        const val DEFAULT_SEARCH_CACHE_TTL_MILLIS = 30L * 60L * 1_000L
        const val DEFAULT_DETAIL_CACHE_TTL_MILLIS = 24L * 60L * 60L * 1_000L
        const val DEFAULT_RESOLVER_CACHE_TTL_MILLIS = 24L * 60L * 60L * 1_000L
        const val DEFAULT_RESOLVER_MISS_CACHE_TTL_MILLIS = 60L * 60L * 1_000L
        const val MAX_RESOLVER_CACHE_ENTRIES = 512
    }

    private val resolverCache = java.util.concurrent.ConcurrentHashMap<String, CachedResolverSuccess>()
    private val bySource: Map<ReiAnixCatalogSource, ReiAnixAnimeCatalogProvider>

    init {
        require(providers.all { it.source.providesMetadata && !it.source.resolvesIdentifiers }) {
            "Only metadata/artwork sources can be registered as catalog providers."
        }
        val grouped = providers.groupBy { it.source }
        require(grouped.values.all { it.size == 1 }) { "A catalog source can have only one active provider." }
        bySource = providers.associate { it.source to CoordinatedProvider(it, requestCoordinator) }
    }

    fun provider(source: ReiAnixCatalogSource): ReiAnixAnimeCatalogProvider? = bySource[source]
    fun registeredSources(): Set<ReiAnixCatalogSource> = bySource.keys

    fun coordinate(resolver: ReiAnixCatalogIdResolver): ReiAnixCatalogIdResolver {
        require(resolver.source == ReiAnixCatalogSource.ANIMAP && resolver.source.resolvesIdentifiers) {
            "The shared identifier resolver slot is reserved for AniMap."
        }
        return object : ReiAnixCatalogIdResolver {
            override val source = resolver.source
            override suspend fun resolveIds(ids: ReiAnixExternalCatalogIds): ReiAnixCatalogResult<ReiAnixCatalogIdentifierMapping?> {
                val canonical = buildString {
                    append(ids.aniListId ?: "").append('|').append(ids.myAnimeListId ?: "")
                    append('|').append(ids.kitsuId.orEmpty()).append('|').append(ids.aniDbId ?: "")
                    ids.additionalIds.toSortedMap().forEach { (catalog, id) ->
                        append('|').append(catalog).append('=').append(id)
                    }
                }
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val operationKey = "crosswalk:$digest"
                val now = System.currentTimeMillis()
                resolverCache[operationKey]?.let { cached ->
                    if (cached.expiresAtMillis > now) return cached.result
                    resolverCache.remove(operationKey, cached)
                }

                val result = requestCoordinator.execute(source, operationKey) { resolver.resolveIds(ids) }
                if (result is ReiAnixCatalogResult.Success) {
                    val fallbackTtl = if (result.value == null) {
                        DEFAULT_RESOLVER_MISS_CACHE_TTL_MILLIS
                    } else {
                        DEFAULT_RESOLVER_CACHE_TTL_MILLIS
                    }
                    val expiry = result.expiresAtMillis
                        ?: safeCatalogCacheExpiry(result.fetchedAtMillis, fallbackTtl)
                    if (expiry > System.currentTimeMillis()) {
                        resolverCache[operationKey] = CachedResolverSuccess(result, expiry)
                        trimResolverCache()
                    }
                }
                return result
            }
        }
    }

    private fun trimResolverCache() {
        val now = System.currentTimeMillis()
        resolverCache.entries.toList().forEach { (key, value) ->
            if (value.expiresAtMillis <= now) resolverCache.remove(key, value)
        }
        val excess = resolverCache.size - MAX_RESOLVER_CACHE_ENTRIES
        if (excess > 0) {
            resolverCache.entries.sortedBy { it.value.expiresAtMillis }
                .take(excess)
                .forEach { (key, value) -> resolverCache.remove(key, value) }
        }
    }

    fun cancelPendingRequests() = requestCoordinator.cancelAll()
}

private fun safeCatalogCacheExpiry(fetchedAtMillis: Long, ttlMillis: Long): Long =
    if (ttlMillis > 0L && fetchedAtMillis > Long.MAX_VALUE - ttlMillis) {
        Long.MAX_VALUE
    } else {
        fetchedAtMillis + ttlMillis
    }

fun reiAnixCatalogHttpError(
    statusCode: Int,
    retryAfterHeader: String? = null,
    nowMillis: Long = System.currentTimeMillis(),
): ReiAnixCatalogError = when {
    statusCode == 429 -> ReiAnixCatalogError.RateLimited(parseCatalogRetryAfterMillis(retryAfterHeader, nowMillis))
    statusCode in 500..599 -> ReiAnixCatalogError.Server(statusCode)
    else -> ReiAnixCatalogError.Http(statusCode)
}

fun parseCatalogRetryAfterMillis(value: String?, nowMillis: Long): Long? {
    val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    raw.toLongOrNull()?.let { seconds ->
        if (seconds < 0L || seconds > Long.MAX_VALUE / 1_000L) return null
        return seconds * 1_000L
    }
    val formatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("GMT")
    }
    val deadline = runCatching { formatter.parse(raw)?.time }.getOrNull() ?: return null
    return (deadline.toDouble() - nowMillis.toDouble())
        .coerceAtLeast(0.0)
        .coerceAtMost(Long.MAX_VALUE.toDouble())
        .toLong()
}

fun reiAnixCatalogErrorFor(error: Throwable): ReiAnixCatalogError = when (error) {
    is TimeoutCancellationException -> ReiAnixCatalogError.TimedOut
    is CancellationException -> throw error
    is JSONException -> ReiAnixCatalogError.InvalidResponse(error.message.orEmpty())
    is IOException -> ReiAnixCatalogError.Network(error.message.orEmpty())
    is IllegalArgumentException -> ReiAnixCatalogError.InvalidRequest(error.message.orEmpty())
    else -> ReiAnixCatalogError.Network(error.message ?: error.javaClass.simpleName)
}
