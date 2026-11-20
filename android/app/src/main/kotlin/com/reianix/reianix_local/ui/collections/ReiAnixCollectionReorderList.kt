package com.reianix.reianix_local.ui.collections

import com.reianix.reianix_local.ui.theme.reiAnixContentBackground

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reianix.reianix_local.data.collections.ReiAnixPersonalCollection
import com.reianix.reianix_local.ui.model.ReiAnixAnimeUiModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import java.util.Locale

/**
 * Long-press any card, then drag over another card to change its position.
 * Reordering is optimistic during the gesture but reaches SQLite only once,
 * on release. Cancel/Back never persists an unfinished drag.
 */
@Composable
internal fun ReiAnixCollectionReorderList(
    collection: ReiAnixPersonalCollection,
    animeById: Map<Long, ReiAnixAnimeUiModel>,
    busy: Boolean,
    onReorder: (List<Long>) -> Unit,
    onOpenAnime: (Long) -> Unit,
    onSetCover: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var draftOrder by remember(collection.id) { mutableStateOf<List<Long>?>(null) }
    var draggedId by remember(collection.id) { mutableStateOf<Long?>(null) }
    var pointerY by remember(collection.id) { mutableFloatStateOf(0f) }
    val order = draftOrder ?: collection.animeIds
    val density = LocalDensity.current
    val edgeSize = with(density) { 72.dp.toPx() }
    val maxScrollPerFrame = with(density) { 14.dp.toPx() }

    fun reorderAt(y: Float) {
        val dragging = draggedId ?: return
        val hovered = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            y >= item.offset && y < item.offset + item.size
        }?.key as? Long ?: return
        if (hovered == dragging) return

        val current = draftOrder ?: collection.animeIds
        val from = current.indexOf(dragging)
        val to = current.indexOf(hovered)
        if (from < 0 || to < 0 || from == to) return
        val updated = current.toMutableList()
        updated.removeAt(from)
        updated.add(to, dragging)
        draftOrder = updated
    }

    LaunchedEffect(collection.animeIds, busy) {
        // Once the transaction completes (including failures), use canonical order.
        if (!busy && draggedId == null) draftOrder = null
    }

    LaunchedEffect(draggedId) {
        if (draggedId == null) return@LaunchedEffect
        while (coroutineContext.isActive && draggedId != null) {
            val layout = listState.layoutInfo
            val start = layout.viewportStartOffset.toFloat()
            val end = layout.viewportEndOffset.toFloat()
            val speed = when {
                pointerY < start + edgeSize ->
                    -((start + edgeSize - pointerY) / edgeSize).coerceIn(0f, 1f) * maxScrollPerFrame
                pointerY > end - edgeSize ->
                    ((pointerY - (end - edgeSize)) / edgeSize).coerceIn(0f, 1f) * maxScrollPerFrame
                else -> 0f
            }
            if (speed != 0f) {
                val consumed = listState.scrollBy(speed)
                if (consumed != 0f) reorderAt(pointerY)
            }
            delay(16L)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(collection.id, collection.animeIds, busy) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { position ->
                        if (!busy) {
                            draggedId = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                position.y >= item.offset && position.y < item.offset + item.size
                            }?.key as? Long
                            if (draggedId != null) {
                                draftOrder = collection.animeIds
                                pointerY = position.y
                            }
                        }
                    },
                    onDrag = { change, _ ->
                        if (draggedId != null) {
                            change.consume()
                            pointerY = change.position.y
                            reorderAt(pointerY)
                        }
                    },
                    onDragCancel = {
                        draggedId = null
                        draftOrder = null
                    },
                    onDragEnd = {
                        val finalOrder = draftOrder
                        draggedId = null
                        if (finalOrder != null && finalOrder != collection.animeIds) {
                            onReorder(finalOrder)
                        } else {
                            draftOrder = null
                        }
                    },
                )
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(items = order, key = { _, id -> id }) { _, animeId ->
            val anime = animeById[animeId]
            val dragging = draggedId == animeId
            CollectionAnimeRow(
                animeId = animeId,
                anime = anime,
                isCover = collection.effectiveCoverId == animeId,
                busy = busy || draggedId != null,
                dragging = dragging,
                onOpen = { onOpenAnime(animeId) },
                onSetCover = { onSetCover(animeId) },
                onRemove = { onRemove(animeId) },
            )
        }
    }
}

/** Render only the canonical local snapshot: no extra metadata or artwork requests. */
@Composable
internal fun CollectionAnimeRow(
    animeId: Long,
    anime: ReiAnixAnimeUiModel?,
    isCover: Boolean,
    busy: Boolean,
    dragging: Boolean = false,
    onOpen: () -> Unit,
    onSetCover: () -> Unit,
    onRemove: () -> Unit,
) {
    val gold = Color(0xFFEAC54F)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (dragging) MaterialTheme.colorScheme.primaryContainer
            else reiAnixContentBackground(MaterialTheme.colorScheme.surfaceVariant),
        contentColor = if (dragging) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = if (dragging) 4.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CollectionPoster(anime, modifier = Modifier.size(60.dp, 84.dp))
            Column(
                modifier = Modifier.weight(1f).then(
                    if (anime == null || busy) Modifier else Modifier.clickable(onClick = onOpen)
                ),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    anime?.title ?: "Anime ausente (ID $animeId)",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (anime != null) {
                    Text(
                        if (anime.availableContentCount == 1) "1 episódio disponível"
                        else "${anime.availableContentCount} episódios disponíveis",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val details = remember(anime.year, anime.genres) {
                        buildList {
                            anime.year?.takeIf { it > 0 }?.let { add(it.toString()) }
                            addAll(anime.genres.map { it.name.trim() }.filter(String::isNotBlank).distinct().take(2))
                        }.joinToString(" • ")
                    }
                    if (details.isNotBlank()) {
                        Text(
                            details,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    anime.score?.takeIf { it.isFinite() && it > 0.0 && it <= 100.0 }?.let { score ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Filled.Star, contentDescription = null, tint = gold, modifier = Modifier.size(13.dp))
                            Text(
                                String.format(Locale("pt", "BR"), "%.1f/10", score / 10.0),
                                style = MaterialTheme.typography.labelSmall,
                                color = gold,
                            )
                        }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = onSetCover,
                    enabled = anime != null && !busy && !isCover,
                    modifier = Modifier.semantics { selected = isCover },
                ) {
                    Icon(
                        if (isCover) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (isCover) "Capa da coleção" else "Usar capa",
                        tint = if (isCover) gold else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = onRemove, enabled = !busy) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Remover da coleção",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
