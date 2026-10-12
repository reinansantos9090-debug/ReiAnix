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
    val isLoading: Boolean = false,
    val results: List<AniListAnime> = emptyList(),
    val error: String? = null,
    val lastAssociationMessage: String? = null,
    val associationRevision: Long = 0L,
)

/**
 * AniList operations are isolated from local-library search and projection.
 * The integration service still persists to the canonical SQLite repository.
 */
class ReiAnixAniListViewModel(context: Context) : ReiAnixViewModel<ReiAnixAniListUiState>() {
    private val integration = ReiAnixAniListIntegrationService(context.applicationContext)
    private val _aniListUiState = MutableStateFlow(ReiAnixAniListUiState())
    override val uiState: StateFlow<ReiAnixAniListUiState> = _aniListUiState.asStateFlow()
    private var searchJob: Job? = null
    private var associationJob: Job? = null

    fun searchAniList(term: String) {
        searchJob?.cancel()
        val query = term.trim()
        if (query.isEmpty()) {
            _aniListUiState.value = ReiAnixAniListUiState()
            return
        }
        _aniListUiState.value = _aniListUiState.value.copy(
            query = query, isLoading = true, results = emptyList(), error = null,
        )
        searchJob = viewModelScope.launch {
            try {
                when (val result = integration.search(query)) {
                    is AniListResult.Success -> if (_aniListUiState.value.query == query) {
                        _aniListUiState.value = _aniListUiState.value.copy(
                            isLoading = false, results = result.value, error = null,
                        )
                    }
                    is AniListResult.Failure -> if (_aniListUiState.value.query == query) {
                        _aniListUiState.value = _aniListUiState.value.copy(
                            isLoading = false, results = emptyList(), error = result.error.userMessage(),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (_aniListUiState.value.query == query) {
                    _aniListUiState.value = _aniListUiState.value.copy(
                        isLoading = false, results = emptyList(),
                        error = error.message?.takeIf(String::isNotBlank)
                            ?: "Não foi possível pesquisar no AniList.",
                    )
                }
            }
        }
    }

    fun associateAniListManually(animeId: Long, anilistId: Int) {
        associationJob?.cancel()
        associationJob = viewModelScope.launch {
            _aniListUiState.value = _aniListUiState.value.copy(isLoading = true, error = null)
            try {
                when (val result = integration.associateManually(animeId, anilistId)) {
                    is AniListResult.Success -> _aniListUiState.value = _aniListUiState.value.copy(
                        isLoading = false,
                        lastAssociationMessage = "Associação AniList salva.",
                        associationRevision = _aniListUiState.value.associationRevision + 1,
                    )
                    is AniListResult.Failure -> _aniListUiState.value = _aniListUiState.value.copy(
                        isLoading = false, error = result.error.userMessage(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _aniListUiState.value = _aniListUiState.value.copy(
                    isLoading = false,
                    error = error.message?.takeIf(String::isNotBlank)
                        ?: "Não foi possível salvar a associação AniList.",
                )
            }
        }
    }

    fun autoMatchAniList(animeId: Long) {
        associationJob?.cancel()
        associationJob = viewModelScope.launch {
            _aniListUiState.value = _aniListUiState.value.copy(isLoading = true, error = null)
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
                        _aniListUiState.value = _aniListUiState.value.copy(
                            isLoading = false,
                            lastAssociationMessage = message,
                            results = (match as? AniListAutoMatchResult.Ambiguous)?.candidates.orEmpty(),
                            associationRevision = _aniListUiState.value.associationRevision + if (matched) 1 else 0,
                        )
                    }
                    is AniListResult.Failure -> _aniListUiState.value = _aniListUiState.value.copy(
                        isLoading = false, error = result.error.userMessage(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _aniListUiState.value = _aniListUiState.value.copy(
                    isLoading = false,
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
