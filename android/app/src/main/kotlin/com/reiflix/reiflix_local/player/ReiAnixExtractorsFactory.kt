package com.reiflix.reiflix_local.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mkv.ReiAnixMatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory

/**
 * Progressive local-file extractor policy aligned with CloudStream's player strategy.
 *
 * Media3 1.11.1 handles ordinary Matroska Cues and a direct SeekHead reference. ReiAnix's
 * Matroska extractor extends that path to traverse chained SeekHead entries, find Cues
 * stored after clusters, build the seek map, and restore the original input position.
 *
 * Fragmented MP4 keeps Media3's SIDX merge and MFRA seek-map support. All other formats
 * continue to use the maintained Media3 DefaultExtractorsFactory implementation.
 */
@UnstableApi
internal class ReiAnixExtractorsFactory : ExtractorsFactory {
    private val delegate = DefaultExtractorsFactory()
        .setFragmentedMp4ExtractorFlags(
            FragmentedMp4Extractor.FLAG_MERGE_FRAGMENTED_SIDX or
                FragmentedMp4Extractor.FLAG_READ_MFRA_FOR_SEEK_MAP,
        )

    override fun createExtractors(): Array<Extractor> =
        replaceMatroska(delegate.createExtractors())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> =
        replaceMatroska(delegate.createExtractors(uri, responseHeaders))

    private fun replaceMatroska(extractors: Array<Extractor>): Array<Extractor> =
        extractors.map { extractor ->
            if (extractor is MatroskaExtractor) {
                ReiAnixMatroskaExtractor(DefaultSubtitleParserFactory())
            } else {
                extractor
            }
        }.toTypedArray()
}
