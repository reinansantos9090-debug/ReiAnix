package com.reianix.reianix_local.ui.discovery

import androidx.compose.ui.platform.LocalContext

import com.reianix.reianix_local.R

import androidx.compose.ui.res.stringResource

import com.reianix.reianix_local.data.artwork.ReiAnixAdultArtworkPolicy

import androidx.compose.foundation.clickable
import androidx.compose.material3.contentColorFor
import com.reianix.reianix_local.ui.theme.LocalReiAnixWallpaperVisible
import com.reianix.reianix_local.ui.theme.reiAnixContentBackground
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.reianix.reianix_local.data.library.ReiAnixLibraryDiscovery
import com.reianix.reianix_local.ui.ReiAnixCompactButton
import com.reianix.reianix_local.ui.artwork.ReiAnixPoster
import com.reianix.reianix_local.ui.model.ReiAnixArtworkUiModel
import com.reianix.reianix_local.viewmodel.ReiAnixLibraryViewModel
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ReiAnixTimelineDialog(
    viewModel: ReiAnixLibraryViewModel,
    onDismiss: () -> Unit,
    onOpenAnime: (Long) -> Unit,
    artworkByAnimeId: Map<Long, ReiAnixArtworkUiModel?> = emptyMap(),
) {
    val context = LocalContext.current
    var groups by remember(viewModel) { mutableStateOf<List<ReiAnixLibraryDiscovery.TimelineGroup>?>(null) }
    var loading by remember(viewModel) { mutableStateOf(true) }
    var error by remember(viewModel) { mutableStateOf<String?>(null) }
    var retryToken by remember(viewModel) { mutableStateOf(0) }

    LaunchedEffect(viewModel, retryToken) {
        loading = true
        error = null
        try {
            groups = viewModel.queryNativeTimelineGroups()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            groups = null
            error = failure.message?.takeIf(String::isNotBlank)
                ?: context.getString(R.string.finish_timeline_error)
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.more_timeline)) },
        text = {
            DiscoveryDialogBody(
                loading = loading,
                error = error,
                empty = !loading && error == null && groups.orEmpty().isEmpty(),
                emptyMessage = stringResource(R.string.finish_timeline_empty),
            ) {
                ReiAnixTimelineList(
                    groups = groups.orEmpty(),
                    artworkByAnimeId = artworkByAnimeId,
                    onOpenAnime = onOpenAnime,
                )
            }
        },
        confirmButton = { ReiAnixCompactButton(text = stringResource(R.string.ui_close), onClick = onDismiss) },
        dismissButton = {
            if (error != null) ReiAnixCompactButton(text = stringResource(R.string.ui_retry), onClick = { retryToken++ })
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/**
 * Preserve the canonical year groups and anime IDs, but compose/decode only the
 * visible rows. Images are read from the Home's resolved local posters (or the
 * timeline SQLite cover_cache fallback); no metadata lookup or artwork download.
 */
@Composable
internal fun ReiAnixTimelineList(
    groups: List<ReiAnixLibraryDiscovery.TimelineGroup>,
    artworkByAnimeId: Map<Long, ReiAnixArtworkUiModel?>,
    onOpenAnime: (Long) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groups.forEach { group ->
            item(key = "year:${group.year ?: "unknown"}", contentType = "year") {
                Text(
                    group.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(
                items = group.items,
                key = { anime -> "anime:${anime.id}" },
                contentType = { "anime" },
            ) { anime ->
                val artwork = artworkByAnimeId[anime.id]
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onOpenAnime(anime.id) },
                    shape = MaterialTheme.shapes.medium,
                    color = reiAnixContentBackground(MaterialTheme.colorScheme.surfaceVariant),
                    contentColor = if (LocalReiAnixWallpaperVisible.current) MaterialTheme.colorScheme.onSurface
                        else contentColorFor(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ReiAnixPoster(
                            localPath = (artwork?.localPath ?: anime.coverCache).takeUnless { ReiAnixAdultArtworkPolicy.blockedJson(anime.genresJson) },
                            contentDescription = "Capa de ${anime.title.ifBlank { "Anime local" }}",
                            identity = "anime:${anime.id}",
                            modifier = Modifier.size(width = 44.dp, height = 62.dp),
                            maxDimensionPx = 160,
                            sourceRevisions = artwork?.sourceRevisions.orEmpty(),
                            // Timeline is a read-only view: never trigger remote downloads.
                            externalUrl = null,
                        )
                        Text(
                            text = anime.title.ifBlank { "Anime local" },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ReiAnixDurationAnomalyDialog(
    viewModel: ReiAnixLibraryViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var report by remember(viewModel) { mutableStateOf<ReiAnixLibraryDiscovery.DurationAnomalyReport?>(null) }
    var loading by remember(viewModel) { mutableStateOf(true) }
    var error by remember(viewModel) { mutableStateOf<String?>(null) }
    var retryToken by remember(viewModel) { mutableStateOf(0) }

    LaunchedEffect(viewModel, retryToken) {
        loading = true
        error = null
        try {
            report = viewModel.queryNativeDurationAnomalies()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report = null
            error = failure.message?.takeIf(String::isNotBlank)
                ?: context.getString(R.string.finish_duration_error)
        } finally {
            loading = false
        }
    }

    val unusual = report?.items.orEmpty().filter { it.status == "unusual" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.more_unusual_durations)) },
        text = {
            DiscoveryDialogBody(
                loading = loading,
                error = error,
                empty = report != null && report!!.anomalyCount == 0,
                emptyMessage = stringResource(R.string.finish_duration_empty),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    report?.let {
                        Text(
                            stringResource(R.string.finish_duration_summary, it.seriesWithAnomalies, it.seriesWithInsufficientData),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(stringResource(R.string.finish_duration_occurrences, it.anomalyCount), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    unusual.forEach { series ->
                        Text(series.animeTitle, style = MaterialTheme.typography.titleSmall)
                        series.anomalies.forEach { anomaly ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        anomaly.episode.episodeTitle?.takeIf(String::isNotBlank)
                                            ?: anomaly.episode.fileName.ifBlank { stringResource(R.string.common_local_episode) },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        "${ReiAnixLibraryDiscovery.formatDuration(anomaly.durationSeconds)} • ${anomaly.label}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { ReiAnixCompactButton(text = stringResource(R.string.ui_close), onClick = onDismiss) },
        dismissButton = {
            if (error != null) ReiAnixCompactButton(text = stringResource(R.string.ui_retry), onClick = { retryToken++ })
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
fun ReiAnixMarathonDialog(
    viewModel: ReiAnixLibraryViewModel,
    animeId: Long,
    currentPath: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var plan by remember(viewModel, animeId) { mutableStateOf<ReiAnixLibraryDiscovery.MarathonPlan?>(null) }
    var loading by remember(viewModel, animeId) { mutableStateOf(true) }
    var error by remember(viewModel, animeId) { mutableStateOf<String?>(null) }
    var retryToken by remember(viewModel, animeId) { mutableStateOf(0) }

    LaunchedEffect(viewModel, animeId, currentPath, retryToken) {
        loading = true
        error = null
        try {
            plan = viewModel.queryNativeMarathonPlan(animeId, currentPath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            plan = null
            error = failure.message?.takeIf(String::isNotBlank)
                ?: context.getString(R.string.finish_marathon_error)
        } finally {
            loading = false
        }
    }

    val startLabel = remember(retryToken) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }
    val endLabel = remember(plan, retryToken) {
        val seconds = plan?.knownDurationSeconds?.takeIf { it.isFinite() && it >= 0.0 && it <= 31_536_000.0 }
        seconds?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + it.toLong() * 1000L)) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_marathon)) },
        text = {
            val emptyPlan = plan?.let { it.items.isEmpty() } == true
            DiscoveryDialogBody(
                loading = loading,
                error = error,
                empty = emptyPlan,
                emptyMessage = stringResource(R.string.finish_marathon_empty),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    plan?.let { value ->
                        Text(stringResource(R.string.finish_marathon_count, value.episodeCount), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.finish_marathon_time, ReiAnixLibraryDiscovery.formatDuration(value.knownDurationSeconds)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(stringResource(R.string.finish_marathon_start, startLabel), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (value.unknownDurationCount > 0) {
                            Text(
                                stringResource(R.string.finish_marathon_unknown, value.unknownDurationCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else if (endLabel != null) {
                            Text(
                                stringResource(R.string.finish_marathon_end, endLabel),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        value.items.take(48).forEachIndexed { index, item ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    if (item.isCurrent) "Agora •" else "${index + 1}.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    item.episode.episodeTitle?.takeIf(String::isNotBlank)
                                        ?: item.episode.fileName.ifBlank { stringResource(R.string.aux_episode_number, index + 1) },
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    ReiAnixLibraryDiscovery.formatDuration(item.remainingSeconds),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (value.items.size > 48) {
                            Text(stringResource(R.string.finish_marathon_more, value.items.size - 48), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { ReiAnixCompactButton(text = stringResource(R.string.ui_close), onClick = onDismiss) },
        dismissButton = {
            if (error != null) ReiAnixCompactButton(text = stringResource(R.string.ui_retry), onClick = { retryToken++ })
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
private fun DiscoveryDialogBody(
    loading: Boolean,
    error: String?,
    empty: Boolean,
    emptyMessage: String,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp, max = 440.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("Consultando biblioteca local…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error != null -> Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            empty -> Text(emptyMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> content()
        }
    }
}
