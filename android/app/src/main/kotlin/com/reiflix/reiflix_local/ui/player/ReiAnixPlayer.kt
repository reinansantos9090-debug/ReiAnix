package com.reiflix.reiflix_local.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.reiflix.reiflix_local.ui.navigation.ReiAnixPlayerArgs
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel

/**
 * Compose navigation boundary for the existing native Media3 player.
 *
 * Compose owns only the stable route identity. The existing Python/SQLite
 * command boundary resolves the canonical episode row and launches the native
 * player Activity; Media3 ownership stays exclusively in that Activity.
 */
@Composable
fun ReiAnixPlayerRoute(
    navController: NavHostController,
    viewModel: ReiAnixLibraryViewModel,
    args: ReiAnixPlayerArgs,
) {
    val episodeId = args.episodeId.trim().toLongOrNull()
    val animeId = args.animeId.trim()
    val lifecycleOwner = LocalLifecycleOwner.current
    val libraryState by viewModel.uiState.collectAsStateWithLifecycle()

    var launchRequestId by rememberSaveable(args.episodeId, args.animeId) {
        mutableStateOf<String?>(null)
    }
    var hostStoppedAfterLaunch by rememberSaveable(args.episodeId, args.animeId) {
        mutableStateOf(false)
    }

    val commandStatus = launchRequestId
        ?.takeIf { it == libraryState.lastCommandId }
        ?.let { libraryState.lastCommandStatus?.trim()?.uppercase() }
    val launchFailed = commandStatus == "FAILED"
    val canLeave = episodeId == null || animeId.isBlank() || launchFailed

    LaunchedEffect(args.episodeId, args.animeId) {
        val canonicalEpisodeId = episodeId ?: return@LaunchedEffect
        if (animeId.isBlank() || launchRequestId != null) return@LaunchedEffect
        launchRequestId = viewModel.openEpisode(canonicalEpisodeId)
    }

    val currentLaunchRequestId by rememberUpdatedState(launchRequestId)
    val currentLaunchFailed by rememberUpdatedState(launchFailed)

    DisposableEffect(lifecycleOwner, args.episodeId, args.animeId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (currentLaunchRequestId != null && !currentLaunchFailed) {
                        hostStoppedAfterLaunch = true
                    }
                }

                Lifecycle.Event.ON_RESUME -> {
                    if (hostStoppedAfterLaunch && currentLaunchRequestId != null) {
                        hostStoppedAfterLaunch = false
                        navController.popBackStack()
                    }
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Prevent a fast Back press from popping the route while the handoff is
    // pending; otherwise the native player could start after Details/Home
    // has already become visible again.
    BackHandler(enabled = canLeave) {
        navController.popBackStack()
    }

    when {
        episodeId == null || animeId.isBlank() -> {
            PlayerHandoffMessage(
                title = "Não foi possível abrir o player",
                message = "A identificação do episódio local é inválida.",
                showProgress = false,
            )
        }

        launchFailed -> {
            PlayerHandoffMessage(
                title = "Não foi possível abrir o episódio",
                message = libraryState.lastCommandError
                    ?: "A mídia local não pôde ser enviada ao player.",
                showProgress = false,
            )
        }

        else -> {
            PlayerHandoffMessage(
                title = "Abrindo player",
                message = "Preparando a reprodução local…",
                showProgress = true,
            )
        }
    }
}

@Composable
private fun PlayerHandoffMessage(
    title: String,
    message: String,
    showProgress: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ReiAnixTokens.Colors.background)
            .semantics {
                contentDescription = "ReiAnixPlayerHandoff"
            }
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showProgress) {
            CircularProgressIndicator()
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = ReiAnixTokens.Colors.text,
            modifier = Modifier.padding(top = if (showProgress) 16.dp else 0.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = ReiAnixTokens.Colors.textMuted,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
