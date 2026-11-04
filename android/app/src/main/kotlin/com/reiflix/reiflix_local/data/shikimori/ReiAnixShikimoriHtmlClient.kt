package com.reiflix.reiflix_local.data.shikimori

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

/** Read-only metadata used for title matching. Original catalog values are never normalized in-place. */
data class ReiAnixShikimoriAnimeIdentity(
    val title: String,
    val aliases: List<String> = emptyList(),
    val year: Int? = null,
    val season: String? = null,
    val mediaKind: String? = null,
)

sealed class ReiAnixShikimoriDiscoveryResult {
    data class Found(val pageUrl: String, val imageUrl: String, val shikimoriAnimeId: Long, val matchedTitle: String) : ReiAnixShikimoriDiscoveryResult()
    data class NotFound(val reason: String) : ReiAnixShikimoriDiscoveryResult()
    data class Ambiguous(val pageUrls: List<String>) : ReiAnixShikimoriDiscoveryResult()
    data class PageNotFound(val pageUrl: String) : ReiAnixShikimoriDiscoveryResult()
    data class InvalidHtml(val pageUrl: String) : ReiAnixShikimoriDiscoveryResult()
    data class NoCover(val pageUrl: String) : ReiAnixShikimoriDiscoveryResult()
    data class RejectedImageUrl(val pageUrl: String) : ReiAnixShikimoriDiscoveryResult()
    data class TitleMismatch(val pageUrl: String) : ReiAnixShikimoriDiscoveryResult()
    data class HttpFailure(val url: String, val statusCode: Int) : ReiAnixShikimoriDiscoveryResult()
    data class Blocked(val url: String, val reason: String) : ReiAnixShikimoriDiscoveryResult()
    data class NetworkFailure(val reason: String) : ReiAnixShikimoriDiscoveryResult()
}

internal data class ReiAnixPublicHttpResponse(
    val statusCode: Int,
    val url: String,
    val contentType: String?,
    val body: String,
    val location: String? = null,
)

internal fun interface ReiAnixPublicHttpTransport {
    /** Executes exactly one request; implementations must not follow redirects. */
    fun getOnce(url: String, maximumBytes: Int): ReiAnixPublicHttpResponse
}

/**
 * HTML-only Shikimori discovery. It uses only the site's robots-declared sitemap and public
 * anime detail pages. Search routes, query-string URLs and every Shikimori API route are rejected.
 */
class ReiAnixShikimoriHtmlClient internal constructor(
    private val transport: ReiAnixPublicHttpTransport = ReiAnixUrlConnectionTransport(),
    private val minimumRequestIntervalMs: Long = DEFAULT_MINIMUM_REQUEST_INTERVAL_MS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    init {
        require(minimumRequestIntervalMs >= 0L) { "Request interval cannot be negative." }
    }

    private data class RobotsRule(val allow: Boolean, val path: String, val regex: Regex, val specificity: Int)
    private data class RobotsRules(val rules: List<RobotsRule>, val sitemapIndexUrl: String) {
        fun allows(path: String): Boolean {
            // robots.txt rules are path-prefix matches unless they explicitly end in '$'.
            val matching = rules.filter { it.regex.containsMatchIn(path) }
            if (matching.isEmpty()) return true
            val mostSpecific = matching.maxOf { it.specificity }
            return matching.filter { it.specificity == mostSpecific }.any { it.allow }
        }
    }
    private data class PageLink(val url: String, val slugTitle: String)
    private data class RankedPage(val page: PageLink, val score: Double, val matchedTitle: String)
    private data class Timed<T>(val at: Long, val value: T)

    private val requestMutex = Mutex()
    private val robotsMutex = Mutex()
    private val sitemapMutex = Mutex()
    @Volatile private var lastRequestAtMs = 0L
    @Volatile private var robotsCache: Timed<RobotsRules>? = null
    @Volatile private var pageCache: Timed<List<PageLink>>? = null

    suspend fun discoverCover(identity: ReiAnixShikimoriAnimeIdentity): ReiAnixShikimoriDiscoveryResult {
        val titles = titleVariants(identity)
        if (titles.isEmpty()) return ReiAnixShikimoriDiscoveryResult.NotFound("EMPTY_LOCAL_TITLE")

        return try {
            val robots = loadRobots()
            if (!robots.allows(SITEMAP_INDEX_PATH)) {
                return ReiAnixShikimoriDiscoveryResult.Blocked(SITEMAP_INDEX_URL, "ROBOTS_DISALLOW_SITEMAP_INDEX")
            }
            val pages = loadAnimeSitemapPages(robots)
            if (pages.isEmpty()) return ReiAnixShikimoriDiscoveryResult.NotFound("EMPTY_PUBLIC_SITEMAP")

            val ranked = pages.mapNotNull { page ->
                val scored = titles.map { it to titleSimilarity(it, page.slugTitle) }.maxByOrNull { it.second }
                    ?: return@mapNotNull null
                if (scored.second < MINIMUM_SITEMAP_CANDIDATE_SCORE) return@mapNotNull null
                val pageYear = YEAR_PATTERN.find(page.slugTitle)?.value?.toIntOrNull()
                if (identity.year != null && pageYear != null && identity.year != pageYear) {
                    return@mapNotNull null
                }
                val yearAdjustment = if (identity.year != null && pageYear == identity.year) YEAR_MATCH_BONUS else 0.0
                RankedPage(page, (scored.second + yearAdjustment).coerceIn(0.0, 1.0), scored.first)
            }.sortedWith(compareByDescending<RankedPage> { it.score }.thenBy { it.page.url })

            val best = ranked.firstOrNull()
                ?: return ReiAnixShikimoriDiscoveryResult.NotFound("NO_TITLE_CANDIDATE")
            val second = ranked.getOrNull(1)
            if (best.score < MINIMUM_ACCEPTED_TITLE_SCORE ||
                (second != null && second.score >= MINIMUM_AMBIGUOUS_CANDIDATE_SCORE &&
                    best.score - second.score < MINIMUM_CANDIDATE_MARGIN)
            ) {
                val possible = ranked.take(MAX_AMBIGUOUS_CANDIDATES)
                    .filter { it.score >= MINIMUM_AMBIGUOUS_CANDIDATE_SCORE }
                    .map { it.page.url }
                return if (possible.isEmpty()) {
                    ReiAnixShikimoriDiscoveryResult.NotFound("TITLE_MATCH_BELOW_CONFIDENCE_THRESHOLD")
                } else ReiAnixShikimoriDiscoveryResult.Ambiguous(possible)
            }

            val pageUri = safePublicUri(best.page.url)
                ?: return ReiAnixShikimoriDiscoveryResult.Blocked(best.page.url, "INVALID_PUBLIC_PAGE_URL")
            if (!isAnimeDetailPath(pageUri.path) || !robots.allows(pageUri.path)) {
                return ReiAnixShikimoriDiscoveryResult.Blocked(best.page.url, "ROBOTS_OR_ROUTE_POLICY")
            }
            val response = requestFollowingAllowedRedirects(best.page.url, MAX_HTML_BYTES, robots)
            if (response.statusCode == HTTP_NOT_FOUND) {
                return ReiAnixShikimoriDiscoveryResult.PageNotFound(response.url)
            }
            if (response.statusCode !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
                return ReiAnixShikimoriDiscoveryResult.HttpFailure(response.url, response.statusCode)
            }
            if (!looksLikeHtml(response.body, response.contentType)) {
                return ReiAnixShikimoriDiscoveryResult.InvalidHtml(response.url)
            }

            val pageId = animeIdFromPageUrl(response.url)
                ?: return ReiAnixShikimoriDiscoveryResult.InvalidHtml(response.url)
            when (val parsed = parseAnimePage(response.body, response.url, pageId, identity)) {
                is ParsedPage.Found -> ReiAnixShikimoriDiscoveryResult.Found(
                    response.url, parsed.imageUrl, pageId, parsed.matchedTitle,
                )
                ParsedPage.InvalidHtml -> ReiAnixShikimoriDiscoveryResult.InvalidHtml(response.url)
                ParsedPage.NoCover -> ReiAnixShikimoriDiscoveryResult.NoCover(response.url)
                ParsedPage.RejectedImageUrl -> ReiAnixShikimoriDiscoveryResult.RejectedImageUrl(response.url)
                ParsedPage.TitleMismatch -> ReiAnixShikimoriDiscoveryResult.TitleMismatch(response.url)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (blocked: PublicAccessException) {
            ReiAnixShikimoriDiscoveryResult.Blocked(blocked.url, blocked.message ?: "PUBLIC_ACCESS_BLOCKED")
        } catch (error: IOException) {
            ReiAnixShikimoriDiscoveryResult.NetworkFailure(error.message ?: "Public HTML request failed")
        } catch (error: Exception) {
            ReiAnixShikimoriDiscoveryResult.NetworkFailure(error.message ?: "Shikimori discovery failed")
        }
    }

    private suspend fun loadRobots(): RobotsRules = robotsMutex.withLock {
        val now = nowMillis()
        robotsCache?.takeIf { now - it.at < ROBOTS_CACHE_TTL_MS }?.let { return it.value }
        val response = requestOnce(ROBOTS_URL, MAX_ROBOTS_BYTES)
        if (response.statusCode !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
            throw IOException("Shikimori robots.txt returned HTTP " + response.statusCode)
        }
        val parsed = parseRobots(response.body)
        val uri = safePublicUri(parsed.sitemapIndexUrl)
        if (uri == null || uri.path != SITEMAP_INDEX_PATH || !parsed.allows(uri.path)) {
            throw PublicAccessException(ROBOTS_URL, "NO_ALLOWED_ROBOTS_DECLARED_SITEMAP_INDEX")
        }
        robotsCache = Timed(nowMillis(), parsed)
        parsed
    }

    private suspend fun loadAnimeSitemapPages(robots: RobotsRules): List<PageLink> = sitemapMutex.withLock {
        val now = nowMillis()
        pageCache?.takeIf { now - it.at < SITEMAP_CACHE_TTL_MS }?.let { return it.value }
        val index = requestFollowingAllowedRedirects(robots.sitemapIndexUrl, MAX_SITEMAP_BYTES, robots)
        if (index.statusCode !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX ||
            (!index.body.contains("<sitemapindex", ignoreCase = true) && !index.body.contains("<urlset", ignoreCase = true))
        ) throw IOException("Shikimori sitemap was unavailable or invalid")

        if (index.body.contains("<urlset", ignoreCase = true)) {
            val directPages = LinkedHashMap<String, PageLink>()
            XML_LOC_PATTERN.findAll(index.body).forEach { match ->
                val page = safePublicUri(decodeEntities(match.groupValues[1].trim())) ?: return@forEach
                if (!isAnimeDetailPath(page.path) || page.rawQuery != null || page.rawFragment != null || !robots.allows(page.path)) return@forEach
                val id = animeIdFromPageUrl(page.toASCIIString()) ?: return@forEach
                val slug = page.path.substringAfterLast('/').replaceFirst(Regex("^(?:z)?\\d+-", RegexOption.IGNORE_CASE), "")
                if (slug.isNotBlank()) directPages.putIfAbsent(page.toASCIIString(), PageLink(page.toASCIIString(), slug.replace('-', ' ')))
            }
            return@withLock directPages.values.toList().also { pageCache = Timed(nowMillis(), it) }
        }

        val shards = XML_LOC_PATTERN.findAll(index.body)
            .map { decodeEntities(it.groupValues[1].trim()) }
            .mapNotNull(::safePublicUri)
            .filter { isAnimeSitemapPath(it.path) && it.rawQuery == null && it.rawFragment == null }
            .filter { robots.allows(it.path) }
            .distinctBy { it.toASCIIString() }
            .sortedBy { shardNumber(it.path) }
            .toList()
        if (shards.isEmpty()) throw PublicAccessException(robots.sitemapIndexUrl, "NO_ROBOTS_ALLOWED_ANIME_SITEMAPS")

        val discovered = LinkedHashMap<String, PageLink>()
        for (shard in shards) {
            val response = requestFollowingAllowedRedirects(shard.toASCIIString(), MAX_SITEMAP_BYTES, robots)
            if (response.statusCode !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX ||
                !response.body.contains("<urlset", ignoreCase = true)
            ) throw IOException("A public Shikimori anime sitemap shard was unavailable or invalid")

            XML_LOC_PATTERN.findAll(response.body).forEach { match ->
                val page = safePublicUri(decodeEntities(match.groupValues[1].trim())) ?: return@forEach
                if (!isAnimeDetailPath(page.path) || page.rawQuery != null || page.rawFragment != null ||
                    !robots.allows(page.path)
                ) return@forEach
                val id = animeIdFromPageUrl(page.toASCIIString()) ?: return@forEach
                val slug = page.path.substringAfterLast('/').substringAfter(id.toString() + "-")
                if (slug.isBlank()) return@forEach
                discovered.putIfAbsent(page.toASCIIString(), PageLink(page.toASCIIString(), slug.replace('-', ' ')))
            }
        }
        discovered.values.toList().also { pageCache = Timed(nowMillis(), it) }
    }

    private suspend fun requestFollowingAllowedRedirects(
        initialUrl: String,
        maximumBytes: Int,
        robots: RobotsRules,
    ): ReiAnixPublicHttpResponse {
        var currentUrl = initialUrl
        for (redirectCount in 0..MAX_REDIRECTS) {
            val uri = safePublicUri(currentUrl)
                ?: throw PublicAccessException(currentUrl, "UNTRUSTED_PUBLIC_URL")
            if (!isAllowedTextPath(uri.path) || !robots.allows(uri.path)) {
                throw PublicAccessException(currentUrl, "ROBOTS_OR_ROUTE_POLICY")
            }
            val response = requestOnce(currentUrl, maximumBytes)
            if (response.statusCode !in REDIRECT_CODES) return response
            val location = response.location ?: return response
            if (redirectCount == MAX_REDIRECTS) {
                throw PublicAccessException(currentUrl, "REDIRECT_LIMIT")
            }
            val next = runCatching { URI(currentUrl).resolve(location).normalize().toASCIIString() }.getOrNull()
                ?: throw PublicAccessException(currentUrl, "INVALID_REDIRECT_DESTINATION")
            val nextUri = safePublicUri(next)
                ?: throw PublicAccessException(next, "CROSS_HOST_OR_UNSAFE_REDIRECT_BLOCKED")
            if (!isAllowedTextPath(nextUri.path) || !robots.allows(nextUri.path)) {
                throw PublicAccessException(next, "REDIRECT_DESTINATION_NOT_ALLOWED")
            }
            currentUrl = next
        }
        throw PublicAccessException(currentUrl, "REDIRECT_LIMIT")
    }

    private suspend fun requestOnce(url: String, maximumBytes: Int): ReiAnixPublicHttpResponse =
        requestMutex.withLock {
            val wait = (lastRequestAtMs + minimumRequestIntervalMs - nowMillis()).coerceAtLeast(0L)
            if (wait > 0) delay(wait)
            try {
                withContext(Dispatchers.IO) { transport.getOnce(url, maximumBytes) }
            } finally {
                lastRequestAtMs = nowMillis()
            }
        }

    private fun parseRobots(body: String): RobotsRules {
        val rules = ArrayList<RobotsRule>()
        var wildcardGroup = false
        var groupHasRules = false
        var sitemapUrl: String? = null
        body.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            val value = line.substring(colon + 1).trim()
            when (name) {
                "user-agent" -> {
                    wildcardGroup = if (groupHasRules) value == "*" else wildcardGroup || value == "*"
                    groupHasRules = false
                }
                "disallow", "allow" -> if (wildcardGroup && value.isNotEmpty()) {
                    rules += makeRobotsRule(name == "allow", value)
                    groupHasRules = true
                }
                "sitemap" -> {
                    val uri = runCatching { URI(value) }.getOrNull()
                    if (sitemapUrl == null && uri != null &&
                        uri.scheme.equals("https", true) && uri.host.equals(SHIKIMORI_HOST, true) &&
                        uri.path == SITEMAP_INDEX_PATH && uri.rawUserInfo == null && uri.rawQuery == null
                    ) sitemapUrl = uri.toASCIIString()
                }
            }
        }
        return RobotsRules(rules, sitemapUrl ?: throw PublicAccessException(ROBOTS_URL, "ROBOTS_HAS_NO_ALLOWED_SITEMAP_INDEX"))
    }

    private fun makeRobotsRule(allow: Boolean, value: String): RobotsRule {
        val endsAtPathEnd = value.endsWith('$')
        val pattern = value.removeSuffix("$")
        val expression = buildString {
            append('^')
            pattern.forEach { ch -> if (ch == '*') append(".*") else append(Regex.escape(ch.toString())) }
            if (endsAtPathEnd) append('$')
        }
        return RobotsRule(allow, pattern, Regex(expression), pattern.count { it != '*' && it != '$' })
    }

    private fun parseAnimePage(
        html: String,
        pageUrl: String,
        pageId: Long,
        identity: ReiAnixShikimoriAnimeIdentity,
    ): ParsedPage {
        val h1 = H1_PATTERN.find(html) ?: return ParsedPage.InvalidHtml
        val heading = decodeEntities(HTML_TAG_PATTERN.replace(h1.groupValues[1], " "))
            .replace(WHITESPACE_PATTERN, " ").trim()
        if (heading.isBlank()) return ParsedPage.InvalidHtml
        val pageYear = pageReleaseYear(html)
        if (identity.year != null && pageYear != null && identity.year != pageYear) {
            return ParsedPage.TitleMismatch
        }

        val headingVariants = (listOf(heading) + heading.split(Regex("[/|]")))
            .map(::normalizeShikimoriTitle).filter(String::isNotBlank)
        val titleVariants = titleVariants(identity)
        val matched = titleVariants.mapNotNull { local ->
            val best = headingVariants.maxByOrNull { titleSimilarity(local, it) } ?: return@mapNotNull null
            Triple(local, titleSimilarity(local, best), best)
        }.maxByOrNull { it.second } ?: return ParsedPage.TitleMismatch
        if (matched.second < MINIMUM_PAGE_HEADING_SCORE) return ParsedPage.TitleMismatch

        val extracted = extractPosterUrls(html, pageUrl, pageId)
        if (extracted.first.isEmpty()) return if (extracted.second) ParsedPage.RejectedImageUrl else ParsedPage.NoCover
        val image = extracted.first.first()
        if (!validShikimoriCoverImageUrl(image, pageId)) return ParsedPage.RejectedImageUrl
        return ParsedPage.Found(image, matched.first)
    }

    private fun pageReleaseYear(html: String): Int? {
        val metadataYear = META_TAG_PATTERN.findAll(html).mapNotNull { match ->
            val attrs = parseHtmlAttributes(match.value)
            val key = (attrs["property"] ?: attrs["name"] ?: "").lowercase(Locale.ROOT)
            if (key != "video:release_date") return@mapNotNull null
            attrs["content"]?.let(YEAR_PATTERN::find)?.value?.toIntOrNull()
        }.firstOrNull()
        if (metadataYear != null) return metadataYear

        // Current public pages expose the airing period in the anime "Status" line
        // (for example, original-title="С 3 октября 2002 г. по 8 февраля 2007 г.").
        val statusValue = ANIME_STATUS_LINE_PATTERN.find(html)?.groupValues?.get(1).orEmpty()
        return YEAR_PATTERN.find(statusValue)?.value?.toIntOrNull()
    }

    private fun extractPosterUrls(html: String, pageUrl: String, pageId: Long): Pair<List<String>, Boolean> {
        val region = findPosterRegion(html, pageId)
        val rawCandidates = ArrayList<String>()
        if (region != null) {
            SOURCE_TAG_PATTERN.findAll(region).forEach { match ->
                val attrs = parseHtmlAttributes(match.value)
                attrs["srcset"]?.let { rawCandidates.addAll(parseSrcSet(it)) }
                attrs["src"]?.let(rawCandidates::add)
            }
            IMAGE_TAG_PATTERN.findAll(region).forEach { match ->
                val attrs = parseHtmlAttributes(match.value)
                listOf("src", "data-src", "data-original").forEach { key -> attrs[key]?.let(rawCandidates::add) }
                listOf("srcset", "data-srcset").forEach { key -> attrs[key]?.let { rawCandidates.addAll(parseSrcSet(it)) } }
            }
            DIV_TAG_PATTERN.findAll(region).forEach { match ->
                parseHtmlAttributes(match.value)["data-href"]?.let(rawCandidates::add)
            }
        }
        META_TAG_PATTERN.findAll(html).forEach { match ->
            val attrs = parseHtmlAttributes(match.value)
            val key = (attrs["property"] ?: attrs["name"] ?: "").lowercase(Locale.ROOT)
            if (key == "og:image" || key == "og:image:secure_url" || key == "twitter:image") {
                attrs["content"]?.let(rawCandidates::add)
            }
        }

        val accepted = rawCandidates.mapNotNull { raw ->
            resolveImageUrl(pageUrl, raw)?.takeIf { validShikimoriCoverImageUrl(it, pageId) }
        }.distinct()
        return accepted to rawCandidates.any(String::isNotBlank)
    }

    private fun findPosterRegion(html: String, pageId: Long): String? {
        val divs = DIV_TAG_PATTERN.findAll(html).toList()
        // On current Shikimori pages, .c-poster is an empty layout marker. The actual
        // poster lives in the following .b-db_entry-poster container with data-href
        // and responsive <picture>/<source>/<img> attributes.
        val posterOpening = divs.firstOrNull { match ->
            if (match.groupValues[1].isNotEmpty()) return@firstOrNull false
            val attrs = parseHtmlAttributes(match.value)
            attrs["class"].orEmpty().split(WHITESPACE_PATTERN).any { it == "b-db_entry-poster" } &&
                validShikimoriCoverImageUrl(attrs["data-href"], pageId)
        }
        if (posterOpening != null) {
            val start = posterOpening.range.last + 1
            var depth = 1
            DIV_TAG_PATTERN.findAll(html, start).forEach { match ->
                if (match.groupValues[1].isNotEmpty()) {
                    depth--
                    if (depth == 0) return html.substring(posterOpening.range.first, match.range.last + 1)
                } else if (!match.value.dropLast(1).trimEnd().endsWith("/")) depth++
            }
        }

        // Keep support for layouts that nest the poster directly inside .c-poster.
        val opening = divs.firstOrNull { match ->
            if (match.groupValues[1].isNotEmpty()) return@firstOrNull false
            parseHtmlAttributes(match.value)["class"].orEmpty()
                .split(WHITESPACE_PATTERN).any { it == "c-poster" }
        } ?: return null
        val start = opening.range.last + 1
        var depth = 1
        DIV_TAG_PATTERN.findAll(html, start).forEach { match ->
            if (match.groupValues[1].isNotEmpty()) {
                depth--
                if (depth == 0) return html.substring(start, match.range.first)
            } else if (!match.value.dropLast(1).trimEnd().endsWith("/")) depth++
        }
        return null
    }

    private fun parseSrcSet(value: String): List<String> =
        value.split(',').mapNotNull { item ->
            val parts = item.trim().split(WHITESPACE_PATTERN).filter(String::isNotBlank)
            parts.firstOrNull()?.let { url ->
                val density = parts.getOrNull(1)?.removeSuffix("x")?.toDoubleOrNull() ?: 1.0
                url to density
            }
        }.sortedByDescending { it.second }.map { it.first }

    private fun resolveImageUrl(pageUrl: String, raw: String): String? {
        val decoded = decodeEntities(raw.trim())
        if (decoded.isEmpty() || decoded.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = runCatching { URI(pageUrl).resolve(decoded).normalize() }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || !uri.host.equals(SHIKIMORI_HOST, true) ||
            uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443) || uri.rawFragment != null
        ) return null
        return uri.toASCIIString()
    }

    private fun safePublicUri(rawUrl: String): URI? {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || !uri.host.equals(SHIKIMORI_HOST, true) ||
            uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443) || uri.rawFragment != null ||
            uri.rawQuery != null || !isAllowedTextPath(uri.path)
        ) return null
        return uri
    }

    private fun isAllowedTextPath(path: String): Boolean =
        path == "/robots.txt" || path == SITEMAP_INDEX_PATH ||
            isAnimeSitemapPath(path) || path.matches(Regex("^/sitemaps/[^/]+\\.xml$", RegexOption.IGNORE_CASE)) || isAnimeDetailPath(path)

    private fun isAnimeSitemapPath(path: String): Boolean =
        ANIME_SITEMAP_SHARD.matches(path)

    private fun isAnimeDetailPath(path: String): Boolean =
        ANIME_DETAIL_PATH.matches(path)

    private fun animeIdFromPageUrl(value: String): Long? {
        val uri = safePublicUri(value) ?: return null
        return ANIME_ID.matchEntire(uri.path.substringAfterLast('/'))?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun shardNumber(path: String): Int =
        ANIME_SITEMAP_SHARD.matchEntire(path)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun titleVariants(identity: ReiAnixShikimoriAnimeIdentity): List<String> =
        (listOf(identity.title) + identity.aliases)
            .asSequence().map(::normalizeShikimoriTitle).filter { it.length >= 2 }
            .distinct().take(MAX_TITLE_VARIANTS).toList()

    private fun titleSimilarity(left: String, right: String): Double {
        val a = normalizeShikimoriTitle(left)
        val b = normalizeShikimoriTitle(right)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val aTokens = a.split(WHITESPACE_PATTERN).filter(String::isNotBlank).toSet()
        val bTokens = b.split(WHITESPACE_PATTERN).filter(String::isNotBlank).toSet()
        if (aTokens.isEmpty() || bTokens.isEmpty()) return 0.0
        val dice = 2.0 * (aTokens intersect bTokens).size / (aTokens.size + bTokens.size)
        val prefix = if (a.startsWith(b) || b.startsWith(a)) {
            0.90 * minOf(a.length, b.length).toDouble() / maxOf(a.length, b.length)
        } else 0.0
        return maxOf(dice, prefix)
    }

    private fun looksLikeHtml(body: String, contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return (type.contains("html") || body.contains("<html", true)) && body.contains("<h1", true)
    }

    private fun decodeEntities(value: String): String = HTML_ENTITY.replace(value) { match ->
        val entity = match.groupValues[1]
        when {
            entity.equals("amp", true) -> "&"
            entity.equals("quot", true) -> "\""
            entity.equals("apos", true) -> "'"
            entity.equals("lt", true) -> "<"
            entity.equals("gt", true) -> ">"
            entity.equals("nbsp", true) -> " "
            entity.startsWith("#x", true) -> entity.drop(2).toIntOrNull(16)?.takeIf(Character::isValidCodePoint)
                ?.let { String(Character.toChars(it)) } ?: match.value
            entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.takeIf(Character::isValidCodePoint)
                ?.let { String(Character.toChars(it)) } ?: match.value
            else -> match.value
        }
    }

    private sealed class ParsedPage {
        data class Found(val imageUrl: String, val matchedTitle: String) : ParsedPage()
        object InvalidHtml : ParsedPage()
        object NoCover : ParsedPage()
        object RejectedImageUrl : ParsedPage()
        object TitleMismatch : ParsedPage()
    }

    private class PublicAccessException(val url: String, message: String) : IOException(message)

    companion object {
        const val SHIKIMORI_HOST = "shikimori.io"
        const val ROBOTS_URL = "https://shikimori.io/robots.txt"
        const val SITEMAP_INDEX_URL = "https://shikimori.io/sitemaps/index.xml"
        const val SITEMAP_INDEX_PATH = "/sitemaps/index.xml"
        const val DEFAULT_MINIMUM_REQUEST_INTERVAL_MS = 1_000L
        const val ROBOTS_CACHE_TTL_MS = 6L * 60L * 60L * 1_000L
        const val SITEMAP_CACHE_TTL_MS = 12L * 60L * 60L * 1_000L
        const val MAX_ROBOTS_BYTES = 128_000
        const val MAX_SITEMAP_BYTES = 1_500_000
        const val MAX_HTML_BYTES = 2_000_000
        const val MAX_TITLE_VARIANTS = 16
        const val MAX_AMBIGUOUS_CANDIDATES = 3
        const val MAX_REDIRECTS = 3
        const val HTTP_NOT_FOUND = 404
        const val HTTP_SUCCESS_MIN = 200
        const val HTTP_SUCCESS_MAX = 299
        const val MINIMUM_SITEMAP_CANDIDATE_SCORE = 0.52
        const val MINIMUM_ACCEPTED_TITLE_SCORE = 0.90
        const val MINIMUM_AMBIGUOUS_CANDIDATE_SCORE = 0.70
        const val MINIMUM_CANDIDATE_MARGIN = 0.12
        const val MINIMUM_PAGE_HEADING_SCORE = 0.78
        const val YEAR_MATCH_BONUS = 0.05
        const val YEAR_MISMATCH_PENALTY = 0.10

        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val YEAR_PATTERN = Regex("\\b(?:19|20)\\d{2}\\b")
        private val ANIME_ID = Regex("^(?:z)?(\\d+)-.+$", RegexOption.IGNORE_CASE)
        private val ANIME_SITEMAP_SHARD = Regex("^/sitemaps/animes(?:-(\\d+))?\\.xml$")
        private val ANIME_DETAIL_PATH = Regex("^/animes/(?:z)?\\d+-[\\p{L}\\p{N}._~!%'-]+$", RegexOption.IGNORE_CASE)
        private val XML_LOC_PATTERN = Regex("(?is)<loc\\b[^>]*>\\s*(.*?)\\s*</loc>")
        private val H1_PATTERN = Regex("(?is)<h1\\b[^>]*>(.*?)</h1>")
        private val ANIME_STATUS_LINE_PATTERN = Regex(
            """(?is)<div\b[^>]*class=["'][^"']*\bkey\b[^"']*["'][^>]*>\s*(?:Статус|Status)\s*:?\s*</div>\s*<div\b[^>]*class=["'][^"']*\bvalue\b[^"']*["'][^>]*>(.*?)</div>""",
        )
        private val HTML_TAG_PATTERN = Regex("(?is)<[^>]+>")
        private val META_TAG_PATTERN = Regex("(?is)<meta\\b[^>]*>")
        private val IMAGE_TAG_PATTERN = Regex("(?is)<img\\b[^>]*>")
        private val SOURCE_TAG_PATTERN = Regex("(?is)<source\\b[^>]*>")
        private val DIV_TAG_PATTERN = Regex("(?is)<\\s*(/?)\\s*div\\b[^>]*>")
        private val HTML_ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|quot|apos|lt|gt|nbsp);", RegexOption.IGNORE_CASE)
        private val WHITESPACE_PATTERN = Regex("\\s+")
    }
}

internal fun normalizeShikimoriTitle(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKD)
        .filterNot {
            Character.getType(it) == Character.NON_SPACING_MARK.toInt() ||
                Character.getType(it) == Character.COMBINING_SPACING_MARK.toInt() ||
                Character.getType(it) == Character.ENCLOSING_MARK.toInt()
        }
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\p{P}\\p{S}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

internal fun validShikimoriCoverImageUrl(value: String?, expectedAnimeId: Long? = null): Boolean {
    val candidate = value?.trim()?.takeIf(String::isNotEmpty) ?: return false
    if (candidate.any { it.isWhitespace() || it.isISOControl() }) return false
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", true) ||
        !uri.host.equals(ReiAnixShikimoriHtmlClient.SHIKIMORI_HOST, true) ||
        uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443) || uri.rawFragment != null
    ) return false
    val path = Regex("^/system/animes/original/(\\d+)\\.(?:jpe?g|png|webp|gif)$", RegexOption.IGNORE_CASE).matchEntire(uri.path)
        ?: Regex("^/uploads/poster/animes/(\\d+)/([^/]+)$").matchEntire(uri.path) ?: return false
    val id = path.groupValues[1].toLongOrNull() ?: return false
    if (expectedAnimeId != null && id != expectedAnimeId) return false
    if (uri.rawQuery != null && !Regex("^[A-Za-z0-9_=&%-]+$").matches(uri.rawQuery)) return false
    val name = path.groupValues.last()
    return name.isNotBlank() && name != "." && name != ".."
}

private fun parseHtmlAttributes(tag: String): Map<String, String> {
    val attrs = LinkedHashMap<String, String>()
    HTML_ATTRIBUTE_PATTERN.findAll(tag).forEach { match ->
        val name = match.groupValues[1].lowercase(Locale.ROOT)
        val value = match.groupValues[2].ifEmpty { match.groupValues[3].ifEmpty { match.groupValues[4] } }
        attrs[name] = value
    }
    return attrs
}

private val HTML_ATTRIBUTE_PATTERN = Regex("""([A-Za-z_:][A-Za-z0-9_:.-]*)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>]+)))?""")

private class ReiAnixUrlConnectionTransport : ReiAnixPublicHttpTransport {
    override fun getOnce(url: String, maximumBytes: Int): ReiAnixPublicHttpResponse {
        val connection = (URL(url).openConnection() as? HttpURLConnection)
            ?: throw IOException("Public Shikimori URL did not produce an HTTP connection")
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "ReiAnix public HTML cover discovery")
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8")
            connection.connect()
            val status = connection.responseCode
            val location = connection.getHeaderField("Location")
            if (status in 300..399) return ReiAnixPublicHttpResponse(status, url, connection.contentType, "", location)
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = if (stream == null) "" else stream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maximumBytes) throw IOException("Shikimori response exceeded configured size limit")
                    output.write(buffer, 0, count)
                }
                String(output.toByteArray(), StandardCharsets.UTF_8)
            }
            return ReiAnixPublicHttpResponse(status, url, connection.contentType, body, location)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 12_000
    }
}
