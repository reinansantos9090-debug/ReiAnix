package com.reiflix.reiflix_local.data.tracemoe

import com.reiflix.reiflix_local.data.anilist.AniListHttpResponse
import com.reiflix.reiflix_local.data.anilist.AniListTransport
import com.reiflix.reiflix_local.data.anilist.ReiAnixAniListClient
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReiAnixGoogleVisionAniListProviderTest {
    @Test
    fun onlyAnExactAniListTitleOrAliasBecomesAWeakCandidate() = runBlocking {
        val transport = AniListTransport { _, _ ->
            AniListHttpResponse(
                200,
                """{"data":{"Page":{"media":[{"id":52991,"title":{"romaji":"Sousou no Frieren","english":"Frieren: Beyond Journey's End","native":"葬送のフリーレン"},"synonyms":["Frieren"],"genres":[],"isAdult":false}]}}}""",
            )
        }
        val provider = ReiAnixGoogleVisionAniListProvider(
            vision = GoogleVisionTitleHintSource { listOf("frieren beyond journey's end") },
            aniList = ReiAnixAniListClient(transport, minimumRequestIntervalMs = 0L),
        )

        val matches = provider.search(File("unused-thumbnail.jpg"))

        assertEquals(1, matches.size)
        assertEquals(52991, matches.single().anilistId)
        assertEquals(TraceMoeEvidenceSource.GOOGLE_WEB_DETECTION, matches.single().source)
        assertEquals(0.86, matches.single().similarity, 0.0001)
    }

    @Test
    fun ambiguousExactTitleSharedByTwoAniListEntriesIsIgnored() = runBlocking {
        val transport = AniListTransport { _, _ ->
            AniListHttpResponse(
                200,
                """{"data":{"Page":{"media":[{"id":101,"title":{"romaji":"Example","english":"Example","native":"例"},"synonyms":["Common Alias"],"genres":[],"isAdult":false},{"id":202,"title":{"romaji":"Another","english":"Another","native":"別"},"synonyms":["Common Alias"],"genres":[],"isAdult":false}]}}}""",
            )
        }
        val provider = ReiAnixGoogleVisionAniListProvider(
            vision = GoogleVisionTitleHintSource { listOf("Common Alias") },
            aniList = ReiAnixAniListClient(transport, minimumRequestIntervalMs = 0L),
        )

        assertTrue(provider.search(File("unused-thumbnail.jpg")).isEmpty())
    }

    @Test
    fun aCharacterOrBroadCategoryNameIsNotAcceptedWithoutAnExactAniListTitle() = runBlocking {
        val transport = AniListTransport { _, _ ->
            AniListHttpResponse(
                200,
                """{"data":{"Page":{"media":[{"id":52991,"title":{"romaji":"Sousou no Frieren","english":"Frieren: Beyond Journey's End","native":"葬送のフリーレン"},"synonyms":["Frieren"],"genres":[],"isAdult":false}]}}}""",
            )
        }
        val provider = ReiAnixGoogleVisionAniListProvider(
            vision = GoogleVisionTitleHintSource { listOf("Fern the mage") },
            aniList = ReiAnixAniListClient(transport, minimumRequestIntervalMs = 0L),
        )

        assertTrue(provider.search(File("unused-thumbnail.jpg")).isEmpty())
    }
}
