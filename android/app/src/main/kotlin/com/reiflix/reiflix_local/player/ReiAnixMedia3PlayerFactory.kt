package com.reiflix.reiflix_local.player

import android.content.Context
import androidx.media3.common.SeekParameters
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
            "software", "ffmpeg" -> DecoderMode.FFMPEG
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
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setExtractorsFactory(extractorsFactory)
        val loadControl = DefaultLoadControl.Builder()
            // Local SAF/MediaStore reads benefit from a moderate forward buffer.
            .setBufferDurationsMs(
                5_000,
                30_000,
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
