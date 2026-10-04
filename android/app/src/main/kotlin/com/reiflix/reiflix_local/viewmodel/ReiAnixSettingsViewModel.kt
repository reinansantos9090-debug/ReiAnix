package com.reiflix.reiflix_local.viewmodel

import android.content.Context
import androidx.annotation.Keep
import androidx.lifecycle.viewModelScope
import com.reiflix.reiflix_local.data.settings.ReiAnixSettingsRepository
import com.reiflix.reiflix_local.ui.model.ReiAnixSettingsUiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Keep
class ReiAnixSettingsViewModel(context: Context) :
    ReiAnixViewModel<ReiAnixSettingsUiState>() {

    private val repository = ReiAnixSettingsRepository(context)
    override val uiState: StateFlow<ReiAnixSettingsUiState> = repository.state

    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }

    override fun onCleared() {
        repository.close()
        super.onCleared()
    }
}
