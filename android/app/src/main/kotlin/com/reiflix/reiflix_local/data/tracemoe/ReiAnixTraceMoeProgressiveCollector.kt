package com.reiflix.reiflix_local.data.tracemoe

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.transform

/**
 * Result accumulated while Trace.moe consumes the local episode-thumbnail stream.
 * The collector owns no thumbnail-generation work and never waits for the full season.
 */
internal data class TraceMoeProgressiveResult(
    val matchesByEpisode: LinkedHashMap<Long, List<TraceMoeSearchMatch>>,
    val consensus: TraceMoeIdentityConsensus?,
)

/**
 * Query each distinct, usable episode thumbnail as soon as it is emitted.
 *
 * The caller can supply previously persisted matches. A null search result means the
 * request could not be made (for example, the shared daily request budget is exhausted);
 * that sample is skipped while the collector keeps listening for later thumbnails.
 * Empty search results are recorded as completed attempts and do not stop the stream.
 *
 * There is deliberately no episode-count limit. Collection ends only when the source
 * flow closes/cancels or the original strict progressive identity policy confirms a match.
 */
internal suspend fun collectTraceMoeSamplesUntilConfirmed(
    samples: Flow<TraceMoeEpisodeSample>,
    initialMatchesByEpisode: Map<Long, List<TraceMoeSearchMatch>>,
    isSampleUsable: suspend (TraceMoeEpisodeSample) -> Boolean,
    search: suspend (TraceMoeEpisodeSample) -> List<TraceMoeSearchMatch>?,
    onMatchesUpdated: suspend (Map<Long, List<TraceMoeSearchMatch>>) -> Unit = {},
): TraceMoeProgressiveResult {
    val matchesByEpisode = LinkedHashMap(initialMatchesByEpisode)
    var consensus = evaluateTraceMoeProgressiveIdentity(matchesByEpisode)

    if (consensus == null) {
        val seenEpisodeIds = HashSet<Long>().apply { addAll(matchesByEpisode.keys) }
        samples.transform { sample ->
            if (sample.episodeId > 0L && seenEpisodeIds.add(sample.episodeId)) {
                emit(sample)
            }
        }.firstOrNull { sample ->
            if (!isSampleUsable(sample)) return@firstOrNull false

            val matches = search(sample) ?: return@firstOrNull false
            matchesByEpisode[sample.episodeId] = matches
            onMatchesUpdated(LinkedHashMap(matchesByEpisode))
            consensus = evaluateTraceMoeProgressiveIdentity(matchesByEpisode)
            consensus != null
        }
    }

    return TraceMoeProgressiveResult(
        matchesByEpisode = matchesByEpisode,
        consensus = consensus,
    )
}
