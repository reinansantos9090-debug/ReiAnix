package com.reianix.reianix_local.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.reianix.reianix_local.ui.model.ReiAnixLibraryUiState
import com.reianix.reianix_local.viewmodel.ReiAnixLibraryViewModel
import com.reianix.reianix_local.viewmodel.ReiAnixLibraryViewModelFactory

/**
 * UI binding intentionally contains no library business rules.
 * State collection follows the Composable lifecycle.
 */
@Composable
fun ReiAnixLibraryRoute(
    viewModel: ReiAnixLibraryViewModel,
    content: @Composable (ReiAnixLibraryUiState) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    content(state)
}


@Composable
fun rememberReiAnixLibraryViewModel(): ReiAnixLibraryViewModel {
    val context = LocalContext.current
    return viewModel(
        factory = ReiAnixLibraryViewModelFactory(context),
    )
}
