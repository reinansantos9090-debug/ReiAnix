package com.reiflix.reiflix_local.viewmodel

import java.util.ArrayDeque

internal data class EpisodeThumbnailWorkItem(
    val animeId: Long,
    val episodeId: Long,
    val revision: String,
)

internal class EpisodeThumbnailWorkQueue {
    private val pendingByAnime = LinkedHashMap<Long, LinkedHashMap<Long, EpisodeThumbnailWorkItem>>()
    private val roundRobinAnimeIds = ArrayDeque<Long>()
    private val scheduledAnimeIds = HashSet<Long>()

    @Synchronized
    fun offer(items: Iterable<EpisodeThumbnailWorkItem>): Int {
        var changed = 0
        for (item in items) {
            if (item.animeId <= 0L || item.episodeId <= 0L) continue
            val animeQueue = pendingByAnime.getOrPut(item.animeId) {
                LinkedHashMap<Long, EpisodeThumbnailWorkItem>().also {
                    if (scheduledAnimeIds.add(item.animeId)) roundRobinAnimeIds.addLast(item.animeId)
                }
            }
            val previous = animeQueue.put(item.episodeId, item)
            if (previous != item) changed++
        }
        return changed
    }

    @Synchronized
    fun poll(priorityAnimeId: Long? = null): EpisodeThumbnailWorkItem? {
        if (priorityAnimeId != null && priorityAnimeId > 0L) {
            val priorityQueue = pendingByAnime[priorityAnimeId]
            if (priorityQueue != null && priorityQueue.isNotEmpty()) {
                val first = priorityQueue.entries.iterator().next()
                priorityQueue.remove(first.key)
                if (priorityQueue.isEmpty()) pendingByAnime.remove(priorityAnimeId)
                return first.value
            }
        }
        while (roundRobinAnimeIds.isNotEmpty()) {
            val animeId = roundRobinAnimeIds.removeFirst()
            scheduledAnimeIds.remove(animeId)
            val animeQueue = pendingByAnime[animeId] ?: continue
            if (animeQueue.isEmpty()) {
                pendingByAnime.remove(animeId)
                continue
            }
            val first = animeQueue.entries.iterator().next()
            animeQueue.remove(first.key)
            if (animeQueue.isEmpty()) pendingByAnime.remove(animeId)
            else if (scheduledAnimeIds.add(animeId)) roundRobinAnimeIds.addLast(animeId)
            return first.value
        }
        return null
    }

    @Synchronized fun size(): Int = pendingByAnime.values.sumOf { it.size }
    @Synchronized fun isEmpty(): Boolean = pendingByAnime.values.all { it.isEmpty() }
}
