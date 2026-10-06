package com.reiflix.reiflix_local.viewmodel

import androidx.annotation.Keep
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Base contract for future screen ViewModels.
 *
 * ViewModels expose immutable StateFlow to the UI and may use viewModelScope for
 * cancellable work. earlier validation stage 01 deliberately does not create domain state or a
 * duplicate Kotlin data layer.
 */
@Keep
abstract class ReiAnixViewModel<State : Any> : ViewModel() {
    abstract val uiState: StateFlow<State>
}
