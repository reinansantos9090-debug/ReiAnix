package com.reianix.reianix_local.ui.collections

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
import androidx.compose.material3.TextButton
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
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = if (dragging) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = if (dragging) 4.dp else 0.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CollectionPoster(anime, modifier = Modifier.size(44.dp, 62.dp))
                        Text(
                            anime?.title ?: "Anime ausente (ID $animeId)",
                            modifier = Modifier.weight(1f).then(
                                if (anime == null || busy || dragging) Modifier
                                else Modifier.clickable { onOpenAnime(animeId) }
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = anime != null && !busy && !dragging,
                            onClick = { onSetCover(animeId) },
                        ) {
                            Text(if (collection.effectiveCoverId == animeId) "★ Capa" else "Usar capa")
                        }
                        TextButton(
                            enabled = !busy && !dragging,
                            onClick = { onRemove(animeId) },
                        ) { Text("Remover") }
                    }
                }
            }
        }
    }
}
