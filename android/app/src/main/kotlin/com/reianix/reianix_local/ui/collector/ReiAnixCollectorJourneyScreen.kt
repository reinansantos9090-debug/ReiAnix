package com.reianix.reianix_local.ui.collector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.reianix.reianix_local.ui.library.rememberReiAnixLibraryViewModel
import com.reianix.reianix_local.ui.navigation.ReiAnixRoutes
import com.reianix.reianix_local.ui.navigation.navigateToTopLevel
import com.reianix.reianix_local.viewmodel.ReiAnixCollectorJourneyViewModel
import com.reianix.reianix_local.viewmodel.ReiAnixCollectorJourneyViewModelFactory
import com.reianix.reianix_local.viewmodel.ReiAnixLibraryViewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private data class CollectorCatalogSignal(
    val id: Long,
    val year: Int?,
    val genres: List<String>,
    val watchedEpisodes: Int,
    val availableEpisodes: Int,
)

@Composable
fun ReiAnixCollectorJourneyRoute(
    navController: NavHostController,
    libraryViewModel: ReiAnixLibraryViewModel = rememberReiAnixLibraryViewModel(),
) {
    CollectorJourneyObserved(
        libraryViewModel = libraryViewModel,
        showBack = true,
        onBack = {
            if (!navController.popBackStack()) {
                navController.navigateToTopLevel(ReiAnixRoutes.HOME)
            }
        },
    )
}

/**
 * Embedded version uses the exact same ViewModel, SQLite-backed progress and
 * event-driven refresh as the standalone Collector destination.
 */
@Composable
fun ReiAnixCollectorJourneyTab(
    libraryViewModel: ReiAnixLibraryViewModel,
) {
    CollectorJourneyObserved(
        libraryViewModel = libraryViewModel,
        showBack = false,
        onBack = {},
    )
}

@Composable
private fun CollectorJourneyObserved(
    libraryViewModel: ReiAnixLibraryViewModel,
    showBack: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: ReiAnixCollectorJourneyViewModel = viewModel(
        factory = ReiAnixCollectorJourneyViewModelFactory(context),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Artwork, episode progress seconds and sort-order updates must not restart
    // the Collector's SQLite read or visibly replace its header every few seconds.
    // Only changes relevant to collection stats/unlocks trigger a refresh.
    LaunchedEffect(libraryViewModel, viewModel) {
        val catalogSignals = libraryViewModel.uiState.map { state ->
            state.animes.map { anime ->
                CollectorCatalogSignal(
                    id = anime.id,
                    year = anime.year,
                    genres = anime.genres.map { it.name },
                    watchedEpisodes = anime.contentEpisodes.count { it.isWatched },
                    availableEpisodes = anime.availableContentCount,
                )
            }
        }.distinctUntilChanged()
        val activitySignals = libraryViewModel.continueWatching.map { episodes ->
            episodes.map { item ->
                // Ignore per-second playback progress; only first activity and
                // which episode is active may matter for Collector achievements.
                item.episodeId to ((item.progressSeconds ?: 0.0) > 0.0)
            }
        }.distinctUntilChanged()
        combine(catalogSignals, activitySignals) { catalog, activity ->
            catalog to activity
        }.distinctUntilChanged().collect {
            viewModel.refresh()
        }
    }

    ReiAnixCollectorJourneyScreen(
        state = state,
        onBack = onBack,
        showBack = showBack,
        onRefresh = viewModel::refresh,
        onSelectTitle = viewModel::selectActiveTitle,
    )
}

@Composable
private fun ReiAnixCollectorJourneyScreen(
    state: com.reianix.reianix_local.viewmodel.ReiAnixCollectorJourneyUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectTitle: (String) -> Unit,
    showBack: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (showBack) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    else Modifier,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Voltar",
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Jornada do Colecionador",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Progresso derivado da biblioteca local",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Stable dimensions: swapping a button for loading text caused
            // the layout to jump on every async refresh.
            OutlinedButton(
                onClick = onRefresh,
                enabled = !state.isLoading && !state.isSaving,
            ) { Text("Atualizar") }
        }

        if (state.error != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(state.error, color = MaterialTheme.colorScheme.onErrorContainer)
                    if (state.journey == null) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onRefresh) { Text("Tentar novamente") }
                    }
                }
            }
        }

        val journey = state.journey
        if (journey == null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (state.isLoading) {
                    androidx.compose.material3.CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Consultando a biblioteca local…")
                } else {
                    Text("A jornada está indisponível", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Os dados não foram substituídos por valores vazios. Atualize para tentar novamente.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRefresh) { Text("Atualizar") }
                }
            }
            return
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("NÍVEL " + journey.level, style = MaterialTheme.typography.labelMedium)
                        Text(
                            journey.xp.toString() + " XP de jornada",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            journey.xpIntoLevel.toString() + "/10 episódios concluídos para o próximo nível",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { journey.levelProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "1 XP equivale a 1 episódio local concluído. XP e níveis não são salvos separadamente.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Título ativo: " + (journey.activeTitleLabel ?: "Nenhum título desbloqueado"),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            item {
                Text("Estatísticas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            item {
                MetricCard(
                    firstLabel = "Animes concluídos",
                    firstValue = journey.animesCompleted.toString(),
                    secondLabel = "Episódios concluídos",
                    secondValue = journey.episodesCompleted.toString(),
                )
            }
            item {
                MetricCard(
                    firstLabel = "Dias ativos",
                    firstValue = journey.activeDays.toString(),
                    secondLabel = "Gêneros explorados",
                    secondValue = journey.genresExplored.toString(),
                )
            }
            item {
                MetricCard(
                    firstLabel = "Décadas exploradas",
                    firstValue = journey.decadesExplored.toString(),
                    secondLabel = "Conquistas",
                    secondValue = journey.achievementsUnlocked.toString() + "/" + journey.achievementsTotal,
                )
            }
            item {
                MetricCard(
                    firstLabel = "Horas concluídas",
                    firstValue = (journey.completedDurationSeconds / 3600L).toString() + " h",
                    secondLabel = "Formatos explorados",
                    secondValue = journey.formatsExplored.toString(),
                )
            }
            item {
                Text("Atividade nos últimos 6 meses",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Dias em que houve reprodução ou progresso registrado.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(journey.monthlyActivity, key = { it.month }) { month ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(month.month, style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(
                        progress = { (month.activeDays / 31f).coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f),
                    )
                    Text(month.activeDays.toString() + " dias",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            item {
                Text("Títulos do colecionador", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Escolha apenas entre títulos realmente desbloqueados. A seleção é confirmada no SQLite antes de atualizar a interface.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(journey.titles) { title ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (title.id == journey.activeTitle)
                            MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(title.title, fontWeight = FontWeight.SemiBold)
                        Text(
                            title.condition,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        if (title.unlocked) {
                            OutlinedButton(
                                onClick = { onSelectTitle(title.id) },
                                enabled = !state.isSaving && title.id != journey.activeTitle,
                            ) {
                                Text(
                                    when {
                                        state.isSaving && title.id == journey.activeTitle -> "Salvando…"
                                        title.id == journey.activeTitle -> "Título ativo"
                                        else -> "Selecionar título"
                                    },
                                )
                            }
                        } else {
                            Text(
                                "Bloqueado",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            item {
                Text("Conquistas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            items(journey.achievements) { achievement ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (achievement.unlocked) "✓" else "○",
                                color = if (achievement.unlocked) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(achievement.title, fontWeight = FontWeight.SemiBold)
                                Text(
                                    achievement.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                achievement.value.toString() + "/" + achievement.target,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = {
                                (achievement.progress.toDouble() / achievement.target.coerceAtLeast(1L).toDouble())
                                    .toFloat().coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            achievement.category + if (achievement.unlocked) " · Desbloqueada" else " · Em progresso",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun MetricCard(
    firstLabel: String,
    firstValue: String,
    secondLabel: String,
    secondValue: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(firstLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(firstValue, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(secondLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(secondValue, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}
