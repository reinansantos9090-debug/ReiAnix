package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.player.DeviceInteractionProfile
import com.reiflix.reiflix_local.player.LocalSubtitleResolver
import com.reiflix.reiflix_local.player.PlayerLocalMetadataStore
import com.reiflix.reiflix_local.player.PlayerMediaPolicy
import com.reiflix.reiflix_local.player.PlayerStartupMetrics
import com.reiflix.reiflix_local.player.PlayerTimeFormatter
import com.reiflix.reiflix_local.player.SystemUiController
import com.reiflix.reiflix_local.scanner.BroadStorageScanner
import com.reiflix.reiflix_local.scanner.MediaStoreScanner
import com.reiflix.reiflix_local.scanner.SafScanner

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.animation.ValueAnimator
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.reiflix.reiflix_local.ui.motion.ReiAnixMotionPolicy
import com.reiflix.reiflix_local.ui.player.ReiAnixNativePlayerTopControls
import com.reiflix.reiflix_local.ui.player.ReiAnixNativePlayerCenterControls
import com.reiflix.reiflix_local.ui.player.ReiAnixNativePlayerBottomControls
import com.reiflix.reiflix_local.ui.player.ReiAnixNativePlayerUiState
import com.reiflix.reiflix_local.ui.theme.ReiAnixComposeTheme
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Full-screen Media3 player for one persisted local, SAF, or MediaStore URI. */
@OptIn(UnstableApi::class)
class NativePlayerActivity : ComponentActivity() {
    private val traceId = UUID.randomUUID().toString()
    private val activityInstanceId = UUID.randomUUID().toString()
    private lateinit var player: ExoPlayer
    private lateinit var composeTopView: ComposeView
    private lateinit var composeCenterView: ComposeView
    private lateinit var composeBottomView: ComposeView
    private val composePlayerUiState = mutableStateOf(ReiAnixNativePlayerUiState())
    private var composeControlsEnabled = false
    private lateinit var uri: Uri
    private lateinit var playerView: PlayerView
    private lateinit var root: FrameLayout
    private lateinit var controls: FrameLayout
    private lateinit var centerControls: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var playPauseButton: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var positionLabel: TextView
    private lateinit var durationLabel: TextView
    private lateinit var feedback: TextView
    private lateinit var errorPanel: LinearLayout
    private lateinit var preparingIndicator: ProgressBar
    private lateinit var lockButton: TextView
    private lateinit var gesturePreferences: SharedPreferences
    private lateinit var systemUiController: SystemUiController
    private lateinit var localMetadataStore: PlayerLocalMetadataStore

    private var locked = false
    private var inPictureInPicture = false
    @Volatile
    private var sessionState = SessionState.ACTIVE
    private var playerGeneration = 0L
    @Volatile
    private var transitionGeneration = 0L
    private var transitionPublishFuture: Future<*>? = null
    private var playerSessionId = UUID.randomUUID().toString()
    private var transitionSourceRequestId = ""
    private var transitionSourceUri = ""
    private var transitionSourceCreatedAtMs = 0L
    private var transitionSourceGeneration = 0L
    private var transitionSourceDirection = ""
    private var transitionSourceMonotonicNs = 0L
    private var playerCommandSequence = 0L
    private var lastPlayerCommandSequence = 0L
    private var transitionStartedAtMs = 0L
    private var activePlayerListener: Player.Listener? = null
    private var errorPublishedForGeneration = false
    private var gestureSafeLeft = 0
    private var gestureSafeTop = 0
    private var gestureSafeRight = 0
    private var gestureSafeBottom = 0
    private var volumeGesturesEnabled = false
    private var brightnessGesturesEnabled = false
    private var doubleTapEnabled = true
    private var longPressEnabled = false
    private var windowBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var autoHideTimeoutMs = CONTROL_TIMEOUT_MS
    private var immersiveSetting = "always"
    private var pipEnabled = true
    private var preferredAudioLanguage = ""
    private var preferredSubtitleLanguage = ""
    private var subtitleMode = "auto"
    private var zoomEnabled = false
    private var doubleTapSeekMs = 10_000L
    private var longPressSpeed = 2f
    private var maxVideoResolution = "auto"
    private var maxVideoFrameRate = 0
    private var maxAudioChannels = 0
    private var subtitleScale = 1f
    private var subtitleBottomPaddingPercent = 8
    private var subtitleEmbeddedStyle = true

    private val handler = Handler(Looper.getMainLooper())
    private var lastSavedPosition = -1L
    private var lastProgressPersistAt = 0L
    private var suppressExitEvent = false
    private var exitReported = false
    private var exitProgressPublished = false
    private var initialSeekApplied = false
    private var playbackWasRequestedBeforeStop = false
    private var requestedPlayWhenReadyForGeneration = true
    private var autoplayNext = true
    private var completionReported = false
    private var controlsVisible = true
    private var moreVisible = false
    private var lastControlsInteraction = 0L
    private var requestId = ""
    private var originRequestId = ""
    private var originCreatedAtMs = 0L
    private var originTransitionGeneration = 0L
    private var originMonotonicNs = 0L
    private var commandCreatedAtMs = 0L
    private var commandReceivedAtMs = 0L
    private var handoffDispatchedAtMs = 0L
    private var activityStartedAtMs = 0L
    private var episodeTapAtMs = 0L
    private var preflightStartedAtMs = 0L
    private var preflightCompletedAtMs = 0L
    private var prepareDispatchedAtMs = 0L
    private var playerReadyAtMs = 0L
    private var firstFrameRenderedAtMs = 0L
    private var initialPositionMsForGeneration = 0L
    private var errorVisible = false
    private var openedReported = false
    private var restoredPositionMs: Long? = null
    private var localMetadata = PlayerLocalMetadataStore.Metadata.empty()
    private var aspectModeLabel = "Ajustar"
    private var episodeChangePending = false
    private var episodeChangeTimeoutRequestId = ""
    private var episodeChangeTimeoutUri = ""
    private var episodeChangeTimeoutGeneration = 0L
    private var episodeChangeTimeoutSessionId = ""
    private var episodeChangeTimeoutPlayerGeneration = 0L
    private var transitionReadyGeneration = -1L
    private var nextTransitionActive = false
    private var previousTransitionActive = false
    private var originPlayerSessionId = ""
    private fun armEpisodeChangeTimeout(reason: String) {
        if (!episodeChangePending ||
            sessionState != SessionState.ACTIVE ||
            !::uri.isInitialized
        ) {
            return
        }
        episodeChangeTimeoutRequestId = requestId
        episodeChangeTimeoutUri = uri.toString()
        episodeChangeTimeoutGeneration = transitionGeneration
        episodeChangeTimeoutSessionId = playerSessionId
        episodeChangeTimeoutPlayerGeneration = playerGeneration
        handler.removeCallbacks(episodeChangeTimeout)
        handler.postDelayed(episodeChangeTimeout, 5_000L)
        logPlayer(
            "EPISODE_CHANGE_WATCHDOG_ARMED requestId=" + requestId.ifEmpty { "-" } +
                " transitionGeneration=" + transitionGeneration +
                " playerGeneration=" + playerGeneration +
                " playerSessionId=" + playerSessionId +
                " reason=" + reason,
        )
    }

    private val episodeChangeTimeout = Runnable {
        if (!episodeChangePending) return@Runnable
        val timeoutContextValid =
            episodeChangeTimeoutRequestId == requestId &&
                episodeChangeTimeoutUri == uri.toString() &&
                episodeChangeTimeoutGeneration == transitionSourceGeneration &&
                episodeChangeTimeoutGeneration == transitionGeneration &&
                episodeChangeTimeoutSessionId == playerSessionId &&
                episodeChangeTimeoutPlayerGeneration == playerGeneration
        if (!timeoutContextValid) {
            publishNavigationTransitionDiagnostic(
                "PLAYER_TIMEOUT_STALE",
                "context_mismatch",
                JSONObject()
                    .put("requestId", requestId)
                    .put("playerSessionId", playerSessionId)
                    .put("playerGeneration", playerGeneration)
                    .put("transitionGeneration", transitionGeneration),
            )
            return@Runnable
        }

        val direction = when {
            nextTransitionActive -> "NEXT"
            previousTransitionActive -> "PREVIOUS"
            else -> "NONE"
        }
        if (direction == "NEXT") {
            publishNextTransitionDiagnostic(
                "NEXT_TRANSITION_STALLED",
                "watchdog_elapsed",
                JSONObject().put(
                    "ageMs",
                    transitionStartedAtMs.takeIf { it > 0L }?.let { System.currentTimeMillis() - it } ?: 0L,
                ),
            )
        } else if (direction == "PREVIOUS") {
            publishPreviousTransitionDiagnostic(
                "PREVIOUS_TRANSITION_STALLED",
                "watchdog_elapsed",
                JSONObject().put(
                    "ageMs",
                    transitionStartedAtMs.takeIf { it > 0L }?.let { System.currentTimeMillis() - it } ?: 0L,
                ),
            )
        }
        logPlayer(
            "EPISODE_CHANGE_WATCHDOG requestId=" + requestId.ifEmpty { "-" } +
                " transitionGeneration=" + transitionGeneration +
                " active=true direction=" + direction,
        )
    }
    private var retryCount = 0
    internal var firstFrameRenderedForTesting = false
        private set
    private var feedbackHideAt = 0L
    private var feedbackSessionId = ""
    private var feedbackRequestId = ""
    private var feedbackPlayerGeneration = 0L
    private var controlsRestoredFromState = false
    private var isTelevision = false
    private var cinemaMode = false
    private var contentMimeType: String? = null
    private var mediaDisplayName: String? = null
    private var mediaSizeBytes: Long? = null
    private var decoderVideoName: String? = null
    private var decoderAudioName: String? = null
    private var videoFormatSummary: String? = null
    private var audioFormatSummary: String? = null
    private var subtitleFormatSummary: String? = null
    private var currentErrorCategory = PlayerMediaPolicy.ErrorCategory.UNKNOWN
    private var currentFailureKind = PlayerMediaPolicy.PlaybackFailureKind.UNKNOWN
    private var currentFailureRetryable = false
    private var playbackErrorForGeneration = false
    private var lastMediaPeriodId = ""
    private var lastLoadUri = ""
    private var lastLoadDataType = -1
    private var lastLoadTrackType = -1
    private var lastLoadErrorClass = ""
    private var lastLoadErrorMessage = ""
    private var pendingPreparation: Future<*>? = null
    /**
     * Control-plane I/O (Next/Previous/exit handoff) is intentionally isolated from
     * progress persistence. A slow SQLite/mailbox consumer must never occupy the
     * same single-thread queue that owns episode transitions.
     */
    private val playbackWorker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ReiAnix-PlayerIO").apply { isDaemon = true }
    }
    /**
     * Progress remains on the existing mailbox + SQLite pipeline, but uses its own
     * single-thread publisher so durable checkpoints cannot delay transition control.
     */
    private val progressWorker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ReiAnix-ProgressIO").apply { isDaemon = true }
    }
    /**
     * Local-media enrichment is isolated from the playback control plane.
     * It never blocks Media3 from receiving a URI that is already known locally.
     */
    private val mediaMetadataWorker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ReiAnix-MediaMetadata").apply { isDaemon = true }
    }
    private val deferredSubtitleCache =
        ConcurrentHashMap<String, List<LocalSubtitleResolver.SubtitleTrack>>()
    private var activeAnalyticsListener: AnalyticsListener? = null
    private var firstFrameWatchGeneration = -1L
    private val firstFrameDiagnosticTimeoutMs = 8_000L

    private val titleValue: String
        get() = intent.getStringExtra("title") ?: "Episódio"

    private val controlsHider = object : Runnable {
        override fun run() {
            if (!controlsVisible ||
                errorVisible ||
                locked ||
                !::player.isInitialized ||
                !player.isPlaying ||
                autoHideTimeoutMs <= 0L
            ) {
                return
            }

            val elapsed = System.currentTimeMillis() - lastControlsInteraction
            val remaining = autoHideTimeoutMs - elapsed
            if (remaining <= 0L) {
                setControlsVisible(false)
            } else {
                handler.postDelayed(this, remaining)
            }
        }
    }

    private val lockAffordanceHider = object : Runnable {
        override fun run() {
            if (locked && !errorVisible && !inPictureInPicture) {
                controlsVisible = false
                controls.visibility = View.INVISIBLE
                topBar.visibility = View.GONE
                centerControls.visibility = View.GONE
                bottomBar.visibility = View.GONE
                moreVisible = false
                findViewByTag<View>("reiflix_more_panel")?.visibility = View.GONE
            }
        }
    }

    private val feedbackHider = object : Runnable {
        override fun run() {
            if (feedbackHideAt > 0L && System.currentTimeMillis() >= feedbackHideAt) {
                feedbackHideAt = 0L
                if (::feedback.isInitialized) {
                    feedback.visibility = if (
                        sessionState == SessionState.ACTIVE &&
                        feedbackSessionId == playerSessionId &&
                        feedbackRequestId == requestId &&
                        feedbackPlayerGeneration == playerGeneration
                    ) View.GONE else View.GONE
                }
            } else if (feedbackHideAt > 0L) {
                handler.postDelayed(this, 120L)
            }
        }
    }

    private val progressReporter = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (now - lastProgressPersistAt >= PROGRESS_PERSIST_INTERVAL_MS) {
                saveProgress("player_progress")
                lastProgressPersistAt = now
            }
            updateProgressUi()
            if (::player.isInitialized && player.playbackState != Player.STATE_ENDED) {
                handler.postDelayed(this, PROGRESS_INTERVAL_MS)
            }
        }
    }

    /**
     * Diagnostics-only first-frame watchdog. It never retries playback and never
     * converts a slow renderer into an automatic error/restart loop.
     */
    private val firstFrameDiagnostic = object : Runnable {
        override fun run() {
            val generation = firstFrameWatchGeneration
            if (generation < 0L ||
                generation != playerGeneration ||
                sessionState != SessionState.ACTIVE ||
                firstFrameRenderedForTesting ||
                errorVisible ||
                !::player.isInitialized
            ) {
                return
            }

            val payload = diagnosticPayload()
                .put("event", "FIRST_FRAME_TIMEOUT")
                .put("stage", "FIRST_FRAME")
                .put("failureStage", "FIRST_FRAME_WAIT")
                .put("failureKind", PlayerMediaPolicy.PlaybackFailureKind.UNKNOWN.name)
                .put("diagnosticOnly", true)
                .put("playbackState", player.playbackStateLabel())
                .put("generation", generation)
                .put("playWhenReady", player.playWhenReady)
                .put("videoWidth", player.videoSize.width)
                .put("videoHeight", player.videoSize.height)
                .put("playerViewAttached", ::playerView.isInitialized && playerView.isAttachedToWindow)
                .put("playerViewVisible", ::playerView.isInitialized && playerView.isShown)
                .put("playerViewWidth", if (::playerView.isInitialized) playerView.width else 0)
                .put("playerViewHeight", if (::playerView.isInitialized) playerView.height else 0)
                .put("windowFocus", window.decorView.hasWindowFocus())
                .put("orientation", resources.configuration.orientation)
                .put("surfaceType", "texture_view")

            logPlayer(
                "FIRST_FRAME_TIMEOUT requestId=" + requestId.ifEmpty { "-" } +
                    " generation=" + generation +
                    " state=" + player.playbackStateLabel() +
                    " isPlaying=" + player.isPlaying +
                    " playWhenReady=" + player.playWhenReady +
                    " video=" + player.videoSize.width + "x" + player.videoSize.height +
                    " playerView=" + playerView.width + "x" + playerView.height +
                    " attached=" + playerView.isAttachedToWindow +
                    " focus=" + window.decorView.hasWindowFocus() +
                    " orientation=" + resources.configuration.orientation +
                    " surfaceType=texture_view",
            )
            val written = NativeMailbox.writeBestEffort(
                this@NativePlayerActivity,
                JSONObject()
                    .put("type", "player_diagnostic")
                    .put("requestId", requestId)
                    .put("payload", payload),
            )
            if (!written) {
                logPlayer("FAILED_TO_PUBLISH player_diagnostic requestId=" + requestId.ifEmpty { "-" })
            }
            firstFrameWatchGeneration = -1L
        }
    }

    private fun armFirstFrameDiagnostics(generation: Long) {
        handler.removeCallbacks(firstFrameDiagnostic)
        if (sessionState != SessionState.ACTIVE ||
            firstFrameRenderedForTesting ||
            errorVisible ||
            generation != playerGeneration
        ) {
            firstFrameWatchGeneration = -1L
            return
        }
        firstFrameWatchGeneration = generation
        handler.postDelayed(firstFrameDiagnostic, firstFrameDiagnosticTimeoutMs)
        logPlayer(
            "FIRST_FRAME_WATCH_ARMED requestId=" + requestId.ifEmpty { "-" } +
                " generation=" + generation +
                " timeoutMs=" + firstFrameDiagnosticTimeoutMs,
        )
    }

    private fun cancelFirstFrameDiagnostics(reason: String) {
        handler.removeCallbacks(firstFrameDiagnostic)
        if (firstFrameWatchGeneration >= 0L) {
            logPlayer(
                "FIRST_FRAME_WATCH_CANCELLED requestId=" + requestId.ifEmpty { "-" } +
                    " generation=" + firstFrameWatchGeneration +
                    " reason=" + reason,
            )
        }
        firstFrameWatchGeneration = -1L
    }

        private fun publishPlayerLifecycle(event: String) {
        NativeMailbox.writeBestEffort(
            this,
            JSONObject().put("type", "diagnostic")
                .put("requestId", requestId)
                .put("payload", JSONObject()
                    .put("event", "PLAYER_LIFECYCLE")
                    .put("lifecycle", event)
                    .put("traceId", traceId)
                    .put("requestId", requestId)
                    .put("playerGeneration", playerGeneration)
                    .put("transitionGeneration", transitionGeneration)
                    .put("sessionState", sessionState.name)
                    .put("activityElapsedRealtimeNs", SystemClock.elapsedRealtimeNanos())
                    .put("playerSessionId", playerSessionId)
                    .put("activityInstanceId", activityInstanceId)
                    .put("originRequestId", originRequestId)
                    .put("originCreatedAtMs", originCreatedAtMs)
                    .put("originTransitionGeneration", originTransitionGeneration)
                    .put("originPlayerSessionId", originPlayerSessionId)
                    .put("transitionDirection", intent.getStringExtra("transitionDirection").orEmpty())
                    .put("episodeId", currentEpisodeId())),
        )
    }

override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        playerSessionId = savedInstanceState?.getString("player_session_id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: intent.getStringExtra("playerSessionId")?.trim()?.takeIf { it.isNotEmpty() }
            ?: playerSessionId
        playerGeneration = savedInstanceState?.getLong("player_generation", playerGeneration) ?: playerGeneration
        transitionGeneration = savedInstanceState?.getLong("transition_generation", transitionGeneration) ?: transitionGeneration
        requestId = savedInstanceState?.getString("session_request_id")?.trim()
            ?: intent.getStringExtra("requestId")?.trim().orEmpty()
        originRequestId = intent.getStringExtra("originRequestId")?.trim().orEmpty()
        originCreatedAtMs = intent.getLongExtra("originCreatedAtMs", 0L)
        originMonotonicNs = intent.getLongExtra("originMonotonicNs", 0L)
        originPlayerSessionId = intent.getStringExtra("originPlayerSessionId")?.trim().orEmpty()
        originTransitionGeneration = intent.getLongExtra("originTransitionGeneration", 0L)
        val originTransitionDirection = intent.getStringExtra("transitionDirection")?.trim()?.uppercase().orEmpty()
        val recreatedPlayer = savedInstanceState?.getString("session_request_id")?.trim()
            ?.takeIf { it.isNotEmpty() } == requestId &&
            savedInstanceState?.getString("player_session_id")?.trim() == playerSessionId

        if (recreatedPlayer) {
            episodeChangePending = savedInstanceState.getBoolean("episode_change_pending", false)
            transitionPhase = runCatching {
                TransitionPhase.valueOf(savedInstanceState.getString("transition_phase").orEmpty())
            }.getOrElse { TransitionPhase.IDLE }
            transitionSourceRequestId = savedInstanceState.getString("transition_source_request_id").orEmpty()
            transitionSourceUri = savedInstanceState.getString("transition_source_uri").orEmpty()
            transitionSourceCreatedAtMs = savedInstanceState.getLong("transition_source_created_at_ms", 0L)
            transitionSourceGeneration = savedInstanceState.getLong("transition_source_generation", 0L)
            transitionSourceDirection = savedInstanceState.getString("transition_source_direction").orEmpty()
            transitionSourceMonotonicNs = savedInstanceState.getLong("transition_source_monotonic_ns", 0L)
            transitionStartedAtMs = savedInstanceState.getLong("transition_started_at_ms", 0L)
            transitionReadyGeneration = savedInstanceState.getLong("transition_ready_generation", -1L)
            nextTransitionActive = savedInstanceState.getBoolean("next_transition_active", false)
            previousTransitionActive = savedInstanceState.getBoolean("previous_transition_active", false)
        }

        val isEpisodeSuccessor = if (recreatedPlayer) {
            false
        } else {
            originRequestId.isNotBlank() && MainActivity.isCurrentPlayerHandoff(
                requestId = requestId,
                originRequestId = originRequestId,
                originCreatedAtMs = originCreatedAtMs,
                playerSessionId = playerSessionId,
                originPlayerSessionId = originPlayerSessionId,
                originTransitionGeneration = originTransitionGeneration,
            )
        }

        if (originRequestId.isNotBlank() && !isEpisodeSuccessor && !recreatedPlayer) {
            val staleEvent = if (originTransitionDirection == "PREVIOUS") "PREVIOUS_REQUEST_STALE" else "NEXT_REQUEST_STALE"
            val staleRejectedEvent = if (originTransitionDirection == "PREVIOUS") "PLAYER_PREVIOUS_STALE_REJECTED" else "PLAYER_NEXT_STALE_REJECTED"
            publishNavigationTransitionDiagnostic(
                staleEvent,
                "origin_on_new_activity",
                JSONObject()
                    .put("requestId", requestId)
                    .put("originRequestId", originRequestId)
                    .put("originCreatedAtMs", originCreatedAtMs)
                    .put("originGeneration", originTransitionGeneration)
                    .put("originPlayerSessionId", originPlayerSessionId)
                    .put("currentPlayerSessionId", playerSessionId)
                    .put("activityInstanceId", activityInstanceId),
            )
            publishNavigationTransitionDiagnostic(
                staleRejectedEvent,
                "origin_on_new_activity",
                JSONObject()
                    .put("requestId", requestId)
                    .put("originRequestId", originRequestId)
                    .put("originCreatedAtMs", originCreatedAtMs)
                    .put("originGeneration", originTransitionGeneration)
                    .put("currentGeneration", transitionGeneration)
                    .put("originPlayerSessionId", originPlayerSessionId)
                    .put("currentPlayerSessionId", playerSessionId)
                    .put("activityInstanceId", activityInstanceId),
            )
            suppressExitEvent = true
            sessionState = SessionState.EXITING
            logPlayer(
                "PLAYER_EXIT_CLASSIFICATION=STALE_HANDOFF reason=stale_handoff" +
                    " requestId=" + requestId.ifEmpty { "-" } +
                    " traceId=" + traceId +
                    " activityInstanceId=" + activityInstanceId,
            )
            finish()
            return
        }

        if (originRequestId.isNotBlank() && isEpisodeSuccessor) {
            transitionGeneration = maxOf(transitionGeneration, originTransitionGeneration)
            setTransitionPhase(TransitionPhase.TARGET_ACTIVITY_ACTIVE, "authorized_successor_activity")
            episodeChangePending = true
            transitionSourceRequestId = originRequestId
            transitionSourceUri = intent.getStringExtra("uri").orEmpty()
            transitionSourceCreatedAtMs = originCreatedAtMs
            transitionSourceGeneration = transitionGeneration
            transitionSourceDirection = originTransitionDirection
            transitionSourceMonotonicNs = originMonotonicNs
            transitionStartedAtMs = originCreatedAtMs
            nextTransitionActive = originTransitionDirection == "NEXT"
            previousTransitionActive = originTransitionDirection == "PREVIOUS"
            transitionReadyGeneration = -1L
            publishNavigationTransitionDiagnostic(
                if (nextTransitionActive) "NEXT_TRANSITION_ACTIVITY_CREATED" else "PREVIOUS_TRANSITION_ACTIVITY_CREATED",
                "authorized_successor_activity",
                JSONObject()
                    .put("originRequestId", originRequestId)
                    .put("requestId", requestId)
                    .put("originGeneration", originTransitionGeneration)
                    .put("transitionGeneration", transitionGeneration)
                    .put("playerSessionId", playerSessionId)
                    .put("activityInstanceId", activityInstanceId),
            )
        }

        publishPlayerLifecycle("onCreate")
        publishNavigationTransitionDiagnostic(
            if (isEpisodeSuccessor) "PLAYER_EPISODE_TRANSITION" else "PLAYER_SESSION_CREATED",
            "onCreate",
            JSONObject()
                .put("playerSessionId", playerSessionId)
                .put("playerGeneration", playerGeneration)
                .put("transitionGeneration", transitionGeneration)
                .put("activityInstanceId", activityInstanceId)
                .put("originRequestId", originRequestId)
                .put("transitionDirection", originTransitionDirection),
        )
        MainActivity.notePlayerActivityCreated(
            activityInstanceId,
            requestId,
            playerSessionId,
            transitionGeneration,
        )

        val traceEpisodeId = intent.getStringExtra("episodeId").orEmpty()
        val traceAnimeId = intent.getStringExtra("animeId").orEmpty()
        PerformanceDiagnostics.attach(this)
        PerformanceDiagnostics.markPlayer(this, "activity_created", requestId,
            intent.getLongExtra("commandCreatedAtMs", 0L), reused = false)
        commandCreatedAtMs = intent.getLongExtra("commandCreatedAtMs", 0L)
        commandReceivedAtMs = intent.getLongExtra("commandReceivedAtMs", 0L)
        handoffDispatchedAtMs = intent.getLongExtra("handoffDispatchedAtMs", 0L)
        activityStartedAtMs = System.currentTimeMillis()
        sessionState = SessionState.ACTIVE
        MainActivity.notePlayerSession(requestId, playerSessionId, transitionGeneration)
        gesturePreferences = getSharedPreferences("reiflix_player_preferences", Context.MODE_PRIVATE)
        localMetadataStore = PlayerLocalMetadataStore(this)
        volumeGesturesEnabled = intent.getBooleanExtra("setting_gestures_volume",
            gesturePreferences.getBoolean(PREF_GESTURES_VOLUME, false))
        brightnessGesturesEnabled = intent.getBooleanExtra("setting_gestures_brightness",
            gesturePreferences.getBoolean(PREF_GESTURES_BRIGHTNESS, false))
        doubleTapEnabled = intent.getBooleanExtra("setting_gestures_double_tap",
            gesturePreferences.getBoolean(PREF_GESTURES_DOUBLE_TAP, true))
        longPressEnabled = intent.getBooleanExtra("setting_gestures_long_press",
            gesturePreferences.getBoolean(PREF_GESTURES_LONG_PRESS, false))
        immersiveSetting = intent.getStringExtra("setting_player_immersive") ?: "always"
        val interactionProfile = DeviceInteractionProfile.detect(this)
        isTelevision = interactionProfile.isTelevision
        cinemaMode = isTelevision
        logPlayer("DEVICE_INTERACTION isTelevision=" + isTelevision + " dpad=" + interactionProfile.hasDpad + " gamepad=" + interactionProfile.hasGamepad + " touch=" + interactionProfile.hasTouchscreen + " cinemaMode=" + cinemaMode)
        autoHideTimeoutMs = intent.getIntExtra("setting_player_auto_hide_seconds", 5)
            .coerceIn(0, 300) * 1000L
        pipEnabled = intent.getBooleanExtra("setting_player_pip", true)
        preferredAudioLanguage = intent.getStringExtra("setting_audio_preferred_language")?.trim().orEmpty()
        preferredSubtitleLanguage = intent.getStringExtra("setting_audio_preferred_subtitle_language")?.trim().orEmpty()
        subtitleMode = intent.getStringExtra("setting_audio_subtitles") ?: "auto"
        zoomEnabled = intent.getBooleanExtra("setting_player_zoom_enabled", false)
        doubleTapSeekMs = intent.getLongExtra("setting_player_double_tap_seek_seconds", 10L)
            .coerceIn(1L, 120L) * 1000L
        longPressSpeed = intent.getFloatExtra("setting_player_long_press_speed", 2f)
            .coerceIn(1f, 3f)
        maxVideoResolution = intent.getStringExtra("setting_player_max_video_resolution") ?: "auto"
        maxVideoFrameRate = intent.getIntExtra("setting_player_max_video_frame_rate", 0).coerceAtLeast(0)
        maxAudioChannels = intent.getIntExtra("setting_player_max_audio_channels", 0).coerceAtLeast(0)
        subtitleScale = intent.getFloatExtra("setting_audio_subtitle_scale", 1f)
            .coerceIn(0.5f, 2f)
        subtitleBottomPaddingPercent = intent.getIntExtra("setting_audio_subtitle_bottom_padding", 8)
            .coerceIn(0, 50)
        subtitleEmbeddedStyle = intent.getBooleanExtra("setting_audio_subtitle_embedded_style", true)
        locked = savedInstanceState?.getBoolean("lock_mode", false)
            ?: gesturePreferences.getBoolean(PREF_LOCK_MODE, false)
        controlsVisible = savedInstanceState?.getBoolean("controls_visible", true) ?: true
        controlsRestoredFromState = savedInstanceState?.containsKey("controls_visible") == true
        logPlayer(
            "PLAYER_ACTIVITY_ON_CREATE requestId=" + requestId.ifEmpty { "-" } +
                " commandCreatedAtMs=" + commandCreatedAtMs +
                " handoffDispatchedAtMs=" + handoffDispatchedAtMs +
                " activityStartedAtMs=" + activityStartedAtMs +
                " task=" + taskId +
                " intentAction=" + (intent.action ?: "-") +
                " component=" + (intent.component?.flattenToShortString() ?: "-"),
        )

        applyConfiguredRotation()
        systemUiController = SystemUiController(window)
        configureWindow()
        if (shouldUseImmersive()) enterImmersiveMode() else restoreSystemUiBeforeExit()
        savedInstanceState?.getFloat("window_brightness", WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            ?.takeIf { it.isFinite() && it >= 0f && it <= 1f }
            ?.let { setWindowBrightness(it) }
        restoredPositionMs = savedInstanceState?.takeIf { it.containsKey("position_ms") }?.getLong("position_ms")
        aspectModeLabel = savedInstanceState?.getString("aspect_mode_label")
            ?: aspectLabelFromSetting(intent.getStringExtra("setting_player_aspect_ratio"))
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = false
            clipToPadding = false
        }
        setContentView(root)
        installBasePlayerView()
        installGestureLayer()
        installControls()
        setLocked(locked, persist = false, announce = false)
        if (controlsRestoredFromState && !locked) {
            setControlsVisible(controlsVisible)
        }
        installBackHandler()
        configurePictureInPicture()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            applyRootInsets(insets)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        ViewCompat.getRootWindowInsets(window.decorView)?.let { applyRootInsets(it) }

        val rawUri = intent.getStringExtra("uri")?.takeIf { it.isNotBlank() }
            ?: savedInstanceState?.getString("session_uri")?.takeIf { it.isNotBlank() }
        logPlayer("URI_RECEIVED requestId=" + requestId.ifEmpty { "-" } +
            " uriOriginal=" + rawUri.orEmpty())
        if (rawUri.isNullOrBlank()) {
            showPlayerError("Arquivo local inválido.", "missing_uri")
            return
        }

        val resolvedUri = normalizeLocalReference(rawUri)
        logPlayer("URI_NORMALIZED requestId=" + requestId.ifEmpty { "-" } +
            " uriNormalized=" + resolvedUri +
            " scheme=" + (resolvedUri?.scheme ?: "-") +
            " authority=" + (resolvedUri?.authority ?: "-"))
        if (resolvedUri == null) {
            showPlayerError("Referência local inválida.", "invalid_uri")
            return
        }
        uri = resolvedUri
        loadLocalMetadataAsync(transitionGeneration, resolvedUri)

        logPlayer("PREFLIGHT_DEFERRED requestId=" + requestId.ifEmpty { "-" } +
            " source=" + sourceFor(uri) + " reason=background_io")

        try {
            logPlayer("EXOPLAYER_CREATE requestId=" + requestId.ifEmpty { "-" })
            logPlayer("MEDIA3_PLAYER_CREATE_START requestId=" + requestId.ifEmpty { "-" })
            player = ExoPlayer.Builder(this).build()
            PerformanceDiagnostics.markPlayer(this, "player_created", requestId,
                commandCreatedAtMs, reused = false)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            // PlayerView owns the video surface/subtitle rendering. Attach the
            // real ExoPlayer before assigning media or preparing it.
            playerView.player = player
            check(playerView.player === player) {
                "PlayerView failed to attach the ExoPlayer instance"
            }
            logPlayer("PLAYER_VIEW_ATTACHED requestId=" + requestId.ifEmpty { "-" } +
                " sameInstance=" + (playerView.player === player))
            savedInstanceState?.getBundle("track_selection_parameters")?.let { bundle ->
                runCatching { TrackSelectionParameters.fromBundle(bundle) }
                    .onSuccess { player.trackSelectionParameters = it }
                    .onFailure { error -> logPlayer("TRACK_SELECTION_RESTORE_FAILED " + error) }
            }
            applyGlobalTrackPreferences()
            applyAdvancedTrackConstraints()
            applySubtitlePreferences()
            autoplayNext = savedInstanceState?.takeIf { it.containsKey("autoplay_next") }?.getBoolean("autoplay_next")
                ?: intent.getBooleanExtra("autoplay", true)

            val savedSpeed = savedInstanceState?.takeIf { it.containsKey("playback_speed") }
                ?.getFloat("playback_speed")
                ?: intent.getFloatExtra("setting_player_default_speed", 1f)
            if (savedSpeed > 0f && savedSpeed.isFinite()) {
                player.setPlaybackSpeed(savedSpeed)
            }

            val savedResize = savedInstanceState?.takeIf { it.containsKey("resize_mode") }
                ?.getInt("resize_mode", AspectRatioFrameLayout.RESIZE_MODE_FIT)
                ?: resizeModeFromSetting(intent.getStringExtra("setting_player_aspect_ratio"))
            playerView.resizeMode = savedResize

            prepareCurrentMedia("initial", savedInstanceState?.takeIf { it.containsKey("play_when_ready") }?.getBoolean("play_when_ready"))
        } catch (exception: Exception) {
            if (::preparingIndicator.isInitialized) preparingIndicator.visibility = View.GONE
            logPlayer(
                "PLAYER_ACTIVITY_FAILED requestId=" + requestId.ifEmpty { "-" } +
                    " generation=" + playerGeneration +
                    " stage=onCreate",
                exception,
            )
            showPlayerError(
                "Não foi possível iniciar o player local.",
                "player_initialization",
                JSONObject().put("stage", "player_activity_create")
                    .put("error", exception.message ?: exception::class.java.simpleName),
            )
        }
    }

    override fun onNewIntent(newIntent: Intent) {
        if (sessionState != SessionState.ACTIVE) {
            logPlayer(
                "PLAYER_REUSE_IGNORED requestId=" +
                    (newIntent.getStringExtra("requestId")?.trim().orEmpty().ifBlank { "-" }) +
                    " reason=session_not_active state=" + sessionState.name,
            )
            return
        }
        val incomingPlayerSessionId = newIntent.getStringExtra("playerSessionId")?.trim().orEmpty()
        val incomingRequestId = newIntent.getStringExtra("requestId")?.trim().orEmpty()
        val incomingOriginRequestId = newIntent.getStringExtra("originRequestId")?.trim().orEmpty()
        val incomingOriginCreatedAtMs = newIntent.getLongExtra("originCreatedAtMs", 0L)
        val incomingOriginTransitionGeneration = newIntent.getLongExtra("originTransitionGeneration", 0L)
        val incomingOriginPlayerSessionId = newIntent.getStringExtra("originPlayerSessionId")?.trim().orEmpty()
        val incomingOriginMonotonicNs = newIntent.getLongExtra("originMonotonicNs", 0L)
        val incomingOriginTransitionDirection = newIntent.getStringExtra("transitionDirection")?.trim()?.uppercase().orEmpty()

        // Validate a reused-player transition before mutating the Activity intent
        // or cancelling the currently valid transition. A delayed duplicate must
        // be rejected without disturbing the already active Next operation.
        val transitionPending = episodeChangePending
        val transitionPendingRequestId = transitionSourceRequestId
        val transitionPendingCreatedAtMs = transitionSourceCreatedAtMs
        val transitionPendingGeneration = transitionSourceGeneration
        val expectedSuccessorBeforeMutation =
            transitionPending &&
                incomingOriginRequestId.isNotBlank() &&
                incomingOriginRequestId == transitionPendingRequestId &&
                incomingOriginCreatedAtMs > 0L &&
                incomingOriginCreatedAtMs == transitionPendingCreatedAtMs &&
                incomingOriginTransitionGeneration == transitionPendingGeneration &&
                incomingOriginPlayerSessionId.isNotBlank() &&
                incomingOriginPlayerSessionId == playerSessionId &&
                incomingPlayerSessionId == playerSessionId &&
                incomingOriginMonotonicNs > 0L &&
                incomingOriginMonotonicNs == transitionSourceMonotonicNs &&
                incomingOriginTransitionDirection == transitionSourceDirection

        if (incomingOriginRequestId.isNotBlank() && !expectedSuccessorBeforeMutation) {
            publishNavigationTransitionDiagnostic(
                if (incomingOriginTransitionDirection == "PREVIOUS") "PREVIOUS_REQUEST_STALE" else "NEXT_REQUEST_STALE",
                "invalid_successor_origin",
                JSONObject()
                    .put("requestId", incomingRequestId)
                    .put("originRequestId", incomingOriginRequestId)
                    .put("originCreatedAtMs", incomingOriginCreatedAtMs)
                    .put("originGeneration", incomingOriginTransitionGeneration)
                    .put("originPlayerSessionId", incomingOriginPlayerSessionId)
                    .put("currentPlayerSessionId", playerSessionId),
            )
            publishNavigationTransitionDiagnostic(
                if (incomingOriginTransitionDirection == "PREVIOUS") "PLAYER_PREVIOUS_STALE_REJECTED" else "PLAYER_NEXT_STALE_REJECTED",
                "invalid_successor_origin",
                JSONObject()
                    .put("requestId", incomingRequestId)
                    .put("originRequestId", incomingOriginRequestId)
                    .put("originGeneration", incomingOriginTransitionGeneration)
                    .put("currentGeneration", transitionGeneration)
                    .put("originPlayerSessionId", incomingOriginPlayerSessionId)
                    .put("currentPlayerSessionId", playerSessionId),
            )
            logPlayer(
                "PLAYER_REUSE_ORIGIN_REJECTED requestId=" + incomingRequestId.ifEmpty { "-" } +
                    " originRequestId=" + incomingOriginRequestId +
                    " expectedRequestId=" + transitionPendingRequestId.ifEmpty { "-" } +
                    " originGeneration=" + incomingOriginTransitionGeneration +
                    " expectedGeneration=" + transitionPendingGeneration +
                    " originPlayerSessionId=" + incomingOriginPlayerSessionId +
                    " currentPlayerSessionId=" + playerSessionId,
            )
            return
        }

        super.onNewIntent(newIntent)
        setIntent(newIntent)
        requestId = incomingRequestId
        originRequestId = incomingOriginRequestId
        originCreatedAtMs = incomingOriginCreatedAtMs
        originTransitionGeneration = incomingOriginTransitionGeneration
        originPlayerSessionId = incomingOriginPlayerSessionId
        originMonotonicNs = incomingOriginMonotonicNs
        transitionSourceDirection = incomingOriginTransitionDirection
        publishPlayerLifecycle("onNewIntent")
        val traceEpisodeId = newIntent.getStringExtra("episodeId").orEmpty()
        val traceAnimeId = newIntent.getStringExtra("animeId").orEmpty()
        PerformanceDiagnostics.attach(this)
        PerformanceDiagnostics.markPlayer(
            this,
            "reuse_intent",
            incomingRequestId,
            newIntent.getLongExtra("commandCreatedAtMs", 0L),
            reused = true,
        )
        logPlayer(
            "PLAYER_REUSE_INTENT requestId=" +
                incomingRequestId.ifEmpty { "-" } +
                " animeId=" + traceAnimeId.ifEmpty { "-" } +
                " episodeId=" + traceEpisodeId.ifEmpty { "-" },
        )

        // A validated successor replaces the current media, but the old
        // transition identity is invalidated only after that validation.
        transitionGeneration += 1L
        MainActivity.notePlayerSession(requestId, playerSessionId, transitionGeneration)
        transitionPublishFuture?.cancel(true)
        transitionPublishFuture = null
        handler.removeCallbacks(episodeChangeTimeout)
        episodeChangeTimeoutRequestId = ""
        episodeChangeTimeoutUri = ""
        episodeChangeTimeoutGeneration = 0L
        episodeChangePending = false
        setTransitionPhase(TransitionPhase.IDLE, "reuse_reset")
        transitionSourceRequestId = ""
        transitionSourceUri = ""
        transitionSourceCreatedAtMs = 0L
        transitionSourceDirection = ""
        transitionSourceMonotonicNs = 0L
        nextTransitionActive = false
        previousTransitionActive = false
        transitionReadyGeneration = -1L

        val rawUri = newIntent.getStringExtra("uri")
        if (rawUri.isNullOrBlank()) {
            showPlayerError("Arquivo local inválido.", "missing_uri_on_reuse")
            return
        }
        val normalized = normalizeLocalReference(rawUri)
        if (normalized == null) {
            showPlayerError("Referência local inválida.", "invalid_uri_on_reuse")
            return
        }

        uri = normalized
        loadLocalMetadataAsync(transitionGeneration, uri)
        requestId = newIntent.getStringExtra("requestId")?.trim().orEmpty()
        originRequestId = newIntent.getStringExtra("originRequestId")?.trim().orEmpty()
        originCreatedAtMs = newIntent.getLongExtra("originCreatedAtMs", 0L)
        originTransitionGeneration = newIntent.getLongExtra("originTransitionGeneration", 0L)
        originMonotonicNs = newIntent.getLongExtra("originMonotonicNs", 0L)
        commandCreatedAtMs = newIntent.getLongExtra("commandCreatedAtMs", 0L)
        commandReceivedAtMs = newIntent.getLongExtra("commandReceivedAtMs", 0L)
        handoffDispatchedAtMs = newIntent.getLongExtra("handoffDispatchedAtMs", 0L)
        activityStartedAtMs = System.currentTimeMillis()
        sessionState = SessionState.ACTIVE
        lastSavedPosition = -1L
        errorPublishedForGeneration = false
        restoredPositionMs = null
        initialSeekApplied = false
        completionReported = false
        openedReported = false
        exitReported = false
        suppressExitEvent = false
        errorVisible = false
        playbackWasRequestedBeforeStop = false

        val expectedSuccessor = expectedSuccessorBeforeMutation

        if (expectedSuccessor) {
            episodeChangePending = true
            transitionSourceRequestId = requestId
            transitionSourceUri = uri.toString()
            transitionSourceCreatedAtMs = originCreatedAtMs
            transitionSourceGeneration = transitionGeneration
            transitionSourceDirection = incomingOriginTransitionDirection
            transitionSourceMonotonicNs = incomingOriginMonotonicNs
            nextTransitionActive = incomingOriginTransitionDirection == "NEXT"
            previousTransitionActive = incomingOriginTransitionDirection == "PREVIOUS"
            transitionReadyGeneration = -1L
            // Keep the watchdog identity explicitly synchronized with the
            // validated successor. prepareCurrentMedia() rebinds it again
            // after beginPlayerGeneration() with the definitive generation.
            episodeChangeTimeoutRequestId = requestId
            episodeChangeTimeoutUri = uri.toString()
            episodeChangeTimeoutGeneration = transitionGeneration
            episodeChangeTimeoutSessionId = playerSessionId
            episodeChangeTimeoutPlayerGeneration = playerGeneration
            handler.removeCallbacks(episodeChangeTimeout)
            logPlayer(
                "PLAYER_REUSE_ORIGIN_VALIDATED requestId=" + requestId.ifEmpty { "-" } +
                    " originRequestId=" + originRequestId +
                    " originGeneration=" + originTransitionGeneration +
                    " sessionGeneration=" + transitionGeneration,
            )
        } else {
            episodeChangePending = false
            if (originRequestId.isNotBlank()) {
                publishNavigationTransitionDiagnostic(
                    if (incomingOriginTransitionDirection == "PREVIOUS") "PREVIOUS_REQUEST_STALE" else "NEXT_REQUEST_STALE",
                    "invalid_successor_origin",
                    JSONObject()
                        .put("originRequestId", originRequestId)
                        .put("originPlayerSessionId", originPlayerSessionId)
                        .put("currentPlayerSessionId", playerSessionId)
                        .put("originGeneration", originTransitionGeneration)
                        .put("currentGeneration", transitionGeneration),
                )
            }
            logPlayer(
                "PLAYER_REUSE_ORIGIN_REJECTED requestId=" + requestId.ifEmpty { "-" } +
                    " originRequestId=" + originRequestId.ifEmpty { "-" } +
                    " expectedRequestId=" + transitionPendingRequestId.ifEmpty { "-" } +
                    " originGeneration=" + originTransitionGeneration +
                    " expectedGeneration=" + transitionPendingGeneration,
            )
        }

        autoplayNext = newIntent.getBooleanExtra("autoplay", autoplayNext)
        doubleTapSeekMs = newIntent.getLongExtra("setting_player_double_tap_seek_seconds", doubleTapSeekMs / 1000L)
        longPressSpeed = newIntent.getFloatExtra("setting_player_long_press_speed", longPressSpeed)
            .coerceIn(1f, 3f)
        maxVideoResolution = newIntent.getStringExtra("setting_player_max_video_resolution") ?: maxVideoResolution
        maxVideoFrameRate = newIntent.getIntExtra("setting_player_max_video_frame_rate", maxVideoFrameRate).coerceAtLeast(0)
        maxAudioChannels = newIntent.getIntExtra("setting_player_max_audio_channels", maxAudioChannels).coerceAtLeast(0)
        subtitleScale = newIntent.getFloatExtra("setting_audio_subtitle_scale", subtitleScale).coerceIn(0.5f, 2f)
        subtitleBottomPaddingPercent = newIntent.getIntExtra("setting_audio_subtitle_bottom_padding", subtitleBottomPaddingPercent).coerceIn(0, 50)
        subtitleEmbeddedStyle = newIntent.getBooleanExtra("setting_audio_subtitle_embedded_style", subtitleEmbeddedStyle)
        preferredAudioLanguage = newIntent.getStringExtra("setting_audio_preferred_language")?.trim().orEmpty()
        preferredSubtitleLanguage = newIntent.getStringExtra("setting_audio_preferred_subtitle_language")?.trim().orEmpty()
        subtitleMode = newIntent.getStringExtra("setting_audio_subtitles") ?: subtitleMode
        immersiveSetting = newIntent.getStringExtra("setting_player_immersive") ?: immersiveSetting
        applyImmersiveAfterLayout()
        applyGlobalTrackPreferences()
        applyAdvancedTrackConstraints()
        applySubtitlePreferences()
        zoomEnabled = newIntent.getBooleanExtra("setting_player_zoom_enabled", false)
        findViewByTag<GestureLayer>("reiflix_gesture_layer")?.resetZoomToFit()
        playerView.resizeMode = resizeModeFromSetting(newIntent.getStringExtra("setting_player_aspect_ratio"))
        aspectModeLabel = aspectLabelFromSetting(newIntent.getStringExtra("setting_player_aspect_ratio"))
        findViewByTag<TextView>("reiflix_aspect_button")?.apply {
            text = aspectModeLabel
            isSelected = aspectModeLabel == "Preencher"
        }

        findViewByTag<TextView>("reiflix_player_title")?.text =
            newIntent.getStringExtra("title") ?: "Episódio"
        updateEpisodeNavigationButtons()
        findViewByTag<View>("reiflix_error_panel")?.visibility = View.GONE
        if (::preparingIndicator.isInitialized) preparingIndicator.visibility = View.VISIBLE
        moreVisible = false
        findViewByTag<View>("reiflix_more_panel")?.visibility = View.GONE
        setControlsVisible(true)

        try {
            prepareCurrentMedia("reuse")
        } catch (exception: Exception) {
            logPlayer("MEDIA_REUSE_FAILED requestId=" + requestId.ifEmpty { "-" }, exception)
            showPlayerError(
                "Não foi possível iniciar o próximo episódio local.",
                "player_reuse",
                JSONObject().put("error", exception.message ?: exception::class.java.simpleName),
            )
        }
    }

    private fun currentEpisodeId(): String = intent.getStringExtra("episodeId")?.trim().orEmpty()

    private fun currentMediaId(): String {
        val episodeId = currentEpisodeId()
        if (episodeId.isNotBlank()) return "episode:$episodeId"
        return if (::uri.isInitialized) uri.toString() else intent.getStringExtra("mediaId").orEmpty()
    }

    private fun buildMediaItem(
        mediaUri: Uri,
        mimeType: String?,
        subtitleTracks: List<LocalSubtitleResolver.SubtitleTrack>,
    ): MediaItem {
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(mediaUri)
            .setMediaId(currentMediaId())
        mimeType?.takeIf { it.startsWith("video/") }?.let { mediaItemBuilder.setMimeType(it) }
        if (subtitleTracks.isNotEmpty()) {
            mediaItemBuilder.setSubtitleConfigurations(
                subtitleTracks.map { track ->
                    MediaItem.SubtitleConfiguration.Builder(track.uri)
                        .setMimeType(track.mimeType)
                        .setLanguage(track.language)
                        .setSelectionFlags(if (track.isDefault) C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                }
            )
        }
        return mediaItemBuilder.build()
    }

    private fun isCurrentPreparation(
        generation: Long,
        localUri: Uri,
        expectedTransitionGeneration: Long,
    ): Boolean =
        generation == playerGeneration &&
            transitionGeneration == expectedTransitionGeneration &&
            sessionState == SessionState.ACTIVE &&
            ::uri.isInitialized &&
            uri == localUri
    private fun isCurrentPreparation(generation: Long, localUri: Uri): Boolean =
        isCurrentPreparation(generation, localUri, transitionGeneration)


    private fun prepareCurrentMedia(reason: String, playWhenReadyOverride: Boolean? = null) {
        if (!::player.isInitialized || sessionState == SessionState.DESTROYED) return
        beginPlayerGeneration(reason)
        if (episodeChangePending) {
            armEpisodeChangeTimeout("prepare_" + reason)
        }
        val generation = playerGeneration
        val preparationTransitionGeneration = transitionGeneration
        if (episodeChangePending && (nextTransitionActive || previousTransitionActive)) {
            setTransitionPhase(TransitionPhase.PREPARING, "media_prepare")
        }
        val localUri = uri
        val shouldPlayWhenReady = playWhenReadyOverride
            ?: intent.getBooleanExtra("autoplay", true)
        requestedPlayWhenReadyForGeneration = shouldPlayWhenReady

        initialSeekApplied = false
        completionReported = false
        firstFrameRenderedForTesting = false
        contentMimeType = null
        mediaDisplayName = null
        mediaSizeBytes = null
        decoderVideoName = null
        decoderAudioName = null
        videoFormatSummary = null
        audioFormatSummary = null
        currentErrorCategory = PlayerMediaPolicy.ErrorCategory.UNKNOWN
        currentFailureKind = PlayerMediaPolicy.PlaybackFailureKind.UNKNOWN
        currentFailureRetryable = false
        playbackErrorForGeneration = false
        lastMediaPeriodId = ""
        lastLoadUri = ""
        lastLoadDataType = -1
        lastLoadTrackType = -1
        lastLoadErrorClass = ""
        lastLoadErrorMessage = ""
        if (reason != "retry") {
            retryCount = 0
        }
        pendingPreparation?.cancel(true)
        cancelFirstFrameDiagnostics("prepare_start")
        if (::preparingIndicator.isInitialized) preparingIndicator.visibility = View.VISIBLE

        episodeTapAtMs = commandCreatedAtMs.takeIf { it > 0L }
            ?: activityStartedAtMs.takeIf { it > 0L }
            ?: System.currentTimeMillis()
        initialPositionMsForGeneration = (restoredPositionMs
            ?: intent.getLongExtra("positionMs", 0L))
            .coerceAtLeast(0L)
        // Media3 receives the saved position atomically with the MediaItem, so no
        // post-READY seek cycle is required on the normal startup path.
        initialSeekApplied = true
        preflightStartedAtMs = 0L
        preflightCompletedAtMs = 0L
        prepareDispatchedAtMs = 0L
        playerReadyAtMs = 0L
        firstFrameRenderedAtMs = 0L

        logPlayer(
            "PLAYER_START requestId=" + requestId.ifEmpty { "-" } +
                " episodeId=" + currentEpisodeId() +
                " reason=" + reason +
                " episodeTapAtMs=" + episodeTapAtMs,
        )
        logPlayer(
            "PREPARE_ASYNC_START generation=$generation requestId=" +
                requestId.ifEmpty { "-" } +
                " reason=" + reason +
                " initialPositionMs=" + initialPositionMsForGeneration,
        )

        pendingPreparation = playbackWorker.submit {
            try {
                preflightStartedAtMs = System.currentTimeMillis()
                logPlayer(
                    "PREFLIGHT_ASYNC_START requestId=" + requestId.ifEmpty { "-" } +
                        " generation=" + generation +
                        " uri=" + localUri +
                        " atMs=" + preflightStartedAtMs,
                )
                val preflightFailure = validateLocalSource(localUri)
                if (preflightFailure != null) {
                    handler.post {
                        if (!isCurrentPreparation(generation, localUri, preparationTransitionGeneration)) return@post
                        logPlayer(
                            "PREFLIGHT_ASYNC_FAILED requestId=" + requestId.ifEmpty { "-" } +
                                " generation=" + generation +
                                " errorCode=" + preflightFailure.code +
                                " error=" + preflightFailure.message,
                        )
                        val preflightClassification = PlayerMediaPolicy.classifyPlaybackFailure(
                            errorCodeName = preflightFailure.code,
                        )
                        showPlayerError(
                            preflightFailure.message,
                            "source_preflight",
                            JSONObject()
                                .put("errorCode", preflightFailure.code)
                                .put("failureStage", "SOURCE_PREFLIGHT"),
                            category = preflightClassification.legacyCategory,
                            failureKind = preflightClassification.kind,
                            retryable = preflightClassification.retryable,
                        )
                    }
                    return@submit
                }

                preflightCompletedAtMs = System.currentTimeMillis()
                logPlayer(
                    "PREFLIGHT_ASYNC_OK requestId=" + requestId.ifEmpty { "-" } +
                        " generation=" + generation +
                        " atMs=" + preflightCompletedAtMs +
                        " latencyMs=" + metricDelta(preflightStartedAtMs, preflightCompletedAtMs),
                )

                // Optional MIME/display-name/size/subtitle discovery starts in parallel
                // but is never awaited by the playback path.
                hydrateLocalMediaReferencesAsync(
                    generation = generation,
                    localUri = localUri,
                    expectedTransitionGeneration = preparationTransitionGeneration,
                )

                handler.post {
                    if (!isCurrentPreparation(generation, localUri, preparationTransitionGeneration)) return@post

                    val subtitleTracks = deferredSubtitleCache[localUri.toString()].orEmpty()
                    val mediaItem = buildMediaItem(
                        mediaUri = localUri,
                        mimeType = null,
                        subtitleTracks = subtitleTracks,
                    )
                    logPlayer(
                        "MEDIA_ITEM requestId=" + requestId.ifEmpty { "-" } +
                            " uri=" + mediaItem.localConfiguration?.uri +
                            " mime=auto subtitleCount=" + subtitleTracks.size +
                            " reason=" + reason,
                    )

                    // The initial PlayerView is already attached to this ExoPlayer.
                    // Keep that surface intact for the fastest first-frame path.
                    if (reason != "initial") {
                        player.pause()
                        detachPlayerViewForMediaReset(reason)
                    }

                    // Use Media3's start-position API so resume does not require a
                    // second seek cycle after READY.
                    player.setMediaItem(mediaItem, initialPositionMsForGeneration)
                    player.playWhenReady = shouldPlayWhenReady

                    if (reason != "initial") {
                        reattachPlayerViewAfterMediaReset(reason)
                    }

                    prepareDispatchedAtMs = System.currentTimeMillis()
                    PerformanceDiagnostics.markPlayer(
                        this@NativePlayerActivity,
                        "prepare_dispatched",
                        requestId,
                        commandCreatedAtMs,
                        reused = reason != "initial",
                    )
                    logPlayer(
                        "MEDIA3_PREPARE_DISPATCHED requestId=" + requestId.ifEmpty { "-" } +
                            " generation=" + generation +
                            " mediaId=" + mediaItem.mediaId +
                            " atMs=" + prepareDispatchedAtMs +
                            " latencyFromPreflightMs=" + metricDelta(preflightCompletedAtMs, prepareDispatchedAtMs) +
                            " startPositionMs=" + initialPositionMsForGeneration +
                            " playWhenReady=" + shouldPlayWhenReady,
                    )

                    try {
                        NativeMailbox.writeBestEffort(
                            this@NativePlayerActivity,
                            JSONObject()
                                .put("type", "diagnostic")
                                .put("requestId", requestId)
                                .put(
                                    "payload",
                                    JSONObject()
                                        .put("event", "PLAYER_PREPARING")
                                        .put("episodeId", currentEpisodeId())
                                        .put("playerSessionId", playerSessionId)
                                        .put("playerGeneration", generation)
                                        .put("transitionGeneration", transitionGeneration)
                                        .put("startPositionMs", initialPositionMsForGeneration),
                                ),
                        )
                        player.prepare()
                    } catch (error: Exception) {
                        logPlayer(
                            "MEDIA3_PREPARE_FAILED requestId=" + requestId.ifEmpty { "-" } +
                                " generation=" + generation +
                                " stage=prepare",
                            error,
                        )
                        showPlayerError(
                            "Não foi possível preparar este arquivo local.",
                            "media3_prepare",
                            JSONObject()
                                .put("stage", "media3_prepare")
                                .put("error", error.message ?: error::class.java.simpleName),
                        )
                        return@post
                    }

                    updateTrackButtons()
                    updatePlayPauseButton()
                    updateProgressUi()
                }
            } catch (cancelled: java.util.concurrent.CancellationException) {
                logPlayer("PREPARE_ASYNC_CANCELLED generation=$generation reason=$reason")
            } catch (error: Exception) {
                handler.post {
                    if (!isCurrentPreparation(generation, localUri, preparationTransitionGeneration)) return@post
                    logPlayer("PREPARE_ASYNC_FAILED generation=$generation reason=$reason", error)
                    val category = PlayerMediaPolicy.classifyError(
                        error::class.java.simpleName,
                        listOfNotNull(error.cause?.javaClass?.simpleName),
                    )
                    showPlayerError(
                        "Não foi possível preparar este arquivo local.",
                        "prepare_io",
                        JSONObject().put("error", error.message ?: error::class.java.simpleName),
                        category,
                    )
                }
            }
        }
    }

    private fun hydrateLocalMediaReferencesAsync(
        generation: Long,
        localUri: Uri,
        expectedTransitionGeneration: Long,
    ) {
        try {
            val appContext = applicationContext
            mediaMetadataWorker.submit {
                android.util.Log.i(
                    TAG,
                    "MEDIA_METADATA_DEFERRED_START requestId=" + requestId.ifEmpty { "-" } +
                        " generation=" + generation +
                        " uri=" + localUri,
                )
                val displayName = displayNameForUri(appContext, localUri)
                val providerMime = runCatching { appContext.contentResolver.getType(localUri) }.getOrNull()
                val resolvedMime = PlayerMediaPolicy.resolveVideoMimeType(providerMime, displayName)
                val sizeBytes = localSizeBytes(appContext, localUri)
                val subtitleTracks = runCatching {
                    LocalSubtitleResolver.resolve(appContext, localUri)
                }.getOrElse { error ->
                    android.util.Log.w(
                        TAG,
                        "SUBTITLE_RESOLVE_FAILED generation=$generation uri=$localUri",
                        error,
                    )
                    emptyList()
                }
                deferredSubtitleCache[localUri.toString()] = subtitleTracks

                handler.post {
                    if (
                        generation != playerGeneration ||
                        transitionGeneration != expectedTransitionGeneration ||
                        sessionState != SessionState.ACTIVE ||
                        !::uri.isInitialized ||
                        uri != localUri
                    ) {
                        return@post
                    }
                    contentMimeType = resolvedMime
                    mediaDisplayName = displayName
                    mediaSizeBytes = sizeBytes
                    logPlayer(
                        "MEDIA_METADATA_DEFERRED_READY requestId=" +
                            requestId.ifEmpty { "-" } +
                            " generation=" + generation +
                            " displayName=" + displayName.orEmpty() +
                            " mime=" + resolvedMime.orEmpty() +
                            " sizeBytes=" + (sizeBytes ?: -1L) +
                            " subtitleCount=" + subtitleTracks.size,
                    )
                    if (sizeBytes == 0L && !errorVisible && !firstFrameRenderedForTesting) {
                        showPlayerError(
                            "Este arquivo está vazio e não contém dados de vídeo.",
                            "empty_file",
                            JSONObject().put("sizeBytes", 0),
                            PlayerMediaPolicy.ErrorCategory.SOURCE_UNAVAILABLE,
                        )
                    }
                }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            logPlayer(
                "MEDIA_METADATA_DEFERRED_REJECTED requestId=" + requestId.ifEmpty { "-" } +
                    " generation=" + generation,
                error,
            )
        }
    }

    private fun createPlayerListener(generation: Long): Player.Listener = object : Player.Listener {
        private fun isCurrent(): Boolean =
            generation == playerGeneration &&
                sessionState == SessionState.ACTIVE &&
                !playbackErrorForGeneration
        override fun onEvents(player: Player, events: Player.Events) {
            if (!isCurrent()) return
            if (events.contains(Player.EVENT_RENDERED_FIRST_FRAME)) {
                firstFrameRenderedForTesting = true
                cancelFirstFrameDiagnostics("first_frame")
                if (::preparingIndicator.isInitialized) preparingIndicator.visibility = View.GONE
                firstFrameRenderedAtMs = System.currentTimeMillis()
                PerformanceDiagnostics.markPlayer(this@NativePlayerActivity, "first_frame",
                    requestId, commandCreatedAtMs, reused = false)
                val timing = playbackTimingPayload(firstFrameRenderedAtMs)
                logPlayer(
                    "PLAYER_START requestId=" + requestId.ifEmpty { "-" } +
                        " episodeId=" + currentEpisodeId() +
                        " preflightMs=" + timing.optLong("preflightDurationMs") +
                        " prepareMs=" + timing.optLong("prepareToReadyMs") +
                        " firstFrameMs=" + timing.optLong("readyToFirstFrameMs") +
                        " totalMs=" + timing.optLong("tapToFirstFrameMs"),
                )
                logPlayer(
                    "FIRST_FRAME_RENDERED requestId=" + requestId.ifEmpty { "-" } +
                        " generation=" + generation +
                        " positionMs=" + player.currentPosition +
                        " video=" + player.videoSize.width + "x" + player.videoSize.height +
                        " playerView=" + playerView.width + "x" + playerView.height +
                        " orientation=" + resources.configuration.orientation +
                        " handoffLatencyMs=" + timing.optString("handoffLatencyMs") +
                        " activityStartupLatencyMs=" + timing.optString("activityStartupLatencyMs") +
                        " preflightLatencyMs=" + timing.optString("preflightLatencyMs") +
                        " prepareLatencyMs=" + timing.optString("prepareLatencyMs") +
                        " firstFrameLatencyMs=" + timing.optString("firstFrameLatencyMs") +
                        " totalOpenToFirstFrameMs=" + timing.optString("totalOpenToFirstFrameMs"),
                )
                val firstFrameEvent = JSONObject()
                    .put("event", "FIRST_FRAME_RENDERED")
                    .put("generation", generation)
                    .put("playerSessionId", playerSessionId)
                    .put("transitionGeneration", transitionGeneration)
                    .put("originMonotonicNs", originMonotonicNs)
                    .put("transitionDirection", transitionSourceDirection)
                    .put("timing", timing)
                if (!NativeMailbox.writeBestEffort(
                        this@NativePlayerActivity,
                        JSONObject()
                            .put("type", "player_diagnostic")
                            .put("requestId", requestId)
                            .put("payload", firstFrameEvent),
                    )
                ) {
                    logPlayer("FAILED_TO_PUBLISH player_diagnostic requestId=" + requestId.ifEmpty { "-" } + " event=FIRST_FRAME_RENDERED")
                }
            }
                if (
                    (nextTransitionActive || previousTransitionActive) &&
                    episodeChangePending &&
                    transitionReadyGeneration == generation &&
                    transitionSourceRequestId == requestId &&
                    transitionSourceUri == uri.toString() &&
                    sessionState == SessionState.ACTIVE
                ) {
                    val direction = if (nextTransitionActive) "NEXT" else "PREVIOUS"
                    publishNavigationTransitionDiagnostic(
                        if (nextTransitionActive) "NEXT_TRANSITION_FIRST_FRAME" else "PREVIOUS_TRANSITION_FIRST_FRAME",
                        "media3_first_frame",
                        JSONObject()
                            .put("episodeId", currentEpisodeId())
                            .put("generation", generation)
                            .put("transitionGeneration", transitionGeneration)
                            .put("playerSessionId", playerSessionId)
                            .put("originMonotonicNs", transitionSourceMonotonicNs),
                    )
                    setTransitionPhase(TransitionPhase.FIRST_FRAME, "media3_first_frame")
                    setTransitionPhase(TransitionPhase.COMMITTED, "first_frame_rendered")
                    episodeChangePending = false
                    episodeChangeTimeoutRequestId = ""
                    episodeChangeTimeoutUri = ""
                    episodeChangeTimeoutGeneration = 0L
                    transitionReadyGeneration = -1L
                    transitionSourceRequestId = ""
                    transitionSourceUri = ""
                    transitionSourceCreatedAtMs = 0L
                    transitionSourceGeneration = 0L
                    transitionSourceDirection = ""
                    transitionSourceMonotonicNs = 0L
                    transitionPublishFuture = null
                    handler.removeCallbacks(episodeChangeTimeout)
                    nextTransitionActive = false
                    previousTransitionActive = false
                    updateEpisodeNavigationButtons()
                    publishNavigationTransitionDiagnostic(
                        if (direction == "NEXT") "NEXT_TRANSITION_COMMITTED" else "PREVIOUS_TRANSITION_COMMITTED",
                        "first_frame_rendered",
                        JSONObject()
                            .put("episodeId", currentEpisodeId())
                            .put("generation", generation)
                            .put("playerSessionId", playerSessionId),
                    )
                    logPlayer(
                        "EPISODE_CHANGE_COMMITTED requestId=" + requestId.ifEmpty { "-" } +
                            " mediaId=" + currentMediaId() +
                            " episodeId=" + currentEpisodeId() +
                            " reason=FIRST_FRAME_RENDERED direction=" + direction,
                    )
                    transitionStartedAtMs = 0L
                }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!isCurrent()) return
            logPlayer(
                "MEDIA_ITEM_TRANSITION generation=$generation transitionGeneration=$transitionGeneration " +
                    "direction=$transitionSourceDirection reason=$reason mediaId=" +
                    mediaItem?.mediaId.orEmpty() +
                    " requestId=" + requestId.ifEmpty { "-" },
            )
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!isCurrent()) return
            logPlayer(
                "PLAY_WHEN_READY_CHANGED requestId=" + requestId.ifEmpty { "-" } +
                    " value=" + playWhenReady +
                    " reason=" + reason +
                    " suppression=" + player.playbackSuppressionReason +
                    " isPlaying=" + player.isPlaying,
            )
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            if (!isCurrent()) return
            logPlayer(
                "PLAYBACK_SUPPRESSION_CHANGED requestId=" + requestId.ifEmpty { "-" } +
                    " reason=" + playbackSuppressionReason +
                    " playWhenReady=" + player.playWhenReady +
                    " isPlaying=" + player.isPlaying,
            )
        }

        override fun onRenderedFirstFrame() {
            if (!isCurrent()) return
            logPlayer(
                "FIRST_FRAME_CALLBACK requestId=" + requestId.ifEmpty { "-" } +
                    " generation=" + playerGeneration +
                    " transitionGeneration=" + transitionGeneration +
                    " renderedAtMs=" + System.currentTimeMillis(),
            )
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (!isCurrent()) return
            logPlayer(
                "VIDEO_SIZE_CHANGED requestId=" + requestId.ifEmpty { "-" } +
                    " width=" + videoSize.width +
                    " height=" + videoSize.height +
                    " unappliedRotationDegrees=" + videoSize.unappliedRotationDegrees +
                    " pixelWidthHeightRatio=" + videoSize.pixelWidthHeightRatio,
            )
        }

        override fun onSurfaceSizeChanged(width: Int, height: Int) {
            if (!isCurrent()) return
            logPlayer(
                "SURFACE_SIZE_CHANGED requestId=" + requestId.ifEmpty { "-" } +
                    " width=" + width +
                    " height=" + height +
                    " attached=" + (::playerView.isInitialized && playerView.isAttachedToWindow) +
                    " visible=" + (::playerView.isInitialized && playerView.isShown),
            )
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (!isCurrent()) return
            val label = when (state) {
                Player.STATE_IDLE -> "STATE_IDLE"
                Player.STATE_BUFFERING -> "STATE_BUFFERING"
                Player.STATE_READY -> "STATE_READY"
                Player.STATE_ENDED -> "STATE_ENDED"
                else -> "STATE_UNKNOWN"
            }
            logPlayer("PLAYBACK_STATE=" + label + " requestId=" + requestId.ifEmpty { "-" } +
                " positionMs=" + if (::player.isInitialized) player.currentPosition else 0L)
            when (state) {
                Player.STATE_READY -> {
                    val readyAtMs = System.currentTimeMillis()
                    val firstReadyForGeneration = playerReadyAtMs == 0L
                    if (firstReadyForGeneration) {
                        playerReadyAtMs = readyAtMs
                    }
                    if (::preparingIndicator.isInitialized) {
                        preparingIndicator.visibility = View.GONE
                    }
                    if (firstReadyForGeneration) {
                        logPlayer(
                            "PLAYER_READY requestId=" + requestId.ifEmpty { "-" } +
                                " generation=" + generation +
                                " playerReadyAtMs=" + playerReadyAtMs +
                                " prepareToReadyMs=" + metricDelta(prepareDispatchedAtMs, playerReadyAtMs) +
                                " tapToReadyMs=" + metricDelta(episodeTapAtMs, playerReadyAtMs),
                        )
                    } else {
                        logPlayer(
                            "PLAYER_READY_REENTRY requestId=" + requestId.ifEmpty { "-" } +
                                " generation=" + generation +
                                " playerReadyAtMs=" + playerReadyAtMs +
                                " reentryAtMs=" + readyAtMs,
                        )
                    }
                    if (episodeChangePending && (nextTransitionActive || previousTransitionActive)) {
                        setTransitionPhase(TransitionPhase.READY, "media3_ready")
                    }
                    // READY means Media3 prepared the media. The preparation indicator
                    // is no longer needed; first-frame telemetry remains independently
                    // measured by EVENT_RENDERED_FIRST_FRAME.
                    if (!openedReported) {
                        openedReported = true
                        val opened = NativeMailbox.writeBestEffort(
                            this@NativePlayerActivity,
                            JSONObject().put("type", "player_opened")
                                .put("requestId", requestId)
                                .put("payload", JSONObject()
                                    .put("uri", uri.toString())
                                    .put("source", sourceFor(uri))
                                    .put("title", titleValue)
                                    .put("mediaId", currentMediaId())
                                    .put("episodeId", currentEpisodeId())
                                    .put("animeId", intent.getStringExtra("animeId").orEmpty())
                                    .put("playerSessionId", playerSessionId)
                                    .put("transitionGeneration", transitionGeneration)
                                    .put("state", "READY"))
                        )
                        if (!opened) {
                            logPlayer("FAILED_TO_PUBLISH player_opened requestId=" + requestId.ifEmpty { "-" })
                        }
                        NativeMailbox.writeBestEffort(
                            this@NativePlayerActivity,
                            JSONObject()
                                .put("type", "diagnostic")
                                .put("requestId", requestId)
                                .put(
                                    "payload",
                                    JSONObject()
                                        .put("event", "PLAYER_READY")
                                        .put("episodeId", currentEpisodeId())
                                        .put("playerSessionId", playerSessionId)
                                        .put("playerGeneration", generation)
                                        .put("transitionGeneration", transitionGeneration),
                                ),
                        )
                    }
                    if (!initialSeekApplied) {
                        // Normal startup preloads the saved position through
                        // setMediaItem(MediaItem, startPositionMs). Keep this defensive
                        // branch only for unusual lifecycle recovery paths.
                        initialSeekApplied = true
                        logPlayer(
                            "RESUME_POSITION_ALREADY_PRELOADED requestId=" +
                                requestId.ifEmpty { "-" } +
                                " generation=$generation" +
                                " requestedPositionMs=" + initialPositionMsForGeneration +
                                " currentPositionMs=" + player.currentPosition.coerceAtLeast(0L),
                        )
                    }
                    if (
                        (
                            nextTransitionActive &&
                            episodeChangePending &&
                            transitionSourceRequestId == requestId &&
                            transitionSourceUri == uri.toString() &&
                            transitionReadyGeneration != generation
                        ) || (
                            previousTransitionActive &&
                            episodeChangePending &&
                            transitionSourceRequestId == requestId &&
                            transitionSourceUri == uri.toString() &&
                            transitionReadyGeneration != generation
                        )
                    ) {
                        transitionReadyGeneration = generation
                        if (transitionStartedAtMs > 0L) {
                            val readyAtMs = System.currentTimeMillis()
                            val transitionLatencyMs = readyAtMs - transitionStartedAtMs
                            PerformanceDiagnostics.markPlayer(
                                this@NativePlayerActivity,
                                "transition_ready",
                                requestId,
                                commandCreatedAtMs,
                                reused = true,
                            )
                            publishNavigationTransitionDiagnostic(
                                if (nextTransitionActive) "NEXT_TRANSITION_READY" else "PREVIOUS_TRANSITION_READY",
                                "media3_ready",
                                JSONObject()
                                    .put("episodeId", currentEpisodeId())
                                    .put("generation", generation)
                                    .put("playerSessionId", playerSessionId)
                                    .put("transitionLatencyMs", transitionLatencyMs),
                            )
                            logPlayer(
                                "PLAYER_TRANSITION_READY requestId=" + requestId.ifEmpty { "-" } +
                                    " transitionLatencyMs=" + transitionLatencyMs +
                                    " originRequestId=" + originRequestId.ifEmpty { "-" } +
                                    " originCreatedAtMs=" + originCreatedAtMs +
                                    " awaitingFirstFrame=true",
                            )
                        }
                    }
                    updateEpisodeNavigationButtons()
                    completionReported = false
                    updateTrackButtons()
                    updatePlayPauseButton()
                    updateProgressUi()
                    startProgressReporting()
                    if (!firstFrameRenderedForTesting && !errorVisible) {
                        armFirstFrameDiagnostics(generation)
                    }
                    if (!errorVisible) scheduleControlsHide()
                }
                Player.STATE_BUFFERING -> {
                    updatePlayPauseButton()
                    if (::preparingIndicator.isInitialized && !errorVisible) {
                        preparingIndicator.visibility = View.VISIBLE
                    }
                    if (!errorVisible) scheduleControlsHide()
                }
                Player.STATE_ENDED -> {
                    completionReported = true
                    saveProgress("player_completed", force = true)
                    updatePlayPauseButton()
                    if (autoplayNext && intent.getBooleanExtra("canNext", false)) {
                        requestEpisode("player_next_request")
                    }
                }
                Player.STATE_IDLE -> updatePlayPauseButton()
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            if (!isCurrent()) return
            val speedLabel = String.format(Locale.US, "%.2fx", playbackParameters.speed)
            findViewByTag<TextView>("reiflix_speed_button")?.apply {
                text = speedLabel
                isSelected = true
            }
            logPlayer(
                "PLAYBACK_SPEED_CHANGED generation=$generation speed=" +
                    playbackParameters.speed + " pitch=" + playbackParameters.pitch,
            )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isCurrent()) return
            if (isPlaying) {
                PerformanceDiagnostics.markPlayer(this@NativePlayerActivity, "playing",
                    requestId, commandCreatedAtMs, reused = false)
            }
            logPlayer(
                "IS_PLAYING_CHANGED=" + isPlaying +
                    " requestId=" + requestId.ifEmpty { "-" } +
                    " playbackState=" + player.playbackState +
                    " playWhenReady=" + player.playWhenReady +
                    " suppression=" + player.playbackSuppressionReason,
            )
            updatePlayPauseButton()
            updatePictureInPictureParams()
            if (!errorVisible) {
                if (isPlaying) {
                    scheduleControlsHide()
                } else {
                    // Pause keeps the controls usable instead of letting a stale
                    // playback timer hide them after the user pauses.
                    handler.removeCallbacks(controlsHider)
                    touchControls()
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (!isCurrent()) return
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                logPlayer(
                    "PLAYER_SEEK requestId=" + requestId.ifEmpty { "-" } +
                        " positionMs=" + newPosition.positionMs,
                )
                saveProgress("player_progress", force = true)
            }
            updateProgressUi()
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            if (!isCurrent()) return
            val videoGroups = tracks.groups.count { it.type == C.TRACK_TYPE_VIDEO && it.isSupported }
            val audioGroups = tracks.groups.count { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
            val textGroups = tracks.groups.count { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
            logPlayer(
                "PLAYER_TRACK_CHANGE requestId=" + requestId.ifEmpty { "-" } +
                    " video=" + videoGroups + " audio=" + audioGroups + " text=" + textGroups,
            )
            if (audioGroups == 0) logPlayer("TRACKS_NO_AUDIO requestId=" + requestId.ifEmpty { "-" })
            if (textGroups == 0) logPlayer("TRACKS_NO_SUBTITLE requestId=" + requestId.ifEmpty { "-" })
            captureTrackFormatSummaries()
            updateTrackButtons()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!isCurrent()) return

            val exoError = error as? ExoPlaybackException
            val rendererIndex = exoError?.rendererIndex?.takeIf { it >= 0 }
            val rendererType = exoError?.type?.let { "TYPE_$it" }.orEmpty()
            val rendererName = exoError?.rendererName.orEmpty()
            val rendererFormatMimeType = exoError?.rendererFormat?.sampleMimeType.orEmpty()
            val mediaPeriodId = exoError?.mediaPeriodId?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: lastMediaPeriodId
            val causeChain = throwableChain(error)
            val causeNames = causeChain.map { it::class.java.simpleName }
            val causeMessages = causeChain.mapNotNull { it.message?.trim()?.takeIf(String::isNotBlank) }
            val codeName = error.errorCodeName.orEmpty()
            val classification = PlayerMediaPolicy.classifyPlaybackFailure(
                errorCodeName = codeName,
                causeNames = causeNames,
                messages = causeMessages,
                rendererIndex = rendererIndex,
            )
            currentErrorCategory = classification.legacyCategory
            currentFailureKind = classification.kind
            currentFailureRetryable = classification.retryable
            playbackErrorForGeneration = true
            cancelFirstFrameDiagnostics("player_error")

            val failureStage = when {
                firstFrameRenderedForTesting -> "PLAYBACK_AFTER_FIRST_FRAME"
                openedReported -> "PLAYER_ERROR_AFTER_READY_BEFORE_FIRST_FRAME"
                else -> "PLAYER_ERROR_BEFORE_READY"
            }
            val rootCause = causeChain.lastOrNull()
            val technicalCode = "media3:" + codeName
            val detail = error.message?.trim().orEmpty()
            val causeSummary = causeChain.joinToString(" -> ") { throwable ->
                throwable::class.java.simpleName +
                    throwable.message?.trim()?.takeIf(String::isNotBlank)?.let { ":$it" }.orEmpty()
            }

            logPlayer(
                "PLAYER_ERROR" +
                    " traceId=" + traceId +
                    " requestId=" + requestId.ifEmpty { "-" } +
                    " generation=" + generation +
                    " stage=" + failureStage +
                    " errorCode=" + codeName +
                    " kind=" + classification.kind.name +
                    " retryable=" + classification.retryable +
                    " rendererIndex=" + (rendererIndex ?: -1) +
                    " rendererType=" + rendererType.ifBlank { "-" } +
                    " mediaPeriodId=" + mediaPeriodId.ifBlank { "-" } +
                    " rendererName=" + rendererName.ifBlank { "-" } +
                    " formatMimeType=" + rendererFormatMimeType.ifBlank { "-" } +
                    " dataSourceUri=" + lastLoadUri.ifBlank { "-" } +
                    " rootCause=" + (rootCause?.javaClass?.simpleName ?: "-"),
                error,
            )

            saveProgress("player_progress", force = true)
            player.pause()

            val diagnostic = diagnosticPayload()
                .put("failureStage", failureStage)
                .put("failureKind", classification.kind.name)
                .put("retryable", classification.retryable)
                .put("errorCode", technicalCode)
                .put("errorCodeName", codeName)
                .put("errorMessage", detail)
                .put("causeChain", causeSummary)
                .put("rootCauseClass", rootCause?.javaClass?.simpleName.orEmpty())
                .put("rootCauseMessage", rootCause?.message?.trim().orEmpty())
                .put("rendererIndex", rendererIndex ?: -1)
                .put("rendererType", rendererType)
                .put("rendererName", rendererName)
                .put("rendererFormatMimeType", rendererFormatMimeType)
                .put("mediaPeriodId", mediaPeriodId)
                .put("dataSourceUri", lastLoadUri)
                .put("dataType", lastLoadDataType)
                .put("trackType", lastLoadTrackType)
                .put("loadErrorClass", lastLoadErrorClass)
                .put("loadErrorMessage", lastLoadErrorMessage)
                .put("uri", uri.toString())
                .put("requestId", requestId)
                .put("playerSessionId", playerSessionId)
                .put("playerGeneration", generation)
                .put("transitionGeneration", transitionGeneration)
                .put("episodeId", currentEpisodeId())

            showPlayerError(
                message = userMessageForFailure(classification.kind),
                reason = failureStage,
                payload = diagnostic,
                category = classification.legacyCategory,
                failureKind = classification.kind,
                retryable = classification.retryable,
            )
        }
    }

    private fun createAnalyticsListener(generation: Long): AnalyticsListener =
        object : AnalyticsListener {
            private fun isCurrent(): Boolean =
                generation == playerGeneration && sessionState == SessionState.ACTIVE

            override fun onPlayerError(
                eventTime: AnalyticsListener.EventTime,
                error: PlaybackException,
            ) {
                if (!isCurrent()) return
                lastMediaPeriodId = eventTime.mediaPeriodId?.toString().orEmpty()
                logPlayer(
                    "MEDIA3_ANALYTICS_PLAYER_ERROR generation=" + generation +
                        " mediaPeriodId=" + lastMediaPeriodId.ifBlank { "-" } +
                        " code=" + error.errorCodeName.orEmpty(),
                )
            }

            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean,
            ) {
                if (!isCurrent()) return
                lastMediaPeriodId = eventTime.mediaPeriodId?.toString().orEmpty()
                lastLoadUri = loadEventInfo.dataSpec.uri.toString()
                lastLoadDataType = mediaLoadData.dataType
                lastLoadTrackType = mediaLoadData.trackType
                lastLoadErrorClass = error::class.java.simpleName
                lastLoadErrorMessage = error.message?.trim().orEmpty()
                if (!wasCanceled) {
                    logPlayer(
                        "MEDIA3_ANALYTICS_LOAD_ERROR generation=" + generation +
                            " mediaPeriodId=" + lastMediaPeriodId.ifBlank { "-" } +
                            " uri=" + lastLoadUri +
                            " dataType=" + lastLoadDataType +
                            " trackType=" + lastLoadTrackType +
                            " errorClass=" + lastLoadErrorClass,
                        error,
                    )
                }
            }

            override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            if (!isCurrent()) return
            logPlayer(
                "DROPPED_VIDEO_FRAMES generation=" + generation +
                    " droppedFrames=" + droppedFrames +
                    " elapsedMs=" + elapsedMs +
                    " positionMs=" + player.currentPosition,
            )
        }

        override fun onVideoCodecError(
            eventTime: AnalyticsListener.EventTime,
            videoCodecError: Exception,
        ) {
            if (!isCurrent()) return
            logPlayer(
                "VIDEO_CODEC_ERROR generation=" + generation +
                    " errorClass=" + videoCodecError::class.java.simpleName +
                    " error=" + (videoCodecError.message?.trim().orEmpty()),
            )
        }

        override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                if (!isCurrent()) return
                decoderVideoName = decoderName
                logPlayer(
                    "VIDEO_DECODER_INITIALIZED generation=$generation decoder=$decoderName " +
                        "durationMs=$initializationDurationMs",
                )
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                if (!isCurrent()) return
                decoderAudioName = decoderName
                logPlayer(
                    "AUDIO_DECODER_INITIALIZED generation=$generation decoder=$decoderName " +
                        "durationMs=$initializationDurationMs",
                )
            }
        }

    private fun detachPlayerViewForMediaReset(reason: String) {
        if (!::playerView.isInitialized || playerView.player !== player) return
        playerView.player = null
        logPlayer(
            "PLAYER_VIEW_DETACHED_FOR_MEDIA_RESET requestId=" +
                requestId.ifEmpty { "-" } +
                " generation=" + playerGeneration +
                " transitionGeneration=" + transitionGeneration +
                " reason=" + reason,
        )
    }

    private fun reattachPlayerViewAfterMediaReset(reason: String) {
        if (!::playerView.isInitialized || sessionState == SessionState.DESTROYED) return
        if (playerView.player !== player) {
            playerView.player = player
            logPlayer(
                "PLAYER_VIEW_REATTACHED_AFTER_MEDIA_RESET requestId=" +
                    requestId.ifEmpty { "-" } +
                    " generation=" + playerGeneration +
                    " transitionGeneration=" + transitionGeneration +
                    " reason=" + reason,
            )
        }
    }

    private fun beginPlayerGeneration(reason: String) {
        if (!::player.isInitialized || sessionState == SessionState.DESTROYED) return
        cancelFirstFrameDiagnostics("new_generation")
        activePlayerListener?.let { player.removeListener(it) }
        activeAnalyticsListener?.let { player.removeAnalyticsListener(it) }
        activePlayerListener = null
        activeAnalyticsListener = null
        pendingPreparation?.cancel(true)
        playerGeneration += 1L
        errorPublishedForGeneration = false
        playbackErrorForGeneration = false
        currentFailureKind = PlayerMediaPolicy.PlaybackFailureKind.UNKNOWN
        currentFailureRetryable = false
        lastMediaPeriodId = ""
        lastLoadUri = ""
        lastLoadDataType = -1
        lastLoadTrackType = -1
        lastLoadErrorClass = ""
        lastLoadErrorMessage = ""
        openedReported = false
        playerReadyAtMs = 0L
        lastSavedPosition = -1L
        lastProgressPersistAt = System.currentTimeMillis()
        val generation = playerGeneration
        activePlayerListener = createPlayerListener(generation)
        activeAnalyticsListener = createAnalyticsListener(generation)
        player.addListener(activePlayerListener!!)
        player.addAnalyticsListener(activeAnalyticsListener!!)
        logPlayer("PLAYER_GENERATION_START generation=$generation reason=$reason requestId=" + requestId.ifEmpty { "-" })
    }


    private fun applyGlobalTrackPreferences() {
        if (!::player.isInitialized) return
        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setPreferredAudioLanguage(preferredAudioLanguage.takeIf { it.isNotBlank() })
        when (subtitleMode) {
            "never" -> builder
                .setPreferredTextLanguage(null)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            "always" -> builder
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredTextLanguage(preferredSubtitleLanguage.takeIf { it.isNotBlank() })
                .setSelectUndeterminedTextLanguage(true)
            else -> builder
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredTextLanguage(preferredSubtitleLanguage.takeIf { it.isNotBlank() })
        }
        player.trackSelectionParameters = builder.build()
        logPlayer(
            "GLOBAL_TRACK_PREFS audio=" + preferredAudioLanguage.ifBlank { "auto" } +
                " subtitle=" + preferredSubtitleLanguage.ifBlank { "auto" } +
                " mode=" + subtitleMode
        )
    }

    private fun applyAdvancedTrackConstraints() {
        if (!::player.isInitialized) return
        val builder = player.trackSelectionParameters.buildUpon()
        when (maxVideoResolution) {
            "480p" -> builder.setMaxVideoSize(854, 480)
            "720p" -> builder.setMaxVideoSize(1280, 720)
            "1080p" -> builder.setMaxVideoSize(1920, 1080)
            "1440p" -> builder.setMaxVideoSize(2560, 1440)
            "2160p" -> builder.setMaxVideoSize(3840, 2160)
            else -> builder.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        builder.setMaxVideoFrameRate(
            if (maxVideoFrameRate > 0) maxVideoFrameRate else Int.MAX_VALUE,
        )
        builder.setMaxAudioChannelCount(
            if (maxAudioChannels > 0) maxAudioChannels else Int.MAX_VALUE,
        )
        player.trackSelectionParameters = builder.build()
        logPlayer(
            "ADVANCED_TRACK_CONSTRAINTS resolution=" + maxVideoResolution +
                " fps=" + maxVideoFrameRate +
                " audioChannels=" + maxAudioChannels,
        )
    }

    private fun applySubtitlePreferences() {
        if (!::playerView.isInitialized) return
        playerView.subtitleView?.apply {
            setFractionalTextSize((SubtitleViewFraction.DEFAULT * subtitleScale).coerceIn(0.02f, 0.12f))
            setBottomPaddingFraction((subtitleBottomPaddingPercent / 100f).coerceIn(0f, 0.5f))
            setApplyEmbeddedStyles(subtitleEmbeddedStyle)
            setApplyEmbeddedFontSizes(subtitleEmbeddedStyle)
            setUserDefaultStyle()
        }
        logPlayer(
            "SUBTITLE_PREFS scale=" + subtitleScale +
                " padding=" + subtitleBottomPaddingPercent +
                " embedded=" + subtitleEmbeddedStyle,
        )
    }

    private object SubtitleViewFraction {
        const val DEFAULT = 0.0533f
    }

    private fun configureWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun canEnterPictureInPicture(): Boolean {
        return pipEnabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private fun configurePictureInPicture() {
        updatePictureInPictureParams()
    }

    private fun updatePictureInPictureParams() {
        if (!canEnterPictureInPicture()) return
        val builder = PictureInPictureParams.Builder()
        if (::playerView.isInitialized && playerView.width > 0 && playerView.height > 0) {
            builder.setSourceRectHint(Rect(playerView.left, playerView.top, playerView.right, playerView.bottom))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(::player.isInitialized && player.playWhenReady && player.isPlaying)
        }
        setPictureInPictureParams(builder.build())
    }

    private fun installBasePlayerView() {
        playerView = layoutInflater.inflate(
            R.layout.native_player_view,
            root,
            false,
        ) as PlayerView
        playerView.apply {
            tag = "reiflix_player_view"
            useController = false
            controllerAutoShow = false
            controllerHideOnTouch = false
            keepScreenOn = true
            setKeepContentOnPlayerReset(true)
            setShutterBackgroundColor(Color.BLACK)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        root.addView(playerView)

        preparingIndicator = ProgressBar(this).apply {
            tag = "reiflix_player_preparing"
            isIndeterminate = true
            visibility = View.VISIBLE
            contentDescription = "Preparando vídeo"
        }
        root.addView(
            preparingIndicator,
            FrameLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = Gravity.CENTER
            },
        )
    }

    private fun installGestureLayer() {
        val gestureLayer = GestureLayer(this).apply {
            tag = "reiflix_gesture_layer"
        }
        root.addView(
            gestureLayer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )
    }

    private fun installControls() {
        controls = FrameLayout(this).apply {
            tag = "reiflix_controls_root"
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
        }
        root.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )
        controls.bringToFront()

        topBar = LinearLayout(this).apply {
            tag = "reiflix_top_bar"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(0x88000000.toInt())
        }
        val topParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.TOP }
        controls.addView(topBar, topParams)

        val back = actionButton("‹", 44) {
            logPlayer("PLAYER_BACK BACK_BUTTON_TOUCH requestId=" + requestId.ifEmpty { "-" })
            finishPlayer("back_button")
        }
        back.tag = "reiflix_back_button"
        back.contentDescription = "Voltar"
        topBar.addView(back, weightParams(44))

        val title = TextView(this).apply {
            text = titleValue
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            tag = "reiflix_player_title"
            contentDescription = "Título do episódio"
        }
        topBar.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))

        lockButton = actionButton(if (locked) "🔒" else "🔓", 48) {
            setLocked(!locked)
        }.apply {
            tag = "reiflix_lock_button"
            contentDescription = if (locked) "Desbloquear controles" else "Bloquear controles"
        }
        topBar.addView(lockButton, weightParams(48))

        val moreButton = actionButton("⋮", 48) {
            toggleMorePanel()
        }
        moreButton.contentDescription = "Mais opções"
        moreButton.tag = "reiflix_more_button"
        topBar.addView(moreButton, weightParams(48))

        centerControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val centerParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER }
        controls.addView(centerControls, centerParams)

        feedback = TextView(this).apply {
            tag = "reiflix_feedback"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setBackgroundColor(0xAA000000.toInt())
            visibility = View.GONE
            isClickable = false
        }
        root.addView(feedback, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER
            topMargin = dp(96)
        })

        errorPanel = LinearLayout(this).apply {
            tag = "reiflix_error_panel"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundColor(0xEE0B0A0F.toInt())
            visibility = View.GONE
            isFocusable = true
        }
        errorPanel.addView(TextView(this).apply {
            tag = "reiflix_error_text"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        errorPanel.addView(TextView(this).apply {
            tag = "reiflix_error_reason"
            textSize = 10f
            setTextColor(0xFFB8B8B8.toInt())
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(8)
        })
        val errorRetry = actionButton("Tentar novamente", 170) {
            retryCurrentMedia()
        }
        errorRetry.tag = "reiflix_error_retry"
        errorPanel.addView(errorRetry, LinearLayout.LayoutParams(dp(190), dp(48)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(14)
        })

        val errorBack = actionButton("Voltar ao ReiAnix", 170) {
            finishPlayer("player_error_back")
        }
        errorBack.tag = "reiflix_error_back"
        errorPanel.addView(errorBack, LinearLayout.LayoutParams(dp(190), dp(48)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
        })
        controls.addView(errorPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER
            leftMargin = dp(18)
            rightMargin = dp(18)
        })

        val previousButton = actionButton("−10", 70) { seekBy(-10_000L, "−10s") }.apply {
            contentDescription = "Voltar 10 segundos"
        }
        previousButton.tag = "reiflix_seek_back"
        centerControls.addView(previousButton, weightParams(70))

        playPauseButton = actionButton("▶", 84) { togglePlayPause() }.apply {
            contentDescription = "Reproduzir ou pausar"
            textSize = 26f
            tag = "reiflix_play_pause"
            minHeight = dp(72)
            minWidth = dp(72)
        }
        centerControls.addView(playPauseButton, weightParams(84))

        val nextButton = actionButton("+10", 70) { seekBy(10_000L, "+10s") }.apply {
            contentDescription = "Avançar 10 segundos"
        }
        nextButton.tag = "reiflix_seek_forward"
        centerControls.addView(nextButton, weightParams(70))

        bottomBar = LinearLayout(this).apply {
            tag = "reiflix_bottom_bar"
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
            setBackgroundColor(0x88000000.toInt())
        }
        val bottomParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.BOTTOM }
        controls.addView(bottomBar, bottomParams)

        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        positionLabel = TextView(this).apply {
            text = "00:00"
            textSize = 11f
            setTextColor(Color.WHITE)
            tag = "reiflix_position"
        }
        durationLabel = TextView(this).apply {
            text = "00:00"
            textSize = 11f
            setTextColor(Color.WHITE)
            tag = "reiflix_duration"
        }
        seekBar = SeekBar(this).apply {
            max = SEEK_PROGRESS_MAX
            contentDescription = "Barra de progresso"
            tag = "reiflix_seekbar"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && ::player.isInitialized && player.duration > 0L) {
                        val target = player.duration * progress.toLong() / SEEK_PROGRESS_MAX
                        positionLabel.text = formatTime(target)
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {
                    touchControls()
                }

                override fun onStopTrackingTouch(bar: SeekBar?) {
                    if (!::player.isInitialized || player.duration <= 0L) return
                    val target = player.duration * seekBar.progress.toLong() / SEEK_PROGRESS_MAX
                    player.seekTo(target.coerceIn(0L, player.duration))
                    saveProgress("player_progress", force = true)
                    showFeedback(formatTime(target))
                    scheduleControlsHide()
                }
            })
        }
        seekRow.addView(positionLabel, LinearLayout.LayoutParams(dp(48), dp(40)))
        seekRow.addView(seekBar, LinearLayout.LayoutParams(0, dp(40), 1f))
        seekRow.addView(durationLabel, LinearLayout.LayoutParams(dp(48), dp(40)))
        bottomBar.addView(seekRow)

        val markerRow = LinearLayout(this).apply {
            tag = "reiflix_marker_row"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val skipOpening = actionButton("Pular abertura", 132) {
            localMetadata.opening?.let { seekToMarker(it.endMs, "Abertura pulada") }
        }.apply {
            tag = "reiflix_skip_opening"
            visibility = View.GONE
            contentDescription = "Pular abertura"
        }
        val skipEnding = actionButton("Pular encerramento", 150) {
            localMetadata.ending?.let { seekToMarker(it.endMs, "Encerramento pulado") }
        }.apply {
            tag = "reiflix_skip_ending"
            visibility = View.GONE
            contentDescription = "Pular encerramento"
        }
        markerRow.addView(skipOpening, weightParams(132))
        markerRow.addView(skipEnding, weightParams(150))
        bottomBar.addView(markerRow)

        val morePanel = LinearLayout(this).apply {
            tag = "reiflix_more_panel"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0xE614141A.toInt())
            visibility = View.GONE
        }
        fun addMoreRow(vararg buttons: View) {
            val row = LinearLayout(this@NativePlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            buttons.forEach { row.addView(it, weightParams(96)) }
            morePanel.addView(row)
        }
        val previousEpisode = actionButton("Anterior", 92) {
            if (intent.getBooleanExtra("canPrevious", false)) requestEpisode("player_previous_request")
        }.apply {
            tag = "reiflix_previous_episode"
            isEnabled = intent.getBooleanExtra("canPrevious", false)
        }
        val nextEpisode = actionButton("Próximo", 92) {
            if (intent.getBooleanExtra("canNext", false)) requestEpisode("player_next_request")
        }.apply {
            tag = "reiflix_next_episode"
            isEnabled = intent.getBooleanExtra("canNext", false)
        }
        val audio = actionButton("Áudio", 92) { showTrackSelection(C.TRACK_TYPE_AUDIO, "Áudio") }.apply {
            tag = "reiflix_audio_button"
        }
        val subtitle = actionButton("Legenda", 92) { showTrackSelection(C.TRACK_TYPE_TEXT, "Legendas") }.apply {
            tag = "reiflix_subtitle_button"
        }
        val speed = actionButton("1.0x", 92) { button -> showSpeedSelection(button) }.apply {
            tag = "reiflix_speed_button"
            contentDescription = "Velocidade de reprodução"
        }
        val aspect = actionButton("Aspecto", 92) { button -> showAspectSelection(button) }.apply {
            text = aspectModeLabel
            tag = "reiflix_aspect_button"
        }
        val markers = actionButton("Marcadores", 92) {
            showLocalMetadataEditor()
        }.apply {
            tag = "reiflix_markers_button"
            contentDescription = "Marcadores e notas locais"
        }
        addMoreRow(previousEpisode, nextEpisode)
        addMoreRow(audio, subtitle)
        addMoreRow(speed, aspect)
        addMoreRow(markers)
        addMoreRow(actionButton("Reiniciar", 92) {
            if (::player.isInitialized) {
                player.seekTo(0L)
                saveProgress("player_progress", force = true)
                showFeedback("00:00")
            }
        }, actionButton("Autoplay", 92) { button ->
            autoplayNext = !autoplayNext
            button.text = "Autoplay " + if (autoplayNext) "ON" else "OFF"
            NativeMailbox.write(
                this@NativePlayerActivity,
                JSONObject().put("type", "player_autoplay_changed")
                    .put("requestId", requestId)
                    .put("payload", JSONObject().put("enabled", autoplayNext))
            )
            showFeedback(if (autoplayNext) "Autoplay ligado" else "Autoplay desligado")
        })

        val volumeGestureButton = actionButton(gestureSettingLabel("Volume", volumeGesturesEnabled), 120) { button ->
            volumeGesturesEnabled = !volumeGesturesEnabled
            gesturePreferences.edit().putBoolean(PREF_GESTURES_VOLUME, volumeGesturesEnabled).apply()
            button.text = gestureSettingLabel("Volume", volumeGesturesEnabled)
            showFeedback(if (volumeGesturesEnabled) "Gesto de volume ligado" else "Gesto de volume desligado")
            touchControls()
        }.apply {
            tag = "reiflix_gesture_volume"
            contentDescription = "Configurar gesto de volume"
        }
        val brightnessGestureButton = actionButton(gestureSettingLabel("Brilho", brightnessGesturesEnabled), 120) { button ->
            brightnessGesturesEnabled = !brightnessGesturesEnabled
            gesturePreferences.edit().putBoolean(PREF_GESTURES_BRIGHTNESS, brightnessGesturesEnabled).apply()
            button.text = gestureSettingLabel("Brilho", brightnessGesturesEnabled)
            showFeedback(if (brightnessGesturesEnabled) "Gesto de brilho ligado" else "Gesto de brilho desligado")
            touchControls()
        }.apply {
            tag = "reiflix_gesture_brightness"
            contentDescription = "Configurar gesto de brilho"
        }
        val doubleTapButton = actionButton(gestureSettingLabel("Double tap", doubleTapEnabled), 120) { button ->
            doubleTapEnabled = !doubleTapEnabled
            gesturePreferences.edit().putBoolean(PREF_GESTURES_DOUBLE_TAP, doubleTapEnabled).apply()
            button.text = gestureSettingLabel("Double tap", doubleTapEnabled)
            showFeedback(if (doubleTapEnabled) "Double tap ligado" else "Double tap desligado")
            touchControls()
        }.apply {
            tag = "reiflix_gesture_double_tap"
            contentDescription = "Configurar double tap"
        }
        val longPressButton = actionButton(gestureSettingLabel("Pressão", longPressEnabled), 120) { button ->
            longPressEnabled = !longPressEnabled
            gesturePreferences.edit().putBoolean(PREF_GESTURES_LONG_PRESS, longPressEnabled).apply()
            button.text = gestureSettingLabel("Pressão", longPressEnabled)
            showFeedback(if (longPressEnabled) "Pressão longa ligada" else "Pressão longa desligada")
            touchControls()
        }.apply {
            tag = "reiflix_gesture_long_press"
            contentDescription = "Configurar pressão longa"
        }
        addMoreRow(volumeGestureButton, brightnessGestureButton)
        addMoreRow(doubleTapButton, longPressButton)
        if (canEnterPictureInPicture()) {
            val pip = actionButton("PIP", 92) { enterPictureInPictureMode() }
            pip.contentDescription = "Picture in Picture"
            addMoreRow(pip)
        }
        addMoreRow(actionButton("Informações", 92) { showTechnicalInfo() }.apply {
            tag = "reiflix_technical_info"
            contentDescription = "Informações técnicas"
        })
        controls.addView(morePanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(56)
            rightMargin = dp(8)
        })

        // Compose owns the primary playback controls. Keep the existing View tree
        // for secondary menus and compatibility, but do not show duplicate primary controls.
        topBar.visibility = View.GONE
        centerControls.visibility = View.GONE
        bottomBar.visibility = View.GONE
        findViewByTag<View>("reiflix_marker_row")?.visibility = View.GONE
        installComposePlayerControls()
        controls.bringToFront()
    }

    private fun installComposePlayerControls() {
        composeControlsEnabled = true

        composeTopView = ComposeView(this).apply {
            tag = "reiflix_compose_player_top"
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ReiAnixComposeTheme {
                    ReiAnixNativePlayerTopControls(
                        state = composePlayerUiState.value,
                        onBack = { finishPlayer("back_button") },
                    )
                }
            }
        }
        composeCenterView = ComposeView(this).apply {
            tag = "reiflix_compose_player_center"
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ReiAnixComposeTheme {
                    ReiAnixNativePlayerCenterControls(
                        state = composePlayerUiState.value,
                        onPlayPause = { togglePlayPause() },
                        onSeekRelative = { deltaMs ->
                            val seconds = deltaMs / 1000L
                            val label = if (seconds < 0L) "−" + (-seconds) + "s" else "+" + seconds + "s"
                            seekBy(deltaMs, label)
                        },
                    )
                }
            }
        }
        composeBottomView = ComposeView(this).apply {
            tag = "reiflix_compose_player_bottom"
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ReiAnixComposeTheme {
                    ReiAnixNativePlayerBottomControls(
                        state = composePlayerUiState.value,
                        onSeekTo = { targetMs ->
                            if (::player.isInitialized && player.duration > 0L) {
                                val safeTarget = targetMs.coerceIn(0L, player.duration)
                                player.seekTo(safeTarget)
                                saveProgress("player_progress", force = true)
                                showFeedback(formatTime(safeTarget))
                                touchControls()
                            }
                        },
                        onToggleLock = { setLocked(!locked) },
                        onResize = {
                            findViewByTag<TextView>("reiflix_aspect_button")?.let(::showAspectSelection)
                        },
                        onSource = { toggleMorePanel() },
                        onNext = {
                            if (intent.getBooleanExtra("canNext", false)) {
                                requestEpisode("player_next_request")
                            }
                        },
                    )
                }
            }
        }

        controls.addView(
            composeTopView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(132),
                Gravity.TOP,
            ),
        )
        controls.addView(
            composeCenterView,
            FrameLayout.LayoutParams(
                dp(240),
                dp(110),
                Gravity.CENTER,
            ),
        )
        controls.addView(
            composeBottomView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(154),
                Gravity.BOTTOM,
            ),
        )
        updateComposeOverlayBounds()
        syncComposePlayerUiState()
    }

    private fun updateComposeOverlayBounds() {
        if (!composeControlsEnabled) return
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val topHeight = dp(if (landscape) 108 else 132)
        val centerHeight = dp(if (landscape) 100 else 110)
        val bottomHeight = dp(if (landscape) 128 else 154)
        if (::composeTopView.isInitialized) {
            composeTopView.layoutParams = (composeTopView.layoutParams as FrameLayout.LayoutParams).apply {
                width = FrameLayout.LayoutParams.MATCH_PARENT
                height = topHeight
                gravity = Gravity.TOP
            }
        }
        if (::composeCenterView.isInitialized) {
            composeCenterView.layoutParams = (composeCenterView.layoutParams as FrameLayout.LayoutParams).apply {
                width = dp(240)
                height = centerHeight
                gravity = Gravity.CENTER
            }
        }
        if (::composeBottomView.isInitialized) {
            composeBottomView.layoutParams = (composeBottomView.layoutParams as FrameLayout.LayoutParams).apply {
                width = FrameLayout.LayoutParams.MATCH_PARENT
                height = bottomHeight
                gravity = Gravity.BOTTOM
            }
        }
    }

    private fun syncComposePlayerUiState() {
        if (!composeControlsEnabled) return
        val currentDuration = if (::player.isInitialized) player.duration else 0L
        val currentPosition = if (::player.isInitialized) player.currentPosition else 0L
        val videoSize = if (::player.isInitialized) player.videoSize else null
        val resolution = videoSize
            ?.takeIf { it.width > 0 && it.height > 0 }
            ?.let { it.width.toString() + "×" + it.height }
        val episodeLabel = Regex("(?i)epis(?:ó|o)dio\\s+([0-9]+(?:[.,][0-9]+)?)")
            .find(titleValue)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(',', '.')
            ?.let { raw ->
                val normalized = raw.toDoubleOrNull()
                if (normalized != null && normalized % 1.0 == 0.0) {
                    "Episódio " + normalized.toInt().toString().padStart(2, '0')
                } else {
                    "Episódio " + raw
                }
            }
            ?: "Episódio"

        composePlayerUiState.value = ReiAnixNativePlayerUiState(
            title = titleValue,
            episodeLabel = listOfNotNull(episodeLabel, resolution).joinToString(" • "),
            technicalLine = buildPlayerTechnicalLine(),
            positionMs = currentPosition.coerceAtLeast(0L),
            durationMs = currentDuration.coerceAtLeast(0L),
            isPlaying = ::player.isInitialized && player.isPlaying,
            isBuffering = ::player.isInitialized && player.playbackState == Player.STATE_BUFFERING,
            ended = ::player.isInitialized && player.playbackState == Player.STATE_ENDED,
            errorVisible = errorVisible,
            controlsVisible = controlsVisible,
            locked = locked,
            canNext = intent.getBooleanExtra("canNext", false),
            canPrevious = intent.getBooleanExtra("canPrevious", false),
            aspectLabel = aspectModeLabel,
            playbackSpeed = if (::player.isInitialized) player.playbackParameters.speed else 1f,
            safeTopPx = gestureSafeTop,
            safeBottomPx = gestureSafeBottom,
        )
        val overlaysVisible = controlsVisible && !locked && !errorVisible && !inPictureInPicture
        if (::composeTopView.isInitialized) composeTopView.visibility = if (overlaysVisible) View.VISIBLE else View.INVISIBLE
        if (::composeCenterView.isInitialized) composeCenterView.visibility = if (overlaysVisible) View.VISIBLE else View.INVISIBLE
        if (::composeBottomView.isInitialized) composeBottomView.visibility = if (overlaysVisible) View.VISIBLE else View.INVISIBLE
    }

    private fun toggleMorePanel() {
        findViewByTag<GestureLayer>("reiflix_gesture_layer")?.cancelInteractions()
        moreVisible = !moreVisible
        val panel = findViewByTag<View>("reiflix_more_panel")
        panel?.visibility = if (moreVisible) View.VISIBLE else View.GONE
        if (moreVisible) {
            panel?.bringToFront()
        }
        controls.bringToFront()
        touchControls()
    }

    private fun hideLegacyPrimaryControls() {
        if (::topBar.isInitialized) topBar.visibility = View.GONE
        if (::centerControls.isInitialized) centerControls.visibility = View.GONE
        if (::bottomBar.isInitialized) bottomBar.visibility = View.GONE
    }

    private fun buildPlayerTechnicalLine(): String {
        if (!::player.isInitialized) return ""

        fun selectedFormat(type: Int): Format? =
            player.currentTracks.groups
                .filter { it.type == type && it.isSupported }
                .flatMap { group ->
                    (0 until group.length)
                        .filter { group.isTrackSupported(it) && group.isTrackSelected(it) }
                        .map { group.getTrackFormat(it) }
                }
                .firstOrNull()

        fun codecLabel(mime: String?): String? = when (mime?.lowercase(Locale.ROOT)) {
            "video/avc" -> "AVC"
            "video/hevc", "video/h265" -> "HEVC"
            "video/x-vnd.on2.vp9" -> "VP9"
            "video/av01" -> "AV1"
            "audio/mp4a-latm" -> "AAC"
            "audio/opus" -> "Opus"
            "audio/vorbis" -> "Vorbis"
            "audio/ac3" -> "AC-3"
            "audio/eac3" -> "E-AC-3"
            "audio/flac" -> "FLAC"
            else -> null
        }

        val video = selectedFormat(C.TRACK_TYPE_VIDEO)
        val audio = selectedFormat(C.TRACK_TYPE_AUDIO)
        val audioLayout = when (audio?.channelCount ?: 0) {
            1 -> "Mono"
            2 -> "Stereo"
            in 3..9 -> (audio?.channelCount ?: 0).toString() + "ch"
            else -> null
        }
        return listOfNotNull(
            codecLabel(video?.sampleMimeType),
            audioLayout,
            codecLabel(audio?.sampleMimeType),
        ).joinToString(" • ")
    }

    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    logPlayer("PLAYER_BACK ANDROID_BACK requestId=" + requestId.ifEmpty { "-" })
                    if (moreVisible) {
                        moreVisible = false
                        findViewByTag<View>("reiflix_more_panel")?.visibility = View.GONE
                        touchControls()
                        return
                    }
                    finishPlayer("android_back")
                }
            },
        )
    }

    private fun applyRootInsets(insets: WindowInsetsCompat) {
        val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
        val cutout = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout())
        val mandatoryGestures = insets.getInsetsIgnoringVisibility(
            WindowInsetsCompat.Type.mandatorySystemGestures(),
        )
        gestureSafeLeft = maxOf(bars.left, cutout.left, mandatoryGestures.left)
        gestureSafeTop = maxOf(bars.top, cutout.top, mandatoryGestures.top)
        gestureSafeRight = maxOf(bars.right, cutout.right, mandatoryGestures.right)
        gestureSafeBottom = maxOf(bars.bottom, cutout.bottom, mandatoryGestures.bottom)

        val topParams = topBar.layoutParams as? FrameLayout.LayoutParams
        if (topParams != null) {
            topParams.topMargin = max(dp(4), gestureSafeTop)
            topBar.layoutParams = topParams
        }
        val bottomParams = bottomBar.layoutParams as? FrameLayout.LayoutParams
        if (bottomParams != null) {
            bottomParams.bottomMargin = max(dp(4), gestureSafeBottom)
            bottomBar.layoutParams = bottomParams
        }
        controls.setPadding(
            max(dp(4), gestureSafeLeft),
            0,
            max(dp(4), gestureSafeRight),
            0,
        )
        syncComposePlayerUiState()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (!::playPauseButton.isInitialized || errorVisible) return super.dispatchKeyEvent(event)
                    if (!controlsVisible) {
                        setControlsVisible(true)
                        playPauseButton.requestFocus()
                        togglePlayPause()
                        return true
                    }
                    // Let the focused player action receive OK/Enter so Audio,
                    // Subtitles, Speed, Aspect, episode navigation and other
                    // existing controls keep their normal click semantics.
                    return super.dispatchKeyEvent(event)
                }
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (isTelevision && !controlsVisible && !errorVisible) {
                        setControlsVisible(true)
                        playPauseButton.requestFocus()
                        touchControls()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun togglePlayPause() {
        if (!::player.isInitialized || errorVisible) return
        when {
            player.playbackState == Player.STATE_ENDED -> {
                // The UI already exposes the replay affordance (↻) for an ended
                // item, so tapping it must explicitly rewind before playback.
                player.seekTo(0L)
                player.play()
                logPlayer("PLAYER_PLAY requestId=" + requestId.ifEmpty { "-" } + " reason=replay")
            }
            player.isPlaying -> {
                player.pause()
                logPlayer("PLAYER_PAUSE requestId=" + requestId.ifEmpty { "-" })
                saveProgress("player_paused", force = true)
            }
            else -> {
                player.play()
                logPlayer("PLAYER_PLAY requestId=" + requestId.ifEmpty { "-" })
            }
        }
        touchControls()
        updatePlayPauseButton()
    }

    private fun updatePlayPauseButton() {
        if (::playPauseButton.isInitialized) {
            playPauseButton.text = when {
                !::player.isInitialized -> "▶"
                player.isPlaying -> "❚❚"
                player.playbackState == Player.STATE_ENDED -> "↻"
                else -> "▶"
            }
        }
        syncComposePlayerUiState()
    }

    private fun updateTrackButtons() {
        if (!::player.isInitialized) return

        fun supportedTrackCount(type: Int): Int =
            player.currentTracks.groups
                .filter { it.type == type && it.isSupported }
                .sumOf { group ->
                    (0 until group.length).count { group.isTrackSupported(it) }
                }

        val audioCount = supportedTrackCount(C.TRACK_TYPE_AUDIO)
        val subtitleCount = supportedTrackCount(C.TRACK_TYPE_TEXT)
        val audioAvailable = audioCount > 0
        val subtitleAvailable = subtitleCount > 0

        // A single audio track has no useful selection UI. Subtitles retain the
        // button even with one track because the existing selector can turn the
        // subtitle track off explicitly.
        findViewByTag<View>("reiflix_audio_button")?.isEnabled = audioAvailable && audioCount > 1
        findViewByTag<View>("reiflix_subtitle_button")?.isEnabled = subtitleAvailable
    }

    private fun updateProgressUi() {
        if (!::player.isInitialized) {
            syncComposePlayerUiState()
            return
        }
        val duration = player.duration
        val position = player.currentPosition.coerceAtLeast(0L)
        if (::seekBar.isInitialized && duration > 0L) {
            seekBar.progress = ((position.toDouble() / duration.toDouble()) * SEEK_PROGRESS_MAX)
                .roundToInt().coerceIn(0, SEEK_PROGRESS_MAX)
        } else if (::seekBar.isInitialized) {
            seekBar.progress = 0
        }
        if (::positionLabel.isInitialized) positionLabel.text = formatTime(position)
        if (::durationLabel.isInitialized) {
            durationLabel.text = if (duration > 0L) formatTime(duration) else "--:--"
        }
        updateMetadataControls(position)
        syncComposePlayerUiState()
    }

    private fun startProgressReporting() {
        handler.removeCallbacks(progressReporter)
        handler.post(progressReporter)
    }

    private fun showSpeedSelection(button: TextView) {
        findViewByTag<GestureLayer>("reiflix_gesture_layer")?.cancelInteractions()
        if (!::player.isInitialized) return
        val speeds = floatArrayOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        val labels = speeds.map { String.format(java.util.Locale.US, "%.2fx", it) }.toTypedArray()
        val currentIndex = speeds.indices.minByOrNull { abs(speeds[it] - player.playbackParameters.speed) } ?: 2
        touchControls()
        AlertDialog.Builder(this)
            .setTitle("Velocidade")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                player.setPlaybackSpeed(speeds[which])
                button.text = labels[which]
                button.isSelected = true
                showFeedback(labels[which])
                touchControls()
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showAspectSelection(button: TextView) {
        findViewByTag<GestureLayer>("reiflix_gesture_layer")?.cancelInteractions()
        val labels = arrayOf("Ajustar", "Preencher")
        val current = when (button.text.toString()) {
            in labels -> button.text.toString()
            "Original", "Auto" -> "Ajustar"
            else -> "Ajustar"
        }
        val currentIndex = labels.indexOf(current).coerceAtLeast(0)
        touchControls()
        AlertDialog.Builder(this)
            .setTitle("Aspecto")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                applyAspectMode(labels[which], button)
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun applyAspectMode(mode: String, button: TextView) {
        if (!::playerView.isInitialized) return
        findViewByTag<GestureLayer>("reiflix_gesture_layer")?.resetZoomToFit()
        playerView.resizeMode = when (mode) {
            "Preencher" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        button.text = if (mode == "Preencher") "Preencher" else "Ajustar"
        button.isSelected = mode == "Preencher"
        aspectModeLabel = button.text.toString()
        showFeedback(button.text.toString())
        touchControls()
        playerView.requestLayout()
        syncComposePlayerUiState()
    }

    private fun captureTrackFormatSummaries() {
        if (!::player.isInitialized) return
        var videoSummary: String? = null
        var audioSummary: String? = null
        for (group in player.currentTracks.groups) {
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                val summary = formatSummary(format, group.isTrackSelected(index))
                when (format.sampleMimeType?.substringBefore('/').orEmpty()) {
                    "video" -> if (videoSummary == null || group.isTrackSelected(index)) videoSummary = summary
                    "audio" -> if (audioSummary == null || group.isTrackSelected(index)) audioSummary = summary
                }
            }
        }
        videoFormatSummary = videoSummary
        audioFormatSummary = audioSummary
        subtitleFormatSummary = player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
            .flatMap { group ->
                (0 until group.length)
                    .filter { group.isTrackSupported(it) && group.isTrackSelected(it) }
                    .map { group.getTrackFormat(it) }
            }
            .firstOrNull()
            ?.let { formatSummary(it, true) }
        syncComposePlayerUiState()
    }

    private fun formatSummary(format: Format, selected: Boolean): String {
        val codec = formatCodecLabel(format.sampleMimeType, format.codecs)
        val label = format.label?.takeIf { it.isNotBlank() }
        val language = format.language?.takeIf { it.isNotBlank() }
        val size = if (format.width > 0 && format.height > 0) {
            format.width.toString() + "x" + format.height
        } else null
        val frameRate = format.frameRate.takeIf { it > 0f }?.let {
            String.format(Locale.US, "%.3f fps", it)
        }
        val channels = format.channelCount.takeIf { it > 0 }?.let { it.toString() + " ch" }
        val sampleRate = format.sampleRate.takeIf { it > 0 }?.let { it.toString() + " Hz" }
        val bitrate = format.bitrate.takeIf { it > 0 }?.let { it.toString() + " bps" }
        return listOfNotNull(
            if (selected) "selected" else null,
            codec, label, language, size, frameRate, channels, sampleRate, bitrate,
        ).joinToString(" • ")
    }

    private fun formatCodecLabel(sampleMimeType: String?, codecs: String?): String {
        val mime = sampleMimeType.orEmpty().lowercase(Locale.ROOT)
        val codec = codecs?.takeIf { it.isNotBlank() }
        return when (mime) {
            "video/avc" -> "H.264" + if (codec != null) " (" + codec + ")" else ""
            "video/hevc" -> "HEVC" + if (codec != null) " (" + codec + ")" else ""
            "video/x-vnd.on2.vp9" -> "VP9" + if (codec != null) " (" + codec + ")" else ""
            "video/av01" -> "AV1" + if (codec != null) " (" + codec + ")" else ""
            "audio/mp4a-latm" -> "AAC" + if (codec != null) " (" + codec + ")" else ""
            "audio/opus" -> "Opus" + if (codec != null) " (" + codec + ")" else ""
            "audio/vorbis" -> "Vorbis" + if (codec != null) " (" + codec + ")" else ""
            "audio/ac3" -> "AC-3" + if (codec != null) " (" + codec + ")" else ""
            "audio/eac3" -> "E-AC-3" + if (codec != null) " (" + codec + ")" else ""
            "audio/flac" -> "FLAC" + if (codec != null) " (" + codec + ")" else ""
            else -> listOfNotNull(sampleMimeType, codec).joinToString(" / ").ifBlank { "desconhecido" }
        }
    }

    private fun metricDelta(startMs: Long, endMs: Long): Any =
        if (startMs > 0L && endMs >= startMs) endMs - startMs else JSONObject.NULL

    private fun playbackTimingPayload(atMs: Long = System.currentTimeMillis()): JSONObject {
        val metrics = PlayerStartupMetrics(
            episodeTapAtMs = episodeTapAtMs,
            preflightStartedAtMs = preflightStartedAtMs,
            preflightCompletedAtMs = preflightCompletedAtMs,
            prepareDispatchedAtMs = prepareDispatchedAtMs,
            playerReadyAtMs = playerReadyAtMs,
            firstFrameRenderedAtMs = atMs,
        )
        return JSONObject()
            .put("commandCreatedAtMs", commandCreatedAtMs)
            .put("episodeTapAtMs", metrics.episodeTapAtMs)
            .put("originRequestId", originRequestId)
            .put("originCreatedAtMs", originCreatedAtMs)
            .put("originTransitionGeneration", originTransitionGeneration)
            .put("playerSessionId", playerSessionId)
            .put("transitionGeneration", transitionGeneration)
            .put("commandReceivedAtMs", commandReceivedAtMs)
            .put("handoffDispatchedAtMs", handoffDispatchedAtMs)
            .put("activityStartedAtMs", activityStartedAtMs)
            .put("preflightStartedAtMs", metrics.preflightStartedAtMs)
            .put("preflightCompletedAtMs", metrics.preflightCompletedAtMs)
            .put("prepareDispatchedAtMs", metrics.prepareDispatchedAtMs)
            .put("playerReadyAtMs", metrics.playerReadyAtMs)
            .put("firstFrameRenderedAtMs", metrics.firstFrameRenderedAtMs)
            .put("commandDeliveryLatencyMs", metricDelta(commandCreatedAtMs, commandReceivedAtMs))
            .put("mainActivityHandoffLatencyMs", metricDelta(commandReceivedAtMs, handoffDispatchedAtMs))
            .put("handoffLatencyMs", metricDelta(commandCreatedAtMs, handoffDispatchedAtMs))
            .put("activityStartupLatencyMs", metricDelta(handoffDispatchedAtMs, activityStartedAtMs))
            .put("tapToPreflightStartMs", metrics.tapToPreflightStartMs)
            .put("preflightDurationMs", metrics.preflightDurationMs)
            .put("prepareDispatchGapMs", metrics.prepareDispatchGapMs)
            .put("prepareToReadyMs", metrics.prepareToReadyMs)
            .put("readyToFirstFrameMs", metrics.readyToFirstFrameMs)
            .put("tapToPrepareMs", metrics.tapToPrepareMs)
            .put("tapToReadyMs", metrics.tapToReadyMs)
            .put("tapToFirstFrameMs", metrics.tapToFirstFrameMs)
            .put("preflightLatencyMs", metrics.preflightDurationMs)
            .put("prepareLatencyMs", metrics.prepareDispatchGapMs)
            .put("firstFrameLatencyMs", metricDelta(prepareDispatchedAtMs, atMs))
            .put("totalOpenToFirstFrameMs", metrics.tapToFirstFrameMs)
            .put("assist_to_activity_ms", metricDelta(commandCreatedAtMs, activityStartedAtMs))
            .put("activity_to_player_ms", metricDelta(activityStartedAtMs, prepareDispatchedAtMs))
            .put("player_prepare_ms", metricDelta(preflightCompletedAtMs, prepareDispatchedAtMs))
            .put("prepare_to_ready_ms", metrics.prepareToReadyMs)
            .put("first_frame_ms", metricDelta(prepareDispatchedAtMs, atMs))
    }

    private fun diagnosticPayload(): JSONObject = JSONObject()
        .put("timestamp", System.currentTimeMillis())
        .put("traceId", traceId)
        .put("timing", playbackTimingPayload())
        .put("uri", if (::uri.isInitialized) uri.toString() else intent.getStringExtra("uri").orEmpty())
        .put("source", if (::uri.isInitialized) sourceFor(uri) else "")
        .put("requestId", requestId)
        .put("activityInstanceId", activityInstanceId)
        .put("mediaId", currentMediaId())
        .put("episodeId", currentEpisodeId())
        .put("playerState", if (::player.isInitialized) player.playbackStateLabel() else "STATE_IDLE")
        .put("playWhenReady", if (::player.isInitialized) player.playWhenReady else false)
        .put("playbackSuppressionReason", if (::player.isInitialized) player.playbackSuppressionReason else Player.PLAYBACK_SUPPRESSION_REASON_NONE)
        .put("isPlaying", if (::player.isInitialized) player.isPlaying else false)
        .put("displayName", mediaDisplayName.orEmpty())
        .put("mimeType", contentMimeType.orEmpty())
        .put("sizeBytes", mediaSizeBytes ?: JSONObject.NULL)
        .put("decoderVideo", decoderVideoName.orEmpty())
        .put("decoderAudio", decoderAudioName.orEmpty())
        .put("video", videoFormatSummary.orEmpty())
        .put("audio", audioFormatSummary.orEmpty())
        .put("subtitleSelected", subtitleFormatSummary.orEmpty())
        .put("playbackSpeed", if (::player.isInitialized) player.playbackParameters.speed else 1f)
        .put("audioTrackCount", if (::player.isInitialized) player.currentTracks.groups.count { it.type == C.TRACK_TYPE_AUDIO && it.isSupported } else 0)
        .put("subtitleTrackCount", if (::player.isInitialized) player.currentTracks.groups.count { it.type == C.TRACK_TYPE_TEXT && it.isSupported } else 0)
.put("durationMs", if (::player.isInitialized) player.duration.coerceAtLeast(0L) else 0L)
        .put("firstFrameRendered", firstFrameRenderedForTesting)
        .put("sessionState", sessionState.name)
        .put("transitionPhase", transitionPhase.name)
        .put("windowFocus", window.decorView.hasWindowFocus())
        .put("orientation", resources.configuration.orientation)
        .put("surfaceType", "texture_view")
        .put("playerViewAttached", ::playerView.isInitialized && playerView.isAttachedToWindow)
        .put("playerViewVisible", ::playerView.isInitialized && playerView.isShown)
        .put("playerViewWidth", if (::playerView.isInitialized) playerView.width else 0)
        .put("playerViewHeight", if (::playerView.isInitialized) playerView.height else 0)
        .put("videoWidth", if (::player.isInitialized) player.videoSize.width else 0)
        .put("videoHeight", if (::player.isInitialized) player.videoSize.height else 0)
        .put("failureKind", currentFailureKind.name)
        .put("retryable", currentFailureRetryable)
        .put("mediaPeriodId", lastMediaPeriodId)
        .put("dataSourceUri", lastLoadUri)
        .put("dataType", lastLoadDataType)
        .put("trackType", lastLoadTrackType)
        .put("loadErrorClass", lastLoadErrorClass)
        .put("loadErrorMessage", lastLoadErrorMessage)

    private fun throwableChain(error: Throwable?): List<Throwable> {
        if (error == null) return emptyList()
        val chain = mutableListOf<Throwable>()
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = error
        while (current != null && chain.size < 8 && seen.add(current)) {
            chain += current
            current = current.cause
        }
        return chain
    }

    private fun ExoPlayer.playbackStateLabel(): String = when (playbackState) {
        Player.STATE_IDLE -> "STATE_IDLE"
        Player.STATE_BUFFERING -> "STATE_BUFFERING"
        Player.STATE_READY -> "STATE_READY"
        Player.STATE_ENDED -> "STATE_ENDED"
        else -> "STATE_UNKNOWN"
    }

    private fun buildTechnicalInfo(): String {
        if (!::player.isInitialized) return "Player ainda não foi inicializado."
        captureTrackFormatSummaries()
        return buildString {
            append("Arquivo: ").append(mediaDisplayName ?: uri.lastPathSegment.orEmpty().ifBlank { "desconhecido" }).append('\n')
            append("MIME: ").append(contentMimeType ?: "não informado").append('\n')
            mediaSizeBytes?.let { append("Tamanho: ").append(it).append(" bytes").append('\n') }
            append("Duração: ").append(formatTime(player.duration.coerceAtLeast(0L))).append('\n')
            append("Velocidade: ").append(String.format(Locale.US, "%.2fx", player.playbackParameters.speed)).append('\n')
            append("Decodificador vídeo: ").append(decoderVideoName ?: "não informado").append('\n')
            append("Decodificador áudio: ").append(decoderAudioName ?: "não informado").append('\n')
            append("Vídeo: ").append(videoFormatSummary ?: "nenhuma faixa detectada").append('\n')
            append("Áudio: ").append(audioFormatSummary ?: "nenhuma faixa detectada").append('\n')
            append("Legenda selecionada: ").append(subtitleFormatSummary ?: "nenhuma").append('\n')
            append("Legendas disponíveis: ").append(player.currentTracks.groups.count { it.type == C.TRACK_TYPE_TEXT && it.isSupported })
        }
    }

    private fun showTechnicalInfo() {
        if (!::player.isInitialized) return
        touchControls()
        AlertDialog.Builder(this)
            .setTitle("Informações técnicas")
            .setMessage(buildTechnicalInfo())
            .setPositiveButton("Fechar", null)
            .show()
    }
    private fun showTrackSelection(trackType: Int, label: String) {
        findViewByTag<GestureLayer>("reiflix_gesture_layer")?.cancelInteractions()
        if (!::player.isInitialized) return

        data class TrackOption(
            val title: String,
            val group: androidx.media3.common.Tracks.Group?,
            val trackIndex: Int,
        )

        val realTracks = mutableListOf<TrackOption>()
        player.currentTracks.groups
            .filter { it.type == trackType && it.isSupported }
            .forEachIndexed { groupIndex, group ->
                for (trackIndex in 0 until group.length) {
                    if (!group.isTrackSupported(trackIndex)) continue
                    val format = group.getTrackFormat(trackIndex)
                    val language = format.language?.takeIf { it.isNotBlank() }
                    val labelText = format.label?.takeIf { it.isNotBlank() }
                    val channels = format.channelCount
                        .takeIf { it > 0 }
                        ?.let { " " + it + "ch" }
                        .orEmpty()
                    val codec = formatCodecLabel(format.sampleMimeType, format.codecs)
                    val suffix = listOfNotNull(
                        language,
                        channels.takeIf { it.isNotBlank() },
                        codec.takeIf { it.isNotBlank() },
                    ).joinToString(" • ")
                    val base = labelText ?: language ?: "Faixa " + (groupIndex + 1) + "." + (trackIndex + 1)
                    realTracks += TrackOption(
                        title = if (suffix.isBlank() || base.contains(suffix, ignoreCase = true)) {
                            base
                        } else {
                            base + " • " + suffix
                        },
                        group = group,
                        trackIndex = trackIndex,
                    )
                }
            }

        if (trackType == C.TRACK_TYPE_AUDIO && realTracks.size <= 1) {
            showFeedback(
                if (realTracks.isEmpty()) "Nenhuma faixa de áudio disponível"
                else "Apenas uma faixa de áudio",
            )
            return
        }
        if (realTracks.isEmpty()) {
            showFeedback("Nenhuma faixa disponível")
            return
        }

        val options = buildList {
            add(TrackOption("Automático", null, -1))
            if (trackType == C.TRACK_TYPE_TEXT) {
                add(TrackOption("Desativadas", null, -2))
            }
            addAll(realTracks)
        }

        val parameters = player.trackSelectionParameters
        val disabled = parameters.disabledTrackTypes.contains(trackType)
        val overriddenGroups = parameters.overrides.keys
        val selectedIndex = when {
            trackType == C.TRACK_TYPE_TEXT && disabled -> options.indexOfFirst { it.trackIndex == -2 }
            overriddenGroups.isNotEmpty() -> options.indexOfFirst { option ->
                option.group != null && option.group.mediaTrackGroup in overriddenGroups
            }
            else -> 0
        }.let { if (it >= 0) it else 0 }

        val labels = options.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(label)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                val option = options.getOrNull(which) ?: return@setSingleChoiceItems
                runCatching {
                    val builder = player.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(trackType)
                    when {
                        trackType == C.TRACK_TYPE_TEXT && option.trackIndex == -2 -> {
                            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        }
                        else -> {
                            builder.setTrackTypeDisabled(trackType, false)
                            if (option.group != null && option.trackIndex >= 0) {
                                builder.setOverrideForType(
                                    TrackSelectionOverride(
                                        option.group.mediaTrackGroup,
                                        option.trackIndex,
                                    ),
                                )
                            }
                        }
                    }
                    player.trackSelectionParameters = builder.build()
                    updateTrackButtons()
                    captureTrackFormatSummaries()
                    logPlayer(
                        "TRACK_SELECTION_APPLIED type=" + trackType + " index=" +
                            option.trackIndex + " label=" + option.title,
                    )
                }.onFailure { error ->
                    logPlayer("TRACK_SELECTION_FAILED type=" + trackType, error)
                    showFeedback("Não foi possível trocar a faixa")
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun seekBy(deltaMs: Long, feedbackText: String) {
        if (!::player.isInitialized || player.duration <= 0L) return
        val target = PlayerGesturePolicy.seekTarget(
            currentPositionMs = player.currentPosition,
            deltaMs = deltaMs,
            durationMs = player.duration,
        )
        player.seekTo(target)
        saveProgress("player_progress", force = true)
        showFeedback(feedbackText)
        touchControls()
    }

    private fun showFeedback(message: String, durationMs: Long = 900L) {
        if (!::feedback.isInitialized) return
        feedback.text = message
        feedback.visibility = View.VISIBLE
        feedbackSessionId = playerSessionId
        feedbackRequestId = requestId
        feedbackPlayerGeneration = playerGeneration
        feedbackHideAt = System.currentTimeMillis() + durationMs
        handler.removeCallbacks(feedbackHider)
        handler.post(feedbackHider)
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        if (!visible) {
            handler.removeCallbacks(controlsHider)
        }
        if (inPictureInPicture) {
            handler.removeCallbacks(controlsHider)
            moreVisible = false
            findViewByTag<View>("reiflix_more_panel")?.visibility = View.GONE
            controls.visibility = View.INVISIBLE
            hideLegacyPrimaryControls()
            syncComposePlayerUiState()
            return
        }
        if (locked) {
            controls.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            topBar.visibility = if (visible) View.VISIBLE else View.GONE
            bottomBar.visibility = View.GONE
            centerControls.visibility = View.GONE
            findViewByTag<View>("reiflix_more_panel")?.visibility = View.GONE
            findViewByTag<View>("reiflix_back_button")?.visibility = View.GONE
            findViewByTag<View>("reiflix_more_button")?.visibility = View.GONE
            if (visible) {
                handler.removeCallbacks(lockAffordanceHider)
                handler.postDelayed(lockAffordanceHider, LOCK_AFFORDANCE_TIMEOUT_MS)
            } else {
                handler.removeCallbacks(lockAffordanceHider)
            }
            syncComposePlayerUiState()
            return
        }

        controls.visibility = if (visible || errorVisible) View.VISIBLE else View.INVISIBLE
        if (composeControlsEnabled) {
            hideLegacyPrimaryControls()
        } else {
            topBar.visibility = if (visible || errorVisible) View.VISIBLE else View.GONE
            bottomBar.visibility = if (visible || errorVisible) View.VISIBLE else View.GONE
            centerControls.visibility = if (visible || errorVisible) View.VISIBLE else View.GONE
        }
        findViewByTag<View>("reiflix_back_button")?.visibility =
            if (composeControlsEnabled) View.GONE else View.VISIBLE
        findViewByTag<View>("reiflix_more_button")?.visibility =
            if (composeControlsEnabled) View.GONE else View.VISIBLE
        moreVisible = false
        findViewByTag<View>("reiflix_more_panel")?.visibility = View.GONE
        syncComposePlayerUiState()
    }

    private fun touchControls() {
        if (locked) {
            handler.removeCallbacks(controlsHider)
            setControlsVisible(true)
            handler.removeCallbacks(lockAffordanceHider)
            handler.postDelayed(lockAffordanceHider, LOCK_AFFORDANCE_TIMEOUT_MS)
            return
        }
        controlsVisible = true
        controls.visibility = View.VISIBLE
        hideLegacyPrimaryControls()
        lastControlsInteraction = System.currentTimeMillis()
        handler.removeCallbacks(controlsHider)
        if (::player.isInitialized && player.isPlaying && !errorVisible && autoHideTimeoutMs > 0L) {
            handler.postDelayed(controlsHider, autoHideTimeoutMs)
        }
        syncComposePlayerUiState()
    }

    private fun scheduleControlsHide() {
        if (!locked) touchControls()
    }

    private fun setLocked(value: Boolean, persist: Boolean = true, announce: Boolean = true) {
        locked = value
        if (persist) {
            gesturePreferences.edit().putBoolean(PREF_LOCK_MODE, locked).apply()
        }
        findViewByTag<View>("reiflix_gesture_layer")?.let {
            (it as? GestureLayer)?.cancelInteractions()
        }
        if (locked) {
            handler.removeCallbacks(controlsHider)
            moreVisible = false
            findViewByTag<View>("reiflix_more_panel")?.visibility = View.GONE
            findViewByTag<View>("reiflix_back_button")?.visibility = View.GONE
            findViewByTag<View>("reiflix_more_button")?.visibility = View.GONE
            controls.visibility = View.VISIBLE
            topBar.visibility = View.VISIBLE
            centerControls.visibility = View.GONE
            bottomBar.visibility = View.GONE
            if (::feedback.isInitialized) feedback.visibility = View.GONE
            handler.removeCallbacks(lockAffordanceHider)
            handler.postDelayed(lockAffordanceHider, LOCK_AFFORDANCE_TIMEOUT_MS)
            controlsVisible = true
        } else {
            findViewByTag<View>("reiflix_back_button")?.visibility = View.VISIBLE
            findViewByTag<View>("reiflix_more_button")?.visibility = View.VISIBLE
            setControlsVisible(true)
        }
        updateLockUi()
        if (announce) showFeedback(if (locked) "Player bloqueado" else "Player desbloqueado")
    }

    private fun updateLockUi() {
        if (!::lockButton.isInitialized) return
        lockButton.text = if (locked) "🔒" else "🔓"
        lockButton.contentDescription = if (locked) "Desbloquear controles" else "Bloquear controles"
    }

    private fun gestureSettingLabel(label: String, enabled: Boolean): String =
        label + " " + if (enabled) "ON" else "OFF"

    private fun setWindowBrightness(value: Float) {
        val safe = value.coerceIn(0f, 1f)
        val attrs = window.attributes
        attrs.screenBrightness = safe
        window.attributes = attrs
        windowBrightness = safe
    }

    private fun adjustBrightness(delta: Float) {
        runCatching {
            var current = window.attributes.screenBrightness
            if (!current.isFinite() || current == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                current = 0.5f
            }
            setWindowBrightness((current + delta).coerceIn(0f, 1f))
            val percent = (windowBrightness * 100f).roundToInt().coerceIn(0, 100)
            showFeedback("Brilho $percent%", 900L)
        }.onFailure { error ->
            logPlayer("GESTURE_BRIGHTNESS_IGNORED", error)
        }
    }

    private fun adjustVolumeByFraction(deltaFraction: Float) {
        runCatching {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (maxVolume <= 0) return@runCatching
            val deltaSteps = max(
                1,
                (maxVolume * abs(deltaFraction)).roundToInt(),
            )
            val target = if (deltaFraction >= 0f) {
                (currentVolume + deltaSteps).coerceAtMost(maxVolume)
            } else {
                (currentVolume - deltaSteps).coerceAtLeast(0)
            }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            val percent = (target.toFloat() / maxVolume.toFloat() * 100f).roundToInt()
            showFeedback("Volume $percent%", 900L)
        }.onFailure { error ->
            logPlayer("GESTURE_VOLUME_IGNORED", error)
        }
    }

    private fun retryCurrentMedia() {
        if (!::player.isInitialized) {
            logPlayer("PLAYER_RETRY_UNAVAILABLE requestId=" + requestId.ifEmpty { "-" })
            return
        }
        if (!currentFailureRetryable) {
            logPlayer(
                "PLAYER_RETRY_REJECTED requestId=" + requestId.ifEmpty { "-" } +
                    " kind=" + currentFailureKind.name,
            )
            showFeedback("Esta falha não pode ser repetida automaticamente", 1600L)
            return
        }
        if (retryCount >= MAX_RETRY_ATTEMPTS) {
            showFeedback("Limite de tentativas atingido", 1400L)
            return
        }

        retryCount += 1
        errorVisible = false
        playbackErrorForGeneration = false
        moreVisible = false
        findViewByTag<View>("reiflix_error_panel")?.visibility = View.GONE
        findViewByTag<View>("reiflix_more_panel")?.visibility = View.GONE
        if (::preparingIndicator.isInitialized) preparingIndicator.visibility = View.VISIBLE
        logPlayer(
            "PLAYER_RETRY requestId=" + requestId.ifEmpty { "-" } +
                " attempt=" + retryCount +
                " kind=" + currentFailureKind.name +
                " retryable=" + currentFailureRetryable,
        )
        prepareCurrentMedia(
            "retry",
            playWhenReadyOverride = intent.getBooleanExtra("autoplay", true),
        )
    }

    private fun diagnosticErrorCode(reason: String): String = when (reason.trim()) {
        "missing_uri",
        "missing_uri_on_reuse" -> "MEDIA_URI_MISSING"
        "invalid_uri",
        "invalid_uri_on_reuse" -> "MEDIA_URI_INVALID"
        "player_initialization",
        "player_reuse" -> "PLAYER_SESSION_INVALID"
        "empty_file" -> "MEDIA_URI_INVALID"
        "media3_prepare" -> "MEDIA3_PREPARE_FAILED"
        "prepare_io" -> "ERROR_CODE_IO_UNSPECIFIED"
        "source_preflight" -> "SOURCE_UNAVAILABLE"
        else -> reason
            .trim()
            .uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9]+"), "_")
            .trim('_')
            .ifBlank { "UNKNOWN_OPEN_FAILURE" }
    }

    private fun showPlayerError(
        message: String,
        reason: String,
        payload: JSONObject = JSONObject(),
        category: PlayerMediaPolicy.ErrorCategory = PlayerMediaPolicy.ErrorCategory.UNKNOWN,
        failureKind: PlayerMediaPolicy.PlaybackFailureKind? = null,
        retryable: Boolean? = null,
    ) {
        val suppliedSession = payload.optString("playerSessionId").trim()
        val suppliedRequest = payload.optString("requestId").trim()
        val suppliedPlayerGeneration = payload.optLong("playerGeneration", 0L)
        val suppliedTransitionGeneration = payload.optLong("transitionGeneration", 0L)
        val errorContextCurrent =
            sessionState == SessionState.ACTIVE &&
                (suppliedSession.isBlank() || suppliedSession == playerSessionId) &&
                (suppliedRequest.isBlank() || suppliedRequest == requestId) &&
                (suppliedPlayerGeneration <= 0L || suppliedPlayerGeneration == playerGeneration) &&
                (suppliedTransitionGeneration <= 0L || suppliedTransitionGeneration == transitionGeneration)
        if (!errorContextCurrent) {
            publishNavigationTransitionDiagnostic(
                "PLAYER_ERROR_STALE_IGNORED",
                reason,
                JSONObject()
                    .put("requestId", suppliedRequest.ifBlank { requestId })
                    .put("playerSessionId", suppliedSession.ifBlank { playerSessionId })
                    .put("playerGeneration", suppliedPlayerGeneration)
                    .put("transitionGeneration", suppliedTransitionGeneration)
                    .put("currentPlayerGeneration", playerGeneration)
                    .put("currentTransitionGeneration", transitionGeneration),
            )
            logPlayer(
                "PLAYER_ERROR_STALE_IGNORED requestId=" + suppliedRequest.ifBlank { requestId } +
                    " reason=" + reason,
            )
            return
        }

        val failurePlayerGeneration = playerGeneration
        val failureTransitionGeneration = transitionGeneration
        val resolvedErrorCode = payload.optString("errorCode").ifBlank { diagnosticErrorCode(reason) }
        val derivedClassification = PlayerMediaPolicy.classifyPlaybackFailure(
            errorCodeName = resolvedErrorCode,
            causeNames = listOfNotNull(
                payload.optString("cause").takeIf(String::isNotBlank),
                payload.optString("rootCauseClass").takeIf(String::isNotBlank),
                payload.optString("loadErrorClass").takeIf(String::isNotBlank),
            ),
            messages = listOfNotNull(
                payload.optString("detail").takeIf(String::isNotBlank),
                payload.optString("rootCauseMessage").takeIf(String::isNotBlank),
                payload.optString("loadErrorMessage").takeIf(String::isNotBlank),
            ),
            rendererIndex = payload.optInt("rendererIndex", -1).takeIf { it >= 0 },
        )
        val resolvedFailureKind = failureKind ?: derivedClassification.kind
        val resolvedRetryable = retryable ?: derivedClassification.retryable
        currentErrorCategory = category.takeIf { it != PlayerMediaPolicy.ErrorCategory.UNKNOWN }
            ?: derivedClassification.legacyCategory
        currentFailureKind = resolvedFailureKind
        currentFailureRetryable = resolvedRetryable
        playbackErrorForGeneration = true

        if (nextTransitionActive || previousTransitionActive) {
            publishNavigationTransitionDiagnostic(
                if (nextTransitionActive) "NEXT_TRANSITION_FAILED" else "PREVIOUS_TRANSITION_FAILED",
                "player_error",
                JSONObject()
                    .put("error", reason)
                    .put("failureKind", resolvedFailureKind.name),
            )
        }
        invalidateTransition("player_error")
        setLocked(false, persist = true, announce = false)
        errorVisible = true
        if (::preparingIndicator.isInitialized) preparingIndicator.visibility = View.GONE
        if (::player.isInitialized) player.pause()
        setControlsVisible(true)
        findViewByTag<View>("reiflix_error_text")?.let { (it as TextView).text = userMessageForFailure(resolvedFailureKind, fallback = message) }
        findViewByTag<View>("reiflix_error_reason")?.let {
            (it as TextView).text = "Detalhe: " + userFailureDetailForFailure(resolvedFailureKind)
        }
        findViewByTag<View>("reiflix_error_retry")?.visibility =
            if (::player.isInitialized && resolvedRetryable) View.VISIBLE else View.GONE
        findViewByTag<View>("reiflix_error_panel")?.visibility = View.VISIBLE
        findViewByTag<View>("reiflix_error_panel")?.bringToFront()
        findViewByTag<View>("reiflix_error_back")?.requestFocus()
        syncComposePlayerUiState()
        if (::feedback.isInitialized) feedback.visibility = View.GONE

        val effectivePayload = diagnosticPayload()
        val supplied = JSONObject(payload.toString())
        val keys = supplied.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            effectivePayload.put(key, supplied.opt(key))
        }
        effectivePayload
            .put("uri", if (::uri.isInitialized) uri.toString() else intent.getStringExtra("uri").orEmpty())
            .put("source", if (::uri.isInitialized) sourceFor(uri) else "")
            .put("requestId", requestId)
            .put("playerSessionId", playerSessionId)
            .put("playerGeneration", failurePlayerGeneration)
            .put("transitionGeneration", failureTransitionGeneration)
            .put("episodeId", currentEpisodeId())
            .put("reason", reason)
            .put("failureStage", payload.optString("failureStage").ifBlank { reason })
            .put("failureKind", resolvedFailureKind.name)
            .put("retryable", resolvedRetryable)
            .put("errorCode", resolvedErrorCode)
            .put("category", currentErrorCategory.name)
            .put("diagnosticOnly", false)
        publishPlayerError(
            userMessageForFailure(resolvedFailureKind, fallback = message),
            effectivePayload,
        )
    }

    private fun userFailureDetailForFailure(
        kind: PlayerMediaPolicy.PlaybackFailureKind,
    ): String = when (kind) {
        PlayerMediaPolicy.PlaybackFailureKind.MEDIA_NOT_FOUND -> "arquivo ausente"
        PlayerMediaPolicy.PlaybackFailureKind.PERMISSION -> "acesso negado ou autorização revogada"
        PlayerMediaPolicy.PlaybackFailureKind.SOURCE -> "fonte local indisponível"
        PlayerMediaPolicy.PlaybackFailureKind.PARSER -> "container/formato não reconhecido"
        PlayerMediaPolicy.PlaybackFailureKind.DECODER -> "falha de inicialização do decoder"
        PlayerMediaPolicy.PlaybackFailureKind.CODEC -> "codec incompatível"
        PlayerMediaPolicy.PlaybackFailureKind.RENDERER -> "falha no renderizador"
        PlayerMediaPolicy.PlaybackFailureKind.TIMEOUT -> "tempo de preparação excedido"
        PlayerMediaPolicy.PlaybackFailureKind.LIFECYCLE -> "sessão/lifecycle invalidado"
        PlayerMediaPolicy.PlaybackFailureKind.STALE -> "requisição obsoleta"
        PlayerMediaPolicy.PlaybackFailureKind.UNKNOWN -> "falha de reprodução não classificada"
    }

    private fun userMessageForFailure(
        kind: PlayerMediaPolicy.PlaybackFailureKind,
        fallback: String? = null,
    ): String = when (kind) {
        PlayerMediaPolicy.PlaybackFailureKind.MEDIA_NOT_FOUND ->
            "O arquivo deste episódio não foi encontrado ou foi removido."
        PlayerMediaPolicy.PlaybackFailureKind.PERMISSION ->
            "O acesso ao arquivo deste episódio foi negado ou revogado."
        PlayerMediaPolicy.PlaybackFailureKind.SOURCE ->
            "O arquivo ou provedor local não está disponível para leitura."
        PlayerMediaPolicy.PlaybackFailureKind.PARSER ->
            "O Media3 não conseguiu interpretar o container ou formato deste arquivo."
        PlayerMediaPolicy.PlaybackFailureKind.DECODER ->
            "Este dispositivo não conseguiu inicializar um decoder compatível com este vídeo."
        PlayerMediaPolicy.PlaybackFailureKind.CODEC ->
            "O codec deste vídeo não é compatível com o decoder disponível."
        PlayerMediaPolicy.PlaybackFailureKind.RENDERER ->
            "O vídeo foi preparado, mas o renderizador não conseguiu exibir os frames."
        PlayerMediaPolicy.PlaybackFailureKind.TIMEOUT ->
            "A preparação do vídeo excedeu o tempo esperado."
        PlayerMediaPolicy.PlaybackFailureKind.LIFECYCLE,
        PlayerMediaPolicy.PlaybackFailureKind.STALE ->
            fallback ?: "A reprodução foi invalidada por uma mudança de sessão."
        PlayerMediaPolicy.PlaybackFailureKind.UNKNOWN ->
            fallback ?: "Não foi possível reproduzir este arquivo neste dispositivo."
    }

    private fun publishPlayerError(message: String, payload: JSONObject = JSONObject()) {
        if (errorPublishedForGeneration) return
        errorPublishedForGeneration = true
        val ok = NativeMailbox.writeBestEffort(
            this,
            JSONObject()
                .put("type", "player_error")
                .put("requestId", requestId)
                .put("message", message)
                .put("payload", payload),
        )
        if (!ok) logPlayer("FAILED_TO_PUBLISH player_error requestId=" + requestId.ifEmpty { "-" })
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun classifyPlayerExit(reason: String): String = when (reason.trim()) {
        "back_button" -> "USER_BACK"
        "user_button" -> "USER_BUTTON"
        "android_back" -> "ANDROID_BACK"
        "player_error_back" -> "ERROR_PANEL_BACK"
        "valid_player_transition" -> "VALID_PLAYER_TRANSITION"
        "stale_handoff" -> "STALE_HANDOFF"
        "invalid_handoff" -> "INVALID_HANDOFF"
        "player_error", "media3_error", "playback_error" -> "PLAYER_ERROR"
        "system_task", "task_removed" -> "SYSTEM_TASK"
        "process_death" -> "PROCESS_DEATH"
        "activity_lifecycle" -> "ACTIVITY_LIFECYCLE"
        "activity_finish" ->
            if (episodeChangePending) "VALID_PLAYER_TRANSITION" else "ACTIVITY_LIFECYCLE"
        else -> if (sessionState == SessionState.EXITING) "ACTIVITY_LIFECYCLE" else "UNKNOWN"
    }

    private fun reportPlayerExit(reason: String) {
        if (sessionState == SessionState.DESTROYED || exitReported) return
        val exitClassification = classifyPlayerExit(reason)
        logPlayer(
            "PLAYER_EXIT_CLASSIFICATION=" + exitClassification +
                " reason=" + reason +
                " requestId=" + requestId.ifEmpty { "-" } +
                " traceId=" + traceId +
                " activityInstanceId=" + activityInstanceId,
        )
        exitReported = true
        suppressExitEvent = true
        val rawDuration = if (::player.isInitialized) player.duration else 0L
        val currentDuration = if (rawDuration > 0L) rawDuration else 0L
        val rawPosition = if (::player.isInitialized) player.currentPosition.coerceAtLeast(0L) else 0L
        val currentPosition = if (currentDuration > 0L) rawPosition.coerceAtMost(currentDuration) else rawPosition
        val exitCapturedAt = nextPlayerEventCreatedAt()
        val payload = JSONObject()
            .put("uri", if (::uri.isInitialized) uri.toString() else intent.getStringExtra("uri").orEmpty())
            .put("mediaId", currentMediaId())
            .put("episodeId", currentEpisodeId())
            .put("animeId", intent.getStringExtra("animeId").orEmpty())
            .put("positionMs", currentPosition)
            .put("durationMs", currentDuration)
            .put("completion", completionReported)
            .put("reason", reason)
            .put("classification", exitClassification)
            .put("traceId", traceId)
            .put("timestamp", exitCapturedAt)
            .put("playerSessionId", playerSessionId)
            .put("activityInstanceId", activityInstanceId)
            .put("transitionGeneration", transitionGeneration)
        val exitEvent = JSONObject()
            .put("type", "player_exited")
            .put("requestId", requestId)
            .put("createdAt", exitCapturedAt)
            .put("payload", payload)

        MainActivity.notePlayerExit(
            requestId,
            exitCapturedAt,
            playerSessionId,
            activityInstanceId,
        )
        exitProgressPublished = true
        try {
            playbackWorker.submit {
                val ok = NativeMailbox.write(this@NativePlayerActivity, exitEvent)
                if (!ok) {
                    logPlayer("FAILED_TO_PUBLISH player_exited requestId=" + requestId.ifEmpty { "-" })
                }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            exitProgressPublished = false
            logPlayer("PLAYER_EXIT_PUBLISH_REJECTED requestId=" + requestId.ifEmpty { "-" }, error)
        }
        logPlayer(
            "player_exit_queued reason=" + reason +
                " requestId=" + requestId.ifEmpty { "-" } +
                " timestamp=" + exitCapturedAt,
        )
    }

    private fun finishPlayer(reason: String) {
        if (sessionState == SessionState.DESTROYED) return
        val exitClassification = classifyPlayerExit(reason)
        logPlayer(
            "PLAYER_FINISH_REQUEST reason=" + reason +
                " classification=" + exitClassification +
                " requestId=" + requestId.ifEmpty { "-" } +
                " activityInstanceId=" + activityInstanceId +
                " playerSessionId=" + playerSessionId,
        )
        sessionState = SessionState.EXITING
        publishNavigationTransitionDiagnostic(
            "PLAYER_SESSION_INVALIDATED",
            reason,
            JSONObject()
                .put("requestId", requestId)
                .put("playerSessionId", playerSessionId)
                .put("activityInstanceId", activityInstanceId)
                .put("playerGeneration", playerGeneration)
                .put("transitionGeneration", transitionGeneration),
        )
        invalidateTransition("finish_player")
        pendingPreparation?.cancel(true)
        cancelFirstFrameDiagnostics("finish_player")
        findViewByTag<GestureLayer>("reiflix_gesture_layer")?.cancelInteractions()
        handler.removeCallbacks(controlsHider)
        handler.removeCallbacks(lockAffordanceHider)
        handler.removeCallbacks(feedbackHider)
        restoreSystemUiBeforeExit()
        PerformanceDiagnostics.markPlayer(this, "player_exited", requestId,
            commandCreatedAtMs, reused = false)
        reportPlayerExit(reason)
        setResult(
            RESULT_OK,
            Intent()
                .putExtra("requestId", requestId)
                .putExtra("reason", reason)
                .putExtra("activityInstanceId", activityInstanceId),
        )
        finish()
    }

    private fun updateEpisodeNavigationButtons() {
        val enabled = !episodeChangePending && !errorVisible
        findViewByTag<TextView>("reiflix_next_episode")?.isEnabled =
            enabled && intent.getBooleanExtra("canNext", false)
        findViewByTag<TextView>("reiflix_previous_episode")?.isEnabled =
            enabled && intent.getBooleanExtra("canPrevious", false)
    }

    private fun publishNavigationTransitionDiagnostic(
        event: String,
        reason: String,
        extra: JSONObject = JSONObject(),
    ) {
        val payload = JSONObject()
            .put("event", event)
            .put("requestId", requestId)
            .put("reason", reason)
            .put("ageMs", transitionStartedAtMs.takeIf { it > 0L }?.let { System.currentTimeMillis() - it } ?: 0L)
            .put("originRequestId", originRequestId)
            .put("originCreatedAtMs", originCreatedAtMs)
            .put("originGeneration", transitionSourceGeneration)
            .put("currentGeneration", transitionGeneration)
            .put("playerSessionId", playerSessionId)
            .put("originMonotonicNs", originMonotonicNs)
            .put("transitionDirection", transitionSourceDirection)
        val keys = extra.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            payload.put(key, extra.opt(key))
        }
        NativeMailbox.writeBestEffort(
            this,
            JSONObject()
                .put("type", "diagnostic")
                .put("requestId", requestId)
                .put("payload", payload),
        )
        logPlayer(
            event + " requestId=" + requestId.ifEmpty { "-" } +
                " ageMs=" + payload.optLong("ageMs", 0L) +
                " originGeneration=" + payload.optLong("originGeneration", 0L) +
                " currentGeneration=" + payload.optLong("currentGeneration", 0L) +
                " reason=" + reason,
        )
    }

    private fun publishNextTransitionDiagnostic(
        event: String,
        reason: String,
        extra: JSONObject = JSONObject(),
    ) = publishNavigationTransitionDiagnostic(event, reason, extra)

    private fun publishPreviousTransitionDiagnostic(
        event: String,
        reason: String,
        extra: JSONObject = JSONObject(),
    ) = publishNavigationTransitionDiagnostic(event, reason, extra)

    private fun invalidateTransition(reason: String) {
        val wasNext = nextTransitionActive
        val wasPrevious = previousTransitionActive
        val staleAgeMs = transitionStartedAtMs.takeIf { it > 0L }?.let { System.currentTimeMillis() - it } ?: 0L
        transitionGeneration += 1L
        transitionPublishFuture?.cancel(true)
        transitionPublishFuture = null
        handler.removeCallbacks(episodeChangeTimeout)
        if (wasNext) {
            publishNextTransitionDiagnostic(
                "NEXT_TRANSITION_INVALIDATED",
                reason,
                JSONObject()
                    .put("ageMs", staleAgeMs)
                    .put("originGeneration", transitionSourceGeneration)
                    .put("currentGeneration", transitionGeneration),
            )
            publishNextTransitionDiagnostic(
                "NEXT_REQUEST_CANCELLED",
                reason,
                JSONObject().put("ageMs", staleAgeMs),
            )
        }
        if (wasPrevious) {
            publishPreviousTransitionDiagnostic(
                "PREVIOUS_TRANSITION_INVALIDATED",
                reason,
                JSONObject()
                    .put("ageMs", staleAgeMs)
                    .put("originRequestId", transitionSourceRequestId)
                    .put("originCreatedAtMs", transitionSourceCreatedAtMs)
                    .put("originGeneration", transitionSourceGeneration)
                    .put("currentGeneration", transitionGeneration)
                    .put("originMonotonicNs", transitionSourceMonotonicNs)
                    .put("playerSessionId", playerSessionId),
            )
            publishPreviousTransitionDiagnostic(
                "PREVIOUS_REQUEST_CANCELLED",
                reason,
                JSONObject().put("ageMs", staleAgeMs),
            )
        }
        if (wasNext || wasPrevious) {
            setTransitionPhase(TransitionPhase.FAILED, "invalidated:" + reason)
        } else {
            setTransitionPhase(TransitionPhase.IDLE, "no_active_transition")
        }
        episodeChangePending = false
        episodeChangeTimeoutRequestId = ""
        episodeChangeTimeoutUri = ""
        episodeChangeTimeoutGeneration = 0L
        episodeChangeTimeoutSessionId = ""
        episodeChangeTimeoutPlayerGeneration = 0L
        transitionReadyGeneration = -1L
        feedbackHideAt = 0L
        if (::feedback.isInitialized) feedback.visibility = View.GONE
        nextTransitionActive = false
        previousTransitionActive = false
        transitionSourceDirection = ""
        transitionSourceRequestId = ""
        transitionSourceUri = ""
        transitionSourceCreatedAtMs = 0L
        transitionSourceGeneration = 0L
        transitionSourceMonotonicNs = 0L
        transitionStartedAtMs = 0L
        logPlayer(
            "PLAYER_TRANSITION_INVALIDATED reason=" + reason +
                " generation=" + transitionGeneration +
                " requestId=" + requestId.ifEmpty { "-" },
        )
        if (::root.isInitialized) {
            updateEpisodeNavigationButtons()
        }
    }

    private fun isCurrentTransition(
        generation: Long,
        expectedRequestId: String = requestId,
        expectedUri: String = if (::uri.isInitialized) uri.toString() else "",
        expectedSessionId: String = playerSessionId,
    ): Boolean =
        generation == transitionGeneration &&
            sessionState == SessionState.ACTIVE &&
            requestId == expectedRequestId &&
            (!expectedUri.isNotBlank() && !::uri.isInitialized || uri.toString() == expectedUri) &&
            playerSessionId == expectedSessionId

    private fun requestEpisode(eventType: String) {
        val isNextRequest = eventType == "player_next_request"
        val isPreviousRequest = eventType == "player_previous_request"
        if (!isNextRequest && !isPreviousRequest) return
        fun requestEvent(nextEvent: String, previousEvent: String): String =
            if (isNextRequest) nextEvent else previousEvent

        if (!::player.isInitialized) {
            publishNavigationTransitionDiagnostic(requestEvent("NEXT_REQUEST_REJECTED", "PREVIOUS_REQUEST_REJECTED"), "player_unavailable")
            return
        }
        if (sessionState != SessionState.ACTIVE) {
            publishNavigationTransitionDiagnostic(requestEvent("NEXT_REQUEST_STALE", "PREVIOUS_REQUEST_STALE"), "session_not_active")
            return
        }
        if (episodeChangePending) {
            publishNavigationTransitionDiagnostic(
                "PLAYER_COMMAND_DUPLICATE",
                "transition_in_progress",
                JSONObject()
                    .put("requestId", requestId)
                    .put("playerSessionId", playerSessionId)
                    .put("transitionGeneration", transitionGeneration),
            )
            publishNavigationTransitionDiagnostic(
                requestEvent("NEXT_REQUEST_DUPLICATE", "PREVIOUS_REQUEST_DUPLICATE"),
                "transition_in_progress",
                JSONObject().put("transitionGeneration", transitionGeneration),
            )
            return
        }
        if (errorVisible) {
            publishNavigationTransitionDiagnostic(requestEvent("NEXT_REQUEST_REJECTED", "PREVIOUS_REQUEST_REJECTED"), "player_error_visible")
            return
        }
        val canNavigate = intent.getBooleanExtra(if (isNextRequest) "canNext" else "canPrevious", false)
        if (!canNavigate) {
            publishNavigationTransitionDiagnostic(
                requestEvent("NEXT_REQUEST_REJECTED", "PREVIOUS_REQUEST_REJECTED"),
                if (isNextRequest) "no_next_episode" else "no_previous_episode",
            )
            return
        }

        val startedAtMs = System.currentTimeMillis()
        val monotonicNs = SystemClock.elapsedRealtimeNanos()
        transitionGeneration += 1L
        val generation = transitionGeneration
        transitionStartedAtMs = startedAtMs
        transitionSourceRequestId = requestId
        transitionSourceUri = uri.toString()
        transitionSourceCreatedAtMs = startedAtMs
        transitionSourceGeneration = generation
        transitionSourceDirection = requestEvent("NEXT", "PREVIOUS")
        transitionSourceMonotonicNs = monotonicNs
        episodeChangePending = true
        setTransitionPhase(TransitionPhase.REQUESTED, "request_accepted")
        nextTransitionActive = isNextRequest
        previousTransitionActive = isPreviousRequest
        transitionReadyGeneration = -1L

        publishNavigationTransitionDiagnostic(
            requestEvent("NEXT_REQUEST_ACCEPTED", "PREVIOUS_REQUEST_ACCEPTED"),
            "single_flight",
            JSONObject()
                .put("transitionGeneration", generation)
                .put("playerGeneration", playerGeneration)
                .put("playerSessionId", playerSessionId)
                .put("originMonotonicNs", monotonicNs),
        )
        publishNavigationTransitionDiagnostic(
            requestEvent("NEXT_TRANSITION_STARTED", "PREVIOUS_TRANSITION_STARTED"),
            "native_mailbox_publish",
            JSONObject()
                .put("transitionGeneration", generation)
                .put("playerGeneration", playerGeneration)
                .put("episodeId", currentEpisodeId())
                .put("animeId", intent.getStringExtra("animeId").orEmpty())
                .put("playerSessionId", playerSessionId)
                .put("originMonotonicNs", monotonicNs),
        )

        handler.removeCallbacks(episodeChangeTimeout)
        armEpisodeChangeTimeout("request_episode")
        updateEpisodeNavigationButtons()
        showFeedback(if (isNextRequest) "Próximo…" else "Anterior…", 1400L)

        if (!completionReported) {
            saveProgress("player_progress", force = true)
        }

        playerCommandSequence += 1L
        val commandSequence = playerCommandSequence
        val payload = JSONObject()
            .put("uri", uri.toString())
            .put("requestId", requestId)
            .put("episodeId", currentEpisodeId())
            .put("animeId", intent.getStringExtra("animeId").orEmpty())
            .put("positionMs", player.currentPosition.coerceAtLeast(0L))
            .put("durationMs", player.duration.coerceAtLeast(0L))
            .put("createdAt", startedAtMs)
            .put("buttonPressedAtMs", startedAtMs)
            .put("monotonicNs", monotonicNs)
            .put("transitionDirection", transitionSourceDirection)
            .put("transitionGeneration", generation)
            .put("playerGeneration", playerGeneration)
            .put("playerSessionId", playerSessionId)

        if (commandSequence < lastPlayerCommandSequence) {
            NativeMailbox.writeBestEffort(
                this,
                JSONObject()
                    .put("type", "diagnostic")
                    .put("requestId", requestId)
                    .put(
                        "payload",
                        JSONObject()
                            .put("event", "PLAYER_COMMAND_OUT_OF_ORDER")
                            .put("requestId", requestId)
                            .put("sequence", commandSequence)
                            .put("previousSequence", lastPlayerCommandSequence)
                            .put("transitionGeneration", generation)
                            .put("transitionDirection", transitionSourceDirection)
                            .put("reason", requestEvent("NEXT_COMMAND_OUT_OF_ORDER", "PREVIOUS_COMMAND_OUT_OF_ORDER")),
                    ),
            )
        }
        lastPlayerCommandSequence = commandSequence

        NativeMailbox.writeBestEffort(
            this,
            JSONObject()
                .put("type", "diagnostic")
                .put("requestId", requestId)
                .put(
                    "payload",
                    JSONObject()
                        .put("event", if (isNextRequest) "NEXT_BUTTON_PRESSED" else "PREVIOUS_BUTTON_PRESSED")
                        .put("requestId", requestId)
                        .put("sequence", commandSequence)
                        .put("createdAt", startedAtMs)
                        .put("monotonicNs", monotonicNs)
                        .put("playerGeneration", playerGeneration)
                        .put("transitionGeneration", generation)
                        .put("episodeId", currentEpisodeId())
                        .put("animeId", intent.getStringExtra("animeId").orEmpty())
                        .put("playerSessionId", playerSessionId)
                        .put("transitionDirection", transitionSourceDirection),
                ),
        )

        val transitionCreatedAtMs = nextPlayerEventCreatedAt()
        val transitionEvent = JSONObject()
            .put("type", eventType)
            .put("requestId", requestId)
            .put("createdAt", transitionCreatedAtMs)
            .put("payload", payload)

        try {
            transitionPublishFuture = playbackWorker.submit {
                try {
                    if (!isCurrentTransition(generation)) return@submit
                    val published = NativeMailbox.write(this@NativePlayerActivity, transitionEvent)
                    handler.post {
                        if (!isCurrentTransition(generation) || !episodeChangePending) return@post
                        transitionPublishFuture = null
                        if (!published) {
                            publishNavigationTransitionDiagnostic(
                                requestEvent("NEXT_TRANSITION_FAILED", "PREVIOUS_TRANSITION_FAILED"),
                                "mailbox_publish_failed",
                                JSONObject().put("error", "native_mailbox_write_failed"),
                            )
                            showFeedback("Não foi possível mudar de episódio.", 1800L)
                            episodeChangePending = false
                            invalidateTransition("mailbox_publish_failed")
                            return@post
                        }
                        setTransitionPhase(TransitionPhase.HANDOFF_DISPATCHED, "native_mailbox_published")
                        handler.postDelayed(episodeChangeTimeout, 5_000L)
                        logPlayer(
                            eventType + " requestId=" + requestId.ifEmpty { "-" } +
                                " transitionGeneration=" + generation +
                                " transitionDirection=" + transitionSourceDirection +
                                " buttonLatencyMs=" + (System.currentTimeMillis() - startedAtMs) +
                                " keepActivity=true",
                        )
                    }
                } catch (_: java.util.concurrent.CancellationException) {
                    logPlayer(
                        eventType + " PUBLISH_CANCELLED requestId=" +
                            requestId.ifEmpty { "-" } +
                            " transitionGeneration=" + generation,
                    )
                } catch (error: Exception) {
                    handler.post {
                        if (!isCurrentTransition(generation)) return@post
                        publishNavigationTransitionDiagnostic(
                            requestEvent("NEXT_TRANSITION_FAILED", "PREVIOUS_TRANSITION_FAILED"),
                            "mailbox_publish_exception",
                            JSONObject().put("error", error.message ?: error::class.java.simpleName),
                        )
                        episodeChangePending = false
                        invalidateTransition("mailbox_publish_exception")
                    }
                }
            }
        } catch (error: Exception) {
            publishNavigationTransitionDiagnostic(
                requestEvent("NEXT_TRANSITION_FAILED", "PREVIOUS_TRANSITION_FAILED"),
                "executor_submit_failed",
                JSONObject().put("error", error.message ?: error::class.java.simpleName),
            )
            episodeChangePending = false
            invalidateTransition("executor_submit_failed")
        }
    }

    private fun seekToSavedPosition(savedPositionMs: Long): Long {
        if (!::player.isInitialized) return 0L
        val normalizedRequested = savedPositionMs.coerceAtLeast(0L)
        val safePosition = PlayerMediaPolicy.safeResumePosition(normalizedRequested, player.duration)
        if (safePosition > 0L && player.duration > 0L) {
            player.seekTo(safePosition)
            logPlayer(
                "RESUME_APPLIED positionMs=" + safePosition +
                    " requestedMs=" + normalizedRequested +
                    " durationMs=" + player.duration +
                    " requestId=" + requestId.ifEmpty { "-" },
            )
        } else if (normalizedRequested > 0L) {
            logPlayer(
                "RESUME_CLAMPED requestedMs=" + normalizedRequested +
                    " durationMs=" + player.duration +
                    " requestId=" + requestId.ifEmpty { "-" },
            )
        }
        return safePosition
    }

    /**
     * Player events are ordered by their persisted creation timestamp on the
     * Python/native bridge. Keep timestamps strictly monotonic within one Activity
     * so a completion/exit cannot be rejected merely because two lifecycle callbacks
     * landed in the same millisecond.
     */
    private fun nextPlayerEventCreatedAt(): Long {
        val wallClockMs = System.currentTimeMillis()
        return PLAYER_EVENT_CLOCK_MS.updateAndGet { previous ->
            max(wallClockMs, previous + 1L)
        }
    }

    private fun buildProgressEvent(eventType: String, force: Boolean): JSONObject? {
        if (!::player.isInitialized) return null
        val episodeId = currentEpisodeId()
        val mediaId = currentMediaId()
        val sessionId = playerSessionId.trim()
        val mediaUri = uri.toString().trim()
        if (episodeId.isBlank() || mediaId.isBlank() || sessionId.isBlank() || mediaUri.isBlank()) {
            logPlayer(
                "PROGRESS_EVENT_REJECTED_INCOMPLETE_IDENTITY requestId=" + requestId.ifEmpty { "-" } +
                    " episodeId=" + episodeId.ifEmpty { "-" } +
                    " mediaId=" + mediaId.ifEmpty { "-" } +
                    " playerSessionId=" + sessionId.ifEmpty { "-" },
            )
            return null
        }
        val rawDuration = player.duration
        val duration = if (rawDuration > 0L) rawDuration else 0L
        val rawPosition = player.currentPosition.coerceAtLeast(0L)
        val position = if (duration > 0L) rawPosition.coerceAtMost(duration) else rawPosition
        if (!force && lastSavedPosition >= 0L && abs(position - lastSavedPosition) < PROGRESS_INTERVAL_MS) return null
        if (force && position == lastSavedPosition &&
            eventType != "player_completed" && eventType != "player_exited"
        ) {
            return null
        }
        lastSavedPosition = position
        return JSONObject()
            .put("type", eventType)
            .put("requestId", requestId)
            .put("createdAt", nextPlayerEventCreatedAt())
            .put(
                "payload",
                JSONObject()
                    .put("uri", mediaUri)
                    .put("mediaId", mediaId)
                    .put("episodeId", episodeId)
                    .put("animeId", intent.getStringExtra("animeId").orEmpty())
                    .put("positionMs", position)
                    .put("durationMs", duration)
                    .put("playerState", if (::player.isInitialized) player.playbackStateLabel() else "STATE_IDLE")
                    .put("isPlaying", if (::player.isInitialized) player.isPlaying else false)
                    .put("playbackSpeed", if (::player.isInitialized) player.playbackParameters.speed else 1f)
                    .put("playerSessionId", playerSessionId)
                    .put("activityInstanceId", activityInstanceId)
            )
    }

    private fun saveProgress(eventType: String, force: Boolean = false) {
        val event = buildProgressEvent(eventType, force) ?: return
        val durable = force || eventType in setOf("player_paused", "player_completed", "player_exited")
        try {
            progressWorker.submit {
                val ok = if (durable) {
                    NativeMailbox.write(this@NativePlayerActivity, event)
                } else {
                    NativeMailbox.writeBestEffort(this@NativePlayerActivity, event)
                }
                if (!ok) {
                    logPlayer(
                        "FAILED_TO_PUBLISH " + eventType +
                            " requestId=" + requestId.ifEmpty { "-" },
                    )
                }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            logPlayer("PROGRESS_PUBLISH_REJECTED event=" + eventType, error)
        }
    }

    override fun onStart() {
        super.onStart()
        publishPlayerLifecycle("onStart")
        logPlayer("onStart requestId=" + requestId.ifEmpty { "-" })
        if (!inPictureInPicture) applyImmersiveAfterLayout()
    }

    override fun onResume() {
        super.onResume()
        publishPlayerLifecycle("onResume")
        logPlayer("onResume requestId=" + requestId.ifEmpty { "-" })
        if (!inPictureInPicture) applyImmersiveAfterLayout()
        findViewByTag<GestureLayer>("reiflix_gesture_layer")?.refreshZoomForLayout()
        if (::player.isInitialized && !errorVisible && !playbackErrorForGeneration) {
            if (!inPictureInPicture && playbackWasRequestedBeforeStop &&
                player.playbackState != Player.STATE_ENDED
            ) {
                player.playWhenReady = true
                playbackWasRequestedBeforeStop = false
                logPlayer(
                    "PLAYER_FOREGROUND_RESUME requestId=" + requestId.ifEmpty { "-" }
                )
            }
            updateProgressUi()
            updatePlayPauseButton()
            updatePictureInPictureParams()
            if (player.playbackState == Player.STATE_READY && !firstFrameRenderedForTesting) {
                armFirstFrameDiagnostics(playerGeneration)
            }
        }
    }

    override fun onPause() {
        publishPlayerLifecycle("onPause")
        cancelFirstFrameDiagnostics("pause")
        if (sessionState != SessionState.EXITING || !exitProgressPublished) {
            saveProgress("player_paused", force = true)
        }
        logPlayer("onPause requestId=" + requestId.ifEmpty { "-" })
        super.onPause()
    }

    override fun onStop() {
        publishPlayerLifecycle("onStop")
        findViewByTag<GestureLayer>("reiflix_gesture_layer")?.cancelInteractions()
        if (isFinishing && !isChangingConfigurations) {
            invalidateTransition("onStop_finishing")
        }
        if (::player.isInitialized && !inPictureInPicture && sessionState == SessionState.ACTIVE) {
            playbackWasRequestedBeforeStop =
                player.playWhenReady &&
                    player.playbackState != Player.STATE_ENDED &&
                    !errorVisible
            if (playbackWasRequestedBeforeStop) {
                player.pause()
                logPlayer(
                    "PLAYER_BACKGROUND_PAUSE requestId=" + requestId.ifEmpty { "-" }
                )
            }
        }
        if (sessionState != SessionState.EXITING || !exitProgressPublished) {
            saveProgress("player_progress", force = true)
        }
        logPlayer("onStop finishing=" + isFinishing + " changingConfig=" + isChangingConfigurations)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        logPlayer("onWindowFocusChanged hasFocus=" + hasFocus +
            " finishing=" + isFinishing + " resumed=" + !isFinishing)
        if (hasFocus && !inPictureInPicture) {
            window.decorView.post { applyImmersiveAfterLayout() }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        logPlayer("PLAYER_PIP inPip=" + isInPictureInPictureMode + " requestId=" + requestId.ifEmpty { "-" })
        inPictureInPicture = isInPictureInPictureMode
        updatePictureInPictureParams()
        if (isInPictureInPictureMode) {
            handler.removeCallbacks(controlsHider)
            findViewByTag<GestureLayer>("reiflix_gesture_layer")?.cancelInteractions()
            setControlsVisible(false)
        } else {
            // PiP exit is a lifecycle/configuration boundary. Reapply the
            // application-wide immersive policy after Android returns focus.
            applyImmersiveAfterLayout()
            if (::player.isInitialized && player.isPlaying && !errorVisible) {
                touchControls()
            }
            ViewCompat.requestApplyInsets(root)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateComposeOverlayBounds()
        logPlayer("onConfigurationChanged orientation=" + newConfig.orientation)
        findViewByTag<GestureLayer>("reiflix_gesture_layer")?.cancelInteractions()
        cancelFirstFrameDiagnostics("configuration_change")
        ViewCompat.requestApplyInsets(root)
        applyImmersiveAfterLayout()
        findViewByTag<GestureLayer>("reiflix_gesture_layer")?.refreshZoomForLayout()
        window.decorView.post {
            if (::player.isInitialized &&
                player.playbackState == Player.STATE_READY &&
                !firstFrameRenderedForTesting &&
                sessionState == SessionState.ACTIVE
            ) {
                armFirstFrameDiagnostics(playerGeneration)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("player_generation", playerGeneration)
        outState.putString("player_session_id", playerSessionId)
        outState.putLong("transition_generation", transitionGeneration)
        outState.putString("session_request_id", requestId)
        outState.putBoolean("episode_change_pending", episodeChangePending)
        outState.putString("transition_phase", transitionPhase.name)
        outState.putString("transition_source_request_id", transitionSourceRequestId)
        outState.putString("transition_source_uri", transitionSourceUri)
        outState.putLong("transition_source_created_at_ms", transitionSourceCreatedAtMs)
        outState.putLong("transition_source_generation", transitionSourceGeneration)
        outState.putString("transition_source_direction", transitionSourceDirection)
        outState.putLong("transition_source_monotonic_ns", transitionSourceMonotonicNs)
        outState.putLong("transition_started_at_ms", transitionStartedAtMs)
        outState.putLong("transition_ready_generation", transitionReadyGeneration)
        outState.putBoolean("next_transition_active", nextTransitionActive)
        outState.putBoolean("previous_transition_active", previousTransitionActive)
        if (::uri.isInitialized) outState.putString("session_uri", uri.toString())
        if (::player.isInitialized) {
            outState.putString("session_request_id", requestId)
            outState.putString("session_uri", if (::uri.isInitialized) uri.toString() else intent.getStringExtra("uri").orEmpty())
            outState.putLong("position_ms", player.currentPosition.coerceAtLeast(0L))
            outState.putLong("duration_ms", player.duration.coerceAtLeast(0L))
            outState.putFloat("playback_speed", player.playbackParameters.speed)
            outState.putBoolean("play_when_ready", player.playWhenReady)
            outState.putBundle("track_selection_parameters", player.trackSelectionParameters.toBundle())
        }
        if (::playerView.isInitialized) {
            outState.putInt("resize_mode", playerView.resizeMode)
        }
        outState.putBoolean("autoplay_next", autoplayNext)
        outState.putBoolean("lock_mode", locked)
        outState.putBoolean("controls_visible", controlsVisible)
        outState.putFloat("window_brightness", window.attributes.screenBrightness)
        outState.putString("aspect_mode_label", findViewByTag<TextView>("reiflix_aspect_button")?.text?.toString() ?: "Ajustar")
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        publishPlayerLifecycle("onDestroy")
        PerformanceDiagnostics.sampleMemory(this, "player_on_destroy")
        PerformanceDiagnostics.detach()
        val shouldReportExit = isFinishing && !suppressExitEvent && !exitReported && !isChangingConfigurations
        if (shouldReportExit) {
            reportPlayerExit("activity_finish")
        }
        MainActivity.notePlayerActivityDestroyed(activityInstanceId, playerSessionId)
        invalidateTransition("destroy")
        transitionGeneration += 1L
        sessionState = SessionState.DESTROYED
        findViewByTag<GestureLayer>("reiflix_gesture_layer")?.resetZoomToFit()
        findViewByTag<GestureLayer>("reiflix_gesture_layer")?.dispose()
        handler.removeCallbacks(progressReporter)
        handler.removeCallbacks(controlsHider)
        handler.removeCallbacks(lockAffordanceHider)
        handler.removeCallbacks(feedbackHider)
        handler.removeCallbacks(episodeChangeTimeout)
        cancelFirstFrameDiagnostics("destroy")
        restoreSystemUiBeforeExit()
        pendingPreparation?.cancel(true)
        playbackWorker.shutdown()
        progressWorker.shutdown()
        mediaMetadataWorker.shutdownNow()
        if (::player.isInitialized) {
            activePlayerListener?.let { player.removeListener(it) }
            activeAnalyticsListener?.let { player.removeAnalyticsListener(it) }
            activePlayerListener = null
            activeAnalyticsListener = null
            if (::playerView.isInitialized && playerView.player === player) {
                playerView.player = null
                logPlayer("PLAYER_VIEW_DETACHED requestId=" + requestId.ifEmpty { "-" })
            }
            player.release()
            logPlayer("player.release requestId=" + requestId.ifEmpty { "-" })
        } else if (shouldReportExit) {
            logPlayer("PLAYER_EXIT_REPORTED_WITHOUT_PLAYER requestId=" + requestId.ifEmpty { "-" })
        }
        logPlayer(
            "onDestroy finishing=" + isFinishing +
                " changingConfig=" + isChangingConfigurations +
                " transitionGeneration=" + transitionGeneration,
        )
        super.onDestroy()
    }

    /**
     * The native player owns the immersive system-bar policy while it is active.
     * The legacy preference remains in the intent contract for compatibility;
     * MainActivity keeps the normal visible-bar policy outside the player.
     */
    private fun shouldUseImmersive(): Boolean =
        resolveImmersivePolicy(
            immersiveSetting,
            resources.configuration.orientation,
        )

    private fun applyConfiguredRotation() {
        requestedOrientation = when (intent.getStringExtra("setting_player_rotation") ?: "auto") {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }

    private fun aspectLabelFromSetting(value: String?): String =
        if (value == "fill") "Preencher" else "Ajustar"

    private fun resizeModeFromSetting(value: String?): Int = when (value) {
        "fill" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    private fun enterImmersiveMode() {
        runCatching {
            systemUiController.applyImmersive()
            ViewCompat.requestApplyInsets(window.decorView)
            logPlayer("PLAYER_IMMERSIVE applied requestId=" + requestId.ifEmpty { "-" })
        }.onFailure { error ->
            logPlayer("PLAYER_IMMERSIVE_POLICY_FAILED", error)
        }
    }

    private fun restoreSystemUiBeforeExit() {
        runCatching {
            // The player is the only immersive surface. Restore one explicit
            // normal system-bar policy before returning to the Flet host.
            systemUiController.applyNormal(useContextAppearance = false)
            ViewCompat.requestApplyInsets(window.decorView)
            logPlayer("PLAYER_SYSTEM_UI_RESTORED requestId=" + requestId.ifEmpty { "-" })
        }.onFailure { error ->
            logPlayer("PLAYER_IMMERSIVE_EXIT_POLICY_FAILED", error)
        }
    }

    private fun applyImmersiveAfterLayout() {
        window.decorView.post {
            if (shouldUseImmersive()) enterImmersiveMode() else restoreSystemUiBeforeExit()
            if (::root.isInitialized) {
                val rootInsets = ViewCompat.getRootWindowInsets(window.decorView)
                if (rootInsets != null) {
                    applyRootInsets(rootInsets)
                    root.requestLayout()
                    logPlayer("ROOT_INSETS_APPLIED requestId=" + requestId.ifEmpty { "-" })
                } else {
                    logPlayer("ROOT_INSETS_UNAVAILABLE requestId=" + requestId.ifEmpty { "-" } +
                        " lifecycle=post_layout")
                }
            }
        }
    }

    private fun loadLocalMetadataAsync(generation: Long, localUri: Uri) {
        if (!::localMetadataStore.isInitialized) return
        try {
            playbackWorker.submit {
                val metadata = localMetadataStore.get(localUri.toString())
                handler.post {
                    if (generation != transitionGeneration ||
                        sessionState != SessionState.ACTIVE ||
                        !::uri.isInitialized ||
                        uri != localUri
                    ) {
                        return@post
                    }
                    localMetadata = metadata
                    updateMetadataControls(if (::player.isInitialized) player.currentPosition else 0L)
                }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            logPlayer("PLAYER_METADATA_LOAD_REJECTED uri=" + localUri, error)
        }
    }

    private fun updateMetadataControls(positionMs: Long) {
        if (!::root.isInitialized) return
        val position = positionMs.coerceAtLeast(0L)
        val opening = localMetadata.opening
        val ending = localMetadata.ending
        findViewByTag<TextView>("reiflix_skip_opening")?.visibility =
            if (opening != null && position >= opening.startMs && position < opening.endMs) View.VISIBLE else View.GONE
        findViewByTag<TextView>("reiflix_skip_ending")?.visibility =
            if (ending != null && position >= ending.startMs && position < ending.endMs) View.VISIBLE else View.GONE
    }

    private fun seekToMarker(targetMs: Long, feedbackText: String) {
        if (!::player.isInitialized || player.duration <= 0L) return
        val safeTarget = targetMs.coerceIn(0L, player.duration)
        player.seekTo(safeTarget)
        saveProgress("player_progress", force = true)
        showFeedback(feedbackText)
        touchControls()
    }

    private fun showLocalMetadataEditor() {
        if (!::localMetadataStore.isInitialized || !::uri.isInitialized) return
        findViewByTag<GestureLayer>("reiflix_gesture_layer")?.cancelInteractions()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        fun field(label: String, valueMs: Long?): android.widget.EditText =
            android.widget.EditText(this).apply {
                hint = label + " (MM:SS)"
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_DATETIME or android.text.InputType.TYPE_DATETIME_VARIATION_TIME
                setText(valueMs?.let { formatTime(it) }.orEmpty())
            }

        val openingStart = field("Abertura início", localMetadata.opening?.startMs)
        val openingEnd = field("Abertura fim", localMetadata.opening?.endMs)
        val endingStart = field("Encerramento início", localMetadata.ending?.startMs)
        val endingEnd = field("Encerramento fim", localMetadata.ending?.endMs)

        container.addView(android.widget.TextView(this).apply {
            text = "Marcadores locais (timestamps informados pelo usuário)"
            setTextColor(Color.WHITE)
            textSize = 13f
        })
        container.addView(openingStart)
        container.addView(openingEnd)
        container.addView(endingStart)
        container.addView(endingEnd)

        if (localMetadata.notes.isNotEmpty()) {
            container.addView(android.widget.TextView(this).apply {
                text = "Notas deste episódio"
                setTextColor(0xFFF5F5F5.toInt())
                setPadding(0, dp(10), 0, dp(4))
            })
            localMetadata.notes.sortedBy { it.timestampMs }.forEach { note ->
                val item = actionButton(formatTime(note.timestampMs) + " • " + note.text, 260) {
                    seekToMarker(note.timestampMs, formatTime(note.timestampMs))
                }
                item.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                item.setOnLongClickListener {
                    localMetadata = localMetadataStore.deleteNote(uri.toString(), note.id)
                    showFeedback("Nota removida")
                    showLocalMetadataEditor()
                    true
                }
                container.addView(item, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(44),
                ).apply { topMargin = dp(4) })
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Marcadores e notas locais")
            .setView(container)
            .setNeutralButton("Nota no tempo atual") { _, _ ->
                handler.post { showAddLocalNoteDialog() }
            }
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar marcadores") { _, _ ->
                localMetadata = localMetadataStore.setSegments(
                    uri.toString(),
                    parseTimestampMs(openingStart.text?.toString()),
                    parseTimestampMs(openingEnd.text?.toString()),
                    parseTimestampMs(endingStart.text?.toString()),
                    parseTimestampMs(endingEnd.text?.toString()),
                )
                updateMetadataControls(if (::player.isInitialized) player.currentPosition else 0L)
                showFeedback("Marcadores salvos")
            }
            .show()
    }

    private fun showAddLocalNoteDialog() {
        if (!::localMetadataStore.isInitialized || !::uri.isInitialized) return
        val input = android.widget.EditText(this).apply {
            hint = "Nota neste ponto do episódio"
            setSingleLine(false)
            maxLines = 5
            setTextColor(Color.WHITE)
            setTextSize(14f)
        }
        AlertDialog.Builder(this)
            .setTitle("Nota em " + formatTime(if (::player.isInitialized) player.currentPosition else 0L))
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                localMetadata = localMetadataStore.addNote(
                    uri.toString(),
                    if (::player.isInitialized) player.currentPosition.coerceAtLeast(0L) else 0L,
                    input.text?.toString().orEmpty(),
                )
                updateMetadataControls(if (::player.isInitialized) player.currentPosition else 0L)
                showFeedback("Nota salva")
            }
            .show()
    }

    private fun parseTimestampMs(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        val parts = value.split(":")
        return try {
            when (parts.size) {
                1 -> (parts[0].toDouble() * 1000.0).toLong().coerceAtLeast(0L)
                2 -> {
                    val minutes = parts[0].toLong().coerceAtLeast(0L)
                    val seconds = parts[1].toDouble().coerceIn(0.0, 59.999)
                    (minutes * 60_000L + (seconds * 1000.0).toLong()).coerceAtLeast(0L)
                }
                3 -> {
                    val hours = parts[0].toLong().coerceAtLeast(0L)
                    val minutes = parts[1].toLong().coerceIn(0L, 59L)
                    val seconds = parts[2].toDouble().coerceIn(0.0, 59.999)
                    (hours * 3_600_000L + minutes * 60_000L + (seconds * 1000.0).toLong()).coerceAtLeast(0L)
                }
                else -> null
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun formatTime(valueMs: Long): String =
        PlayerTimeFormatter.format(valueMs)

    private fun actionButton(label: String, widthDp: Int, action: (TextView) -> Unit): TextView {
        return TextView(this).apply {
            text = label
            contentDescription = label
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(2), dp(4), dp(2))
            isClickable = true
            isFocusable = true
            setBackgroundColor(0x66000000)
            minWidth = dp(widthDp)
            minHeight = dp(44)
            setOnClickListener { action(this) }
        }
    }

    private fun weightParams(widthDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)

    private fun sourceFor(localUri: Uri): String {
        return when {
            localUri.scheme.equals("content", true) && localUri.authority == MediaStore.AUTHORITY -> "mediastore"
            localUri.scheme.equals("content", true) -> "saf"
            localUri.scheme.equals("file", true) -> "broad_storage"
            else -> "unknown"
        }
    }

    private fun normalizeLocalReference(rawReference: String): Uri? {
        val reference = rawReference.trim()
        if (reference.isBlank()) return null
        val parsed = runCatching { Uri.parse(reference) }.getOrNull() ?: return null
        if (parsed.scheme.isNullOrBlank() && reference.startsWith(File.separator)) {
            return runCatching { Uri.fromFile(File(reference).canonicalFile) }.getOrNull()
        }
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "content" && scheme != "file") return null
        return if (parsed.scheme == scheme) parsed else parsed.buildUpon().scheme(scheme).build()
    }

    private data class SourceValidationFailure(
        val message: String,
        val code: String,
    )

    private fun validateLocalSource(localUri: Uri): SourceValidationFailure? {
        val result = when (localUri.scheme?.lowercase()) {
            "content" -> {
                val safAuthorized = runCatching {
                    SafScanner.isAuthorizedDocument(this, localUri)
                }.getOrDefault(false)
                val mediaStoreAuthorized = if (!safAuthorized) {
                    runCatching {
                        MediaStoreScanner.isAuthorizedDocument(this, localUri)
                    }.getOrDefault(false)
                } else false
                logPlayer(
                    "AUTH_CHECK scheme=content authority=" + localUri.authority.orEmpty() +
                        " saf=" + safAuthorized + " mediastore=" + mediaStoreAuthorized,
                )
                if (!safAuthorized && !mediaStoreAuthorized) {
                    if (localUri.authority == MediaStore.AUTHORITY &&
                        !MediaStoreScanner.hasReadPermission(this)
                    ) {
                        SourceValidationFailure(
                            "A permissão para ler vídeos foi revogada.",
                            "STORAGE_PERMISSION_MISSING",
                        )
                    } else if (localUri.authority == MediaStore.AUTHORITY) {
                        SourceValidationFailure(
                            "Este vídeo do armazenamento de mídia não está mais disponível.",
                            "MEDIASTORE_ITEM_UNAVAILABLE",
                        )
                    } else {
                        SourceValidationFailure(
                            "A autorização deste arquivo não está mais disponível.",
                            "SAF_PERMISSION_MISSING",
                        )
                    }
                } else {
                    val readable = runCatching {
                        contentResolver.openFileDescriptor(localUri, "r")?.use { true } == true
                    }.onFailure { error ->
                        logPlayer(
                            "CONTENT_READ_PREFLIGHT_FAILED authority=" + localUri.authority.orEmpty(),
                            error,
                        )
                    }.getOrDefault(false)
                    if (!readable) {
                        SourceValidationFailure(
                            "O provedor local não está disponível para leitura deste arquivo.",
                            "MEDIA_URI_INVALID",
                        )
                    } else null
                }
            }
            "file" -> {
                val file = runCatching {
                    File(localUri.path ?: "").canonicalFile
                }.getOrNull() ?: return SourceValidationFailure(
                    "Arquivo local inválido.",
                    "MEDIA_URI_INVALID",
                )
                val hasBroadAccess = runCatching {
                    BroadStorageScanner.hasAccess(this)
                }.getOrDefault(false)
                val authorized = runCatching {
                    BroadStorageScanner.isAuthorizedFile(this, localUri)
                }.getOrDefault(false)
                logPlayer(
                    "AUTH_CHECK scheme=file broad=" + hasBroadAccess +
                        " authorized=" + authorized + " exists=" + file.exists(),
                )
                when {
                    !file.exists() -> SourceValidationFailure(
                        "Arquivo local removido ou indisponível.",
                        "FILE_NOT_FOUND",
                    )
                    !file.isFile -> SourceValidationFailure(
                        "A referência local não aponta para um arquivo.",
                        "MEDIA_URI_INVALID",
                    )
                    !hasBroadAccess -> SourceValidationFailure(
                        "O acesso amplo ao armazenamento não está disponível para este arquivo.",
                        "STORAGE_PERMISSION_MISSING",
                    )
                    !authorized -> SourceValidationFailure(
                        "Este arquivo não pertence a uma pasta autorizada pelo ReiAnix.",
                        "STORAGE_PERMISSION_MISSING",
                    )
                    !file.canRead() -> SourceValidationFailure(
                        "O arquivo local não pode ser lido neste momento.",
                        "MEDIA_URI_INVALID",
                    )
                    else -> null
                }
            }
            else -> SourceValidationFailure(
                "A reprodução aceita somente referências locais content:// ou file://.",
                "MEDIA_URI_INVALID",
            )
        }
        logPlayer(
            "validateLocalSource result=" + (result?.code ?: "OK") +
                " uriScheme=" + localUri.scheme +
                " authority=" + localUri.authority.orEmpty(),
        )
        return result
    }

    private fun displayNameForUri(context: Context, localUri: Uri): String? =
        when (localUri.scheme?.lowercase(Locale.ROOT)) {
            "file" -> runCatching { File(localUri.path ?: "").name }.getOrNull()
            "content" -> runCatching {
                context.contentResolver.query(
                    localUri,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                    null, null, null,
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val index = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                }
            }.getOrNull()
            else -> null
        } ?: localUri.lastPathSegment?.substringAfterLast('/')

    private fun localSizeBytes(context: Context, localUri: Uri): Long? =
        when (localUri.scheme?.lowercase(Locale.ROOT)) {
            "file" -> runCatching { File(localUri.path ?: "").length() }.getOrNull()
            "content" -> {
                val descriptorSize = runCatching {
                    context.contentResolver.openFileDescriptor(localUri, "r")?.use { descriptor ->
                        descriptor.statSize.takeIf { it >= 0L }
                    }
                }.getOrNull()
                descriptorSize ?: runCatching {
                    context.contentResolver.query(
                        localUri,
                        arrayOf(MediaStore.MediaColumns.SIZE),
                        null, null, null,
                    )?.use { cursor ->
                        if (!cursor.moveToFirst()) return@use null
                        val index = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                        if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
                    }
                }.getOrNull()
            }
            else -> null
        }
    private fun <T : View> findViewByTag(tagValue: String): T? =
        root.findViewWithTag(tagValue)

    private fun logPlayer(message: String, error: Throwable? = null) {
        if (error != null) {
            android.util.Log.e(TAG, message, error)
        } else {
            android.util.Log.i(TAG, message)
        }
    }

    /**
     * Single owner for player touch arbitration. The order is:
     * pinch/multi-touch -> pan while zoomed -> directional swipe -> tap/double-tap.
     * Controls remain outside this layer and therefore consume their own touches.
     *
     * Double-tap seeking intentionally stays separate from pinch. A second tap is
     * consumed by GestureDetector, while a drag crossing touchSlop cancels the
     * detector before it can be interpreted as a tap.
     */
    private enum class GestureMode {
        IDLE,
        DOUBLE_TAP,
        PINCH,
        PAN,
        VERTICAL,
    }

    private inner class GestureLayer(context: Context) : View(context) {
        private val touchConfig = ViewConfiguration.get(context)
        private val touchSlop = touchConfig.scaledTouchSlop.toFloat()

        private val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    if (!zoomEnabled ||
                        !gestureInteractionAllowed() ||
                        !::playerView.isInitialized ||
                        !::player.isInitialized
                    ) {
                        return false
                    }
                    pinchActive = true
                    gestureMode = GestureMode.PINCH
                    gestureConsumed = true
                    restoreLongPressSpeed()
                    cancelGestureDetector()
                    lastPanX = detector.focusX
                    lastPanY = detector.focusY
                    touchControls()
                    logPlayer("GESTURE_START type=pinch requestId=" + requestId.ifEmpty { "-" })
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (!pinchActive || !gestureInteractionAllowed()) return true
                    val rawFactor = detector.scaleFactor
                    if (!rawFactor.isFinite() || rawFactor <= 0f) return true

                    // ScaleGestureDetector already reports a relative scale. Do not
                    // amplify it again. A per-callback cap keeps large span changes
                    // from jumping directly to the ceiling.
                    val effectiveRawFactor = rawFactor.coerceIn(MIN_SCALE_FACTOR, MAX_SCALE_FACTOR)
                    val previousScale = zoomScale
                    val nextScale = PlayerGesturePolicy.clampZoom(
                        previousScale * effectiveRawFactor,
                        MIN_ZOOM,
                        MAX_ZOOM,
                    )
                    val effectiveFactor = if (previousScale > 0f) {
                        nextScale / previousScale
                    } else {
                        1f
                    }

                    val pivotX = detector.focusX - width * 0.5f
                    val pivotY = detector.focusY - height * 0.5f
                    var nextTranslationX =
                        zoomTranslationX + (1f - effectiveFactor) * (pivotX - zoomTranslationX)
                    var nextTranslationY =
                        zoomTranslationY + (1f - effectiveFactor) * (pivotY - zoomTranslationY)

                    zoomScale = nextScale
                    val bounds = calculatePanBounds()
                    val clamped = PlayerGesturePolicy.clampTranslation(
                        nextTranslationX,
                        nextTranslationY,
                        bounds,
                    )
                    nextTranslationX = clamped.first
                    nextTranslationY = clamped.second
                    zoomTranslationX = nextTranslationX
                    zoomTranslationY = nextTranslationY

                    // Manual zoom always uses FIT as its base. FILL remains an
                    // independent aspect preference and is restored when zoom resets.
                    if (playerView.resizeMode != AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    applyZoomTransform()
                    updateAspectButtonFromZoom()

                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastZoomFeedbackAtMs >= ZOOM_FEEDBACK_INTERVAL_MS) {
                        lastZoomFeedbackAtMs = now
                        val label = if (zoomScale <= ZOOM_SNAP_THRESHOLD) {
                            "FIT"
                        } else {
                            "ZOOM " + String.format(java.util.Locale.US, "%.2fx", zoomScale)
                        }
                        showFeedback(label, 300L)
                    }
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    finishPinchGesture(cancelled = false)
                }
            },
        )

        private val gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                    if (systemGestureEdge || gestureConsumed) return true
                    if (locked) {
                        touchControls()
                        return true
                    }
                    if (!gestureInteractionAllowed()) return true
                    handleTap()
                    return true
                }

                override fun onDoubleTap(event: MotionEvent): Boolean {
                    if (!gestureInteractionAllowed() || !doubleTapEnabled || systemGestureEdge) {
                        return true
                    }

                    gestureConsumed = true
                    gestureMode = GestureMode.DOUBLE_TAP
                    when (PlayerGesturePolicy.side(event.x, width)) {
                        PlayerGesturePolicy.Side.LEFT -> {
                            logPlayer("PLAYER_DOUBLE_TAP side=left requestId=" + requestId.ifEmpty { "-" })
                            seekBy(-doubleTapSeekMs, "−" + (doubleTapSeekMs / 1000L) + "s")
                        }

                        PlayerGesturePolicy.Side.RIGHT -> {
                            logPlayer("PLAYER_DOUBLE_TAP side=right requestId=" + requestId.ifEmpty { "-" })
                            seekBy(doubleTapSeekMs, "+" + (doubleTapSeekMs / 1000L) + "s")
                        }

                        PlayerGesturePolicy.Side.CENTER -> {
                            logPlayer("PLAYER_DOUBLE_TAP side=center_ignored requestId=" + requestId.ifEmpty { "-" })
                        }
                    }
                    return true
                }

                override fun onLongPress(event: MotionEvent) {
                    if (!gestureInteractionAllowed() || !longPressEnabled || gestureConsumed || systemGestureEdge) {
                        return
                    }
                    previousSpeedForLongPress = player.playbackParameters.speed
                    longPressActive = true
                    player.setPlaybackSpeed(longPressSpeed)
                    val speedLabel = String.format(java.util.Locale.US, "%.2fx", longPressSpeed)
                    showFeedback(speedLabel, 8_000L)
                    logPlayer("PLAYER_LONG_PRESS speed=" + speedLabel + " requestId=" + requestId.ifEmpty { "-" })
                }
            },
        )

        private var downX = 0f
        private var downY = 0f
        private var gestureConsumed = false
        private var systemGestureEdge = false
        private var pinchActive = false
        private var lastVerticalY: Float? = null
        private var lastPanX: Float? = null
        private var lastPanY: Float? = null
        private var zoomScale = 1f
        private var zoomTranslationX = 0f
        private var zoomTranslationY = 0f
        private var zoomAnimator: ValueAnimator? = null
        private var lastZoomFeedbackAtMs = 0L
        private val zoomMatrix = Matrix()
        private var longPressActive = false
        private var previousSpeedForLongPress = 1f
        private var gestureMode = GestureMode.IDLE

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (inPictureInPicture) return true
            if (zoomEnabled) {
                scaleDetector.onTouchEvent(event)
            } else if (event.pointerCount > 1) {
                // Multi-touch is intentionally consumed while zoom is disabled so
                // it cannot fall through to the vertical/seek gesture classifier.
                gestureConsumed = true
                gestureMode = GestureMode.IDLE
                restoreLongPressSpeed()
                cancelGestureDetector(event)
                return true
            }

            if (zoomEnabled && (event.pointerCount > 1 || pinchActive || gestureMode == GestureMode.PINCH)) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        gestureConsumed = true
                        gestureMode = GestureMode.PINCH
                        restoreLongPressSpeed()
                        cancelGestureDetector(event)
                        lastPanX = pointerCenterX(event)
                        lastPanY = pointerCenterY(event)
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (pinchActive && event.pointerCount >= 2) {
                            val centerX = pointerCenterX(event)
                            val centerY = pointerCenterY(event)
                            val previousX = lastPanX
                            val previousY = lastPanY
                            if (previousX != null && previousY != null && zoomScale > 1.01f) {
                                applyPanDelta(centerX - previousX, centerY - previousY)
                            }
                            lastPanX = centerX
                            lastPanY = centerY
                        }
                    }

                    MotionEvent.ACTION_POINTER_UP -> {
                        lastPanX = null
                        lastPanY = null
                    }

                    MotionEvent.ACTION_UP -> {
                        if (pinchActive) finishPinchGesture(cancelled = false)
                        resetTransientState()
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        if (pinchActive) finishPinchGesture(cancelled = true)
                        cancelGestureDetector(event)
                        resetTransientState()
                    }
                }
                return true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    gestureConsumed = false
                    systemGestureEdge = isSystemGestureEdge(event.x, event.y)
                    lastVerticalY = null
                    gestureMode = GestureMode.IDLE
                    if (systemGestureEdge) {
                        gestureConsumed = true
                        cancelGestureDetector(event)
                    } else if (locked) {
                        gestureDetector.onTouchEvent(event)
                    } else if (!gestureInteractionAllowed()) {
                        gestureConsumed = true
                        cancelGestureDetector(event)
                    } else {
                        gestureDetector.onTouchEvent(event)
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (gestureConsumed || systemGestureEdge) return true
                    if (locked) return true

                    val dx = event.x - downX
                    val dy = event.y - downY

                    if (zoomScale > 1.01f && PlayerGesturePolicy.isMeaningfulMovement(
                            dx,
                            dy,
                            touchSlop,
                        )) {
                        if (gestureMode != GestureMode.PAN) {
                            gestureMode = GestureMode.PAN
                            gestureConsumed = true
                            cancelGestureDetector(event)
                            restoreLongPressSpeed()
                            lastPanX = event.x
                            lastPanY = event.y
                            touchControls()
                            logPlayer("GESTURE_START type=pan requestId=" + requestId.ifEmpty { "-" })
                        }
                        val previousX = lastPanX
                        val previousY = lastPanY
                        if (previousX != null && previousY != null) {
                            applyPanDelta(event.x - previousX, event.y - previousY)
                        }
                        lastPanX = event.x
                        lastPanY = event.y
                        return true
                    }

                    when (PlayerGesturePolicy.direction(dx, dy, touchSlop)) {
                        PlayerGesturePolicy.Direction.VERTICAL -> {
                            if (gestureMode == GestureMode.IDLE) {
                                gestureConsumed = true
                                gestureMode = GestureMode.VERTICAL
                                lastVerticalY = event.y
                                handleVerticalGestureDelta(downX, event.y - downY)
                                cancelGestureDetector(event)
                                restoreLongPressSpeed()
                                logPlayer(
                                    "GESTURE_START type=vertical side=" +
                                        PlayerGesturePolicy.side(downX, width).name.lowercase() +
                                        " requestId=" + requestId.ifEmpty { "-" },
                                )
                            } else if (gestureMode == GestureMode.VERTICAL) {
                                val previousY = lastVerticalY
                                if (previousY != null) {
                                    handleVerticalGestureDelta(downX, event.y - previousY)
                                }
                                lastVerticalY = event.y
                            }
                        }

                        PlayerGesturePolicy.Direction.HORIZONTAL -> {
                            gestureConsumed = true
                            gestureMode = GestureMode.IDLE
                            cancelGestureDetector(event)
                            restoreLongPressSpeed()
                            logPlayer(
                                "GESTURE_HORIZONTAL_IGNORED requestId=" +
                                    requestId.ifEmpty { "-" },
                            )
                        }

                        PlayerGesturePolicy.Direction.NONE -> Unit
                    }
                }

                MotionEvent.ACTION_UP -> {
                    when (gestureMode) {
                        GestureMode.VERTICAL -> {
                            logPlayer(
                                "GESTURE_END type=vertical_ignored_or_applied requestId=" +
                                    requestId.ifEmpty { "-" },
                            )
                            touchControls()
                            resetTransientState()
                            return true
                        }

                        GestureMode.PAN -> {
                            applyZoomTransform()
                            logPlayer("GESTURE_END type=pan requestId=" + requestId.ifEmpty { "-" })
                            touchControls()
                            resetTransientState()
                            return true
                        }

                        GestureMode.DOUBLE_TAP -> {
                            resetTransientState()
                            return true
                        }

                        else -> Unit
                    }

                    if (!gestureConsumed && !systemGestureEdge && (locked || gestureInteractionAllowed())) {
                        gestureDetector.onTouchEvent(event)
                    }
                    resetTransientState()
                }

                MotionEvent.ACTION_CANCEL -> {
                    restoreLongPressSpeed()
                    if (pinchActive) finishPinchGesture(cancelled = true)
                    cancelGestureDetector(event)
                    resetTransientState()
                    logPlayer(
                        "GESTURE_END type=cancel requestId=" +
                            requestId.ifEmpty { "-" },
                    )
                }
            }
            return true
        }

        private fun gestureInteractionAllowed(): Boolean =
            !locked && !inPictureInPicture && !errorVisible && ::player.isInitialized

        private fun isSystemGestureEdge(x: Float, y: Float): Boolean =
            x < gestureSafeLeft ||
                x > width - gestureSafeRight ||
                y < gestureSafeTop ||
                y > height - gestureSafeBottom

        private fun handleVerticalGestureDelta(startX: Float, deltaY: Float) {
            if (height <= 0) return
            val fraction = PlayerGesturePolicy.verticalDeltaFraction(deltaY, height)
            if (fraction == 0f) return

            when (PlayerGesturePolicy.side(startX, width)) {
                PlayerGesturePolicy.Side.LEFT -> {
                    if (brightnessGesturesEnabled) {
                        adjustBrightness(fraction)
                    }
                }

                PlayerGesturePolicy.Side.RIGHT -> {
                    if (volumeGesturesEnabled) {
                        adjustVolumeByFraction(fraction)
                    }
                }

                PlayerGesturePolicy.Side.CENTER -> Unit
            }
        }

        private fun cancelGestureDetector(event: MotionEvent? = null) {
            val cancel = if (event != null) {
                MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            } else {
                val now = android.os.SystemClock.uptimeMillis()
                MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            }
            gestureDetector.onTouchEvent(cancel)
            cancel.recycle()
        }

        private fun restoreLongPressSpeed() {
            if (!longPressActive || !::player.isInitialized) return
            val restore = previousSpeedForLongPress.takeIf { it > 0f && it.isFinite() } ?: 1f
            player.setPlaybackSpeed(restore)
            longPressActive = false
            showFeedback(
                String.format(java.util.Locale.US, "%.2fx", restore),
                500L,
            )
            logPlayer("PLAYER_LONG_PRESS_END speed=" + restore + " requestId=" + requestId.ifEmpty { "-" })
        }

        fun refreshZoomForLayout() {
            if (zoomScale > 1.01f) {
                applyZoomTransform()
            } else {
                zoomScale = 1f
                zoomTranslationX = 0f
                zoomTranslationY = 0f
                val video = playerView.videoSurfaceView
                if (video is TextureView) {
                    video.setTransform(Matrix())
                } else {
                    video?.apply {
                        scaleX = 1f
                        scaleY = 1f
                        translationX = 0f
                        translationY = 0f
                    }
                }
            }
        }

        fun cancelInteractions() {
            if (pinchActive) finishPinchGesture(cancelled = true)
            gestureConsumed = true
            gestureMode = GestureMode.IDLE
            lastVerticalY = null
            systemGestureEdge = false
            restoreLongPressSpeed()
            cancelGestureDetector()
            lastPanX = null
            lastPanY = null
            pinchActive = false
        }

        fun dispose() {
            zoomAnimator?.cancel()
            zoomAnimator = null
            cancelInteractions()
        }

        fun resetZoomToFit() {
            zoomAnimator?.cancel()
            zoomAnimator = null
            zoomScale = 1f
            zoomTranslationX = 0f
            zoomTranslationY = 0f
            if (::playerView.isInitialized) {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                val video = playerView.videoSurfaceView
                if (video is TextureView) {
                    video.setTransform(Matrix())
                } else {
                    video?.apply {
                        scaleX = 1f
                        scaleY = 1f
                        translationX = 0f
                        translationY = 0f
                    }
                }
                updateAspectButtonFromZoom()
            }
        }

        private fun finishPinchGesture(cancelled: Boolean) {
            if (!pinchActive) return
            pinchActive = false
            gestureConsumed = true
            gestureMode = GestureMode.PINCH
            lastPanX = null
            lastPanY = null

            if (!cancelled) {
                logPlayer("GESTURE_END type=pinch requestId=" + requestId.ifEmpty { "-" })
                if (zoomScale <= ZOOM_SNAP_THRESHOLD) {
                    animateZoomToFit()
                } else {
                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    updateAspectButtonFromZoom()
                    applyZoomTransform()
                    showFeedback(
                        "ZOOM " + String.format(java.util.Locale.US, "%.2fx", zoomScale),
                        900L,
                    )
                }
                touchControls()
            } else {
                logPlayer("GESTURE_CANCEL type=pinch requestId=" + requestId.ifEmpty { "-" })
            }
        }

        private fun animateZoomToFit() {
            zoomAnimator?.cancel()
            if (!ReiAnixMotionPolicy.systemAnimationsEnabled()) {
                zoomScale = 1f
                zoomTranslationX = 0f
                zoomTranslationY = 0f
                restoreConfiguredAspectMode()
                applyZoomTransform()
                showFeedback("FIT", 900L)
                zoomAnimator = null
                return
            }

            val startScale = zoomScale
            val startX = zoomTranslationX
            val startY = zoomTranslationY
            zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 160L
                addUpdateListener { animator ->
                    val progress = animator.animatedValue as Float
                    zoomScale = startScale + (1f - startScale) * progress
                    zoomTranslationX = startX * (1f - progress)
                    zoomTranslationY = startY * (1f - progress)
                    applyZoomTransform()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        zoomScale = 1f
                        zoomTranslationX = 0f
                        zoomTranslationY = 0f
                        restoreConfiguredAspectMode()
                        applyZoomTransform()
                        showFeedback("FIT", 900L)
                        zoomAnimator = null
                    }
                })
                start()
            }
        }

        private fun handleTap() {
            logPlayer("PLAYER_SINGLE_TAP requestId=" + requestId.ifEmpty { "-" })
            if (controlsVisible) {
                setControlsVisible(false)
            } else {
                touchControls()
            }
        }

        private fun pointerCenterX(event: MotionEvent): Float =
            if (event.pointerCount >= 2) (event.getX(0) + event.getX(1)) * 0.5f else event.x

        private fun pointerCenterY(event: MotionEvent): Float =
            if (event.pointerCount >= 2) (event.getY(0) + event.getY(1)) * 0.5f else event.y

        private fun applyPanDelta(deltaX: Float, deltaY: Float) {
            zoomTranslationX += deltaX
            zoomTranslationY += deltaY
            val bounds = calculatePanBounds()
            val clamped = PlayerGesturePolicy.clampTranslation(
                zoomTranslationX,
                zoomTranslationY,
                bounds,
            )
            zoomTranslationX = clamped.first
            zoomTranslationY = clamped.second
            applyZoomTransform()
        }

        private fun calculatePanBounds(): PlayerGesturePolicy.PanBounds {
            val (baseWidth, baseHeight) = displayedVideoSize()
            return PlayerGesturePolicy.panBounds(
                displayedWidth = baseWidth * zoomScale,
                displayedHeight = baseHeight * zoomScale,
                viewportWidth = width.toFloat(),
                viewportHeight = height.toFloat(),
            )
        }

        private fun displayedVideoSize(): Pair<Float, Float> {
            val viewportWidth = width.toFloat()
            val viewportHeight = height.toFloat()
            if (viewportWidth <= 1f || viewportHeight <= 1f) {
                return Pair(viewportWidth.coerceAtLeast(1f), viewportHeight.coerceAtLeast(1f))
            }

            val video = player.videoSize
            if (video.width <= 0 || video.height <= 0) {
                val surface = playerView.videoSurfaceView
                return Pair(
                    surface?.width?.toFloat()?.coerceAtLeast(1f) ?: viewportWidth,
                    surface?.height?.toFloat()?.coerceAtLeast(1f) ?: viewportHeight,
                )
            }

            val pixelRatio = video.pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
            val contentWidth = video.width.toFloat() * pixelRatio
            val contentHeight = video.height.toFloat()
            val contentAspect = contentWidth / contentHeight
            if (!contentAspect.isFinite() || contentAspect <= 0f) {
                return Pair(viewportWidth, viewportHeight)
            }

            val fitScale = min(
                viewportWidth / contentWidth,
                viewportHeight / contentHeight,
            )
            // Manual zoom bounds are always measured from the FIT frame. This is
            // the single base scale for GestureLayer transforms.
            return Pair(
                contentWidth * fitScale,
                contentHeight * fitScale,
            )
        }

        private fun updateAspectButtonFromZoom() {
            val button = findViewByTag<TextView>("reiflix_aspect_button") ?: return
            if (zoomScale <= ZOOM_SNAP_THRESHOLD) {
                val label = aspectLabelFromSetting(intent.getStringExtra("setting_player_aspect_ratio"))
                button.text = label
                button.isSelected = label == "Preencher"
            } else {
                button.text = "Zoom " + String.format(java.util.Locale.US, "%.2fx", zoomScale)
                button.isSelected = true
            }
        }

        private fun restoreConfiguredAspectMode() {
            val value = intent.getStringExtra("setting_player_aspect_ratio")
            val label = aspectLabelFromSetting(value)
            playerView.resizeMode = resizeModeFromSetting(value)
            findViewByTag<TextView>("reiflix_aspect_button")?.apply {
                text = label
                isSelected = label == "Preencher"
            }
        }

        private fun resetTransientState() {
            gestureMode = GestureMode.IDLE
            lastPanX = null
            lastPanY = null
            lastVerticalY = null
            systemGestureEdge = false
        }

        private fun applyZoomTransform() {
            val video = playerView.videoSurfaceView ?: return
            val bounds = calculatePanBounds()
            val clamped = PlayerGesturePolicy.clampTranslation(
                zoomTranslationX,
                zoomTranslationY,
                bounds,
            )
            zoomTranslationX = clamped.first
            zoomTranslationY = clamped.second

            if (video is TextureView) {
                zoomMatrix.reset()
                if (video.width > 1 && video.height > 1 && width > 1 && height > 1) {
                    zoomMatrix.setScale(
                        zoomScale,
                        zoomScale,
                        video.width * 0.5f,
                        video.height * 0.5f,
                    )
                    zoomMatrix.postTranslate(zoomTranslationX, zoomTranslationY)
                }
                video.isOpaque = false
                video.setTransform(zoomMatrix)
            } else {
                if (video.width <= 1 || video.height <= 1 || width <= 1 || height <= 1) {
                    video.post { applyZoomTransform() }
                    return
                }
                video.pivotX = video.width * 0.5f
                video.pivotY = video.height * 0.5f
                video.scaleX = zoomScale
                video.scaleY = zoomScale
                video.translationX = zoomTranslationX
                video.translationY = zoomTranslationY
            }
            video.invalidate()
        }
    }

    internal object PlayerGesturePolicy {
        enum class Direction { NONE, HORIZONTAL, VERTICAL }
        enum class Side { LEFT, CENTER, RIGHT }

        data class PanBounds(
            val maxX: Float,
            val maxY: Float,
        )

        fun direction(dx: Float, dy: Float, touchSlop: Float, dominance: Float = 1.15f): Direction {
            val ax = abs(dx)
            val ay = abs(dy)
            if (max(ax, ay) < touchSlop) return Direction.NONE
            return when {
                ax > ay * dominance -> Direction.HORIZONTAL
                ay > ax * dominance -> Direction.VERTICAL
                else -> Direction.NONE
            }
        }

        fun side(x: Float, width: Int): Side {
            if (width <= 0) return Side.CENTER
            val fraction = (x / width.toFloat()).coerceIn(0f, 1f)
            return when {
                fraction < 0.40f -> Side.LEFT
                fraction > 0.60f -> Side.RIGHT
                else -> Side.CENTER
            }
        }

        fun isMeaningfulMovement(dx: Float, dy: Float, touchSlop: Float): Boolean =
            max(abs(dx), abs(dy)) >= touchSlop

        fun clampZoom(scale: Float, minZoom: Float, maxZoom: Float): Float =
            scale.coerceIn(minZoom, maxZoom)

        fun panBounds(
            displayedWidth: Float,
            displayedHeight: Float,
            viewportWidth: Float,
            viewportHeight: Float,
        ): PanBounds =
            PanBounds(
                maxX = max(0f, (displayedWidth - viewportWidth) * 0.5f),
                maxY = max(0f, (displayedHeight - viewportHeight) * 0.5f),
            )

        fun clampTranslation(
            translationX: Float,
            translationY: Float,
            bounds: PanBounds,
        ): Pair<Float, Float> =
            Pair(
                translationX.coerceIn(-bounds.maxX, bounds.maxX),
                translationY.coerceIn(-bounds.maxY, bounds.maxY),
            )

        fun seekTarget(currentPositionMs: Long, deltaMs: Long, durationMs: Long): Long =
            if (durationMs <= 0L) {
                currentPositionMs.coerceAtLeast(0L)
            } else {
                (currentPositionMs + deltaMs).coerceIn(0L, durationMs)
            }

        fun verticalDeltaFraction(deltaY: Float, viewportHeight: Int): Float =
            if (viewportHeight <= 0) {
                0f
            } else {
                (-(deltaY / viewportHeight.toFloat()) * 0.6f).coerceIn(-0.12f, 0.12f)
            }
    }

    private enum class SessionState { ACTIVE, EXITING, DESTROYED }

    private enum class TransitionPhase {
        IDLE,
        REQUESTED,
        HANDOFF_DISPATCHED,
        TARGET_ACTIVITY_ACTIVE,
        PREPARING,
        READY,
        FIRST_FRAME,
        COMMITTED,
        FAILED,
    }

    private var transitionPhase = TransitionPhase.IDLE

    private fun setTransitionPhase(next: TransitionPhase, reason: String) {
        if (transitionPhase == next) return
        val previous = transitionPhase
        transitionPhase = next
        logPlayer(
            "PLAYER_TRANSITION_PHASE from=" + previous.name +
                " to=" + next.name +
                " reason=" + reason +
                " requestId=" + requestId.ifEmpty { "-" } +
                " transitionGeneration=" + transitionGeneration +
                " playerSessionId=" + playerSessionId,
        )
    }

    companion object {
        internal fun resolveImmersivePolicy(setting: String?, orientation: Int): Boolean =
            when (setting?.trim()?.lowercase()) {
                "never" -> false
                "landscape" -> orientation == Configuration.ORIENTATION_LANDSCAPE
                else -> true
            }

        private val PLAYER_EVENT_CLOCK_MS = AtomicLong(0L)

        private const val PREF_GESTURES_VOLUME = "gesture_volume"
        private const val PREF_GESTURES_BRIGHTNESS = "gesture_brightness"
        private const val PREF_GESTURES_DOUBLE_TAP = "gesture_double_tap"
        private const val PREF_GESTURES_LONG_PRESS = "gesture_long_press"
        private const val PREF_LOCK_MODE = "player_lock_mode"
        private const val TAG = "[REIFLIX][PLAYER]"
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val PROGRESS_PERSIST_INTERVAL_MS = 15_000L
        private const val CONTROL_TIMEOUT_MS = 3_500L
        private const val SEEK_PROGRESS_MAX = 1000
        private const val MIN_ZOOM = 1f
        // 2.0x keeps anime readable while avoiding the aggressive 3.0x ceiling.
        private const val MAX_ZOOM = 2f
        private const val MIN_SCALE_FACTOR = 0.90f
        private const val MAX_SCALE_FACTOR = 1.10f
        private const val ZOOM_SNAP_THRESHOLD = 1.05f
        private const val ZOOM_FEEDBACK_INTERVAL_MS = 80L
        private const val MAX_RETRY_ATTEMPTS = 2
        private const val LOCK_AFFORDANCE_TIMEOUT_MS = 2_200L
    }
}
