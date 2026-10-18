package com.reiflix.reiflix_local.data.library

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-local invalidation stream for native Compose projections backed by SQLite.
 *
 * Producers publish only after committing the canonical database mutation. Events are
 * invalidation hints, not persisted domain data; consumers always reread SQLite. A
 * small drop-oldest buffer intentionally coalesces bursts from artwork downloads.
 */
data class ReiAnixLibraryInvalidation(
    val reason: String,
    val animeId: Long? = null,
    val entityType: String? = null,
    val entityId: Long? = null,
)

object ReiAnixLibraryInvalidationBus {
    private val mutableEvents = MutableSharedFlow<ReiAnixLibraryInvalidation>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events = mutableEvents.asSharedFlow()

    fun publish(
        reason: String,
        animeId: Long? = null,
        entityType: String? = null,
        entityId: Long? = null,
    ) {
        val normalizedReason = reason.trim()
        if (normalizedReason.isBlank()) return
        mutableEvents.tryEmit(
            ReiAnixLibraryInvalidation(
                reason = normalizedReason,
                animeId = animeId?.takeIf { it > 0L },
                entityType = entityType?.trim()?.takeIf { it.isNotEmpty() },
                entityId = entityId?.takeIf { it > 0L },
            ),
        )
    }
}
