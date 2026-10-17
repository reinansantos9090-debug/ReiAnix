package com.reiflix.reiflix_local.viewmodel

import android.util.Log
import androidx.annotation.Keep
import androidx.lifecycle.viewModelScope
import com.reiflix.reiflix_local.ReiAnixAppDependencies
import com.reiflix.reiflix_local.data.collector.ReiAnixCollectorJourney
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReiAnixCollectorJourneyUiState(
    val journey: ReiAnixCollectorJourney? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
)

@Keep
class ReiAnixCollectorJourneyViewModel(
    dependencies: ReiAnixAppDependencies,
) : ReiAnixViewModel<ReiAnixCollectorJourneyUiState>() {
    private val service = dependencies.collectorJourneyService
    private val mutableState = MutableStateFlow(ReiAnixCollectorJourneyUiState())
    override val uiState: StateFlow<ReiAnixCollectorJourneyUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isLoading = true, error = null)
            try {
                val result = service.loadJourney()
                mutableState.value = mutableState.value.copy(
                    journey = result,
                    isLoading = false,
                    error = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Collector Journey refresh failed", error)
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    error = "Não foi possível consultar os dados canônicos da jornada. Tente atualizar novamente.",
                )
            }
        }
    }

    fun selectActiveTitle(titleId: String) {
        if (mutableState.value.isSaving) return
        loadJob?.cancel()
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isLoading = false, isSaving = true, error = null)
            try {
                val updated = service.setActiveTitle(titleId)
                mutableState.value = mutableState.value.copy(
                    journey = updated,
                    isSaving = false,
                    error = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Collector Journey title selection failed", error)
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    error = "Não foi possível salvar o título selecionado. O estado confirmado foi preservado.",
                )
            }
        }
    }

    companion object {
        private const val TAG = "ReiAnixCollectorJourney"
    }
}
