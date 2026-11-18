package com.reianix.reianix_local.player.cast

import android.os.SystemClock
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import java.lang.ref.WeakReference
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Roku output of the native player. It reuses the Google Cast HTTP byte-range
 * server but uses ECP + our sideloaded SceneGraph receiver instead of Cast SDK.
 * Roku ECP has no authoritative per-frame position callback: remote progress
 * displayed on the phone is an estimate until the user seeks or disconnects.
 */
internal class RokuCastController(
    private val activity: FragmentActivity,
    private val video: () -> ReiAnixCastController.Video?,
    private val pauseLocal: () -> Unit,
    private val resumeLocal: (Long, Boolean) -> Unit,
    private val onState: () -> Unit,
    private val onMessage: (String) -> Unit,
) {
    private val serial = AtomicLong()
    private var device: RokuEcpClient.Device? = null
    private var source: String? = null
    private var pending = false
    private var progressBaseMs = 0L
    private var progressClockMs = 0L
    private var duration = 0L
    private var remotePlaying = false
    private var pendingPositionMs = 0L
    private var pendingWasPlaying = false

    init {
        activeController = WeakReference(this)
    }

    val isCasting: Boolean
        get() = device != null && source != null && source == video()?.uri?.toString()
    val isLoading: Boolean get() = pending
    val isConnected: Boolean get() = device != null

    fun durationMs() = duration
    fun playing() = isCasting && remotePlaying
    fun positionMs(): Long {
        val elapsed = if (remotePlaying) {
            (SystemClock.elapsedRealtime() - progressClockMs).coerceAtLeast(0L)
        } else 0L
        val estimate = progressBaseMs + elapsed
        return if (duration > 0L) estimate.coerceIn(0L, duration) else estimate.coerceAtLeast(0L)
    }

    fun connect(address: String) {
        if (pending) return
        val current = video() ?: run {
            onMessage("Nenhum vídeo local disponível para transmitir")
            return
        }
        // All device IDs originate from SSDP. Do not allow arbitrary hostnames.
        if (!Regex("""^(\d{1,3}\.){3}\d{1,3}$""").matches(address)) {
            onMessage("Endereço de Roku inválido")
            return
        }
        val target = runCatching {
            val ip = InetAddress.getByName(address)
            require(ip is Inet4Address && ip.isSiteLocalAddress)
            RokuEcpClient.Device(ip, "Roku ($address)")
        }.getOrNull() ?: run {
            onMessage("A Roku deve estar na mesma rede local")
            return
        }
        transmit(current, target)
    }

    private fun transmit(current: ReiAnixCastController.Video, target: RokuEcpClient.Device) {
        val requestId = serial.incrementAndGet()
        val priorPosition = current.positionMs.coerceAtLeast(0L)
        // Preserve remote playback intent when advancing episodes: the phone
        // player is deliberately paused during the previous Roku session.
        val priorPlaying = if (device != null) remotePlaying else current.shouldPlay
        pendingPositionMs = priorPosition
        pendingWasPlaying = priorPlaying
        pending = true
        pauseLocal()
        onState()
        activity.lifecycleScope.launch {
            try {
                val published = withContext(Dispatchers.IO) {
                    LocalCastMediaServer.publish(activity.applicationContext, current.uri)
                }
                if (requestId != serial.get() || video()?.uri != current.uri) {
                    if (requestId == serial.get()) pending = false
                    return@launch
                }
                CastStreamingService.ensureRunning(activity, rokuAddress = target.id)
                RokuEcpClient.play(
                    target, published.url, current.title, published.mimeType,
                    priorPosition, priorPlaying,
                ).getOrThrow()
                if (requestId != serial.get()) return@launch
                device = target
                source = current.uri.toString()
                pending = false
                progressBaseMs = priorPosition
                progressClockMs = SystemClock.elapsedRealtime()
                duration = current.durationMs.coerceAtLeast(0L)
                remotePlaying = priorPlaying
                onState()
                onMessage("Vídeo enviado para Roku • use o controle da TV se necessário")
            } catch (error: Exception) {
                if (requestId != serial.get()) return@launch
                pending = false
                device = null
                source = null
                CastStreamingService.stop(activity)
                resumeLocal(priorPosition, priorPlaying)
                onState()
                onMessage(error.message ?: "Não foi possível transmitir para a Roku")
            }
        }
    }

    /** A different local episode may be selected while Roku is connected. */
    fun onLocalMediaPrepared() {
        if (device == null && !pending) {
            // Reattach when a player Activity is recreated while its foreground
            // Roku streaming service still owns the endpoint.
            val address = CastStreamingService.activeRokuAddress
            val current = video()
            if (address != null && current != null &&
                LocalCastMediaServer.currentSource == current.uri.toString()
            ) {
                val ip = runCatching { InetAddress.getByName(address) }.getOrNull()
                if (ip is Inet4Address && ip.isSiteLocalAddress) {
                    device = RokuEcpClient.Device(ip, "Roku ($address)")
                    source = current.uri.toString()
                    progressBaseMs = current.positionMs.coerceAtLeast(0L)
                    progressClockMs = SystemClock.elapsedRealtime()
                    duration = current.durationMs.coerceAtLeast(0L)
                    remotePlaying = true
                    pauseLocal()
                    onState()
                }
            }
        }
        val target = device ?: return
        val current = video() ?: return
        if (!pending && source != current.uri.toString()) transmit(current, target)
    }

    private fun send(name: String, seconds: Long? = null) {
        val target = device ?: return
        activity.lifecycleScope.launch {
            RokuEcpClient.command(target, name, seconds).onFailure { failure ->
                onMessage(failure.message ?: "A Roku não respondeu")
            }
        }
    }

    fun togglePlayPause(): Boolean {
        if (!isCasting) return false
        val now = positionMs()
        progressBaseMs = now
        progressClockMs = SystemClock.elapsedRealtime()
        remotePlaying = !remotePlaying
        send(if (remotePlaying) "play" else "pause")
        onState()
        return true
    }

    fun seekTo(position: Long): Boolean {
        if (!isCasting || duration <= 0L) return false
        progressBaseMs = position.coerceIn(0L, duration)
        progressClockMs = SystemClock.elapsedRealtime()
        send("seek", progressBaseMs / 1_000L)
        onState()
        return true
    }

    fun seekBy(delta: Long): Boolean = seekTo(positionMs() + delta)

    /** Roku TV volume depends on the model; there is no reliable ECP percent. */
    fun adjustVolume(up: Boolean): Boolean {
        val target = device ?: return false
        activity.lifecycleScope.launch {
            RokuEcpClient.volumeKey(target, up).onFailure { failure ->
                onMessage(failure.message ?: "Volume indisponível na Roku")
            }
        }
        return true
    }

    fun disconnect() {
        val target = device
        val hadSession = target != null || pending
        val position = if (pending && target == null) pendingPositionMs else positionMs()
        val shouldPlay = if (pending && target == null) pendingWasPlaying else remotePlaying
        serial.incrementAndGet()
        device = null
        source = null
        pending = false
        remotePlaying = false
        if (target != null) {
            activity.lifecycleScope.launch { RokuEcpClient.command(target, "stop") }
        }
        CastStreamingService.stop(activity)
        if (hadSession) resumeLocal(position, shouldPlay)
        onState()
    }

    private fun onServiceStopped() {
        if (device == null && !pending) return
        val position = if (pending && device == null) pendingPositionMs else positionMs()
        val shouldPlay = if (pending && device == null) pendingWasPlaying else remotePlaying
        serial.incrementAndGet()
        device = null
        source = null
        pending = false
        remotePlaying = false
        resumeLocal(position, shouldPlay)
        onState()
    }

    /** Releasing the Activity must not stop the TV's foreground stream. */
    fun release() {
        serial.incrementAndGet()
        if (activeController?.get() === this) activeController = null
    }

    companion object {
        private var activeController: WeakReference<RokuCastController>? = null

        /** The foreground service owns stream lifetime, including notification stop. */
        fun onStreamingServiceStopped() {
            activeController?.get()?.onServiceStopped()
        }
    }
}
