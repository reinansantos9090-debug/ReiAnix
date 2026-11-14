package com.reiflix.reiflix_local.viewmodel

import android.content.Context
import com.reiflix.reiflix_local.ReiAnixAppDependencies
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class ReiAnixLibraryViewModelFactory(
    context: Context,
    private val dependencies: ReiAnixAppDependencies? = ReiAnixAppDependencies.existingOrNull(context),
) : ViewModelProvider.Factory {
    private val appContext = context.applicationContext

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ReiAnixLibraryViewModel::class.java)) {
            "Unsupported ViewModel: ${modelClass.name}"
        }
        return ReiAnixLibraryViewModel(appContext, dependencies) as T
    }
}
