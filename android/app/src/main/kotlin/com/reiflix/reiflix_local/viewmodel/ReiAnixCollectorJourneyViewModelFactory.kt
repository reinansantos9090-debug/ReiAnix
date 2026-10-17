package com.reiflix.reiflix_local.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.reiflix.reiflix_local.ReiAnixAppDependencies

class ReiAnixCollectorJourneyViewModelFactory(
    context: Context,
) : ViewModelProvider.Factory {
    private val appContext = context.applicationContext

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ReiAnixCollectorJourneyViewModel::class.java)) {
            "Unsupported ViewModel: " + modelClass.name
        }
        return ReiAnixCollectorJourneyViewModel(
            ReiAnixAppDependencies.from(appContext),
        ) as T
    }
}
