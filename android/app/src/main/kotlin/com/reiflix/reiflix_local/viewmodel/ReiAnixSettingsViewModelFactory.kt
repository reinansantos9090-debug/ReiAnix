package com.reiflix.reiflix_local.viewmodel

import android.content.Context
import com.reiflix.reiflix_local.ReiAnixAppDependencies
import androidx.annotation.Keep
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

@Keep
class ReiAnixSettingsViewModelFactory(
    private val context: Context,
    private val dependencies: ReiAnixAppDependencies? = ReiAnixAppDependencies.existingOrNull(context),
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ReiAnixSettingsViewModel::class.java)) {
            "Unsupported ViewModel: ${modelClass.name}"
        }
        return ReiAnixSettingsViewModel(context, dependencies) as T
    }
}
