package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.player.NativePlayerRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class NativePlayerRequestTest {
    @Test
    fun parsesBridgeContractWithDefaultsAndValidation() {
        val values = mapOf(
            "request_id" to "req-123",
            "created_at" to "1700000000123",
            "uri" to "content://media/video/1",
            "episode_id" to "42",
            "anime_id" to "7",
            "origin_request_id" to "req-origin",
            "origin_created_at" to "1700000000001",
            "origin_transition_generation" to "17",
            "title" to "Episode 1",
            "position_ms" to "-40",
            "can_next" to "true",
            "autoplay" to "false",
            "setting_player_default_speed" to "-2",
            "setting_player_auto_hide_seconds" to "999",
            "setting_player_double_tap_seek_seconds" to "-1",
            "setting_player_long_press_speed" to "0",
            "setting_player_max_video_frame_rate" to "-4",
            "setting_audio_subtitle_scale" to "0",
            "setting_audio_subtitle_bottom_padding" to "-8",
            "setting_audio_subtitle_embedded_style" to "false",
        )

        val request = NativePlayerRequest.fromQueryParameters(values::get)

        assertEquals("req-123", request.requestId)
        assertEquals(1700000000123L, request.commandCreatedAtMs)
        assertEquals("content://media/video/1", request.episodeUri)
        assertEquals("42", request.episodeId)
        assertEquals("7", request.animeId)
        assertEquals("req-origin", request.originRequestId)
        assertEquals(1700000000001L, request.originCreatedAtMs)
        assertEquals(17L, request.originTransitionGeneration)
        assertEquals("Episode 1", request.title)
        assertEquals(0L, request.positionMs)
        assertTrue(request.canNext)
        assertFalse(request.canPrevious)
        assertFalse(request.autoplay)
        assertFalse(request.zoomEnabled)
        assertEquals(1f, request.defaultSpeed)
        assertEquals(300, request.autoHideSeconds)
        assertEquals(1L, request.doubleTapSeekSeconds)
        assertEquals(1f, request.longPressSpeed)
        assertEquals(0, request.maxVideoFrameRate)
        assertEquals(0.5f, request.audioSubtitleScale)
        assertEquals(0, request.audioSubtitleBottomPadding)
        assertFalse(request.audioSubtitleEmbeddedStyle)
    }

    @Test
    fun keepsExplicitPlayerContractValues() {
        val request = NativePlayerRequest.fromQueryParameters(
            mapOf(
                "request_id" to "req-456",
                "created_at" to "1700000000456",
                "uri" to "file:///storage/emulated/0/episode.mkv",
                "episode_id" to "84",
                "anime_id" to "12",
                "can_next" to "true",
                "can_previous" to "true",
                "autoplay" to "true",
                "setting_player_default_speed" to "1.5",
                "setting_player_aspect_ratio" to "fill",
                "setting_player_zoom_enabled" to "true",
                "setting_player_immersive" to "always",
                "setting_player_rotation" to "landscape",
                "setting_player_pip" to "false",
                "setting_player_auto_hide_seconds" to "8",
                "setting_player_double_tap_seek_seconds" to "15",
                "setting_player_long_press_speed" to "2.5",
                "setting_audio_preferred_language" to "pt-BR",
                "setting_audio_preferred_subtitle_language" to "en-US",
                "setting_audio_subtitles" to "auto",
                "setting_audio_subtitle_scale" to "1.25",
                "setting_audio_subtitle_bottom_padding" to "12",
                "setting_audio_subtitle_embedded_style" to "true",
                "setting_gestures_volume" to "true",
                "setting_gestures_brightness" to "true",
                "setting_gestures_double_tap" to "true",
                "setting_gestures_long_press" to "true",
                "setting_player_max_video_resolution" to "1080p",
                "setting_player_max_video_frame_rate" to "60",
                "setting_player_max_audio_channels" to "6",
            )::get,
        )

        assertEquals("req-456", request.requestId)
        assertEquals(1700000000456L, request.commandCreatedAtMs)
        assertEquals("file:///storage/emulated/0/episode.mkv", request.episodeUri)
        assertEquals("84", request.episodeId)
        assertEquals("12", request.animeId)
        assertTrue(request.canNext)
        assertTrue(request.canPrevious)
        assertTrue(request.autoplay)
        assertEquals(1.5f, request.defaultSpeed)
        assertEquals("fill", request.aspectRatio)
        assertTrue(request.zoomEnabled)
        assertEquals("landscape", request.rotation)
        assertFalse(request.pip)
        assertEquals(8, request.autoHideSeconds)
        assertEquals(15L, request.doubleTapSeekSeconds)
        assertEquals(2.5f, request.longPressSpeed)
        assertEquals("pt-BR", request.audioPreferredLanguage)
        assertEquals("en-US", request.audioPreferredSubtitleLanguage)
        assertEquals("auto", request.audioSubtitles)
        assertEquals(1.25f, request.audioSubtitleScale)
        assertEquals(12, request.audioSubtitleBottomPadding)
        assertTrue(request.audioSubtitleEmbeddedStyle)
        assertTrue(request.gesturesVolume)
        assertTrue(request.gesturesBrightness)
        assertTrue(request.gesturesDoubleTap)
        assertTrue(request.gesturesLongPress)
        assertEquals("1080p", request.maxVideoResolution)
        assertEquals(60, request.maxVideoFrameRate)
        assertEquals(6, request.maxAudioChannels)
    }

    @Test
    fun parsesARealisticReiflixNativeUrlWithoutAndroidUi() {
        val url = URI(
            "reiflix://native?action=play" +
                "&request_id=req%2F789" +
                "&created_at=1700000000789" +
                "&uri=content%3A%2F%2Fmedia%2Fexternal%2Fvideo%2F7" +
                "&episode_id=206" +
                "&anime_id=31" +
                "&title=Temp+07+Ep+06" +
                "&position_ms=12345" +
                "&can_next=true" +
                "&can_previous=false" +
                "&autoplay=true" +
                "&setting_player_default_speed=1.25" +
                "&setting_player_aspect_ratio=fill" +
                "&setting_player_immersive=always" +
                "&setting_player_rotation=auto" +
                "&setting_player_pip=true" +
                "&setting_player_auto_hide_seconds=9" +
                "&setting_player_double_tap_seek_seconds=15" +
                "&setting_player_long_press_speed=2.25" +
                "&setting_player_max_video_resolution=1080p" +
                "&setting_player_max_video_frame_rate=60" +
                "&setting_player_max_audio_channels=6" +
                "&setting_gestures_volume=false" +
                "&setting_gestures_brightness=true" +
                "&setting_gestures_double_tap=true" +
                "&setting_gestures_long_press=false" +
                "&setting_audio_preferred_language=pt-BR" +
                "&setting_audio_preferred_subtitle_language=en-US" +
                "&setting_audio_subtitles=auto" +
                "&setting_audio_subtitle_scale=1.25" +
                "&setting_audio_subtitle_bottom_padding=12" +
                "&setting_audio_subtitle_embedded_style=true",
        )
        val params = url.rawQuery
            .orEmpty()
            .split("&")
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val parts = pair.split("=", limit = 2)
                val key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8)
                val value = URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8)
                key to value
            }

        val request = NativePlayerRequest.fromQueryParameters(params::get)

        assertEquals("req/789", request.requestId)
        assertEquals(1700000000789L, request.commandCreatedAtMs)
        assertEquals("content://media/external/video/7", request.episodeUri)
        assertEquals("206", request.episodeId)
        assertEquals("31", request.animeId)
        assertEquals("Temp 07 Ep 06", request.title)
        assertEquals(12345L, request.positionMs)
        assertTrue(request.canNext)
        assertFalse(request.canPrevious)
        assertTrue(request.autoplay)
        assertEquals(1.25f, request.defaultSpeed)
        assertEquals("fill", request.aspectRatio)
        assertEquals("always", request.immersive)
        assertEquals("auto", request.rotation)
        assertTrue(request.pip)
        assertEquals(9, request.autoHideSeconds)
        assertEquals(15L, request.doubleTapSeekSeconds)
        assertEquals(2.25f, request.longPressSpeed)
        assertEquals("1080p", request.maxVideoResolution)
        assertEquals(60, request.maxVideoFrameRate)
        assertEquals(6, request.maxAudioChannels)
        assertFalse(request.gesturesVolume)
        assertTrue(request.gesturesBrightness)
        assertTrue(request.gesturesDoubleTap)
        assertFalse(request.gesturesLongPress)
        assertEquals("pt-BR", request.audioPreferredLanguage)
        assertEquals("en-US", request.audioPreferredSubtitleLanguage)
        assertEquals("auto", request.audioSubtitles)
        assertEquals(1.25f, request.audioSubtitleScale)
        assertEquals(12, request.audioSubtitleBottomPadding)
        assertTrue(request.audioSubtitleEmbeddedStyle)
    }
}
