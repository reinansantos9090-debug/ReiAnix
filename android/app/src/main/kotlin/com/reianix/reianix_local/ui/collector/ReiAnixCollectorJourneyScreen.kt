package com.reianix.reianix_local.ui.collector

import com.reianix.reianix_local.R

import androidx.compose.ui.res.stringResource

import com.reianix.reianix_local.ui.motion.reiAnixScreenFade
import com.reianix.reianix_local.ui.theme.reiAnixContentBackground

import com.reianix.reianix_local.ui.theme.reiAnixScreenBackground

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
internal fun ReiAnixCollectorJourneyScreen(
    state: com.reianix.reianix_local.viewmodel.ReiAnixCollectorJourneyUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectTitle: (String) -> Unit,
    showBack: Boolean = true,
) {
    var page by rememberSaveable { mutableStateOf("menu") }
    fun back() { if (page == "menu") onBack() else page = "menu" }
    BackHandler(enabled = page != "menu") { back() }
    val pageFade = Modifier.reiAnixScreenFade(page)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(reiAnixScreenBackground())
            .then(pageFade),
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
            if (showBack || page != "menu") {
                IconButton(onClick = { back() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.ui_back),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (page) {
                        "stats" -> stringResource(R.string.finish_journey_stats)
                        "activity" -> stringResource(R.string.finish_journey_my_activity)
                        "titles" -> stringResource(R.string.more_journey_titles)
                        "achievements" -> stringResource(R.string.more_achievements)
                        else -> "Jornada do Colecionador"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (state.isLoading && state.journey != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
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
                    if (state.journey != null) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onRefresh, enabled = !state.isLoading && !state.isSaving) { Text(stringResource(R.string.ui_retry)) }
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
                    Text(stringResource(R.string.more_library_read))
                } else {
                    Text(stringResource(R.string.more_journey_unavailable), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.more_journey_error),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRefresh) { Text(stringResource(R.string.ui_retry)) }
                }
            }
            return
        }

        key(page) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (page) {
                    "stats" -> {
            item {
                MetricCard(
                    firstLabel = stringResource(R.string.finish_journey_completed),
                    firstValue = journey.animesCompleted.toString(),
                    secondLabel = stringResource(R.string.profile_completed_count),
                    secondValue = journey.episodesCompleted.toString(),
                )
            }
            item {
                MetricCard(
                    firstLabel = stringResource(R.string.finish_journey_days),
                    firstValue = journey.activeDays.toString(),
                    secondLabel = stringResource(R.string.finish_journey_genres),
                    secondValue = journey.genresExplored.toString(),
                )
            }
            item {
                MetricCard(
                    firstLabel = stringResource(R.string.finish_journey_decades),
                    firstValue = journey.decadesExplored.toString(),
                    secondLabel = stringResource(R.string.more_achievements),
                    secondValue = journey.achievementsUnlocked.toString() + "/" + journey.achievementsTotal,
                )
            }
            item {
                MetricCard(
                    firstLabel = stringResource(R.string.finish_journey_hours),
                    firstValue = (journey.completedDurationSeconds / 3600L).toString() + " h",
                    secondLabel = stringResource(R.string.finish_journey_formats),
                    secondValue = journey.formatsExplored.toString(),
                )
            }
                    }
                    "activity" -> {
            item {
                Text(stringResource(R.string.more_journey_activity),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.more_journey_activity_desc),
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
                    }
                    "titles" -> {
            item {
                Text(stringResource(R.string.more_journey_titles), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.more_journey_title_hint),
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
                        else reiAnixContentBackground(MaterialTheme.colorScheme.surface),
                        contentColor = MaterialTheme.colorScheme.onSurface,
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
                                        state.isSaving && title.id == journey.activeTitle -> stringResource(R.string.flow_saving)
                                        title.id == journey.activeTitle -> stringResource(R.string.finish_journey_active_title)
                                        else -> stringResource(R.string.finish_journey_select_title)
                                    },
                                )
                            }
                        } else {
                            Text(
                                stringResource(R.string.more_locked),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
                    }
                    "achievements" -> {
            item {
                Text(stringResource(R.string.more_achievements), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            items(journey.achievements) { achievement ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = reiAnixContentBackground(MaterialTheme.colorScheme.surface), contentColor = MaterialTheme.colorScheme.onSurface),
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
                    }
                    else -> {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                    containerColor = reiAnixContentBackground(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f)),
                    contentColor = MaterialTheme.colorScheme.onSurface)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                        Text(stringResource(R.string.finish_journey_level, journey.level.toString()), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.finish_journey_xp, journey.xp.toString()), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(progress = { journey.levelProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(journey.xpIntoLevel.toString() + " / " + (journey.xpIntoLevel + journey.xpToNext) + " XP", style = MaterialTheme.typography.labelSmall)
                            Text(stringResource(R.string.more_next_level), style = MaterialTheme.typography.labelSmall)
                        }
                        Text(stringResource(R.string.finish_journey_title_value, journey.activeTitleLabel ?: stringResource(R.string.finish_journey_no_title)),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    JourneySummaryCard(Icons.Outlined.EmojiEvents, journey.achievementsUnlocked.toString() + "/" + journey.achievementsTotal,
                        stringResource(R.string.more_achievements), Color(0xFFFFC768), Modifier.weight(1f)) { page = "achievements" }
                    JourneySummaryCard(Icons.Outlined.CalendarToday, journey.activeDays.toString(),
                        stringResource(R.string.finish_journey_days), MaterialTheme.colorScheme.primary, Modifier.weight(1f)) { page = "activity" }
                }
            }
            item {
                JourneyCategory(Icons.Outlined.BarChart, stringResource(R.string.finish_journey_stats), stringResource(R.string.finish_journey_evolution)) { page = "stats" }
                JourneyCategory(Icons.Outlined.ShowChart, stringResource(R.string.finish_journey_my_activity), stringResource(R.string.more_journey_activity)) { page = "activity" }
                JourneyCategory(Icons.Outlined.MilitaryTech, stringResource(R.string.more_journey_titles), stringResource(R.string.finish_journey_equip)) { page = "titles" }
                JourneyCategory(Icons.Outlined.EmojiEvents, stringResource(R.string.more_achievements),
                    stringResource(R.string.finish_journey_unlock_count, journey.achievementsUnlocked.toString(), journey.achievementsTotal.toString())) { page = "achievements" }
            }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
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
        colors = CardDefaults.cardColors(containerColor = reiAnixContentBackground(MaterialTheme.colorScheme.surface), contentColor = MaterialTheme.colorScheme.onSurface),
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

@Composable
private fun JourneyCategory(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun JourneySummaryCard(icon: ImageVector, value: String, label: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick), colors = CardDefaults.cardColors(
        containerColor = reiAnixContentBackground(MaterialTheme.colorScheme.surfaceContainer),
        contentColor = MaterialTheme.colorScheme.onSurface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
