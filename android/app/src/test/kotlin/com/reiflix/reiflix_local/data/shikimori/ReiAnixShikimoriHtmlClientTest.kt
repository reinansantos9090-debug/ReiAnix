package com.reiflix.reiflix_local.data.shikimori

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixShikimoriHtmlClientTest {
    @Test
    fun normalizesAccentsAndPunctuationWithoutChangingSemanticWords() {
        assertEquals(normalizeShikimoriTitle("  Café: Re:Zero! "), normalizeShikimoriTitle("cafe re zero"))
        assertTrue(normalizeShikimoriTitle("Naruto: Shippuden") != normalizeShikimoriTitle("Naruto"))
    }

    @Test
    fun acceptsOnlyHttpsShikimoriOriginalPosterForMatchingAnimeId() {
        assertTrue(validShikimoriCoverImageUrl("https://shikimori.io/system/animes/original/20.jpg?1711965679", 20L))
        assertFalse(validShikimoriCoverImageUrl("http://shikimori.io/system/animes/original/20.jpg", 20L))
        assertFalse(validShikimoriCoverImageUrl("https://example.com/system/animes/original/20.jpg", 20L))
        assertFalse(validShikimoriCoverImageUrl("https://shikimori.io/system/animes/original/21.jpg", 20L))
        assertFalse(validShikimoriCoverImageUrl("https://shikimori.io/api/animes/20", 20L))
        assertTrue(validShikimoriCoverImageUrl("https://shikimori.io/uploads/poster/animes/20/main_2x-cover.webp", 20L))
    }

    @Test
    fun discoversMainPosterFromAllowedPublicHtmlAndRobotsDeclaredSitemap() = runBlocking {
        val transport = FakeTransport(
            mapOf(
                ReiAnixShikimoriHtmlClient.ROBOTS_URL to ReiAnixPublicHttpResponse(
                    200, ReiAnixShikimoriHtmlClient.ROBOTS_URL, "text/plain",
                    "User-agent: *\nAllow: /\nSitemap: ${ReiAnixShikimoriHtmlClient.SITEMAP_INDEX_URL}\n",
                ),
                ReiAnixShikimoriHtmlClient.SITEMAP_INDEX_URL to ReiAnixPublicHttpResponse(
                    200, ReiAnixShikimoriHtmlClient.SITEMAP_INDEX_URL, "application/xml",
                    "<sitemapindex><sitemap><loc>https://shikimori.io/sitemaps/animes.xml</loc></sitemap></sitemapindex>",
                ),
                "https://shikimori.io/sitemaps/animes.xml" to ReiAnixPublicHttpResponse(
                    200, "https://shikimori.io/sitemaps/animes.xml", "application/xml",
                    "<urlset><url><loc>https://shikimori.io/animes/z20-naruto</loc></url></urlset>",
                ),
                "https://shikimori.io/animes/z20-naruto" to ReiAnixPublicHttpResponse(
                    200, "https://shikimori.io/animes/z20-naruto", "text/html",
                    "<html><h1>Naruto</h1><div class=\"c-poster\"><a><img src=\"/system/animes/original/20.jpg?1711965679\"></a></div></html>",
                ),
            ),
        )
        val client = ReiAnixShikimoriHtmlClient(transport, minimumRequestIntervalMs = 0)
        val result = client.discoverCover(ReiAnixShikimoriAnimeIdentity("Naruto", year = 2002))
        assertTrue("Expected a reliable poster match, got $result", result is ReiAnixShikimoriDiscoveryResult.Found)
        result as ReiAnixShikimoriDiscoveryResult.Found
        assertEquals(20L, result.shikimoriAnimeId)
        assertEquals("https://shikimori.io/system/animes/original/20.jpg?1711965679", result.imageUrl)
    }

    @Test
    fun rejectsSimilarButDifferentPageTitleInsteadOfAssociatingItsPoster() = runBlocking {
        val transport = FakeTransport(
            mapOf(
                ReiAnixShikimoriHtmlClient.ROBOTS_URL to ReiAnixPublicHttpResponse(
                    200, ReiAnixShikimoriHtmlClient.ROBOTS_URL, "text/plain",
                    "User-agent: *\nAllow: /\nSitemap: ${ReiAnixShikimoriHtmlClient.SITEMAP_INDEX_URL}\n",
                ),
                ReiAnixShikimoriHtmlClient.SITEMAP_INDEX_URL to ReiAnixPublicHttpResponse(
                    200, ReiAnixShikimoriHtmlClient.SITEMAP_INDEX_URL, "application/xml",
                    "<urlset><url><loc>https://shikimori.io/animes/z20-naruto-shippuden</loc></url></urlset>",
                ),
                "https://shikimori.io/animes/z20-naruto-shippuden" to ReiAnixPublicHttpResponse(
                    200, "https://shikimori.io/animes/z20-naruto-shippuden", "text/html",
                    "<html><h1>Naruto: Shippuden</h1><div class=\"c-poster\"><img src=\"/system/animes/original/20.jpg\"></div></html>",
                ),
            ),
        )
        val client = ReiAnixShikimoriHtmlClient(transport, minimumRequestIntervalMs = 0)
        val result = client.discoverCover(ReiAnixShikimoriAnimeIdentity("Naruto"))
        assertTrue(result is ReiAnixShikimoriDiscoveryResult.TitleMismatch ||
            result is ReiAnixShikimoriDiscoveryResult.Ambiguous ||
            result is ReiAnixShikimoriDiscoveryResult.NotFound)
    }


    @Test
    fun matchesAnAlternateTitleWhenPrimaryTitleDiffers() = runBlocking {
        val page = "https://shikimori.io/animes/z20-re-zero"
        val client = clientFor(
            mapOf(page to ReiAnixPublicHttpResponse(
                200, page, "text/html",
                "<html><h1>Re:Zero</h1><div class=\"c-poster\"><img src=\"/system/animes/original/20.jpg\"></div></html>",
            )),
        )
        val result = client.discoverCover(
            ReiAnixShikimoriAnimeIdentity("Re Zero: Starting Life", aliases = listOf("Re:Zero"), year = 2016),
        )
        assertTrue("Expected alias match, got $result", result is ReiAnixShikimoriDiscoveryResult.Found)
    }

    @Test
    fun returnsAmbiguousInsteadOfPickingFirstOfDuplicateTitles() = runBlocking {
        val first = "https://shikimori.io/animes/z20-bleach"
        val second = "https://shikimori.io/animes/z21-bleach"
        val client = clientFor(
            mapOf(
                first to ReiAnixPublicHttpResponse(200, first, "text/html", "<html><h1>Bleach</h1></html>"),
                second to ReiAnixPublicHttpResponse(200, second, "text/html", "<html><h1>Bleach</h1></html>"),
            ),
        )
        val result = client.discoverCover(ReiAnixShikimoriAnimeIdentity("Bleach"))
        assertTrue("Expected ambiguity, got $result", result is ReiAnixShikimoriDiscoveryResult.Ambiguous)
    }

    @Test
    fun reportsNoCoverWhenPublicPageHasNoPosterRegion() = runBlocking {
        val page = "https://shikimori.io/animes/z20-naruto"
        val client = clientFor(
            mapOf(page to ReiAnixPublicHttpResponse(
                200, page, "text/html", "<html><h1>Naruto</h1><img src=\"/system/animes/original/20.jpg\"></html>",
            )),
        )
        assertTrue(client.discoverCover(ReiAnixShikimoriAnimeIdentity("Naruto")) is ReiAnixShikimoriDiscoveryResult.NoCover)
    }

    @Test
    fun reportsMissingPagesAndUnexpectedContentWithoutThrowing() = runBlocking {
        val missing = "https://shikimori.io/animes/z20-naruto"
        val missingClient = clientFor(mapOf(missing to ReiAnixPublicHttpResponse(404, missing, "text/html", "")))
        assertTrue(
            missingClient.discoverCover(ReiAnixShikimoriAnimeIdentity("Naruto")) is ReiAnixShikimoriDiscoveryResult.PageNotFound,
        )

        val unexpected = "https://shikimori.io/animes/z20-naruto"
        val unexpectedClient = clientFor(
            mapOf(unexpected to ReiAnixPublicHttpResponse(200, unexpected, "application/json", "{\"error\":\"unexpected\"}")),
        )
        assertTrue(
            unexpectedClient.discoverCover(ReiAnixShikimoriAnimeIdentity("Naruto")) is ReiAnixShikimoriDiscoveryResult.InvalidHtml,
        )
    }

    @Test
    fun rejectsDisallowedPagesAndCrossHostRedirects() = runBlocking {
        val page = "https://shikimori.io/animes/z20-naruto"
        val disallowed = clientFor(
            mapOf(page to ReiAnixPublicHttpResponse(200, page, "text/html", "<html><h1>Naruto</h1></html>")),
            robotsText = listOf("User-agent: *", "Disallow: /animes", "Sitemap: ${ReiAnixShikimoriHtmlClient.SITEMAP_INDEX_URL}", "").joinToString(System.lineSeparator()),
        )
        val blockedByRobots = disallowed.discoverCover(ReiAnixShikimoriAnimeIdentity("Naruto"))
        assertTrue(blockedByRobots is ReiAnixShikimoriDiscoveryResult.NotFound ||
            blockedByRobots is ReiAnixShikimoriDiscoveryResult.Blocked)

        val redirecting = clientFor(
            mapOf(page to ReiAnixPublicHttpResponse(
                302, page, "text/html", "", "https://example.com/animes/z20-naruto",
            )),
        )
        assertTrue(
            redirecting.discoverCover(ReiAnixShikimoriAnimeIdentity("Naruto")) is ReiAnixShikimoriDiscoveryResult.Blocked,
        )
    }


    @Test
    fun reportsRateLimitWithoutRetryingOrTreatingItAsNoMatch() = runBlocking {
        val page = "https://shikimori.io/animes/z20-naruto"
        val client = clientFor(
            mapOf(page to ReiAnixPublicHttpResponse(429, page, "text/plain", "Too Many Requests")),
        )
        val result = client.discoverCover(ReiAnixShikimoriAnimeIdentity("Naruto"))
        assertTrue("HTTP 429 must remain a visible access failure, got $result", result is ReiAnixShikimoriDiscoveryResult.HttpFailure)
        result as ReiAnixShikimoriDiscoveryResult.HttpFailure
        assertEquals(429, result.statusCode)
    }

    @Test
    fun prefersLargestResponsivePosterCandidateButValidatesItsAnimeId() = runBlocking {
        val page = "https://shikimori.io/animes/z20-naruto"
        val client = clientFor(
            mapOf(page to ReiAnixPublicHttpResponse(
                200, page, "text/html",
                "<html><h1>Naruto</h1><div class=\"c-poster\"></div><div class=\"b-db_entry-poster b-image\" data-href=\"https://shikimori.io/uploads/poster/animes/20/cover.jpeg\"><picture><source srcset=\"https://shikimori.io/uploads/poster/animes/20/main.webp, https://shikimori.io/uploads/poster/animes/20/main_2x.webp 2x\" type=\"image/webp\"><img src=\"https://shikimori.io/uploads/poster/animes/20/main_alt.jpeg\"></picture></div></html>",
            )),
        )
        val result = client.discoverCover(ReiAnixShikimoriAnimeIdentity("Naruto"))
        assertTrue("Expected valid responsive poster, got $result", result is ReiAnixShikimoriDiscoveryResult.Found)
        result as ReiAnixShikimoriDiscoveryResult.Found
        assertTrue(result.imageUrl.endsWith("main_2x.webp"))
    }


    @Test
    fun rejectsKnownYearMismatchInsteadOfChoosingAnotherSeasonOrRemake() = runBlocking {
        val page = "https://shikimori.io/animes/z20-your-name-2017"
        val client = clientFor(
            mapOf(page to ReiAnixPublicHttpResponse(
                200, page, "text/html",
                "<html><h1>Your Name</h1><div class=\"c-poster\"><img src=\"/system/animes/original/20.jpg\"></div></html>",
            )),
        )
        val result = client.discoverCover(ReiAnixShikimoriAnimeIdentity("Your Name", year = 2016))
        assertTrue("A known year mismatch must not be accepted, got $result", result is ReiAnixShikimoriDiscoveryResult.NotFound)
    }


    @Test
    fun rejectsPageWhenVisibleReleaseYearConflictsWithLocalAnimeYear() = runBlocking {
        val page = "https://shikimori.io/animes/z20-naruto"
        val client = clientFor(
            mapOf(page to ReiAnixPublicHttpResponse(
                200, page, "text/html",
                "<html><div class=\"line\"><div class=\"key\">Статус:</div><div class=\"value\"><span class=\"b-anime_status_tag released\"></span><span class=\"b-tooltipped dotted mobile\" original-title=\"С 3 октября 2017 г. по 8 февраля 2018 г.\">в 2017-2018 гг.</span></div></div><h1>Naruto</h1><div class=\"c-poster\"><img src=\"/system/animes/original/20.jpg\"></div></html>",
            )),
        )
        val result = client.discoverCover(ReiAnixShikimoriAnimeIdentity("Naruto", year = 2002))
        assertTrue("Visible release-year mismatch must reject the page, got $result", result is ReiAnixShikimoriDiscoveryResult.TitleMismatch)
    }

    private fun clientFor(
        pageResponses: Map<String, ReiAnixPublicHttpResponse>,
        robotsText: String = listOf("User-agent: *", "Allow: /", "Sitemap: ${ReiAnixShikimoriHtmlClient.SITEMAP_INDEX_URL}", "").joinToString(System.lineSeparator()),
    ): ReiAnixShikimoriHtmlClient {
        val sitemap = "<urlset>" + pageResponses.keys.joinToString("") { "<url><loc>$it</loc></url>" } + "</urlset>"
        val responses = linkedMapOf(
            ReiAnixShikimoriHtmlClient.ROBOTS_URL to ReiAnixPublicHttpResponse(
                200, ReiAnixShikimoriHtmlClient.ROBOTS_URL, "text/plain", robotsText,
            ),
            ReiAnixShikimoriHtmlClient.SITEMAP_INDEX_URL to ReiAnixPublicHttpResponse(
                200, ReiAnixShikimoriHtmlClient.SITEMAP_INDEX_URL, "application/xml", sitemap,
            ),
        )
        responses.putAll(pageResponses)
        return ReiAnixShikimoriHtmlClient(FakeTransport(responses), minimumRequestIntervalMs = 0)
    }

    private class FakeTransport(private val responses: Map<String, ReiAnixPublicHttpResponse>) :
        ReiAnixPublicHttpTransport {
        override fun getOnce(url: String, maximumBytes: Int): ReiAnixPublicHttpResponse =
            responses[url] ?: ReiAnixPublicHttpResponse(404, url, "text/plain", "")
    }
}
