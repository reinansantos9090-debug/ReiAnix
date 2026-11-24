package com.reianix.reianix_local.data.anilist

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReiAnixAniListDescriptionLocalizerTest {
    @Test
    fun validatedTranslationIsPersistedAndReusableWithoutNetwork() = runBlocking {
        val directory = File(System.getProperty("java.io.tmpdir"), "reianix-anilist-" + System.nanoTime())
        assertTrue(directory.mkdirs())
        try {
            val cacheFile = File(directory, "anilist_description_ptbr.json")
            val source = "The story follows a young hero who protects their people from danger every day."
            val translated = "A história acompanha um jovem herói que protege seu povo do perigo todos os dias."
            val firstTransport = FakeTranslationTransport(
                AniListTranslationHttpResponse(
                    200,
                    JSONObject()
                        .put("responseStatus", 200)
                        .put("responseData", JSONObject().put("translatedText", translated))
                        .toString(),
                ),
            )
            val first = ReiAnixAniListDescriptionLocalizer(cacheFile, firstTransport)
            assertEquals(translated, first.localizeToPtBr(source, "en"))
            assertEquals(1, firstTransport.calls)
            assertTrue(cacheFile.isFile)

            val offlineTransport = FakeTranslationTransport(
                AniListTranslationHttpResponse(503, """{"error":"offline"}"""),
            )
            val second = ReiAnixAniListDescriptionLocalizer(cacheFile, offlineTransport)
            assertEquals(translated, second.getCachedDescriptionPtBr(source, "en"))
            assertEquals(0, offlineTransport.calls)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun PortugueseDescriptionIsReturnedWithoutCallingTranslationService() = runBlocking {
        val directory = File(System.getProperty("java.io.tmpdir"), "reianix-anilist-" + System.nanoTime())
        assertTrue(directory.mkdirs())
        try {
            val transport = FakeTranslationTransport(AniListTranslationHttpResponse(500, "{}"))
            val localizer = ReiAnixAniListDescriptionLocalizer(
                File(directory, "anilist_description_ptbr.json"),
                transport,
            )
            val source = "Esta é uma história sobre uma jovem que protege seu povo."
            assertEquals(source, localizer.localizeToPtBr(source, "pt-BR"))
            assertEquals(0, transport.calls)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun normalizationRemovesHtmlWithoutLosingSynopsisText() {
        val localizer = ReiAnixAniListDescriptionLocalizer(
            File(System.getProperty("java.io.tmpdir"), "reianix-unused-" + System.nanoTime() + ".json"),
        )
        val normalized = localizer.normalizeDescription("<p>The <b>story</b> begins.</p><p>Another paragraph.</p>")
        assertTrue(normalized.contains("The story begins."))
        assertTrue(normalized.contains("Another paragraph."))
        assertTrue(!normalized.contains("<p>"))
        assertNotNull(normalized)
    }

    @Test
    fun anonymousQuotaPersistsAcrossInstancesAndRespectsRollingWindow() {
        val directory = File(System.getProperty("java.io.tmpdir"), "reianix-quota-" + System.nanoTime())
        try {
            val file = File(directory, "quota.json")
            var now = 100_000_000L
            val first = ReiAnixMyMemoryQuota(file) { now }
            assertTrue(first.reserve(4_900))
            val reopened = ReiAnixMyMemoryQuota(file) { now }
            assertTrue(reopened.reserve(100))
            assertTrue(!reopened.reserve(1))
            now += ReiAnixMyMemoryQuota.WINDOW_MS
            assertTrue(reopened.reserve(480))
            reopened.pause(60)
            assertTrue(!ReiAnixMyMemoryQuota(file) { now }.reserve(1))
            now += 60_000L
            assertTrue(reopened.reserve(1))
        } finally {
            directory.deleteRecursively()
        }
    }

    private class FakeTranslationTransport(
        private val response: AniListTranslationHttpResponse,
    ) : AniListTranslationTransport {
        var calls: Int = 0

        override suspend fun get(url: String): AniListTranslationHttpResponse {
            calls++
            return response
        }
    }
}
