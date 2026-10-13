package com.reiflix.reiflix_local.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.app.PendingIntent
import androidx.core.content.getSystemService
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerNotificationManager

/**
 * Owns the one Android media session and its notification for an Activity-owned
 * local video player. It deliberately does not keep playback alive after the
 * player Activity is destroyed.
 */
@OptIn(UnstableApi::class)
class PlayerMediaControls(
    context: Context,
    private val player: Player,
    sessionId: String,
    private val titleProvider: () -> String,
    private val episodeProvider: () -> String,
) {
    private val appContext = context.applicationContext
    private val mediaSession: MediaSession
    private val notificationManager: PlayerNotificationManager
    private var released = false

    init {
        ensureNotificationChannel()
        mediaSession = MediaSession.Builder(appContext, player)
            .setId(sessionId)
            .build()
        notificationManager = PlayerNotificationManager.Builder(
            appContext,
            NOTIFICATION_ID,
            CHANNEL_ID,
        )
            .setMediaDescriptionAdapter(object : PlayerNotificationManager.MediaDescriptionAdapter {
                override fun getCurrentContentTitle(player: Player): CharSequence {
                    val metadataTitle = player.mediaMetadata.title?.toString()?.trim().orEmpty()
                    return metadataTitle.ifBlank { titleProvider().ifBlank { "ReiAnix" } }
                }

                override fun createCurrentContentIntent(player: Player): PendingIntent? = null

                override fun getCurrentContentText(player: Player): CharSequence {
                    val metadataSubtitle = player.mediaMetadata.artist?.toString()?.trim().orEmpty()
                    return metadataSubtitle.ifBlank { episodeProvider() }
                }

                override fun getCurrentLargeIcon(
                    player: Player,
                    callback: PlayerNotificationManager.BitmapCallback,
                ): Bitmap? = null
            })
            .build()
            .apply {
                setMediaSessionToken(mediaSession.platformToken)
                setUseNextAction(false)
                setUsePreviousAction(false)
                setUseFastForwardAction(true)
                setUseRewindAction(true)
                setUsePlayPauseActions(true)
                setUseStopAction(false)
                setPlayer(player)
            }
    }

    fun release() {
        if (released) return
        released = true
        notificationManager.setPlayer(null)
        mediaSession.release()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Reprodução",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Controles da reprodução local do ReiAnix"
                setShowBadge(false)
            },
        )
    }

    companion object {
        internal const val CHANNEL_ID = "reianix_media_playback"
        internal const val NOTIFICATION_ID = 2301
    }
}
