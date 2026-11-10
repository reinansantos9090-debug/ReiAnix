package com.reiflix.reiflix_local.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.reiflix.reiflix_local.ui.model.ReiAnixEpisodeUiModel
import com.reiflix.reiflix_local.ui.artwork.ReiAnixEpisodeThumbnail

@Composable
fun ReiAnixEpisodePlaylist(
    episodes: List<ReiAnixEpisodeUiModel>, currentId: Long,
    onSelect: (Long) -> Unit, onClose: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(episodes, currentId) {
        val index = episodes.indexOfFirst { it.id == currentId }
        if (index >= 0) listState.scrollToItem(index)
    }
    Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
        color = Color(0xFF161616), contentColor = Color.White) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Episódios", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onClose) { Text("Fechar", color = Color.White) }
            }
            Text("${episodes.size} episódios", style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFCCCCCC))
            Spacer(Modifier.height(12.dp))
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(episodes, key = { it.id }) { episode ->
                    val selected = episode.id == currentId
                    Surface(shape = RoundedCornerShape(12.dp),
                        color = if (selected) Color(0xFF20283A) else Color(0xFF161616),
                        contentColor = Color.White) {
                        Row(Modifier.fillMaxWidth().clickable(enabled = episode.isPlayable) { onSelect(episode.id) }
                            .padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ReiAnixEpisodeThumbnail(localPath = episode.artwork?.localPath,
                                contentDescription = null, identity = episode.stableKey,
                                sourceRevisions = episode.artwork?.sourceRevisions.orEmpty(),
                                modifier = Modifier.width(96.dp).aspectRatio(16f / 9f))
                            Column(Modifier.weight(1f)) {
                                Text(episode.number?.let { "Episódio ${if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()}" }
                                    ?: episode.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall)
                                Text(if (selected) "Reproduzindo agora" else episode.title ?: episode.fileName,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
