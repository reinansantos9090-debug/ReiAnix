package com.reianix.reianix_local.ui.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.reianix.reianix_local.ReiAnixAppDependencies
import com.reianix.reianix_local.data.personal.ReiAnixPersonalAnimeService
import com.reianix.reianix_local.data.personal.ReiAnixWatchStatus
import com.reianix.reianix_local.ui.ReiAnixTopBar
import com.reianix.reianix_local.ui.model.ReiAnixAnimeUiModel
import com.reianix.reianix_local.ui.theme.ReiAnixResponsiveRoot

/** Read-only view of the named tracking categories edited on the anime details screen. */
@Composable
fun ReiAnixWatchStatusCollections(
    animes: List<ReiAnixAnimeUiModel>,
    onOpenAnime: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember(context) { ReiAnixAppDependencies.from(context).sqliteSettingsRepository }
    var statusById by remember { mutableStateOf<Map<Long, ReiAnixWatchStatus>>(emptyMap()) }
    var selected by remember { mutableStateOf(ReiAnixWatchStatus.WATCHING) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // One SQLite preference read on destination entry, never on Library scrolling.
    LaunchedEffect(settings) {
        loading = true
        try {
            val preferences = settings.allPreferences()
            statusById = animes.mapNotNull { anime ->
                val value = preferences[ReiAnixPersonalAnimeService.statusKey(anime.id)]
                val status = ReiAnixWatchStatus.entries.firstOrNull { it.name == value }
                if (status == null) null else anime.id to status
            }.toMap()
        } catch (_: Exception) {
            error = "Não foi possível carregar suas listas."
        } finally {
            loading = false
        }
    }

    val selectedAnimes = animes.filter { statusById[it.id] == selected }
    ReiAnixResponsiveRoot {
        Column(modifier = Modifier.fillMaxSize()) {
            ReiAnixTopBar(title = "Listas de acompanhamento")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReiAnixWatchStatus.entries.forEach { status ->
                    FilterChip(
                        selected = status == selected,
                        onClick = { selected = status },
                        label = {
                            Text(status.label + " (" + statusById.values.count { it == status } + ")")
                        },
                    )
                }
            }
            OutlinedButton(
                modifier = Modifier.padding(horizontal = 16.dp),
                onClick = onBack,
            ) { Text("Voltar para coleções") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (loading) Text("Carregando suas listas…", modifier = Modifier.padding(16.dp))
            else if (selectedAnimes.isEmpty()) {
                Text("Nenhum anime nesta categoria. Use a página de detalhes para adicionar.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(selectedAnimes, key = { it.id }) { anime ->
                        OutlinedButton(
                            onClick = { onOpenAnime(anime.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(anime.title) }
                    }
                }
            }
        }
    }
}
