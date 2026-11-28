package com.reianix.reianix_local.ui.player

import com.reianix.reianix_local.R

import androidx.compose.ui.res.stringResource

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.reianix.reianix_local.ui.ReiAnixRecoverableErrorState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.reianix.reianix_local.ui.navigation.ReiAnixPlayerArgs
import com.reianix.reianix_local.ui.theme.ReiAnixTokens
import com.reianix.reianix_local.viewmodel.ReiAnixLibraryViewModel
import kotlinx.coroutines.flow.first

/**
 * Compose navigation boundary for the existing native Media3 player.
 *
 * Compose passes the stable episode identity to the native ViewModel. The ViewModel
 * resolves the canonical SQLite row and requests MainActivity's existing native
 * handoff directly; Media3 ownership stays exclusively in NativePlayerActivity.
 */
@Composable
fun ReiAnixPlayerRoute(
    navController: NavHostController,
    viewModel: ReiAnixLibraryViewModel,
    args: ReiAnixPlayerArgs,
) {
    val episodeId = args.episodeId.trim().toLongOrNull()
    val animeId = args.animeId.trim()
    val canonicalAnimeId = animeId.toLongOrNull()
    val lifecycleOwner = LocalLifecycleOwner.current
    val libraryState by viewModel.uiState.collectAsStateWithLifecycle()

    var launchRequestId by rememberSaveable(args.episodeId, args.animeId) {
        mutableStateOf<String?>(null)
    }
    var hostStoppedAfterLaunch by rememberSaveable(args.episodeId, args.animeId) {
        mutableStateOf(false)
    }

    var returnedAfterLaunch by rememberSaveable(args.episodeId, args.animeId) {
        mutableStateOf(false)
    }
    fun leaveAfterLaunch() {
        if (!returnedAfterLaunch) {
            returnedAfterLaunch = true
            navController.popBackStack()
        }
    }

    val commandStatus = launchRequestId
        ?.takeIf { it == libraryState.lastCommandId }
        ?.let { libraryState.lastCommandStatus?.trim()?.uppercase() }
    val launchFailed = commandStatus == "FAILED"
    val externalChooserOpened = commandStatus == "COMPLETED" &&
        libraryState.lastCommandAction == "external_player_handoff"
    val canLeave = episodeId == null || canonicalAnimeId == null || launchFailed || externalChooserOpened

    // The system resolver can be translucent and never stop the host Activity.
    // Leave the temporary handoff route once the chooser has been launched.
    LaunchedEffect(externalChooserOpened) {
        if (externalChooserOpened) leaveAfterLaunch()
    }

    LaunchedEffect(args.episodeId, args.animeId) {
        val canonicalEpisodeId = episodeId ?: return@LaunchedEffect
        val expectedAnimeId = canonicalAnimeId ?: return@LaunchedEffect
        if (launchRequestId != null) return@LaunchedEffect
        launchRequestId = viewModel.openEpisode(
            canonicalEpisodeId,
            expectedAnimeId = expectedAnimeId,
        )
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
                        leaveAfterLaunch()
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
    BackHandler {
        if (canLeave) {
            navController.popBackStack()
        }
    }

    when {
        episodeId == null || canonicalAnimeId == null -> {
            PlayerHandoffMessage(
                title = stringResource(R.string.more_player_open_failed),
                message = stringResource(R.string.more_episode_id_invalid),
                onRetry = null,
            )
        }

        launchFailed -> {
            PlayerHandoffMessage(
                title = stringResource(R.string.more_episode_open_failed),
                message = libraryState.lastCommandError
                    ?: stringResource(R.string.polish_handoff_error),
                onRetry = {
                    launchRequestId = viewModel.openEpisode(
                        episodeId,
                        expectedAnimeId = canonicalAnimeId,
                    )
                },
            )
        }

        else -> Unit
    }
}

@Composable
private fun PlayerHandoffMessage(
    title: String,
    message: String,
    onRetry: (() -> Unit)?,
) {
    if (onRetry != null) {
        ReiAnixRecoverableErrorState(
            title = title,
            message = message,
            onRetry = onRetry,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .semantics {
                    contentDescription = "$title. $message"
                },
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .semantics {
                    contentDescription = "$title. $message"
                }
                .padding(ReiAnixTokens.Spacing.xxl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = ReiAnixTokens.Spacing.sm),
            )
        }
    }
}

/** Opens the native activity directly, keeping the current screen in the back stack. */
@Composable
internal fun rememberEpisodeLauncher(viewModel: ReiAnixLibraryViewModel): (Long, Long) -> Unit {
    var requestId by remember(viewModel) { mutableStateOf<String?>(null) }
    var lastEpisode by remember(viewModel) { mutableStateOf<Pair<Long, Long>?>(null) }
    var launchError by remember(viewModel) { mutableStateOf<String?>(null) }
    val launch = remember(viewModel) {
        { episodeId: Long, animeId: Long ->
            if (requestId == null) {
                requestId = viewModel.openEpisode(episodeId, expectedAnimeId = animeId)
                if (requestId != null) lastEpisode = episodeId to animeId
            }
        }
    }

    val handoffError = stringResource(R.string.polish_handoff_error)
    val activeRequest = requestId
    if (activeRequest != null) {
        BackHandler { /* Keep the current screen while native launch is pending. */ }
        DisposableEffect(viewModel, activeRequest) {
            onDispose { viewModel.cancelNativePlayerHandoff(activeRequest) }
        }
        LaunchedEffect(viewModel, activeRequest) {
            val result = viewModel.uiState.first { state -> state.lastCommandId == activeRequest }
            if (result.lastCommandStatus == "FAILED") {
                launchError = result.lastCommandError ?: handoffError
            }
            requestId = null
        }
    }

    launchError?.let { message ->
        AlertDialog(
            onDismissRequest = { launchError = null },
            title = { Text(stringResource(R.string.more_episode_open_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    launchError = null
                    lastEpisode?.let { (episodeId, animeId) -> launch(episodeId, animeId) }
                }) { Text(stringResource(R.string.ui_retry)) }
            },
            dismissButton = {
                TextButton(onClick = { launchError = null }) { Text(stringResource(R.string.ui_close)) }
            },
        )
    }
    return launch
}
