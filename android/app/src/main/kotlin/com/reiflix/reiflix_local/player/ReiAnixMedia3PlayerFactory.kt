package com.reiflix.reiflix_local.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory

/**
 * Single construction point for the local Media3 player.
 *
 * Media3's default extractors already cover MP4/fMP4, Matroska/WebM and common
 * local formats. We keep those maintained extractors, but own the factory so
 * future format-specific flags can be enabled only when a real fixture proves
 * they are needed. Decoder fallback is enabled for initialization failures;
 * it does not force software decoding during normal playback.
 */
@OptIn(UnstableApi::class)
internal object ReiAnixMedia3PlayerFactory {
    fun create(context: Context): ExoPlayer {
        val extractorsFactory = DefaultExtractorsFactory()
        val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }
}
