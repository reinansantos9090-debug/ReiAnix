package com.reiflix.reiflix_local.ui.discovery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.reiflix.reiflix_local.data.library.ReiAnixLibraryDiscovery
import com.reiflix.reiflix_local.ui.ReiAnixCompactButton
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ReiAnixTimelineDialog(
    viewModel: ReiAnixLibraryViewModel,
    onDismiss: () -> Unit,
    onOpenAnime: (Long) -> Unit,
) {
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
                ?: "Não foi possível consultar a linha do tempo local."
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Linha do tempo") },
        text = {
            DiscoveryDialogBody(
                loading = loading,
                error = error,
                empty = !loading && error == null && groups.orEmpty().isEmpty(),
                emptyMessage = "A biblioteca ainda não possui itens para a linha do tempo.",
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    groups.orEmpty().forEach { group ->
                        Text(group.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        group.items.forEach { item ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpenAnime(item.id) },
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(item.title.ifBlank { "Anime local" }, style = MaterialTheme.typography.bodyMedium)
                                        val secondary = listOfNotNull(
                                            item.season?.takeIf(String::isNotBlank),
                                            item.status?.takeIf(String::isNotBlank),
                                        ).joinToString(" • ")
                                        if (secondary.isNotBlank()) {
                                            Text(secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Text("Abrir", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        },
        confirmButton = { ReiAnixCompactButton(text = "Fechar", onClick = onDismiss) },
        dismissButton = {
            if (error != null) ReiAnixCompactButton(text = "Tentar novamente", onClick = { retryToken++ })
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
fun ReiAnixDurationAnomalyDialog(
    viewModel: ReiAnixLibraryViewModel,
    onDismiss: () -> Unit,
) {
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
                ?: "Não foi possível analisar as durações locais."
        } finally {
            loading = false
        }
    }

    val unusual = report?.items.orEmpty().filter { it.status == "unusual" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Durações incomuns") },
        text = {
            DiscoveryDialogBody(
                loading = loading,
                error = error,
                empty = report != null && report!!.anomalyCount == 0,
                emptyMessage = "Nenhuma duração incomum foi identificada com os dados locais atuais.",
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    report?.let {
                        Text(
                            "${it.seriesWithAnomalies} série(s) com duração incomum • ${it.seriesWithInsufficientData} com dados insuficientes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("${it.anomalyCount} ocorrência(s) encontrada(s)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
                                            ?: anomaly.episode.fileName.ifBlank { "Episódio local" },
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
        confirmButton = { ReiAnixCompactButton(text = "Fechar", onClick = onDismiss) },
        dismissButton = {
            if (error != null) ReiAnixCompactButton(text = "Tentar novamente", onClick = { retryToken++ })
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
                ?: "Não foi possível calcular a maratona com os dados locais."
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
        title = { Text("Maratona") },
        text = {
            val emptyPlan = plan?.let { it.items.isEmpty() } == true
            DiscoveryDialogBody(
                loading = loading,
                error = error,
                empty = emptyPlan,
                emptyMessage = "Nenhum episódio restante foi encontrado. Episódios concluídos não entram na sequência.",
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    plan?.let { value ->
                        Text("${value.episodeCount} episódio(s) na sequência", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Tempo conhecido: ${ReiAnixLibraryDiscovery.formatDuration(value.knownDurationSeconds)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("Início: $startLabel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (value.unknownDurationCount > 0) {
                            Text(
                                "${value.unknownDurationCount} episódio(s) sem duração; término parcialmente desconhecido.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else if (endLabel != null) {
                            Text(
                                "Término estimado: $endLabel • velocidade normal, sem pausas.",
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
                                        ?: item.episode.fileName.ifBlank { "Episódio ${index + 1}" },
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
                            Text("Mais ${value.items.size - 48} episódio(s) na sequência.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { ReiAnixCompactButton(text = "Fechar", onClick = onDismiss) },
        dismissButton = {
            if (error != null) ReiAnixCompactButton(text = "Tentar novamente", onClick = { retryToken++ })
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
