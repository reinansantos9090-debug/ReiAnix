package com.reianix.reianix_local.player.cast

import android.net.Uri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Cast is an optional output of the existing local player, not another library
 * or a second playback database. All SDK callbacks are dispatched on main.
 */
class ReiAnixCastController(
    private val activity: FragmentActivity,
    private val video: () -> Video?,
    private val pauseLocal: () -> Unit,
    private val resumeLocal: (positionMs: Long, shouldPlay: Boolean) -> Unit,
    private val onState: () -> Unit,
    private val onFinished: () -> Unit,
    private val onMessage: (String) -> Unit,
) {
    data class Video(
        val uri: Uri,
        val title: String,
        val positionMs: Long,
        val shouldPlay: Boolean,
        val durationMs: Long,
    )

    private var manager: SessionManager? = null
    private var session: CastSession? = null
    private var client: RemoteMediaClient? = null
    private var activeSourceUri: String? = null
    private var pendingSourceUri: String? = null
    private val requestSerial = AtomicLong(0L)
    private var loading = false
    private var lastPositionMs = 0L
    private var lastDurationMs = 0L
    private var lastWasPlaying = false
    private var finishedReported = false

    val isCasting: Boolean
        get() = activeSourceUri != null && activeSourceUri == video()?.uri?.toString() &&
            session?.isConnected == true

    val isLoading: Boolean get() = loading

    fun positionMs(): Long =
        if (isCasting && ended()) durationMs().coerceAtLeast(lastPositionMs)
        else if (isCasting) (client?.approximateStreamPosition ?: lastPositionMs).coerceAtLeast(0L)
        else lastPositionMs

    fun durationMs(): Long =
        if (isCasting) (client?.mediaInfo?.streamDuration ?: lastDurationMs).coerceAtLeast(0L)
        else lastDurationMs

    fun playing(): Boolean = if (isCasting) client?.isPlaying == true else false

    fun ended(): Boolean = isCasting &&
        client?.mediaStatus?.idleReason == MediaStatus.IDLE_REASON_FINISHED

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            if (!isCasting) return
            lastPositionMs = positionMs()
            lastDurationMs = durationMs()
            lastWasPlaying = playing()
            onState()
            if (ended() && !finishedReported) {
                finishedReported = true
                onFinished()
            }
        }
        override fun onMetadataUpdated() = onState()
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStarted(session: CastSession, sessionId: String) = attach(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            onMessage("Não foi possível conectar ao Chromecast")
        }
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionEnded(session: CastSession, error: Int) = detach(restoreLocal = true)
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attach(session)
        override fun onSessionResumeFailed(session: CastSession, error: Int) =
            detach(restoreLocal = true)
        override fun onSessionSuspended(session: CastSession, reason: Int) = onState()
    }

    fun start() {
        val castManager = try {
            CastContext.getSharedInstance(activity).sessionManager
        } catch (error: Exception) {
            onMessage("Google Cast não está disponível neste dispositivo")
            return
        }
        manager = castManager
        castManager.addSessionManagerListener(sessions, CastSession::class.java)
        castManager.currentCastSession?.takeIf { it.isConnected }?.let { attach(it) }
    }

    /** Called only after the local MediaItem has been installed in ExoPlayer. */
    fun onLocalMediaPrepared() {
        val castSession = session ?: return
        if (!castSession.isConnected || loading) return
        val current = video() ?: return
        if (activeSourceUri == current.uri.toString()) return
        if (LocalCastMediaServer.currentSource == current.uri.toString() &&
            castSession.remoteMediaClient?.hasMediaSession() == true &&
            // Only reattach if this receiver is still playing our own endpoint.
            castSession.remoteMediaClient?.mediaInfo?.contentId?.contains("/media/") == true
        ) {
            pauseLocal()
            activeSourceUri = current.uri.toString()
            lastPositionMs = positionMs()
            onState()
            return
        }
        sendToReceiver(current, castSession)
    }

    private fun attach(newSession: CastSession) {
        if (session === newSession && client != null) {
            onLocalMediaPrepared()
            return
        }
        client?.unregisterCallback(mediaCallback)
        session = newSession
        client = newSession.remoteMediaClient?.also { it.registerCallback(mediaCallback) }
        activeSourceUri = null
        pendingSourceUri = null
        loading = false
        finishedReported = false
        onLocalMediaPrepared()
    }

    private fun sendToReceiver(current: Video, castSession: CastSession) {
        val requestId = requestSerial.incrementAndGet()
        pendingSourceUri = current.uri.toString()
        loading = true
        lastPositionMs = current.positionMs.coerceAtLeast(0L)
        lastDurationMs = current.durationMs.coerceAtLeast(0L)
        lastWasPlaying = current.shouldPlay
        // Stop phone audio immediately; restore it on any transfer failure.
        pauseLocal()
        onState()
        activity.lifecycleScope.launch {
            try {
                // SAF, size probing and socket binding must never stall the player UI.
                val published = withContext(Dispatchers.IO) {
                    LocalCastMediaServer.publish(activity.applicationContext, current.uri)
                }
                if (requestId != requestSerial.get() || session !== castSession ||
                    !castSession.isConnected || video()?.uri != current.uri
                ) return@launch
                CastStreamingService.ensureRunning(activity)
                val remote = castSession.remoteMediaClient
                    ?: throw IllegalStateException("Chromecast sem controle remoto")
                val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                    putString(MediaMetadata.KEY_TITLE, current.title)
                }
                val mediaInfo = MediaInfo.Builder(published.url)
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(published.mimeType)
                    .setMetadata(metadata)
                    .setStreamDuration(current.durationMs.coerceAtLeast(0L))
                    .build()
                val load = MediaLoadRequestData.Builder()
                    .setMediaInfo(mediaInfo)
                    .setAutoplay(current.shouldPlay)
                    .setCurrentTime(current.positionMs.coerceAtLeast(0L))
                    .build()
                remote.load(load).setResultCallback { result ->
                    if (requestId != requestSerial.get()) return@setResultCallback
                    loading = false
                    pendingSourceUri = null
                    if (result.status.isSuccess) {
                        activeSourceUri = current.uri.toString()
                        finishedReported = false
                    } else {
                        activeSourceUri = null
                        CastStreamingService.stop(activity)
                        resumeLocal(lastPositionMs, lastWasPlaying)
                        onMessage("A TV não conseguiu reproduzir este formato")
                    }
                    onState()
                }
            } catch (error: Exception) {
                if (requestId != requestSerial.get()) return@launch
                loading = false
                pendingSourceUri = null
                activeSourceUri = null
                CastStreamingService.stop(activity)
                resumeLocal(lastPositionMs, lastWasPlaying)
                onMessage(error.message ?: "Não foi possível transmitir o vídeo")
                onState()
            }
        }
    }

    /** Adjust the receiver device volume rather than the silent local audio stream. */
    fun adjustVolumeByFraction(delta: Float): Int? {
        if (!isCasting) return null
        val castSession = session ?: return null
        return runCatching {
            val next = (castSession.volume + delta.toDouble()).coerceIn(0.0, 1.0)
            castSession.setVolume(next)
            (next * 100.0).toInt()
        }.getOrNull()
    }

    fun togglePlayPause(): Boolean {
        if (!isCasting) return false
        if (playing()) client?.pause() else client?.play()
        onState()
        return true
    }

    fun seekTo(position: Long): Boolean {
        if (!isCasting) return false
        val duration = durationMs()
        if (duration <= 0) return false
        val safePosition = position.coerceIn(0L, duration)
        client?.seek(MediaSeekOptions.Builder().setPosition(safePosition).build())
        lastPositionMs = safePosition
        onState()
        return true
    }

    fun seekBy(deltaMs: Long): Boolean {
        if (!isCasting) return false
        val position = positionMs()
        val duration = durationMs()
        return seekTo((position + deltaMs).coerceIn(0L, duration))
    }

    private fun detach(restoreLocal: Boolean) {
        val hadCast = activeSourceUri != null || loading
        val position = if (isCasting) positionMs() else lastPositionMs
        val wasPlaying = if (isCasting) playing() else lastWasPlaying
        requestSerial.incrementAndGet()
        client?.unregisterCallback(mediaCallback)
        client = null
        session = null
        activeSourceUri = null
        pendingSourceUri = null
        loading = false
        if (restoreLocal && hadCast) resumeLocal(position, wasPlaying)
        onState()
    }

    /** Do not end a TV session when the phone player Activity is destroyed. */
    fun release() {
        requestSerial.incrementAndGet()
        manager?.removeSessionManagerListener(sessions, CastSession::class.java)
        manager = null
        detach(restoreLocal = false)
    }
}
