package com.reianix.reianix_local.ui.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.reianix.reianix_local.ui.theme.ReiAnixResponsiveRoot

/** Read-only view of the named tracking categories edited on the anime details screen. */
@Composable
fun ReiAnixWatchStatusCollections(
    animes: List<ReiAnixAnimeUiModel>,
    onOpenAnime: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val dependencies = remember(context) { ReiAnixAppDependencies.from(context) }
    val settings = remember(dependencies) { dependencies.sqliteSettingsRepository }
    val library = remember(dependencies) { dependencies.sqliteLibraryRepository }
    var statusById by remember { mutableStateOf<Map<Long, ReiAnixWatchStatus>>(emptyMap()) }
    var trackedTitles by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var selected by remember { mutableStateOf(ReiAnixWatchStatus.WATCHING) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // One SQLite preference read on destination entry, never on Library scrolling.
    LaunchedEffect(settings) {
        loading = true
        try {
            val preferences = settings.allPreferences()
            val saved = preferences.asSequence().mapNotNull { (key, value) ->
                if (!key.startsWith("reianix.personal.status.")) return@mapNotNull null
                val id = key.removePrefix("reianix.personal.status.").toLongOrNull()
                    ?.takeIf { it > 0L } ?: return@mapNotNull null
                val status = ReiAnixWatchStatus.entries.firstOrNull { it.name == value }
                    ?: return@mapNotNull null
                id to status
            }.toMap()
            statusById = saved
            trackedTitles = withContext(Dispatchers.IO) {
                buildList {
                    var offset = 0
                    while (true) {
                        val page = library.listAnime(200, offset)
                        page.forEach { anime ->
                            if (anime.id in saved) add(anime.id to anime.title)
                        }
                        offset += page.size
                        if (page.size < 200) break
                    }
                }
            }
        } catch (_: Exception) {
            error = "Não foi possível carregar suas listas."
        } finally {
            loading = false
        }
    }

    val selectedAnimes = trackedTitles.filter { statusById[it.first] == selected }
    ReiAnixResponsiveRoot {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            ReiAnixTopBar(title = "Listas de acompanhamento", navigationIcon = Icons.AutoMirrored.Filled.ArrowBack, onNavigationClick = onBack)
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
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (loading) Text("Carregando suas listas…", modifier = Modifier.padding(16.dp))
            else if (selectedAnimes.isEmpty()) {
                Text("Nenhum anime nesta categoria. Use a página de detalhes para adicionar.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(selectedAnimes, key = { it.first }) { anime ->
                        OutlinedButton(
                            onClick = { onOpenAnime(anime.first) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(anime.second) }
                    }
                }
            }
        }
    }
}
