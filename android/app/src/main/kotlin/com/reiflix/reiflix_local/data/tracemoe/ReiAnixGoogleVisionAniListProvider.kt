package com.reiflix.reiflix_local.data.tracemoe

import com.reiflix.reiflix_local.data.anilist.AniListAnime
import com.reiflix.reiflix_local.data.anilist.AniListResult
import com.reiflix.reiflix_local.data.anilist.ReiAnixAniListClient
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.Locale

private const val GOOGLE_WEB_DETECTION_SUPPORTING_SCORE = 0.86

/**
 * Converts generic web-search title hints into conservative AniList candidates.
 *
 * A Google web entity score is not an anime-scene similarity score. It is never
 * interpreted as confidence. A hint is accepted only when it exactly matches one
 * AniList title/alias, and its fixed low supporting score requires confirmation
 * from another distinct episode thumbnail in the existing consensus policy.
 */
class ReiAnixGoogleVisionAniListProvider(
    private val vision: GoogleVisionTitleHintSource,
    private val aniList: ReiAnixAniListClient,
) : ReiAnixTraceMoeSearchProvider {
    override suspend fun search(imageFile: File): List<TraceMoeSearchMatch> {
        val hints = vision.searchTitleHints(imageFile).asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(::normalizeVisualTitle)
            .take(MAX_TITLE_HINTS)
            .toList()
        val matches = linkedMapOf<Int, TraceMoeSearchMatch>()
        for (hint in hints) {
            when (val result = aniList.searchAnime(hint, limit = 10)) {
                is AniListResult.Success -> {
                    val exact = result.value.filter { anime ->
                        anime.allIdentityTitles().any { title ->
                            normalizeVisualTitle(title) == normalizeVisualTitle(hint)
                        }
                    }.distinctBy(AniListAnime::id)
                    // An alias shared by multiple AniList entries is not safe evidence.
                    if (exact.size == 1) {
                        val anime = exact.single()
                        val match = TraceMoeSearchMatch(
                            anilistId = anime.id,
                            similarity = GOOGLE_WEB_DETECTION_SUPPORTING_SCORE,
                            source = TraceMoeEvidenceSource.GOOGLE_WEB_DETECTION,
                        )
                        val previous = matches[anime.id]
                        if (previous == null || match.similarity > previous.similarity) {
                            matches[anime.id] = match
                        }
                    }
                }
                is AniListResult.Failure -> throw IOException(
                    "AniList could not verify a Google Web Detection title hint.",
                )
            }
        }
        return matches.values.sortedByDescending(TraceMoeSearchMatch::similarity)
    }

    private fun AniListAnime.allIdentityTitles(): List<String> = buildList {
        titleEnglish?.let(::add)
        titleRomaji?.let(::add)
        titleNative?.let(::add)
        addAll(synonyms)
    }

    private companion object {
        const val MAX_TITLE_HINTS = 4
    }
}

internal fun normalizeVisualTitle(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
