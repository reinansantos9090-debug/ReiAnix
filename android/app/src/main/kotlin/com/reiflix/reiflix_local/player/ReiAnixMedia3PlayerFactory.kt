package com.reiflix.reiflix_local.player

import android.content.Context
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * Owns Media3 construction policy for local-file playback. AUTO prefers a
 * hardware MediaCodec decoder and falls back to bundled software/FFmpeg when
 * MediaCodec cannot support the selected stream.
 */
@UnstableApi
internal object ReiAnixMedia3PlayerFactory {
    data class CreatedPlayer(
        val player: ExoPlayer,
        val decoderManager: DecoderManager,
    )

    fun create(
        context: Context,
        decoderPreference: String = "auto",
    ): CreatedPlayer {
        val initialVideoMode = when (decoderPreference.trim().lowercase()) {
            "hardware" -> DecoderMode.HARDWARE
            "software" -> DecoderMode.SOFTWARE
            "ffmpeg" -> DecoderMode.FFMPEG
            else -> DecoderMode.AUTO
        }
        val decoderManager = DecoderManager(
            initialVideoMode = initialVideoMode,
            initialAudioMode = DecoderMode.AUTO,
        )
        val renderersFactory = NextRenderersFactory(context).apply {
            // setDecoderManager also enables fallback and extension renderers.
            setEnableDecoderFallback(true)
            setDecoderManager(decoderManager)
        }

        val extractorsFactory = ReiAnixExtractorsFactory()
        val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)
        val preferences = context.getSharedPreferences("reianix_player_preferences", Context.MODE_PRIVATE)
        val maxBufferMs = preferences.getInt("max_buffer_ms", 30_000)
            .coerceIn(15_000, 120_000)
        val loadControl = DefaultLoadControl.Builder()
            // Tunable for local SAF/MediaStore storage; never inherit network/HLS buffering.
            .setBufferDurationsMs(
                5_000,
                maxBufferMs,
                1_000,
                3_000,
            )
            .setBackBuffer(30_000, true)
            .build()

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekParameters(SeekParameters(300_000L, 300_000L))
            .setLoadControl(loadControl)
            .build()
        decoderManager.attach(player)
        return CreatedPlayer(player, decoderManager)
    }
}
