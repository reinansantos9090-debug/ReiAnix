package com.reianix.reianix_local.player

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import android.graphics.Bitmap
import android.net.Uri
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PlayerEnhancementPolicyTest {
    @Test fun smartResumeIsOptionalAndClamped() {
        assertEquals(20_000L, PlayerEnhancementPolicy.resumePosition(20_000L, false))
        assertEquals(15_000L, PlayerEnhancementPolicy.resumePosition(20_000L, true))
        assertEquals(0L, PlayerEnhancementPolicy.resumePosition(3_000L, true))
    }

    @Test fun onlyWholePositiveEpisodeNumbersAreAccepted() {
        assertEquals(1, PlayerEnhancementPolicy.episodeNumber(1.0))
        assertNull(PlayerEnhancementPolicy.episodeNumber(0.0))
        assertNull(PlayerEnhancementPolicy.episodeNumber(2.5))
        assertNull(PlayerEnhancementPolicy.episodeNumber(Double.NaN))
    }

    @Test fun manualMarkersWinAndInvalidRemoteRangesAreIgnored() {
        val manual = PlayerLocalMetadataStore.Metadata(
            "episode", opening = PlayerLocalMetadataStore.Segment(1000, 2000))
        val remote = listOf(
            PlayerSkipSegment("op", 100, 800),
            PlayerSkipSegment("ed", 9000, 10000),
            PlayerSkipSegment("recap", 0, 500),
            PlayerSkipSegment("ed", 9999, 10001),
            PlayerSkipSegment("unknown", 0, 100),
        )
        assertEquals(
            listOf(PlayerSkipSegment("recap", 0, 500), PlayerSkipSegment("op", 1000, 2000),
                PlayerSkipSegment("ed", 9000, 10000)),
            PlayerEnhancementPolicy.segments(manual, remote, 10000))
    }

    @Test fun aniskipRejectsMismatchedCutsAndMalformedIntervals() {
        val context = RuntimeEnvironment.getApplication()
        val json = JSONObject("""{"found":true,"results":[
            {"skipType":"op","episodeLength":1200,"interval":{"startTime":10,"endTime":95}},
            {"skipType":"ed","episodeLength":1190,"interval":{"startTime":1100,"endTime":1190}},
            {"skipType":"recap","episodeLength":1200,"interval":{"startTime":-1,"endTime":5}},
            {"skipType":"recap","episodeLength":1200,"interval":{"startTime":0,"endTime":8}}
        ]}""")
        assertEquals(listOf(PlayerSkipSegment("op", 10000, 95000), PlayerSkipSegment("recap", 0, 8000)),
            AniSkipSegments(context).parse(json, 1200000))
        assertTrue(AniSkipSegments(context).parse(JSONObject("""{"found":false}"""), 1200000).isEmpty())
    }

    @Test fun introDbOnlySuppliesSegmentsThatFitTheLocalVideo() {
        val context = RuntimeEnvironment.getApplication()
        val response = JSONObject("""{
            "intro":{"start_ms":1039,"end_ms":91039},
            "outro":{"start_ms":1286120,"end_ms":1356110},
            "recap":null
        }""")

        assertEquals(
            listOf(PlayerSkipSegment("op", 1039, 91039)),
            AniSkipSegments(context).parseIntroDb(response, durationMs = 1340000),
        )
    }

    @Test fun diskCacheIsReusedAndInvalidatedForChangedMedia() {
        val context = RuntimeEnvironment.getApplication()
        File(context.cacheDir, "seek-preview-v2").deleteRecursively()
        val source = File(context.cacheDir, "preview-source.mp4").apply { writeText("test") }
        val uri = Uri.fromFile(source)
        val image = Bitmap.createBitmap(24, 13, Bitmap.Config.ARGB_8888)
        try {
            PersistentSeekPreviewCache(context, uri, 120000).put(5000, image)
            val reopened = PersistentSeekPreviewCache(context, uri, 120000)
            assertTrue(5000L in reopened.availableTimes())
            assertNotNull(reopened.read(5000))
            source.appendText("changed")
            assertNull(PersistentSeekPreviewCache(context, uri, 120000).read(5000))
        } finally {
            image.recycle()
            source.delete()
            File(context.cacheDir, "seek-preview-v2").deleteRecursively()
        }
    }
}
