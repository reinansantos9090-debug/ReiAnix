package com.reiflix.reiflix_local.viewmodel

import android.content.Context
import com.reiflix.reiflix_local.ReiAnixAppDependencies
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val ANI_LIST_SEARCH_DEBOUNCE_MS = 300L

data class ReiAnixAniListUiState(
    val query: String = "",
    val isSearchLoading: Boolean = false,
    val isManualAssociationLoading: Boolean = false,
    val isAutoMatchLoading: Boolean = false,
    val results: List<AniListAnime> = emptyList(),
    val error: String? = null,
    val selectedAnime: AniListAnime? = null,
    val isDetailsLoading: Boolean = false,
    val detailsError: String? = null,
    /** Exact SQLite AniList associations; empty means none was found, not title mismatch. */
    val associatedLocalAnimeIds: List<Long> = emptyList(),
    val localAssociationError: String? = null,
    val isMetadataRefreshLoading: Boolean = false,
    val lastAssociationMessage: String? = null,
    val associationRevision: Long = 0L,
) {
    val isLoading: Boolean
        get() = isSearchLoading || isManualAssociationLoading || isAutoMatchLoading || isMetadataRefreshLoading
}

/**
 * AniList operations are isolated from local-library search and projection.
 * The integration service still persists to the canonical SQLite repository.
 */
class ReiAnixAniListViewModel(
    context: Context,
    dependencies: ReiAnixAppDependencies? = null,
) : ReiAnixViewModel<ReiAnixAniListUiState>() {
    private val integration = dependencies?.aniListIntegrationService
        ?: ReiAnixAniListIntegrationService(context.applicationContext)
    private val _aniListUiState = MutableStateFlow(ReiAnixAniListUiState())
    override val uiState: StateFlow<ReiAnixAniListUiState> = _aniListUiState.asStateFlow()
    private val _discoveryQuery = MutableStateFlow("")
    private var discoveryQueryJob: Job? = null
    private var searchJob: Job? = null
    private var detailsJob: Job? = null
    private var detailsRequestId: Long = 0L
    private var metadataRefreshJob: Job? = null
    private var metadataRefreshRequestId: Long = 0L

    init {
        discoveryQueryJob = viewModelScope.launch {
            _discoveryQuery
                .debounce(ANI_LIST_SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { term -> searchAniList(term) }
        }
    }

    /** Entry point for the Search screen; requests are debounced and cancellable. */
    fun setDiscoveryQuery(term: String) {
        _discoveryQuery.value = term
    }

    /** Explicit retry path used after a remote search failure. */
    fun retryDiscoverySearch(term: String) {
        searchAniList(term)
    }

    /**
     * Loads one external catalogue record without persisting it to the local
     * library, favorites, history, or AniList association tables.
     */
    fun loadAniListDetails(anilistId: Int) {
        detailsJob?.cancel()
        val requestId = ++detailsRequestId
        if (anilistId <= 0) {
            _aniListUiState.value = _aniListUiState.value.copy(
                selectedAnime = null,
                isDetailsLoading = false,
                detailsError = "O identificador AniList é inválido.",
                associatedLocalAnimeIds = emptyList(),
                localAssociationError = null,
            )
            return
        }
        _aniListUiState.value = _aniListUiState.value.copy(
            selectedAnime = null,
            isDetailsLoading = true,
            detailsError = null,
            associatedLocalAnimeIds = emptyList(),
            localAssociationError = null,
        )
        detailsJob = viewModelScope.launch {
            try {
                when (val result = integration.lookupAnimeById(anilistId)) {
                    is AniListResult.Success -> if (requestId == detailsRequestId) {
                        var localAssociationError: String? = null
                        val localAnimeIds = if (result.value == null) {
                            emptyList()
                        } else {
                            try {
                                integration.findLocalAnimeIdsByAniListId(anilistId)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                localAssociationError = "Não foi possível verificar as associações locais agora."
                                emptyList()
                            }
                        }
                        if (requestId == detailsRequestId) {
                            _aniListUiState.value = _aniListUiState.value.copy(
                                selectedAnime = result.value,
                                isDetailsLoading = false,
                                detailsError = if (result.value == null) {
                                    "O AniList não encontrou esse anime."
                                } else {
                                    null
                                },
                                associatedLocalAnimeIds = localAnimeIds,
                                localAssociationError = localAssociationError,
                            )
                        }
                    }
                    is AniListResult.Failure -> if (requestId == detailsRequestId) {
                        _aniListUiState.value = _aniListUiState.value.copy(
                            isDetailsLoading = false,
                            detailsError = result.error.userMessage(),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                if (requestId == detailsRequestId) {
                    _aniListUiState.value = _aniListUiState.value.copy(isDetailsLoading = false)
                }
                throw cancelled
            } catch (error: Exception) {
                if (requestId == detailsRequestId) {
                    _aniListUiState.value = _aniListUiState.value.copy(
                        isDetailsLoading = false,
                        detailsError = error.message?.takeIf(String::isNotBlank)
                            ?: "Não foi possível carregar os detalhes AniList.",
                    )
                }
            } finally {
                if (requestId == detailsRequestId && _aniListUiState.value.isDetailsLoading) {
                    _aniListUiState.value = _aniListUiState.value.copy(isDetailsLoading = false)
                }
            }
        }
    }
    /** Refreshes only a local anime's existing AniList association through the native service. */
    fun refreshAniListMetadata(animeId: Long) {
        metadataRefreshJob?.cancel()
        val requestId = ++metadataRefreshRequestId
        if (animeId <= 0L) {
            _aniListUiState.value = _aniListUiState.value.copy(
                isMetadataRefreshLoading = false,
                error = "Não foi possível atualizar os metadados: identificador local inválido.",
            )
            return
        }
        _aniListUiState.value = _aniListUiState.value.copy(
            isMetadataRefreshLoading = true,
            error = null,
            lastAssociationMessage = null,
        )
        metadataRefreshJob = viewModelScope.launch {
            try {
                when (val result = integration.refreshMetadata(animeId)) {
                    is AniListResult.Success -> if (requestId == metadataRefreshRequestId) {
                        val retainedStaleMetadata = result.value.metadataStatus
                            .equals("stale", ignoreCase = true)
                        _aniListUiState.value = _aniListUiState.value.copy(
                            isMetadataRefreshLoading = false,
                            error = null,
                            lastAssociationMessage = if (retainedStaleMetadata) {
                                "Não foi possível atualizar os metadados do AniList agora; os dados locais foram mantidos e marcados como desatualizados."
                            } else {
                                "Metadados AniList atualizados."
                            },
                            associationRevision = _aniListUiState.value.associationRevision + 1,
                        )
                    }
                    is AniListResult.Failure -> if (requestId == metadataRefreshRequestId) {
                        val missingAssociation = (result.error as? AniListError.InvalidRequest)
                            ?.message
                            ?.contains("no valid AniList association", ignoreCase = true) == true
                        if (!missingAssociation) {
                            _aniListUiState.value = _aniListUiState.value.copy(
                                isMetadataRefreshLoading = false,
                                error = result.error.userMessage(),
                            )
                        } else {
                            // Legacy main.py's refresh_metadata(force=True) searched
                            // by the local title when no AniList ID was associated;
                            // a refresh action must not become a hard failure merely
                            // because the local entry has not been matched yet.
                            when (val matchResult = integration.autoMatch(animeId, force = true)) {
                                is AniListResult.Failure -> if (requestId == metadataRefreshRequestId) {
                                    _aniListUiState.value = _aniListUiState.value.copy(
                                        isMetadataRefreshLoading = false,
                                        error = matchResult.error.userMessage(),
                                    )
                                }
                                is AniListResult.Success -> if (requestId == metadataRefreshRequestId) {
                                    when (val match = matchResult.value) {
                                        is AniListAutoMatchResult.Matched -> {
                                            _aniListUiState.value = _aniListUiState.value.copy(
                                                isMetadataRefreshLoading = false,
                                                error = null,
                                                lastAssociationMessage = "Metadados AniList encontrados e atualizados.",
                                                associationRevision = _aniListUiState.value.associationRevision + 1,
                                            )
                                        }
                                        is AniListAutoMatchResult.AlreadyMatched,
                                        is AniListAutoMatchResult.AlreadyManuallyMatched -> {
                                            when (val refreshed = integration.refreshMetadata(animeId)) {
                                                is AniListResult.Success -> {
                                                    val retainedStaleMetadata = refreshed.value.metadataStatus
                                                        .equals("stale", ignoreCase = true)
                                                    _aniListUiState.value = _aniListUiState.value.copy(
                                                        isMetadataRefreshLoading = false,
                                                        error = null,
                                                        lastAssociationMessage = if (retainedStaleMetadata) {
                                                            "Não foi possível atualizar os metadados do AniList agora; os dados locais foram mantidos e marcados como desatualizados."
                                                        } else {
                                                            "Metadados AniList atualizados."
                                                        },
                                                        associationRevision = _aniListUiState.value.associationRevision + 1,
                                                    )
                                                }
                                                is AniListResult.Failure -> _aniListUiState.value =
                                                    _aniListUiState.value.copy(
                                                        isMetadataRefreshLoading = false,
                                                        error = refreshed.error.userMessage(),
                                                    )
                                            }
                                        }
                                        is AniListAutoMatchResult.Ambiguous -> _aniListUiState.value =
                                            _aniListUiState.value.copy(
                                                isMetadataRefreshLoading = false,
                                                error = null,
                                                results = match.candidates,
                                                lastAssociationMessage =
                                                    "Foram encontradas várias correspondências possíveis. Revise as opções AniList antes de associar.",
                                                associationRevision = _aniListUiState.value.associationRevision + 1,
                                            )
                                        AniListAutoMatchResult.NoMatch -> _aniListUiState.value =
                                            _aniListUiState.value.copy(
                                                isMetadataRefreshLoading = false,
                                                error = null,
                                                lastAssociationMessage = "Nenhuma correspondência AniList confiável foi encontrada.",
                                                associationRevision = _aniListUiState.value.associationRevision + 1,
                                            )
                                        AniListAutoMatchResult.RecentlyAttempted -> _aniListUiState.value =
                                            _aniListUiState.value.copy(
                                                isMetadataRefreshLoading = false,
                                                error = null,
                                                lastAssociationMessage = "A correspondência AniList foi tentada recentemente. Tente novamente mais tarde.",
                                            )
                                        AniListAutoMatchResult.Disabled -> _aniListUiState.value =
                                            _aniListUiState.value.copy(
                                                isMetadataRefreshLoading = false,
                                                error = "O AniList está desativado nas configurações.",
                                            )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                if (requestId == metadataRefreshRequestId) {
                    _aniListUiState.value = _aniListUiState.value.copy(isMetadataRefreshLoading = false)
                }
                throw cancelled
            } catch (error: Exception) {
                if (requestId == metadataRefreshRequestId) {
                    _aniListUiState.value = _aniListUiState.value.copy(
                        isMetadataRefreshLoading = false,
                        error = error.message?.takeIf(String::isNotBlank)
                            ?: "Não foi possível atualizar os metadados AniList.",
                    )
                }
            } finally {
                if (requestId == metadataRefreshRequestId && _aniListUiState.value.isMetadataRefreshLoading) {
                    _aniListUiState.value = _aniListUiState.value.copy(isMetadataRefreshLoading = false)
                }
            }
        }
    }

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
                    is AniListResult.Success -> if (isCurrentAniListSearchRequest(requestId, searchRequestId)) {
                        _aniListUiState.value = _aniListUiState.value.copy(
                            isSearchLoading = false, results = result.value, error = null,
                        )
                    }
                    is AniListResult.Failure -> if (isCurrentAniListSearchRequest(requestId, searchRequestId)) {
                        _aniListUiState.value = _aniListUiState.value.copy(
                            isSearchLoading = false, results = emptyList(), error = result.error.userMessage(),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                if (isCurrentAniListSearchRequest(requestId, searchRequestId)) {
                    _aniListUiState.value = _aniListUiState.value.copy(isSearchLoading = false)
                }
                throw cancelled
            } catch (error: Exception) {
                if (isCurrentAniListSearchRequest(requestId, searchRequestId)) {
                    _aniListUiState.value = _aniListUiState.value.copy(
                        isSearchLoading = false, results = emptyList(),
                        error = error.message?.takeIf(String::isNotBlank)
                            ?: "Não foi possível pesquisar no AniList.",
                    )
                }
            } finally {
                if (isCurrentAniListSearchRequest(requestId, searchRequestId) && _aniListUiState.value.isSearchLoading) {
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


/**
 * Guard used by every AniList search completion path: cancellation/failure of an
 * older query must not overwrite the state owned by the latest query.
 */
internal fun isCurrentAniListSearchRequest(
    requestId: Long,
    currentRequestId: Long,
): Boolean = requestId == currentRequestId
