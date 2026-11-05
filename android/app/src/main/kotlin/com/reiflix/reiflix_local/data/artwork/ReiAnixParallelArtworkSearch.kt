package com.reiflix.reiflix_local.data.artwork

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Keeps every distinct title supplied by metadata providers. Deliberately does not
 * normalize case, translate, truncate or fuzzy-deduplicate provider-owned aliases.
 */
internal fun allDistinctArtworkSearchTitles(candidates: Iterable<String?>): List<String> =
    candidates.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinct()

/**
 * Runs title lookups through a small worker pool and delivers completions in completion order.
 * The caller can persist/publish a useful image as soon as one provider query finishes, while
 * remaining variants continue independently. A failed lookup is delivered as a Result and does
 * not cancel its siblings. Only the worker pool is created; title count does not create task count.
 */
internal suspend fun <T> runParallelArtworkSearches(
    titles: List<String>,
    maxConcurrent: Int = DEFAULT_MAX_CONCURRENT_ARTWORK_SEARCHES,
    search: suspend (String) -> T,
    onCompleted: suspend (String, Result<T>) -> Unit,
) = coroutineScope {
    require(maxConcurrent in 1..MAX_ALLOWED_ARTWORK_SEARCHES) {
        "Artwork search concurrency must be between 1 and $MAX_ALLOWED_ARTWORK_SEARCHES."
    }
    val queries = titles.filter(String::isNotBlank).distinct()
    if (queries.isEmpty()) return@coroutineScope

    val pending = Channel<String>(capacity = maxConcurrent)
    // Backpressure is bounded by the worker count; a slow persistence callback cannot
    // accumulate an unbounded queue of remote result payloads.
    val completed = Channel<Pair<String, Result<T>>>(capacity = maxConcurrent)
    val producer = launch {
        try {
            queries.forEach { pending.send(it) }
        } finally {
            pending.close()
        }
    }
    val workers = List(minOf(maxConcurrent, queries.size)) {
        launch {
            for (query in pending) {
                val outcome = try {
                    Result.success(search(query))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }
                completed.send(query to outcome)
            }
        }
    }
    val completionCloser = launch {
        workers.joinAll()
        completed.close()
    }

    repeat(queries.size) {
        val (query, outcome) = completed.receive()
        onCompleted(query, outcome)
    }
    producer.join()
    workers.joinAll()
    completionCloser.join()
}

internal const val DEFAULT_MAX_CONCURRENT_ARTWORK_SEARCHES = 4
internal const val MAX_ALLOWED_ARTWORK_SEARCHES = 8
