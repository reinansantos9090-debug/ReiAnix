package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.bridge.NativeMailbox

import android.app.Activity
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import org.json.JSONObject

/** Debug-only aggregated Android performance diagnostics. */
object PerformanceDiagnostics {
    private const val FLUSH_INTERVAL_MS = 5_000L
    private const val JANK_MULTIPLIER = 2.0
    private val handler = Handler(Looper.getMainLooper())

    private var attachedWindow: Window? = null
    private var frameListener: Window.OnFrameMetricsAvailableListener? = null
    private var frames = 0L
    private var framesOverBudget = 0L
    private var jankyFrames = 0L
    private var framesOver32Ms = 0L
    private var framesOver50Ms = 0L
    private var framesOver100Ms = 0L
    private var totalFrameDurationNs = 0L
    private var maxFrameDurationNs = 0L
    private var refreshRateHz = 60f
    private var lastFlushAtMs = 0L
    private var lastMemoryPssKb = -1L

    private data class PlayerMark(
        var commandCreatedAtMs: Long = 0L,
        var activityCreatedAtMs: Long = 0L,
        var playerCreatedAtMs: Long = 0L,
        var prepareAtMs: Long = 0L,
        var firstFrameAtMs: Long = 0L,
        var playingAtMs: Long = 0L,
        var reused: Boolean = false,
    )

    private val playerMarks = mutableMapOf<String, PlayerMark>()

    @JvmStatic fun enabled(): Boolean = BuildConfig.DEBUG

    @JvmStatic
    fun attach(activity: Activity) {
        if (!enabled()) return
        refreshRateHz = activity.display?.refreshRate?.takeIf { it > 0f } ?: 60f
        detach()
        attachedWindow = activity.window
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (duration <= 0L) return@OnFrameMetricsAvailableListener
            frames += 1
            if (duration > frameBudgetNs()) framesOverBudget += 1
            if (duration > frameBudgetNs() * JANK_MULTIPLIER) jankyFrames += 1
            if (duration > 32_000_000L) framesOver32Ms += 1
            if (duration > 50_000_000L) framesOver50Ms += 1
            if (duration > 100_000_000L) framesOver100Ms += 1
            totalFrameDurationNs += duration
            maxFrameDurationNs = maxOf(maxFrameDurationNs, duration)
            maybeFlush(activity)
        }
        frameListener = listener
        activity.window.addOnFrameMetricsAvailableListener(listener, handler)
        sampleMemory(activity, "main_attach")
        lastFlushAtMs = SystemClock.elapsedRealtime()
    }

    @JvmStatic
    fun detach() {
        val window = attachedWindow
        val listener = frameListener
        if (window != null && listener != null) {
            window.removeOnFrameMetricsAvailableListener(listener)
        }
        attachedWindow = null
        frameListener = null
    }

    @JvmStatic
    fun sampleMemory(activity: Activity, stage: String) {
        if (!enabled()) return
        lastMemoryPssKb = Debug.getPss()
        NativeMailbox.writeBestEffort(
            activity,
            JSONObject().put("type", "performance_metrics")
                .put("payload", JSONObject().put("stage", stage)
                    .put("memoryPssKb", lastMemoryPssKb)
                    .put("refreshRateHz", refreshRateHz)),
        )
    }

    @JvmStatic
    fun markPlayer(activity: Activity, stage: String, requestId: String,
                   commandCreatedAtMs: Long = 0L, reused: Boolean = false) {
        if (!enabled()) return
        val id = requestId.ifBlank { "-" }
        val mark = playerMarks.getOrPut(id) { PlayerMark() }
        if (commandCreatedAtMs > 0L) mark.commandCreatedAtMs = commandCreatedAtMs
        mark.reused = mark.reused || reused
        val now = System.currentTimeMillis()
        when (stage) {
            "activity_created" -> mark.activityCreatedAtMs = now
            "player_created" -> mark.playerCreatedAtMs = now
            "prepare_dispatched" -> mark.prepareAtMs = now
            "first_frame" -> mark.firstFrameAtMs = now
            "playing" -> mark.playingAtMs = now
            "reuse_intent" -> mark.reused = true
        }
        if (stage == "first_frame" || stage == "playing" || stage == "player_exited") {
            val payload = JSONObject().put("stage", stage).put("requestId", id)
                .put("reused", mark.reused)
                .put("commandCreatedAtMs", mark.commandCreatedAtMs)
                .put("activityCreatedAtMs", mark.activityCreatedAtMs)
                .put("playerCreatedAtMs", mark.playerCreatedAtMs)
                .put("prepareAtMs", mark.prepareAtMs)
                .put("firstFrameAtMs", mark.firstFrameAtMs)
                .put("playingAtMs", mark.playingAtMs)
            if (mark.commandCreatedAtMs > 0L && mark.firstFrameAtMs > 0L) {
                payload.put("commandToFirstFrameMs",
                    mark.firstFrameAtMs - mark.commandCreatedAtMs)
            }
            if (mark.activityCreatedAtMs > 0L && mark.firstFrameAtMs > 0L) {
                payload.put("activityToFirstFrameMs",
                    mark.firstFrameAtMs - mark.activityCreatedAtMs)
            }
            if (mark.prepareAtMs > 0L && mark.firstFrameAtMs > 0L) {
                payload.put("prepareToFirstFrameMs",
                    mark.firstFrameAtMs - mark.prepareAtMs)
            }
            NativeMailbox.writeBestEffort(
                activity,
                JSONObject().put("type", "performance_player")
                    .put("requestId", id).put("payload", payload),
            )
        }
        if (playerMarks.size > 128) playerMarks.remove(playerMarks.keys.first())
    }

    private fun maybeFlush(activity: Activity) {
        if (SystemClock.elapsedRealtime() - lastFlushAtMs < FLUSH_INTERVAL_MS) return
        lastFlushAtMs = SystemClock.elapsedRealtime()
        val count = frames
        val avgMs = if (count == 0L) 0.0
        else totalFrameDurationNs.toDouble() / count.toDouble() / 1_000_000.0
        NativeMailbox.writeBestEffort(
            activity,
            JSONObject().put("type", "performance_metrics")
                .put("payload", JSONObject()
                    .put("stage", "frame_window")
                    .put("frames", count)
                    .put("framesOverBudget", framesOverBudget)
                    .put("jankyFrames", jankyFrames)
                    .put("framesOver16Ms", framesOverBudget)
                    .put("framesOver32Ms", framesOver32Ms)
                    .put("framesOver50Ms", framesOver50Ms)
                    .put("framesOver100Ms", framesOver100Ms)
                    .put("avgFrameMs", avgMs)
                    .put("maxFrameMs", maxFrameDurationNs.toDouble() / 1_000_000.0)
                    .put("refreshRateHz", refreshRateHz)
                    .put("frameBudgetMs",
                        frameBudgetNs().toDouble() / 1_000_000.0)
                    .put("memoryPssKb", lastMemoryPssKb)),
        )
        frames = 0L
        framesOverBudget = 0L
        jankyFrames = 0L
        framesOver32Ms = 0L
        framesOver50Ms = 0L
        framesOver100Ms = 0L
        totalFrameDurationNs = 0L
        maxFrameDurationNs = 0L
    }

    private fun frameBudgetNs(): Long =
        (1_000_000_000.0 / refreshRateHz.toDouble()).toLong().coerceAtLeast(1L)
}
