package com.reianix.reianix_local.ui.details

import com.reianix.reianix_local.ui.model.ReiAnixDetailsAnimeUiModel

/**
 * Full source fingerprint for the episode-thumbnail pipeline.
 *
 * Runs off the main thread. Playback progress and watch flags deliberately do
 * not alter the fingerprint, whereas media URI/identity, availability, file
 * revision, duration, and thumbnail path still trigger a new canonical pass.
 */
internal fun detailsThumbnailRevision(anime: ReiAnixDetailsAnimeUiModel?): String {
    if (anime == null) return ""

    val episodes = (
        anime.seasons.asSequence().flatMap { it.episodes.asSequence() } +
            anime.specials.asSequence() +
            anime.mediaFiles.asSequence()
        )
        .distinctBy { it.id }
        .sortedBy { it.id }

    return buildString {
        var first = true
        for (episode in episodes) {
            if (!first) append('|')
            first = false
            append(episode.id)
            append('\u001f').append(episode.media.reference.orEmpty())
            append('\u001f').append(episode.media.uri.orEmpty())
            append('\u001f').append(episode.media.path.orEmpty())
            append('\u001f').append(episode.media.mediaIdentity.orEmpty())
            append('\u001f').append(episode.media.availability.name)
            append('\u001f').append(episode.media.sourceAvailabilityState.orEmpty())
            append('\u001f').append(episode.fileSizeBytes?.toString().orEmpty())
            append('\u001f').append(episode.modifiedAt?.toString().orEmpty())
            append('\u001f').append(episode.durationSeconds?.toString().orEmpty())
            append('\u001f').append(episode.artwork?.localPath.orEmpty())
        }
    }
}
