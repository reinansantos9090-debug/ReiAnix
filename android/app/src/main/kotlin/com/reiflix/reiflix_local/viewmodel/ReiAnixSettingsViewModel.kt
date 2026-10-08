package com.reiflix.reiflix_local.viewmodel

import android.content.Context
import androidx.annotation.Keep
import androidx.lifecycle.viewModelScope
import com.reiflix.reiflix_local.data.settings.ReiAnixSettingsRepository
import com.reiflix.reiflix_local.ui.model.ReiAnixSettingsUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Keep
class ReiAnixSettingsViewModel(context: Context) :
    ReiAnixViewModel<ReiAnixSettingsUiState>() {

    private val repository = ReiAnixSettingsRepository(context)
    override val uiState: StateFlow<ReiAnixSettingsUiState> = repository.state

    /**
     * Narrow projections used by the persistent Compose shell. The Settings
     * screen still consumes the full canonical state, while unrelated setting
     * mutations do not force Home/Library/theme composition to rebuild.
     */
    val themeMode: StateFlow<String> = uiState
        .map { it.settings["appearance.theme"] ?: "dark" }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), "dark")

    val appearanceCardSize: StateFlow<String> = uiState
        .map { it.settings["appearance.card_size"] ?: "medium" }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), "medium")

    val appearanceShowThumbnails: StateFlow<Boolean> = uiState
        .map { it.settings["appearance.show_thumbnails"] == "true" }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), true)

    val libraryGridDensity: StateFlow<String> = uiState
        .map { it.settings["library.grid_density"] ?: "medium" }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), "medium")

    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }

    fun setSetting(key: String, value: String) {
        repository.setSetting(key, value)
    }

    fun requestAction(action: String) {
        repository.requestAction(action)
    }

    fun requestAccountAction(action: String) {
        repository.requestAccountAction(action)
    }

    override fun onCleared() {
        repository.close()
        super.onCleared()
    }
}