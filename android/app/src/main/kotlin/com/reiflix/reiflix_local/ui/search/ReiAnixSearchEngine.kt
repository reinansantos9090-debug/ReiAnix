package com.reiflix.reiflix_local.ui.search

import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import java.text.Normalizer
import java.util.Locale

/**
 * In-memory search index over the canonical Compose library projection.
 *
 * The index contains only fields already delivered by LibraryStore through
 * ComposeLibraryBridge. It never performs persistence or remote access.
 */
class ReiAnixSearchIndex private constructor(
    private val documents: List<SearchDocument>,
) {
    fun search(rawQuery: String): List<ReiAnixAnimeUiModel> {
        val terms = normalizeSearchText(rawQuery)
            .split(' ')
            .filter(String::isNotBlank)
            .distinct()

        if (terms.isEmpty()) return emptyList()

        return documents
            .asSequence()
            .filter { document ->
                terms.all { term -> document.normalizedSearchText.contains(term) }
            }
            .map { document -> SearchMatch(document.anime, score(document, terms)) }
            .sortedWith(
                compareByDescending<SearchMatch> { it.score }
                    .thenBy { it.anime.title.lowercase(Locale.ROOT) }
                    .thenBy { it.anime.id },
            )
            .map { it.anime }
            .toList()
    }

    private fun score(
        document: SearchDocument,
        terms: List<String>,
    ): Int {
        val normalizedTitle = document.normalizedTitle
        val normalizedAliases = document.normalizedAliases
        val normalizedAlternateTitles = document.normalizedAlternateTitles
        val normalizedGenres = document.normalizedGenres
        val normalizedStudio = document.normalizedStudio

        var result = 0
        val normalizedQuery = terms.joinToString(" ")

        if (normalizedTitle == normalizedQuery) {
            result += 1000
        } else if (normalizedTitle.startsWith(normalizedQuery)) {
            result += 800
        } else if (normalizedTitle.contains(normalizedQuery)) {
            result += 600
        } else if (normalizedAlternateTitles.any { it == normalizedQuery }) {
            result += 520
        } else if (normalizedAlternateTitles.any { it.startsWith(normalizedQuery) }) {
            result += 420
        } else if (normalizedAlternateTitles.any { it.contains(normalizedQuery) }) {
            result += 320
        } else if (normalizedStudio == normalizedQuery) {
            result += 300
        }

        for (term in terms) {
            when {
                normalizedTitle == term -> result += 350
                normalizedTitle.contains(term) -> result += 180
                normalizedAlternateTitles.any { it == term } -> result += 150
                normalizedAlternateTitles.any { it.contains(term) } -> result += 110
                normalizedAliases.any { it == term } -> result += 140
                normalizedAliases.any { it.contains(term) } -> result += 95
                normalizedGenres.any { it == term } -> result += 120
                normalizedGenres.any { it.contains(term) } -> result += 80
                normalizedStudio == term -> result += 70
                normalizedStudio.contains(term) -> result += 50
                document.normalizedSearchText.contains(term) -> result += 20
            }
        }

        return result
    }

    private data class SearchMatch(
        val anime: ReiAnixAnimeUiModel,
        val score: Int,
    )

    private data class SearchDocument(
        val anime: ReiAnixAnimeUiModel,
        val normalizedTitle: String,
        val normalizedAliases: List<String>,
        val normalizedAlternateTitles: List<String>,
        val normalizedGenres: List<String>,
        val normalizedStudio: String,
        val normalizedSearchText: String,
    )

    companion object {
        fun build(animes: List<ReiAnixAnimeUiModel>): ReiAnixSearchIndex =
            ReiAnixSearchIndex(
                animes
                    .distinctBy(ReiAnixAnimeUiModel::id)
                    .map { anime ->
                    val normalizedTitle = normalizeSearchText(anime.title)
                    val normalizedAliases = anime.aliases.map(::normalizeSearchText).filter(String::isNotBlank)
                    val normalizedAlternateTitles = buildList {
                        anime.romajiTitle?.let { add(normalizeSearchText(it)) }
                        anime.englishTitle?.let { add(normalizeSearchText(it)) }
                        anime.nativeTitle?.let { add(normalizeSearchText(it)) }
                    }.filter(String::isNotBlank).distinct()
                    val normalizedGenres = anime.genres.map { normalizeSearchText(it.name) }
                    val normalizedStudio = normalizeSearchText(anime.studio.orEmpty())
                    val contentTerms = buildList {
                        addAll(normalizedAliases)
                        anime.romajiTitle?.let { add(normalizeSearchText(it)) }
                        anime.englishTitle?.let { add(normalizeSearchText(it)) }
                        anime.nativeTitle?.let { add(normalizeSearchText(it)) }
                        anime.description?.let { add(normalizeSearchText(it)) }
                        anime.status?.let { add(normalizeSearchText(it)) }
                        anime.format?.let { add(normalizeSearchText(it)) }
                        anime.seasonLabel?.let { add(normalizeSearchText(it)) }
                        addAll(anime.userTags.map(::normalizeSearchText))
                        anime.personalNote?.let { add(normalizeSearchText(it)) }
                        anime.year?.let { add(it.toString()) }
                        addAll(normalizedGenres)
                        anime.contentEpisodes.forEach { episode ->
                            episode.title?.let { add(normalizeSearchText(it)) }
                            add(normalizeSearchText(episode.fileName))
                            episode.seasonNumber?.let { season ->
                                add("s" + season.toString().padStart(2, '0'))
                                add("season $season")
                            }
                            episode.number?.let { number ->
                                val displayNumber = number.toDisplayNumber()
                                add("e$displayNumber")
                                add("ep $displayNumber")
                                add("episode $displayNumber")
                                episode.seasonNumber?.let { season ->
                                    add(
                                        "s" + season.toString().padStart(2, '0') +
                                            "e" + displayNumber.padStart(2, '0'),
                                    )
                                }
                            }
                        }
                    }

                    val searchableFields = buildList {
                        add(anime.title)
                        addAll(anime.aliases)
                        addAll(anime.genres.map { it.name })
                        anime.romajiTitle?.let(::add)
                        anime.englishTitle?.let(::add)
                        anime.nativeTitle?.let(::add)
                        anime.description?.let(::add)
                        anime.studio?.let(::add)
                        anime.status?.let(::add)
                        anime.format?.let(::add)
                        anime.seasonLabel?.let(::add)
                        addAll(anime.userTags)
                        anime.personalNote?.let(::add)
                        anime.year?.let { add(it.toString()) }
                        addAll(contentTerms)
                    }

                    SearchDocument(
                        anime = anime,
                        normalizedTitle = normalizedTitle,
                        normalizedAliases = normalizedAliases,
                        normalizedAlternateTitles = normalizedAlternateTitles,
                        normalizedGenres = normalizedGenres,
                        normalizedStudio = normalizedStudio,
                        normalizedSearchText = normalizeSearchText(searchableFields.joinToString(" ")),
                    )
                },
            )

        fun normalizeSearchText(value: String): String =
            Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replace("\\p{M}+".toRegex(), "")
                .lowercase(Locale.ROOT)
                .replace("[^\\p{L}\\p{Nd}]+".toRegex(), " ")
                .trim()
                .replace("\\s+".toRegex(), " ")

        private fun Double.toDisplayNumber(): String =
            if (this % 1.0 == 0.0) {
                toInt().toString()
            } else {
                toString().trimEnd('0').trimEnd('.')
            }
    }
}

object ReiAnixSearchEngine {
    fun buildIndex(animes: List<ReiAnixAnimeUiModel>): ReiAnixSearchIndex =
        ReiAnixSearchIndex.build(animes)

    fun normalizeSearchText(value: String): String =
        ReiAnixSearchIndex.normalizeSearchText(value)
}
