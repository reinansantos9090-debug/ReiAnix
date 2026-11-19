package com.reianix.reianix_local.player

import android.os.SystemClock

/**
 * In-process, one-shot link from a validated profile scene to the existing
 * native player launch. Never serialized into an externally writable URI.
 */
internal object ProfileClipHandoff {
    private data class Pending(
        val requestId: String,
        val scene: PlayerLocalMetadataStore.FavoriteScene,
        val expiresAt: Long,
    )
    private var pending: Pending? = null

    @Synchronized
    fun put(requestId: String, scene: PlayerLocalMetadataStore.FavoriteScene) {
        pending = Pending(requestId, scene, SystemClock.elapsedRealtime() + 60_000L)
    }

    @Synchronized
    fun take(requestId: String, mediaId: String): PlayerLocalMetadataStore.FavoriteScene? {
        val item = pending ?: return null
        if (SystemClock.elapsedRealtime() > item.expiresAt) {
            pending = null
            return null
        }
        if (item.requestId != requestId || item.scene.mediaId != mediaId) return null
        pending = null
        return item.scene
    }

    @Synchronized
    fun clear(requestId: String) {
        if (pending?.requestId == requestId) pending = null
    }
}
