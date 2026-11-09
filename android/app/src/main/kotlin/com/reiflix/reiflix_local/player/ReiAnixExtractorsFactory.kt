package com.reiflix.reiflix_local.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.FragmentedMp4Extractor

/**
 * Progressive-file extractor policy for the local library.
 *
 * Media3 1.11.1 already contains the Matroska SeekHead/Cues logic (including
 * locating Cues after the first Cluster), so ReiAnix deliberately uses that
 * maintained implementation instead of vendoring an older fork. With no usable
 * Cues index, Media3 reports the source as unseekable; the UI must not pretend
 * that a guessed duration makes it seekable.
 *
 * Fragmented MP4 is configured to merge multiple SIDX boxes. Media3 1.11.1's
 * DefaultExtractorsFactory enables mfra-based seeking by default.
 */
@UnstableApi
internal class ReiAnixExtractorsFactory : ExtractorsFactory {
    private val delegate = DefaultExtractorsFactory()
        .setFragmentedMp4ExtractorFlags(
            FragmentedMp4Extractor.FLAG_MERGE_FRAGMENTED_SIDX or
                FragmentedMp4Extractor.FLAG_READ_MFRA_FOR_SEEK_MAP,
        )

    override fun createExtractors(): Array<Extractor> = delegate.createExtractors()

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> = delegate.createExtractors(uri, responseHeaders)
}
