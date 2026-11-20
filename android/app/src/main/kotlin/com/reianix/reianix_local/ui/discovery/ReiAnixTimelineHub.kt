package com.reianix.reianix_local.ui.discovery

import com.reianix.reianix_local.ui.motion.reiAnixScreenFade
import com.reianix.reianix_local.ui.theme.reiAnixScreenBackground

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.reianix.reianix_local.data.library.ReiAnixLibraryDiscovery
import com.reianix.reianix_local.ui.ReiAnixTopBar
import com.reianix.reianix_local.ui.collections.ReiAnixRouletteTab
import com.reianix.reianix_local.ui.collector.ReiAnixCollectorJourneyTab
import com.reianix.reianix_local.ui.navigation.ReiAnixRoutes
import com.reianix.reianix_local.ui.navigation.navigateToDetails
import com.reianix.reianix_local.ui.navigation.navigateToPlayer
import com.reianix.reianix_local.ui.theme.LocalReiAnixResponsiveMetrics
import com.reianix.reianix_local.ui.theme.ReiAnixResponsiveRoot
import com.reianix.reianix_local.viewmodel.ReiAnixLibraryViewModel
import kotlinx.coroutines.CancellationException

/**
 * Full-screen Timeline hub with three independent tabs. The anime timeline
 * query runs only on demand, the roulette uses the canonical in-memory catalog,
 * and the Collector uses its existing ViewModel and local achievement data.
 */
@Composable
fun ReiAnixTimelineHubRoute(
    navController: NavHostController,
    viewModel: ReiAnixLibraryViewModel,
    initialSection: String,
) {
    val allowedSections = listOf("animes", "roleta", "colecionador")
    var selectedTab by rememberSaveable(initialSection) {
        mutableStateOf(initialSection.takeIf(allowedSections::contains) ?: "animes")
    }

    var groups by remember(viewModel) {
        mutableStateOf<List<ReiAnixLibraryDiscovery.TimelineGroup>?>(null)
    }
    var loading by remember(viewModel) { mutableStateOf(true) }
    var error by remember(viewModel) { mutableStateOf<String?>(null) }
    var retryToken by remember(viewModel) { mutableIntStateOf(0) }
    var loadedToken by remember(viewModel) { mutableIntStateOf(-1) }

    LaunchedEffect(viewModel, selectedTab, retryToken) {
        if (selectedTab != "animes" || loadedToken == retryToken) return@LaunchedEffect
        loading = true
        error = null
        try {
            groups = viewModel.queryNativeTimelineGroups()
            loadedToken = retryToken
        } catch (cancelled: CancellationException) {
            // Switching tabs cancels this read. Do not mark a partial result as loaded.
            throw cancelled
        } catch (failure: Exception) {
            groups = null
            error = failure.message?.takeIf(String::isNotBlank)
                ?: "Não foi possível carregar a linha do tempo local."
            loadedToken = retryToken
        } finally {
            loading = false
        }
    }

    ReiAnixResponsiveRoot {
        Column(
            modifier = Modifier.fillMaxSize().background(reiAnixScreenBackground()),
        ) {
            ReiAnixTopBar(
                title = "Linha do tempo",
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onNavigationClick = { navController.popBackStack() },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "animes" to "Animes",
                    "roleta" to "Roleta",
                    "colecionador" to "Colecionador",
                ).forEach { (section, label) ->
                    FilterChip(
                        selected = selectedTab == section,
                        onClick = { selectedTab = section },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().weight(1f).reiAnixScreenFade(selectedTab)) {
                when (selectedTab) {
                    "animes" -> {
                        val homeState by viewModel.homeState.collectAsStateWithLifecycle()
                        val artworkByAnimeId = remember(homeState.animes) {
                            homeState.animes.associate { it.id to it.artwork }
                        }
                        when {
                            loading -> CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                            )
                            error != null -> Column(
                                modifier = Modifier.align(Alignment.Center).padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                                Button(onClick = { retryToken++ }) { Text("Tentar novamente") }
                            }
                            groups.orEmpty().isEmpty() -> Text(
                                text = "A biblioteca ainda não possui itens para a linha do tempo.",
                                modifier = Modifier.align(Alignment.Center).padding(20.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            else -> {
                                ReiAnixTimelineList(
                                    groups = groups.orEmpty(),
                                    artworkByAnimeId = artworkByAnimeId,
                                    modifier = Modifier.fillMaxSize().padding(
                                        horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                                    ),
                                    onOpenAnime = { animeId ->
                                        navController.navigateToDetails(
                                            animeId = animeId.toString(),
                                            origin = ReiAnixRoutes.HOME,
                                        )
                                    },
                                )
                            }
                        }
                    }
                    "roleta" -> {
                        val catalog by viewModel.uiState.collectAsStateWithLifecycle()
                        ReiAnixRouletteTab(
                            viewModel = viewModel,
                            animes = catalog.animes,
                            onOpenAnime = { animeId ->
                                navController.navigateToDetails(
                                    animeId = animeId.toString(),
                                    origin = ReiAnixRoutes.HOME,
                                )
                            },
                            onWatch = { episodeId, animeId ->
                                navController.navigateToPlayer(
                                    episodeId = episodeId.toString(),
                                    animeId = animeId.toString(),
                                    origin = ReiAnixRoutes.HOME,
                                )
                            },
                        )
                    }
                    "colecionador" -> ReiAnixCollectorJourneyTab(
                        libraryViewModel = viewModel,
                    )
                }
            }
        }
    }
}
