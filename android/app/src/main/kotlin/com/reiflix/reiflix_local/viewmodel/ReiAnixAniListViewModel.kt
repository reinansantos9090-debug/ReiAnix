package com.reiflix.reiflix_local.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.reiflix.reiflix_local.data.anilist.AniListAnime
import com.reiflix.reiflix_local.data.anilist.AniListAutoMatchResult
import com.reiflix.reiflix_local.data.anilist.AniListError
import com.reiflix.reiflix_local.data.anilist.AniListResult
import com.reiflix.reiflix_local.data.anilist.ReiAnixAniListIntegrationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReiAnixAniListUiState(
    val query: String = "",
    val isSearchLoading: Boolean = false,
    val isManualAssociationLoading: Boolean = false,
    val isAutoMatchLoading: Boolean = false,
    val results: List<AniListAnime> = emptyList(),
    val error: String? = null,
    val lastAssociationMessage: String? = null,
    val associationRevision: Long = 0L,
) {
    val isLoading: Boolean
        get() = isSearchLoading || isManualAssociationLoading || isAutoMatchLoading
}

/**
 * AniList operations are isolated from local-library search and projection.
 * The integration service still persists to the canonical SQLite repository.
 */
class ReiAnixAniListViewModel(context: Context) : ReiAnixViewModel<ReiAnixAniListUiState>() {
    private val integration = ReiAnixAniListIntegrationService(context.applicationContext)
    private val _aniListUiState = MutableStateFlow(ReiAnixAniListUiState())
    override val uiState: StateFlow<ReiAnixAniListUiState> = _aniListUiState.asStateFlow()
    private var searchJob: Job? = null
    private var manualAssociationJob: Job? = null
    private var autoMatchJob: Job? = null
    private var searchRequestId: Long = 0L
    private var manualAssociationRequestId: Long = 0L
    private val autoMatchRequestedAnimeIds = mutableSetOf<Long>()

    fun searchAniList(term: String) {
        searchJob?.cancel()
        val requestId = ++searchRequestId
        val query = term.trim()
        if (query.isEmpty()) {
            _aniListUiState.value = _aniListUiState.value.copy(
                query = "", isSearchLoading = false, results = emptyList(), error = null,
            )
            return
        }
        _aniListUiState.value = _aniListUiState.value.copy(
            query = query, isSearchLoading = true, results = emptyList(), error = null,
        )
        searchJob = viewModelScope.launch {
            try {
                when (val result = integration.search(query)) {
                    is AniListResult.Success -> if (requestId == searchRequestId) {
                        _aniListUiState.value = _aniListUiState.value.copy(
                            isSearchLoading = false, results = result.value, error = null,
                        )
                    }
                    is AniListResult.Failure -> if (requestId == searchRequestId) {
                        _aniListUiState.value = _aniListUiState.value.copy(
                            isSearchLoading = false, results = emptyList(), error = result.error.userMessage(),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                if (requestId == searchRequestId) {
                    _aniListUiState.value = _aniListUiState.value.copy(isSearchLoading = false)
                }
                throw cancelled
            } catch (error: Exception) {
                if (requestId == searchRequestId) {
                    _aniListUiState.value = _aniListUiState.value.copy(
                        isSearchLoading = false, results = emptyList(),
                        error = error.message?.takeIf(String::isNotBlank)
                            ?: "Não foi possível pesquisar no AniList.",
                    )
                }
            } finally {
                if (requestId == searchRequestId && _aniListUiState.value.isSearchLoading) {
                    _aniListUiState.value = _aniListUiState.value.copy(isSearchLoading = false)
                }
            }
        }
    }

    fun associateAniListManually(animeId: Long, anilistId: Int) {
        manualAssociationJob?.cancel()
        val requestId = ++manualAssociationRequestId
        manualAssociationJob = viewModelScope.launch {
            _aniListUiState.value = _aniListUiState.value.copy(
                isManualAssociationLoading = true, error = null,
            )
            try {
                when (val result = integration.associateManually(animeId, anilistId)) {
                    is AniListResult.Success -> if (requestId == manualAssociationRequestId) {
                        _aniListUiState.value = _aniListUiState.value.copy(
                            isManualAssociationLoading = false,
                            lastAssociationMessage = "Associação AniList salva.",
                            associationRevision = _aniListUiState.value.associationRevision + 1,
                        )
                    }
                    is AniListResult.Failure -> if (requestId == manualAssociationRequestId) {
                        _aniListUiState.value = _aniListUiState.value.copy(
                            isManualAssociationLoading = false, error = result.error.userMessage(),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                if (requestId == manualAssociationRequestId) {
                    _aniListUiState.value = _aniListUiState.value.copy(isManualAssociationLoading = false)
                }
                throw cancelled
            } catch (error: Exception) {
                if (requestId == manualAssociationRequestId) {
                    _aniListUiState.value = _aniListUiState.value.copy(
                        isManualAssociationLoading = false,
                        error = error.message?.takeIf(String::isNotBlank)
                            ?: "Não foi possível salvar a associação AniList.",
                    )
                }
            }
        }
    }

    fun autoMatchAniList(animeId: Long) {
        if (!autoMatchRequestedAnimeIds.add(animeId)) return
        val searchRevisionAtStart = searchRequestId
        autoMatchJob = viewModelScope.launch {
            _aniListUiState.value = _aniListUiState.value.copy(
                isAutoMatchLoading = true, error = null,
            )
            try {
                when (val result = integration.autoMatch(animeId)) {
                    is AniListResult.Success -> {
                        val match = result.value
                        val message = when (match) {
                            AniListAutoMatchResult.Disabled -> "Correspondência automática desativada nas configurações."
                            AniListAutoMatchResult.NoMatch -> "Nenhuma correspondência confiável encontrada."
                            AniListAutoMatchResult.RecentlyAttempted -> "A busca automática já foi tentada recentemente."
                            is AniListAutoMatchResult.AlreadyManuallyMatched -> "A associação manual existente foi preservada."
                            is AniListAutoMatchResult.AlreadyMatched -> "A associação AniList existente foi preservada."
                            is AniListAutoMatchResult.Matched -> "Anime identificado no AniList."
                            is AniListAutoMatchResult.Ambiguous -> "Há resultados ambíguos. Selecione manualmente a obra correta."
                        }
                        val matched = match is AniListAutoMatchResult.Matched
                        val current = _aniListUiState.value
                        val ambiguousCandidates = (match as? AniListAutoMatchResult.Ambiguous)?.candidates
                        _aniListUiState.value = current.copy(
                            isAutoMatchLoading = false,
                            lastAssociationMessage = message,
                            results = if (
                                ambiguousCandidates != null &&
                                searchRevisionAtStart == searchRequestId &&
                                current.query.isBlank() &&
                                !current.isSearchLoading
                            ) ambiguousCandidates else current.results,
                            associationRevision = current.associationRevision + if (matched) 1 else 0,
                        )
                    }
                    is AniListResult.Failure -> _aniListUiState.value = _aniListUiState.value.copy(
                        isAutoMatchLoading = false, error = result.error.userMessage(),
                    )
                }
            } catch (cancelled: CancellationException) {
                _aniListUiState.value = _aniListUiState.value.copy(isAutoMatchLoading = false)
                throw cancelled
            } catch (error: Exception) {
                _aniListUiState.value = _aniListUiState.value.copy(
                    isAutoMatchLoading = false,
                    error = error.message?.takeIf(String::isNotBlank)
                        ?: "Não foi possível associar o anime ao AniList.",
                )
            }
        }
    }

    fun clearAniListMessage() {
        _aniListUiState.value = _aniListUiState.value.copy(lastAssociationMessage = null)
    }
}

private fun AniListError.userMessage(): String = when (this) {
    is AniListError.Network -> "Não foi possível conectar ao AniList. Os dados locais foram preservados."
    is AniListError.Http -> "O AniList respondeu com erro HTTP $statusCode."
    is AniListError.RateLimited -> "O AniList limitou as requisições. Tente novamente mais tarde."
    is AniListError.GraphQl -> messages.joinToString(" ")
    is AniListError.InvalidRequest -> message
    is AniListError.InvalidResponse -> "Resposta inválida do AniList ou falha ao salvar os metadados."
}
