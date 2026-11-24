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
            PlayerSkipSegment("op", 10000, 95000),
            PlayerSkipSegment("ed", 1100000, 1190000),
            PlayerSkipSegment("recap", 0, 50000),
            PlayerSkipSegment("ed", 1199999, 1200001),
            PlayerSkipSegment("unknown", 0, 100),
        )
        assertEquals(
            listOf(PlayerSkipSegment("op", 1000, 2000),
                PlayerSkipSegment("ed", 1100000, 1190000)),
            PlayerEnhancementPolicy.segments(manual, remote, 1200000))
    }

    @Test fun aniskipRejectsMismatchedCutsAndMalformedIntervals() {
        val context = RuntimeEnvironment.getApplication()
        val json = JSONObject("""{"found":true,"results":[
            {"skipType":"op","episodeLength":1200,"interval":{"startTime":10,"endTime":95}},
            {"skipType":"ed","episodeLength":1190,"interval":{"startTime":1100,"endTime":1190}},
            {"skipType":"recap","episodeLength":1200,"interval":{"startTime":-1,"endTime":5}},
            {"skipType":"recap","episodeLength":1200,"interval":{"startTime":0,"endTime":8}}
        ]}""")
        assertEquals(listOf(PlayerSkipSegment("op", 10000, 95000)),
            AniSkipSegments(context).parse(json, 1200000))
        assertTrue(AniSkipSegments(context).parse(JSONObject("""{"found":false}"""), 1200000).isEmpty())
    }

    @Test fun rejectsMiddleEpisodeEndingAndAmbiguousRemoteMarkers() {
        val duration = 1477000L // 24:37
        val wrong = PlayerSkipSegment("ed", 870000, 960000)
        val opening = PlayerSkipSegment("op", 30000, 120000)
        val ending = PlayerSkipSegment("ed", 1320000, 1410000)
        assertFalse(PlayerEnhancementPolicy.plausibleRemoteSegment(wrong, duration))
        assertEquals(listOf(opening, ending),
            PlayerEnhancementPolicy.trustedRemoteSegments(listOf(wrong, opening, ending, ending), duration))
        assertTrue(PlayerEnhancementPolicy.trustedRemoteSegments(
            listOf(ending, ending.copy(startMs = 1300000)), duration).isEmpty())
        assertFalse(PlayerEnhancementPolicy.plausibleRemoteSegment(
            PlayerSkipSegment("op", 700000, 790000), duration))
        val manual = PlayerLocalMetadataStore.Metadata("episode",
            ending = PlayerLocalMetadataStore.Segment(wrong.startMs, wrong.endMs))
        assertEquals(listOf(wrong), PlayerEnhancementPolicy.segments(manual, listOf(ending), duration))
    }

    @Test fun seasonRelativeNumbersMustNotSilentlyQueryFirstSeason() {
        assertEquals(1, PlayerEnhancementPolicy.aniSkipEpisodeNumber(1, 1.0, null, "regular"))
        assertEquals(62, PlayerEnhancementPolicy.aniSkipEpisodeNumber(2, 1.0, 62.0, "regular"))
        assertNull(PlayerEnhancementPolicy.aniSkipEpisodeNumber(2, 1.0, null, "regular"))
        assertNull(PlayerEnhancementPolicy.aniSkipEpisodeNumber(1, 1.0, 62.0, "regular"))
        assertNull(PlayerEnhancementPolicy.aniSkipEpisodeNumber(1, 1.0, null, "special"))
    }

    @Test fun skipButtonAnchorFollowsOptionsSlot() {
        assertEquals(0.859375f, PlayerShortcutPolicy.optionsCenterFraction(PlayerShortcutPolicy.default), 0.0001f)
        assertEquals(0.875f, PlayerShortcutPolicy.optionsCenterFraction(PlayerShortcutPolicy.default.drop(1)), 0.0001f)
        assertEquals(0.5f, PlayerShortcutPolicy.optionsCenterFraction(listOf(PlayerShortcut.OPTIONS)), 0.0001f)
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
