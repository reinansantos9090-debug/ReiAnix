package com.reianix.reianix_local.player

import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Foreground media playback ownership for the explicitly selected audio-only
 * mode. The Activity still owns the existing player and session, so there is
 * no second decoder and exiting the player releases playback as before.
 * All registry changes occur on the main thread.
 */
@OptIn(UnstableApi::class)
class AudioOnlyPlaybackService : MediaSessionService() {
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = requestedSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val session = requestedSession
        if (session == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // A previous player may have been closed and reopened rapidly.
        sessions.filter { it !== session }.forEach { removeSession(it) }
        if (!isSessionAdded(session)) addSession(session)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Closing the task ends playback; screen-off alone does not.
        sessions.forEach { it.player.pause() }
        stopSelf()
    }

    override fun onDestroy() {
        sessions.toList().forEach { removeSession(it) }
        super.onDestroy()
    }

    companion object {
        private var requestedSession: MediaSession? = null

        internal fun start(context: Context, session: MediaSession): Boolean {
            requestedSession = session
            return runCatching {
                context.startService(Intent(context, AudioOnlyPlaybackService::class.java)) != null
            }.getOrDefault(false).also { ok ->
                if (!ok && requestedSession === session) requestedSession = null
            }
        }

        internal fun stop(context: Context, session: MediaSession) {
            if (requestedSession !== session) return
            requestedSession = null
            context.stopService(Intent(context, AudioOnlyPlaybackService::class.java))
        }
    }
}
