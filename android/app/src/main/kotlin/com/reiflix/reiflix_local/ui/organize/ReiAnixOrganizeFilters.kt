package com.reiflix.reiflix_local.ui.organize

import androidx.annotation.Keep
import com.reiflix.reiflix_local.ui.library.ReiAnixLibraryFilterEngine
import com.reiflix.reiflix_local.ui.library.ReiAnixLibraryFilters
import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability
import com.reiflix.reiflix_local.ui.search.ReiAnixSearchIndex

@Keep
data class ReiAnixOrganizeFilters(
    val query: String = "",
    val state: String = DEFAULT_STATE,
    val genreKey: String? = null,
    val sort: String = DEFAULT_SORT,
    val mode: ReiAnixOrganizeMode = ReiAnixOrganizeMode.OVERVIEW,
) {
    companion object {
        const val DEFAULT_STATE = "Todos"
        const val DEFAULT_SORT = "Mais recentes"

        val states = listOf(
            "Todos",
            "Favoritos",
            "Fixados",
            "Não assistidos",
            "Em andamento",
            "Concluídos",
            "Assistidos",
        )
    }
}

enum class ReiAnixOrganizeMode {
    OVERVIEW,
    COLLECTION,
}

data class ReiAnixOrganizeCategory(
    val label: String,
    val count: Int,
)

fun filterOrganizeAnimes(
    animes: List<ReiAnixAnimeUiModel>,
    searchIndex: ReiAnixSearchIndex,
    filters: ReiAnixOrganizeFilters,
): List<ReiAnixAnimeUiModel> {
    val query = filters.query.trim()
    val searched = if (query.isBlank()) animes else searchIndex.search(query)

    val stateFiltered = searched.filter { anime ->
        matchesOrganizeState(anime, filters.state)
    }

    val genreFiltered = filters.genreKey
        ?.takeIf { it.isNotBlank() }
        ?.let { key -> stateFiltered.filter { anime -> anime.genres.any { it.stableKey == key } } }
        ?: stateFiltered

    return ReiAnixLibraryFilterEngine.filter(
        animes = genreFiltered,
        filters = ReiAnixLibraryFilters(sort = filters.sort),
    )
}

fun buildOrganizeCategories(animes: List<ReiAnixAnimeUiModel>): List<ReiAnixOrganizeCategory> =
    ReiAnixOrganizeFilters.states.map { state ->
        ReiAnixOrganizeCategory(
            label = state,
            count = animes.count { matchesOrganizeState(it, state) },
        )
    }

fun matchesOrganizeState(
    anime: ReiAnixAnimeUiModel,
    state: String,
): Boolean {
    val episodes = anime.contentEpisodes
    val available = episodes.filter { episode ->
        episode.media.availability == ReiAnixMediaAvailability.AVAILABLE
    }

    return when (state) {
        ReiAnixOrganizeFilters.DEFAULT_STATE -> true
        "Favoritos" -> anime.favorite
        "Fixados" -> anime.pinned
        "Assistidos" -> episodes.any { it.isCompleted }
        "Não assistidos" -> available.any { !it.isCompleted && it.progressFraction <= 0f }
        "Em andamento" -> available.any { !it.isCompleted && it.progressFraction > 0f }
        "Concluídos" -> available.isNotEmpty() && available.all { it.isCompleted }
        else -> true
    }
}
