package com.reianix.reianix_local

import com.reianix.reianix_local.bridge.NativeMailbox
import com.reianix.reianix_local.data.settings.ReiAnixSettingsContract
import com.reianix.reianix_local.data.sqlite.ReiAnixSqliteSettingsRepository
import com.reianix.reianix_local.player.DeviceInteractionProfile
import com.reianix.reianix_local.player.LocalSubtitleResolver
import com.reianix.reianix_local.player.NativePlayerRequest
import com.reianix.reianix_local.player.PlayerLocalMetadataStore
import com.reianix.reianix_local.player.FavoriteSceneDialogs
import com.reianix.reianix_local.player.PlayerEnhancementPolicy
import com.reianix.reianix_local.player.PlayerSkipSegment
import com.reianix.reianix_local.player.AniSkipSegments
import com.reianix.reianix_local.player.ComplementarySkipSources
import com.reianix.reianix_local.player.SkipConsensus
import com.reianix.reianix_local.player.SkipEvidence
import com.reianix.reianix_local.player.SkipProvider
import com.reianix.reianix_local.player.SkipProviderResult
import com.reianix.reianix_local.player.LocalSkipAudio
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.reianix.reianix_local.player.PlayerShortcut
import com.reianix.reianix_local.player.PlayerShortcutPolicy
import com.reianix.reianix_local.player.PlayerAspectMode
import com.reianix.reianix_local.player.tv.ReiAnixTvPicker
import com.reianix.reianix_local.player.PlayerAnimePreferences
import com.reianix.reianix_local.player.PlayerAnimeTrackChoice
import androidx.media3.ui.CaptionStyleCompat
import com.reianix.reianix_local.player.PlayerMediaPolicy
import com.reianix.reianix_local.player.ExternalVideoChooser
import com.reianix.reianix_local.player.PlayerStartupMetrics
import com.reianix.reianix_local.player.PlayerTimeFormatter
import com.reianix.reianix_local.player.RelativeSeekOverlay
import com.reianix.reianix_local.player.SeekScrubVisibilityGuard
import com.reianix.reianix_local.player.SystemUiController
import com.reianix.reianix_local.scanner.BroadStorageScanner
import com.reianix.reianix_local.scanner.MediaStoreScanner
import com.reianix.reianix_local.scanner.SafScanner

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.view.KeyEvent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Rational
import android.util.Log
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
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Dp
import com.reianix.reianix_local.ui.motion.ReiAnixMotionPolicy
import com.reianix.reianix_local.ui.player.ReiAnixNativePlayerTopControls
import com.reianix.reianix_local.ui.player.ReiAnixNativePlayerCenterControls
import com.reianix.reianix_local.ui.player.ReiAnixNativePlayerBottomControls
import com.reianix.reianix_local.ui.player.ReiAnixNativePlayerOptionsPanel
import com.reianix.reianix_local.ui.player.ReiAnixNativePlayerUiState
import com.reianix.reianix_local.ui.theme.ReiAnixComposeTheme
import com.reianix.reianix_local.ui.theme.ReiAnixTokens
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.reianix.reianix_local.player.PlayerMediaControls
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
import kotlin.math.roundToLong

/** Full-screen Media3 player for one persisted local, SAF, or MediaStore URI. */
@OptIn(UnstableApi::class)
class NativePlayerActivity : FragmentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.reianix.reianix_local.ui.settings.ReiAnixAppLocales.localizedContext(newBase))
    }

    private val traceId = UUID.randomUUID().toString()
    private val activityInstanceId = UUID.randomUUID().toString()
    private lateinit var player: ExoPlayer
    private lateinit var composeTopView: ComposeView
    private lateinit var composeCenterView: ComposeView
    private lateinit var composeBottomView: ComposeView
    private val composePlayerUiState = mutableStateOf(ReiAnixNativePlayerUiState())
    private val accumulatedSeekSeconds = mutableStateOf(0L)
    private val optionsPanelResetSignal = mutableStateOf(0)
    private var composeControlsEnabled = false
    private lateinit var uri: Uri
    private lateinit var playerView: PlayerView
    private lateinit var playerControlScrim: View
    private var playerControlsMotion: AnimatorSet? = null
    // Separate alpha tracks, sharing the same native animation clock.
    private val rewindControlAlpha = mutableStateOf(0f)
    private val playControlAlpha = mutableStateOf(0f)
    private val forwardControlAlpha = mutableStateOf(0f)
    private var playerControlsPresentation: ControlsPresentation? = null

    private enum class ControlsPresentation { HIDDEN, FULL, LOCKED, LOCKED_HIDDEN, ERROR, PIP }
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
    private lateinit var relativeSeekOverlay: RelativeSeekOverlay
    private lateinit var errorPanel: LinearLayout
    private lateinit var preparingIndicator: ProgressBar
    private var bufferingStartedAt = -1L
    private var loadingFinished = false
    private val showRebufferingIndicator = Runnable { syncBufferingIndicatorVisibility() }
    private lateinit var lockButton: TextView
    private lateinit var systemUiController: SystemUiController
    private lateinit var localMetadataStore: PlayerLocalMetadataStore
    private var mediaControls: PlayerMediaControls? = null
    private val consumptionService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ReiAnixAppDependencies.from(applicationContext).consumptionService
    }

    private var locked = false
    private var inPictureInPicture = false
    // Keep this until a fullscreen resume: some systems report PiP=false before
    // onStop when the user dismisses the floating window.
    private var pictureInPictureSession = false
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
    private var horizontalSeekEnabled = false
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
    private var audioOnly = false
    private var smartSkipButton: TextView? = null
    private var smartSkipEnabled = true
    private var smartSkipGeneration = 0L
    private var playerShortcuts = PlayerShortcutPolicy.default
    private var activeAnimePreferences: PlayerAnimePreferences? = null
    private var activeAnimePreferencesKey: String? = null
    private val animePreferencesMutex = Mutex()
    @Volatile private var animePreferenceWriteVersion = 0L
    private var subtitleColor = "system"
    private var subtitleFont = "system"
    private var captionRevision = 0L
    private var remoteSkipSegments = emptyList<PlayerSkipSegment>()
    private var skipLookupGeneration = -1L
    private var skipLookupDuration = -1L
    private var skipLookupJob: kotlinx.coroutines.Job? = null

    private val handler = Handler(Looper.getMainLooper())
    private var sleepTimerDeadlineMs = 0L
    private var sleepTimerPaused = false
    private var sleepTimerDialog: AlertDialog? = null
    private val sleepTimerTick = object : Runnable {
        override fun run() {
            if (sessionState == SessionState.DESTROYED || sessionState == SessionState.EXITING) return
            expireSleepTimerIfDue()
            syncComposePlayerUiState()
            if (sleepTimerDeadlineMs > 0L) handler.postDelayed(this, 1_000L)
        }
    }

    private fun sleepTimerRemainingMs(): Long =
        if (sleepTimerDeadlineMs > 0L) (sleepTimerDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L) else 0L

    private fun expireSleepTimerIfDue(): Boolean {
        if (sleepTimerDeadlineMs <= 0L || SystemClock.elapsedRealtime() < sleepTimerDeadlineMs) return false
        sleepTimerDeadlineMs = 0L
        sleepTimerPaused = true
        handler.removeCallbacks(sleepTimerTick)
        playbackWasRequestedBeforeStop = false
        requestedPlayWhenReadyForGeneration = false
        if (episodeChangePending) invalidateTransition("sleep_timer")
        if (::player.isInitialized) {
            player.pause()
            saveProgress("player_paused", force = true, notifyUiAfterCommit = true)
            updatePlayPauseButton()
            updatePictureInPictureParams()
        }
        return true
    }

    private fun setSleepTimer(minutes: Int?) {
        handler.removeCallbacks(sleepTimerTick)
        sleepTimerPaused = false
        sleepTimerDeadlineMs = minutes?.let { SystemClock.elapsedRealtime() + it * 60_000L } ?: 0L
        if (sleepTimerDeadlineMs > 0L) handler.post(sleepTimerTick)
        syncComposePlayerUiState()
        touchControls()
    }

    // Keep the native list/radio logic, but reuse the ⋮ menu's rounded
    // translucent charcoal surface for every options dialog.
    private fun showPlayerMenuDialog(dialog: AlertDialog) {
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(R.drawable.reianix_player_menu_dialog_background)
            val availableWidth = (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(240))
            setLayout(minOf(dp(410), availableWidth), WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun showSleepTimerSelection() {
        if (episodeChangePending || sleepTimerDialog?.isShowing == true) return
        val choices = (listOf(15, 30, 45, 60, 90).map { getString(R.string.native_timer_minutes, it) } + listOf(getString(R.string.native_custom), getString(R.string.native_disable))).toTypedArray()
        sleepTimerDialog = AlertDialog.Builder(this, R.style.ReiAnixPlayerMenuDialogTheme)
            .setTitle(getString(R.string.ui_timer))
            .setItems(choices) { _, which ->
                when (which) {
                    in 0..4 -> setSleepTimer(listOf(15, 30, 45, 60, 90)[which])
                    5 -> showCustomSleepTimer()
                    else -> setSleepTimer(null)
                }
            }
            .setNegativeButton(getString(R.string.locale_cancel), null)
            .create().also { showPlayerMenuDialog(it) }
    }

    private fun showCustomSleepTimer() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.native_timer_range)
            setSingleLine(true)
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setTextColor(Color.WHITE)
            setHintTextColor(0xFFBDBDBD.toInt())
            backgroundTintList = ColorStateList.valueOf(ReiAnixTokens.Colors.primary.toArgb())
        }
        val dialog = AlertDialog.Builder(this, R.style.ReiAnixPlayerMenuDialogTheme).setTitle(getString(R.string.ui_timer)).setView(input)
            .setPositiveButton(getString(R.string.tail_start), null).setNegativeButton(getString(R.string.locale_cancel), null).create()
        sleepTimerDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val minutes = input.text.toString().toIntOrNull()
                if (minutes == null || minutes !in 1..1440) input.error = getString(R.string.native_timer_invalid)
                else { setSleepTimer(minutes); dialog.dismiss() }
            }
        }
        showPlayerMenuDialog(dialog)
    }

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
    private var controlsVisible = false
    private val seekScrubVisibility = SeekScrubVisibilityGuard()
    private var moreVisible = false
    private var tvPicker: ReiAnixTvPicker? = null
    // Lives above the controls overlay: the options menu must remain usable after
    // the 3.5s player-controls timeout hides the playback UI.
    private var menuDismissScrim: View? = null
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
    private val favoriteSceneDialogs by lazy {
        FavoriteSceneDialogs(this, { root }, ::formatTime) {
            Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
        }
    }
    private data class FavoriteClipSession(
        val scene: PlayerLocalMetadataStore.FavoriteScene,
        val originalItem: MediaItem,
        val originalPositionMs: Long,
        val originalDurationMs: Long,
        val originalTracks: TrackSelectionParameters,
        val originalCompletion: Boolean,
        val sourceDurationMs: Long,
        var restoring: Boolean = false,
    )
    private var favoriteClipSession: FavoriteClipSession? = null
    private val inFavoriteClip: Boolean get() = favoriteClipSession?.restoring == false
    private val effectiveSeekMs: Long get() = if (inFavoriteClip) 10_000L else doubleTapSeekMs
    private var favoriteSceneUiRevision = 0L
    private var cachedFavoriteScenes: List<PlayerLocalMetadataStore.FavoriteScene>? = null
    private var favoriteRestoreWaitingForFrame = false
    private var favoriteOpeningWaitingForFrame = false
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

            return@Runnable
        }

        val direction = when {
            nextTransitionActive -> "NEXT"
            previousTransitionActive -> "PREVIOUS"
            else -> "NONE"
        }
        if (direction == "NEXT") {

        } else if (direction == "PREVIOUS") {

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

    internal fun mediaSessionPlatformTokenForTesting(): android.media.session.MediaSession.Token? =
        mediaControls?.platformToken
    private var feedbackHideAt = 0L
    private var feedbackSessionId = ""
    private var feedbackRequestId = ""
    private var feedbackPlayerGeneration = 0L
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
     * Native consumption persistence and mailbox publication use one ordered worker.
     * Successfully persisted events are marked so Python only refreshes UI/diagnostics;
     * if native persistence fails, the legacy LibraryStore path remains the fallback.
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

    private val titleValue: String
        get() = favoriteClipSession?.takeUnless { it.restoring }?.scene?.note?.text ?: intent.getStringExtra("title") ?: "Episódio"

    private val controlsHider = object : Runnable {
        override fun run() {
            if (seekScrubVisibility.active ||
                !controlsVisible ||
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
                setMorePanelVisible(false)
                syncComposePlayerUiState()
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
                // One UI projection invalidation per existing durable checkpoint,
                // not for each 250 ms position observation.
                saveProgress("player_progress", notifyUiAfterCommit = true)
                lastProgressPersistAt = now
            }
            updateProgressUi()
            if (::player.isInitialized && player.playbackState != Player.STATE_ENDED) {
                handler.postDelayed(this, PROGRESS_INTERVAL_MS)
            }
        }
    }
    private fun publishLegacyPlayerEvent(
        event: JSONObject,
        durable: Boolean = false,
    ): Boolean {
        if (!intent.getBooleanExtra("nativeCompose", false)) {
            return if (durable) {
                NativeMailbox.write(this, event)
            } else {
                NativeMailbox.writeBestEffort(this, event)
            }
        }

        // Native Compose handles playback locally; do not collect player telemetry.
        return true
    }





override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        }


        MainActivity.notePlayerActivityCreated(
            activityInstanceId,
            requestId,
            playerSessionId,
            transitionGeneration,
        )

        val traceEpisodeId = intent.getStringExtra("episodeId").orEmpty()
        val traceAnimeId = intent.getStringExtra("animeId").orEmpty()


        commandCreatedAtMs = intent.getLongExtra("commandCreatedAtMs", 0L)
        commandReceivedAtMs = intent.getLongExtra("commandReceivedAtMs", 0L)
        handoffDispatchedAtMs = intent.getLongExtra("handoffDispatchedAtMs", 0L)
        activityStartedAtMs = System.currentTimeMillis()
        sessionState = SessionState.ACTIVE
        MainActivity.notePlayerSession(requestId, playerSessionId, transitionGeneration)
        localMetadataStore = PlayerLocalMetadataStore(this)
        volumeGesturesEnabled = intent.getBooleanExtra("setting_gestures_volume", false)
        brightnessGesturesEnabled = intent.getBooleanExtra("setting_gestures_brightness", false)
        horizontalSeekEnabled = intent.getBooleanExtra("setting_gestures_horizontal_seek", false)
        doubleTapEnabled = intent.getBooleanExtra("setting_gestures_double_tap", true)
        longPressEnabled = intent.getBooleanExtra("setting_gestures_long_press", false)
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
        zoomEnabled = intent.getBooleanExtra("setting_player_zoom_enabled", true)
        doubleTapSeekMs = PlayerGesturePolicy.seekIntervalMs(
            intent.getLongExtra("setting_player_double_tap_seek_seconds", 10L),
        )
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
            ?: intent.getBooleanExtra("setting_player_lock_mode", false)
        val initialControlsVisible = savedInstanceState?.getBoolean("controls_visible", false) ?: false
        controlsVisible = initialControlsVisible
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
        val restoredAspect = savedInstanceState?.getString("aspect_mode_label")
            ?.let { PlayerAspectMode.fromLabel(it) }
            ?: PlayerAspectMode.fromSetting(intent.getStringExtra("setting_player_aspect_ratio"))
        aspectModeLabel = restoredAspect.label
        intent.putExtra("setting_player_aspect_ratio", restoredAspect.setting)
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = false
            clipToPadding = false
        }
        setContentView(root)
        installBasePlayerView()
        installGestureLayer()
        installControls()
        setLocked(locked, persist = false)
        setControlsVisible(initialControlsVisible)
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

            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            mediaControls = PlayerMediaControls(
                context = this,
                player = player,
                sessionId = activityInstanceId,
                titleProvider = { titleValue },
                episodeProvider = { currentEpisodeLabel() },
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
            loadCaptionAppearance()
            autoplayNext = savedInstanceState?.takeIf { it.containsKey("autoplay_next") }?.getBoolean("autoplay_next")
                ?: intent.getBooleanExtra("autoplay", true)

            val requestedSpeed = savedInstanceState?.takeIf { it.containsKey("playback_speed") }
                ?.getFloat("playback_speed")
                ?: intent.getFloatExtra("setting_player_default_speed", 1f)
            player.setPlaybackSpeed(PlayerGesturePolicy.normalizePlaybackSpeed(requestedSpeed))

            // Restore the configured fixed mode rather than a transient pinch transform.
            playerView.resizeMode = resizeModeFromSetting(restoredAspect.setting)

            sleepTimerDeadlineMs = savedInstanceState?.getLong("sleep_timer_deadline_ms", 0L) ?: 0L
            sleepTimerPaused = savedInstanceState?.getBoolean("sleep_timer_paused", false) ?: false
            if (sleepTimerDeadlineMs > 0L) handler.post(sleepTimerTick)
            prepareCurrentMedia("initial", savedInstanceState?.takeIf { it.containsKey("play_when_ready") }?.getBoolean("play_when_ready"))
        } catch (exception: Exception) {
            finishLoadingIndicator()
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

        dismissFavoriteSceneUi()
        favoriteOpeningWaitingForFrame = false
        favoriteRestoreWaitingForFrame = false
        cachedFavoriteScenes = null
        if (::playerView.isInitialized) playerView.setKeepContentOnPlayerReset(true)

        if (favoriteClipSession != null) {
            activePlayerListener?.let { player.removeListener(it) }
            activeAnalyticsListener?.let { player.removeAnalyticsListener(it) }
            activePlayerListener = null
            activeAnalyticsListener = null
            handler.removeCallbacks(progressReporter)
            player.stop()
            player.clearMediaItems()
            favoriteClipSession = null
        }
        super.onNewIntent(newIntent)
        val previousAnimeKey = currentAnimePreferencesKey()
        setIntent(newIntent)
        // Reuse the in-memory profile for another episode of the SAME anime.
        // This also avoids reading a stale disk snapshot while an async
        // preference write from the previous episode is still completing.
        if (previousAnimeKey != currentAnimePreferencesKey()) {
            activeAnimePreferences = null
            activeAnimePreferencesKey = null
        }
        requestId = incomingRequestId
        originRequestId = incomingOriginRequestId
        originCreatedAtMs = incomingOriginCreatedAtMs
        originTransitionGeneration = incomingOriginTransitionGeneration
        originPlayerSessionId = incomingOriginPlayerSessionId
        originMonotonicNs = incomingOriginMonotonicNs
        transitionSourceDirection = incomingOriginTransitionDirection

        val traceEpisodeId = newIntent.getStringExtra("episodeId").orEmpty()
        val traceAnimeId = newIntent.getStringExtra("animeId").orEmpty()


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

        if (::uri.isInitialized && uri != normalized) {
            tvPicker?.close()
            tvPicker = null
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
        volumeGesturesEnabled = newIntent.getBooleanExtra("setting_gestures_volume", volumeGesturesEnabled)
        brightnessGesturesEnabled = newIntent.getBooleanExtra("setting_gestures_brightness", brightnessGesturesEnabled)
        horizontalSeekEnabled = newIntent.getBooleanExtra("setting_gestures_horizontal_seek", horizontalSeekEnabled)
        doubleTapEnabled = newIntent.getBooleanExtra("setting_gestures_double_tap", doubleTapEnabled)
        longPressEnabled = newIntent.getBooleanExtra("setting_gestures_long_press", longPressEnabled)
        val incomingLockMode = newIntent.getBooleanExtra("setting_player_lock_mode", locked)
        val incomingDefaultSpeed = newIntent.getFloatExtra(
            "setting_player_default_speed",
            if (::player.isInitialized) player.playbackParameters.speed else 1f,
        )
        if (::player.isInitialized) {
            player.setPlaybackSpeed(PlayerGesturePolicy.normalizePlaybackSpeed(incomingDefaultSpeed))
        }
        if (incomingLockMode != locked) {
            setLocked(incomingLockMode, persist = false)
        }
        doubleTapSeekMs = PlayerGesturePolicy.seekIntervalMs(
            newIntent.getLongExtra("setting_player_double_tap_seek_seconds", doubleTapSeekMs / 1000L),
        )
        longPressSpeed = newIntent.getFloatExtra("setting_player_long_press_speed", longPressSpeed)
            .coerceIn(1f, 3f)
        maxVideoResolution = newIntent.getStringExtra("setting_player_max_video_resolution") ?: maxVideoResolution
        maxVideoFrameRate = newIntent.getIntExtra("setting_player_max_video_frame_rate", maxVideoFrameRate).coerceAtLeast(0)
        maxAudioChannels = newIntent.getIntExtra("setting_player_max_audio_channels", maxAudioChannels).coerceAtLeast(0)
        subtitleScale = newIntent.getFloatExtra("setting_audio_subtitle_scale", subtitleScale).coerceIn(0.5f, 2f)
        subtitleBottomPaddingPercent = newIntent.getIntExtra("setting_audio_subtitle_bottom_padding", subtitleBottomPaddingPercent).coerceIn(0, 50)
        subtitleEmbeddedStyle = newIntent.getBooleanExtra("setting_audio_subtitle_embedded_style", subtitleEmbeddedStyle)
        preferredAudioLanguage = newIntent.getStringExtra("setting_audio_preferred_language")?.trim() ?: preferredAudioLanguage
        preferredSubtitleLanguage = newIntent.getStringExtra("setting_audio_preferred_subtitle_language")?.trim() ?: preferredSubtitleLanguage
        subtitleMode = newIntent.getStringExtra("setting_audio_subtitles") ?: subtitleMode
        immersiveSetting = newIntent.getStringExtra("setting_player_immersive") ?: immersiveSetting
        // The Player Activity is reused for adjacent episodes: apply the new
        // episode's runtime preferences instead of retaining the first one's.
        pipEnabled = newIntent.getBooleanExtra("setting_player_pip", pipEnabled)
        autoHideTimeoutMs = newIntent.getIntExtra(
            "setting_player_auto_hide_seconds",
            (autoHideTimeoutMs / 1000L).toInt(),
        ).coerceIn(0, 300) * 1000L
        applyConfiguredRotation()
        applyImmersiveAfterLayout()
        applyGlobalTrackPreferences()
        applyAdvancedTrackConstraints()
        applySubtitlePreferences()
        activeAnimePreferences?.let(::applySavedAnimePreferences)
        loadCaptionAppearance()
        zoomEnabled = newIntent.getBooleanExtra("setting_player_zoom_enabled", zoomEnabled)
        findViewByTag<GestureLayer>("reianix_gesture_layer")?.resetZoomToFit()
        // Preserve the configured aspect mode when reusing this Activity without an extra.
        val incomingAspectRatio = newIntent.getStringExtra("setting_player_aspect_ratio")
            ?.let { PlayerAspectMode.fromSetting(it).setting }
            ?: PlayerAspectMode.fromLabel(aspectModeLabel).setting
        intent.putExtra("setting_player_aspect_ratio", incomingAspectRatio)
        playerView.resizeMode = resizeModeFromSetting(incomingAspectRatio)
        aspectModeLabel = aspectLabelFromSetting(incomingAspectRatio)
        findViewByTag<TextView>("reianix_aspect_button")?.apply {
            text = aspectModeLabel
            isSelected = aspectModeLabel != "Ajustar"
        }
        findViewByTag<TextView>("reianix_gesture_horizontal_seek")?.apply {
            text = gestureSettingLabel(getString(R.string.ui_gestures), horizontalSeekEnabled)
            isSelected = horizontalSeekEnabled
        }
        findViewByTag<TextView>("reianix_gesture_double_tap")?.apply {
            text = gestureSettingLabel(getString(R.string.ui_double_tap), doubleTapEnabled)
            isSelected = doubleTapEnabled
        }

        findViewByTag<TextView>("reianix_player_title")?.text =
            newIntent.getStringExtra("title") ?: "Episódio"
        updateEpisodeNavigationButtons()
        findViewByTag<View>("reianix_error_panel")?.visibility = View.GONE
        startLoadingIndicator()
        setMorePanelVisible(false)
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

    private fun currentPlayerSettingsSnapshot(): JSONObject =
        JSONObject()
            .put("player.autoplay_next", autoplayNext)
            .put("player.default_speed", if (::player.isInitialized) player.playbackParameters.speed else 1f)
            .put("player.aspect_ratio", PlayerAspectMode.fromLabel(aspectModeLabel).setting)
            .put("player.zoom_enabled", zoomEnabled)
            .put("player.immersive", immersiveSetting)
            .put("player.rotation", intent.getStringExtra("setting_player_rotation") ?: "auto")
            .put("player.lock_mode", locked)
            .put("player.auto_hide_seconds", (autoHideTimeoutMs / 1000L).coerceAtLeast(1L))
            .put("player.double_tap_seek_seconds", (doubleTapSeekMs / 1000L).coerceAtLeast(1L))
            .put("player.long_press_speed", longPressSpeed)
            .put("player.max_video_resolution", maxVideoResolution)
            .put("player.max_video_frame_rate", maxVideoFrameRate)
            .put("player.max_audio_channels", maxAudioChannels)
            .put("player.pip", pipEnabled)
            .put("gestures.volume", volumeGesturesEnabled)
            .put("gestures.brightness", brightnessGesturesEnabled)
            .put("gestures.horizontal_seek", horizontalSeekEnabled)
            .put("gestures.double_tap", doubleTapEnabled)
            .put("gestures.long_press", longPressEnabled)
            .put("audio.preferred_language", preferredAudioLanguage)
            .put("audio.preferred_subtitle_language", preferredSubtitleLanguage)
            .put("audio.subtitles", subtitleMode)
            .put("audio.subtitle_scale", subtitleScale)
            .put("audio.subtitle_bottom_padding", subtitleBottomPaddingPercent)
            .put("audio.subtitle_embedded_style", subtitleEmbeddedStyle)

    private fun currentEpisodeId(): String = intent.getStringExtra("episodeId")?.trim().orEmpty()

    private fun currentEpisodeLabel(): String {
        val explicitTitle = intent.getStringExtra("episodeTitle")?.trim().orEmpty()
        if (explicitTitle.isNotBlank()) return explicitTitle
        val episodeNumber = intent.getStringExtra("episodeNumber")?.trim().orEmpty()
        if (episodeNumber.isNotBlank()) return "Episódio $episodeNumber"
        val episodeId = currentEpisodeId()
        if (episodeId.isNotBlank()) return "Episódio $episodeId"
        return if (::uri.isInitialized) uri.lastPathSegment?.substringAfterLast("/")?.ifBlank { null } ?: "Arquivo local"
            else "Arquivo local"
    }

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
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(titleValue.ifBlank { "ReiAnix" })
                    .setArtist(currentEpisodeLabel())
                    .setDisplayTitle(currentEpisodeLabel().ifBlank { titleValue })
                    .build(),
            )
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
        expireSleepTimerIfDue()
        val shouldPlayWhenReady = !sleepTimerPaused && (playWhenReadyOverride
            ?: intent.getBooleanExtra("autoplay", true))
        requestedPlayWhenReadyForGeneration = shouldPlayWhenReady
        skipLookupJob?.cancel()
        remoteSkipSegments = emptyList()
        skipLookupGeneration = -1L
        skipLookupDuration = -1L

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

        startLoadingIndicator()

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

        val requestedStartPositionMs = initialPositionMsForGeneration
        val allowNativeResumeLookup = restoredPositionMs == null &&
                    !intent.getBooleanExtra("positionMsSpecified", intent.hasExtra("positionMs"))
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

                // Recover progress only when neither saved Activity state nor an
                // explicit caller position exists; the persisted episode ID is authoritative.
                var startupPositionMs = requestedStartPositionMs
                if (allowNativeResumeLookup) {
                    val canonicalEpisodeId = currentEpisodeId().toLongOrNull()
                    if (canonicalEpisodeId != null && canonicalEpisodeId > 0L) {
                        try {
                            val savedProgress = kotlinx.coroutines.runBlocking {
                                consumptionService.getProgress(canonicalEpisodeId)
                            }
                            val savedPositionMs = savedProgress?.positionMs?.takeIf { it >= 0L }
                            val savedDurationMs = savedProgress?.durationMs?.takeIf { it > 0L }
                            val resumePosition = resolveNativePlaybackStartPosition(
                                progressSeconds = savedPositionMs?.div(1_000.0),
                                durationSeconds = savedDurationMs?.div(1_000.0),
                                watched = savedProgress?.watched,
                                resumeEnabled = true,
                            )
                            if (resumePosition.positionSeconds > 0.0 && savedPositionMs != null) {
                                // Keep the canonical millisecond precision; the common policy
                                // only decides whether the position is eligible for resume.
                                startupPositionMs = savedPositionMs
                                logPlayer(
                                    "RESUME_FROM_NATIVE_SQLITE episodeId=" + canonicalEpisodeId +
                                        " positionMs=" + startupPositionMs +
                                        " durationMs=" + (savedDurationMs ?: -1L) +
                                        " requestId=" + requestId.ifEmpty { "-" },
                                )
                            }
                        } catch (error: Exception) {
                            logPlayer("RESUME_FROM_NATIVE_SQLITE_FAILED episodeId=" + canonicalEpisodeId, error)
                        }
                    }
                }

                if (startupPositionMs > 0L && restoredPositionMs == null && reason != "retry") {
                    val smart = runCatching {
                        kotlinx.coroutines.runBlocking {
                            ReiAnixSqliteSettingsRepository(applicationContext)
                                .getPreference("player.smart_resume", ReiAnixSettingsContract.defaultFor("player.smart_resume") ?: "false") == "true"
                        }
                    }.getOrDefault(false)
                    startupPositionMs = PlayerEnhancementPolicy.resumePosition(startupPositionMs, smart)
                }

                // Optional MIME/display-name/size/subtitle discovery starts in parallel
                // but is never awaited by the playback path.
                hydrateLocalMediaReferencesAsync(
                    generation = generation,
                    localUri = localUri,
                    expectedTransitionGeneration = preparationTransitionGeneration,
                )

                handler.post {
                    if (!isCurrentPreparation(generation, localUri, preparationTransitionGeneration)) return@post
                    initialPositionMsForGeneration = startupPositionMs.coerceAtLeast(0L)

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
                    player.playWhenReady = shouldPlayWhenReady && !sleepTimerPaused && !expireSleepTimerIfDue()

                    if (reason != "initial") {
                        reattachPlayerViewAfterMediaReset(reason)
                    }

                    prepareDispatchedAtMs = System.currentTimeMillis()

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
                val sanitizedUri = localUri.scheme + "://" + (localUri.authority ?: "local") + "/[REDACTED]"
                android.util.Log.i(
                    TAG,
                    "MEDIA_METADATA_DEFERRED_START requestId=" + requestId.ifEmpty { "-" } +
                        " generation=" + generation +
                        " uri=" + sanitizedUri,
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
                        "SUBTITLE_RESOLVE_FAILED generation=$generation uri=$sanitizedUri",
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
                if (inFavoriteClip && favoriteOpeningWaitingForFrame) revealFavoriteClip()
                if (favoriteRestoreWaitingForFrame) {
                    favoriteRestoreWaitingForFrame = false
                    favoriteSceneDialogs.setBusy(false)
                    playerView.setKeepContentOnPlayerReset(true)
                }

                finishLoadingIndicator()
                syncComposePlayerUiState()
                firstFrameRenderedAtMs = System.currentTimeMillis()

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
            if (playWhenReady && expireSleepTimerIfDue()) {
                player.pause()
                return
            }
            // A deliberate Play from PiP, headset or controls can resume after expiry.
            if (playWhenReady) sleepTimerPaused = false
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
            finishLoadingIndicator()
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
            if (::playerView.isInitialized) {
                playerView.requestLayout()
                findViewByTag<GestureLayer>("reianix_gesture_layer")?.refreshZoomForLayout()
            }
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
            if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                finishLoadingIndicator()
            } else if (state == Player.STATE_BUFFERING) {
                syncBufferingIndicatorVisibility()
            }
            val clipSession = favoriteClipSession
            val restoredEpisode = (state == Player.STATE_READY || state == Player.STATE_ENDED) &&
                clipSession?.restoring == true &&
                player.currentMediaItem?.mediaId == clipSession.originalItem.mediaId
            if (restoredEpisode) {
                favoriteClipSession = null
                completionReported = clipSession?.originalCompletion == true
                touchControls()
                val needsFrame = state == Player.STATE_READY && !audioOnly &&
                    player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO) && !firstFrameRenderedForTesting
                favoriteRestoreWaitingForFrame = needsFrame
                if (!needsFrame) {
                    favoriteSceneDialogs.setBusy(false)
                    playerView.setKeepContentOnPlayerReset(true)
                }
            }
            if (inFavoriteClip && favoriteOpeningWaitingForFrame && (state == Player.STATE_ENDED ||
                    (state == Player.STATE_READY && (audioOnly || !player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO))))) {
                revealFavoriteClip()
            }
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
                    if (!openedReported && favoriteClipSession == null) {
                        openedReported = true
                        val opened = publishLegacyPlayerEvent(
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
                                    .put("activityInstanceId", activityInstanceId)
                                    .put("generation", playerGeneration)
                                    .put("playerGeneration", playerGeneration)
                                    .put("transitionGeneration", transitionGeneration)
                                    .put("originRequestId", originRequestId)
                                    .put("originCreatedAtMs", originCreatedAtMs)
                                    .put("originPlayerSessionId", originPlayerSessionId)
                                    .put("originTransitionGeneration", originTransitionGeneration)
                                    .put("transitionDirection", intent.getStringExtra("transitionDirection").orEmpty())
                                    .put("state", "READY"))
                        )
                        if (!opened) {
                            logPlayer("FAILED_TO_PUBLISH player_opened requestId=" + requestId.ifEmpty { "-" })
                        }
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
                    completionReported = if (restoredEpisode) clipSession?.originalCompletion == true else false
                    updateTrackButtons()
                    updatePlayPauseButton()
                    updateProgressUi()
                    startProgressReporting()
                    // A profile request is explicit, validated against the canonical
                    // episode and consumed once. Reuse the existing virtual clip flow.
                    if (favoriteClipSession == null && !episodeChangePending) {
                        val scene = com.reianix.reianix_local.player.ProfileClipHandoff.take(requestId, uri.toString())
                        if (scene != null) {
                            handler.post {
                                if (isCurrent() && favoriteScenesAvailable() && !errorVisible) {
                                    playFavoriteClip(scene)
                                }
                            }
                        }
                    }
                    if (!firstFrameRenderedForTesting && !errorVisible) {

                    }
                    if (!errorVisible) scheduleControlsHide()
                }
                Player.STATE_BUFFERING -> {
                    updatePlayPauseButton()
                    if (!errorVisible) scheduleControlsHide()
                }
                Player.STATE_ENDED -> {
                    if (favoriteClipSession != null || restoredEpisode) {
                        player.pause()
                        updateProgressUi()
                        touchControls()
                        return
                    }
                    completionReported = true
                    saveProgress("player_completed", force = true)
                    updatePlayPauseButton()
                    if (!expireSleepTimerIfDue() && !sleepTimerPaused && autoplayNext && intent.getBooleanExtra("canNext", false)) {
                        requestEpisode("player_next_request")
                    }
                }
                Player.STATE_IDLE -> updatePlayPauseButton()
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            if (!isCurrent()) return
            val speedLabel = String.format(Locale.US, "%.2fx", playbackParameters.speed)
            findViewByTag<TextView>("reianix_speed_button")?.apply {
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
                    handler.removeCallbacks(controlsHider)
                    // Buffering temporarily stops playback while playWhenReady
                    // remains true. Only a pause should reveal hidden controls.
                    if (!player.playWhenReady) touchControls()
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
                if (favoriteClipSession == null && completionReported &&
                    newPosition.positionMs < oldPosition.positionMs &&
                    (player.duration <= 0L || newPosition.positionMs < player.duration)
                ) {
                    completionReported = false
                }
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
            applySavedAnimeTrackChoices()
            if (activeAnimePreferences?.audioOnly == true && !audioOnly && audioGroups > 0) {
                setAudioOnly(true)
            }
            captureTrackFormatSummaries()
            updateTrackButtons()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!isCurrent()) return
            if (inFavoriteClip) {
                logPlayer("FAVORITE_CLIP_PLAYBACK_FAILED", error)
                Toast.makeText(this@NativePlayerActivity, getString(R.string.native_scene_play_failed), Toast.LENGTH_LONG).show()
                handler.post { if (inFavoriteClip && !isFinishing && !isDestroyed) exitFavoriteClip() }
                return
            }
            if (favoriteClipSession?.restoring == true || favoriteRestoreWaitingForFrame) {
                favoriteRestoreWaitingForFrame = false
                favoriteSceneDialogs.dismiss()
                playerView.setKeepContentOnPlayerReset(true)
            }

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
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)
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
            val custom = subtitleColor != "system" || subtitleFont != "system"
            setApplyEmbeddedStyles(subtitleEmbeddedStyle && !custom)
            setApplyEmbeddedFontSizes(subtitleEmbeddedStyle && subtitleScale == 1f && !custom)
            setUserDefaultStyle()
            if (custom) {
                val font = when (subtitleFont) {
                    "serif" -> Typeface.SERIF
                    "mono" -> Typeface.MONOSPACE
                    "sans" -> Typeface.SANS_SERIF
                    else -> Typeface.DEFAULT
                }
                val color = when (subtitleColor) {
                    "yellow" -> Color.YELLOW
                    "cyan" -> Color.CYAN
                    else -> Color.WHITE
                }
                setStyle(CaptionStyleCompat(color,
                    Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    Color.BLACK, font))
            }
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

    private fun canEnterPictureInPicture(): Boolean =
        pipEnabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun isPictureInPictureActive(): Boolean =
        inPictureInPicture ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode)

    private fun canRequestPictureInPicture(): Boolean =
        favoriteClipSession == null && !audioOnly && canEnterPictureInPicture() &&
            sessionState == SessionState.ACTIVE &&
            !errorVisible &&
            !playbackErrorForGeneration &&
            ::player.isInitialized &&
            ::uri.isInitialized &&
            uri.toString().isNotBlank() &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED

    private fun requestPictureInPicture() {
        if (audioOnly) {
            showFeedback(getString(R.string.native_pip_audio))
            return
        }
        if (!canRequestPictureInPicture()) {
            logPlayer(
                "PLAYER_PIP_REQUEST_REJECTED requestId=" + requestId.ifEmpty { "-" } +
                    " sessionState=" + sessionState.name +
                    " playerInitialized=" + ::player.isInitialized +
                    " playbackState=" + (if (::player.isInitialized) player.playbackState.toString() else "uninitialized") +
                    " errorVisible=" + errorVisible,
            )
            return
        }
        updatePictureInPictureParams()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && canRequestPictureInPicture()) {
                enterPictureInPictureMode(pictureInPictureParamsForCurrentSession())
            }
        }.onFailure { error ->
            logPlayer("PLAYER_PIP_REQUEST_FAILED requestId=" + requestId.ifEmpty { "-" }, error)
        }
    }

    private fun pictureInPictureParamsForCurrentSession(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        if (::playerView.isInitialized && playerView.width > 0 && playerView.height > 0) {
            builder.setSourceRectHint(Rect(playerView.left, playerView.top, playerView.right, playerView.bottom))
        }
        if (::player.isInitialized) {
            val size = player.videoSize
            val width = size.width.toFloat() * size.pixelWidthHeightRatio
            val height = size.height.toFloat()
            if (width.isFinite() && height.isFinite() && width > 0f && height > 0f) {
                val ratio = (width / height).coerceIn(100f / 239f, 239f / 100f)
                val numerator = (ratio * 1000f).roundToInt().coerceIn(100, 2390)
                builder.setAspectRatio(Rational(numerator, 1000))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(
                canRequestPictureInPicture() && player.playWhenReady && player.isPlaying,
            )
        }
        return builder.build()
    }

    private fun configurePictureInPicture() {
        updatePictureInPictureParams()
    }

    private fun updatePictureInPictureParams() {
        if (!canEnterPictureInPicture()) return
        runCatching {
            setPictureInPictureParams(pictureInPictureParamsForCurrentSession())
        }.onFailure { error ->
            logPlayer("PLAYER_PIP_PARAMS_UPDATE_FAILED requestId=" + requestId.ifEmpty { "-" }, error)
        }
    }

    private fun installBasePlayerView() {
        playerView = layoutInflater.inflate(
            R.layout.native_player_view,
            root,
            false,
        ) as PlayerView
        playerView.apply {
            tag = "reianix_player_view"
            useController = false
            controllerAutoShow = false
            controllerHideOnTouch = false
            keepScreenOn = true
            setKeepContentOnPlayerReset(true)
            setShutterBackgroundColor(Color.BLACK)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        root.addView(playerView)

        // CloudStream's phone player uses shadow_overlay = #66000000.
        // Draw behind every control and feedback layer, never intercept gestures.
        playerControlScrim = View(this).apply {
            tag = "reianix_player_control_scrim"
            setBackgroundColor(0x66000000)
            alpha = 0f
            visibility = View.INVISIBLE
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(
            playerControlScrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        preparingIndicator = ProgressBar(this).apply {
            tag = "reianix_player_preparing"
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(ReiAnixTokens.Colors.primary.toArgb())
            visibility = View.VISIBLE
            contentDescription = getString(R.string.tail_prepare_video)
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
            tag = "reianix_gesture_layer"
        }
        root.addView(
            gestureLayer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )
        relativeSeekOverlay = RelativeSeekOverlay(this) { seconds ->
            accumulatedSeekSeconds.value = seconds
            syncBufferingIndicatorVisibility()
        }
        root.addView(
            relativeSeekOverlay,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        // A configuration event can arrive before the new viewport is measured.
        // Reconcile geometry after Android lays out the new landscape/portrait size.
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                gestureLayer.refreshZoomForLayout()
                playerView.requestLayout()
            }
        }
    }

    private fun installControls() {
        controls = object : FrameLayout(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                // A fading-out overlay must not intercept video gestures.
                if (!controlsVisible && !errorVisible) return false
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            tag = "reianix_controls_root"
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
            tag = "reianix_top_bar"
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

        val back = actionButton("Voltar", 44) {
            logPlayer("PLAYER_BACK BACK_BUTTON_TOUCH requestId=" + requestId.ifEmpty { "-" })
            finishPlayer("back_button")
        }
        back.tag = "reianix_back_button"
        back.contentDescription = getString(R.string.ui_back)
        topBar.addView(back, weightParams(44))

        val title = TextView(this).apply {
            text = titleValue
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            tag = "reianix_player_title"
            contentDescription = getString(R.string.ui_episode_title)
        }
        topBar.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))

        lockButton = actionButton(if (locked) "Desbloquear" else getString(R.string.ui_lock), 48) {
            setLocked(!locked)
        }.apply {
            tag = "reianix_lock_button"
            contentDescription = if (locked) getString(R.string.ui_unlock_controls) else getString(R.string.native_lock_controls)
        }
        topBar.addView(lockButton, weightParams(48))

        val moreButton = actionButton("Opções", 48) {
            toggleMorePanel()
        }
        moreButton.contentDescription = getString(R.string.ui_more_options)
        moreButton.tag = "reianix_more_button"
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
            tag = "reianix_feedback"
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
            tag = "reianix_error_panel"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundColor(0xEE0B0A0F.toInt())
            visibility = View.GONE
            isFocusable = true
        }
        errorPanel.addView(TextView(this).apply {
            tag = "reianix_error_text"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        errorPanel.addView(TextView(this).apply {
            tag = "reianix_error_reason"
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
        errorRetry.tag = "reianix_error_retry"
        errorPanel.addView(errorRetry, LinearLayout.LayoutParams(dp(190), dp(48)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(14)
        })

        val errorBack = actionButton("Voltar ao ReiAnix", 170) {
            finishPlayer("player_error_back")
        }
        errorBack.tag = "reianix_error_back"
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

        val previousButton = actionButton("−10", 70, showPressFeedback = false) { seekBy(-10_000L) }.apply {
            contentDescription = getString(R.string.tail_rewind_10)
        }
        previousButton.tag = "reianix_seek_back"
        centerControls.addView(previousButton, weightParams(70))

        playPauseButton = actionButton("Reproduzir", 84) { togglePlayPause() }.apply {
            contentDescription = getString(R.string.tail_play_pause)
            textSize = 26f
            tag = "reianix_play_pause"
            minHeight = dp(72)
            minWidth = dp(72)
        }
        centerControls.addView(playPauseButton, weightParams(84))

        val nextButton = actionButton("+10", 70, showPressFeedback = false) { seekBy(10_000L) }.apply {
            contentDescription = getString(R.string.tail_forward_10)
        }
        nextButton.tag = "reianix_seek_forward"
        centerControls.addView(nextButton, weightParams(70))

        bottomBar = LinearLayout(this).apply {
            tag = "reianix_bottom_bar"
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
            tag = "reianix_position"
        }
        durationLabel = TextView(this).apply {
            text = "00:00"
            textSize = 11f
            setTextColor(Color.WHITE)
            tag = "reianix_duration"
        }
        seekBar = SeekBar(this).apply {
            max = SEEK_PROGRESS_MAX
            contentDescription = getString(R.string.tail_progress_bar)
            tag = "reianix_seekbar"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && ::player.isInitialized && player.duration > 0L) {
                        val target = player.duration * progress.toLong() / SEEK_PROGRESS_MAX
                        positionLabel.text = formatTime(target)
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {
                    setSeekScrubActive(true)
                }

                override fun onStopTrackingTouch(bar: SeekBar?) {
                    try {
                        if (!::player.isInitialized || player.duration <= 0L) return
                        val target = player.duration * seekBar.progress.toLong() / SEEK_PROGRESS_MAX
                        player.seekTo(target.coerceIn(0L, player.duration))
                        saveProgress("player_progress", force = true)
                    } finally {
                        setSeekScrubActive(false)
                    }
                }
            })
        }
        seekRow.addView(positionLabel, LinearLayout.LayoutParams(dp(48), dp(40)))
        seekRow.addView(seekBar, LinearLayout.LayoutParams(0, dp(40), 1f))
        seekRow.addView(durationLabel, LinearLayout.LayoutParams(dp(48), dp(40)))
        bottomBar.addView(seekRow)

        val markerRow = LinearLayout(this).apply {
            tag = "reianix_marker_row"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val skipOpening = actionButton("Pular abertura", 132) {
            localMetadata.opening?.let { seekToMarker(it.endMs) }
        }.apply {
            tag = "reianix_skip_opening"
            visibility = View.GONE
            contentDescription = getString(R.string.tail_skip_opening)
        }
        val skipEnding = actionButton("Pular encerramento", 150) {
            localMetadata.ending?.let { seekToMarker(it.endMs) }
        }.apply {
            tag = "reianix_skip_ending"
            visibility = View.GONE
            contentDescription = getString(R.string.tail_skip_ending)
        }
        markerRow.addView(skipOpening, weightParams(132))
        markerRow.addView(skipEnding, weightParams(150))
        bottomBar.addView(markerRow)

        val morePanel = ComposeView(this).apply {
            tag = "reianix_more_panel"
            visibility = View.GONE
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ReiAnixComposeTheme {
                    ReiAnixNativePlayerOptionsPanel(
                        state = composePlayerUiState.value,
                        showPictureInPicture = !composePlayerUiState.value.favoriteClipMode && canEnterPictureInPicture(),
                        resetSignal = optionsPanelResetSignal.value,
                        onPrevious = {
                            if (!episodeChangePending && intent.getBooleanExtra("canPrevious", false)) {
                                requestEpisode("player_previous_request")
                            }
                        },
                        onNext = {
                            if (!episodeChangePending && intent.getBooleanExtra("canNext", false)) {
                                requestEpisode("player_next_request")
                            }
                        },
                        onSleepTimer = { showSleepTimerSelection() },
                        onAudioTracks = { showTrackSelection(C.TRACK_TYPE_AUDIO, getString(R.string.ui_select_audio)) },
                        onSubtitles = { showTrackSelection(C.TRACK_TYPE_TEXT, getString(R.string.ui_select_subtitles)) },
                        onPlaybackSpeed = {
                            showSpeedSelection(TextView(this@NativePlayerActivity))
                        },
                        onRestart = {
                            if (::player.isInitialized && !episodeChangePending) {
                                player.seekTo(0L)
                                saveProgress("player_progress", force = true)
                            }
                        },
                        onMarkers = { showLocalMetadataEditor() },
                        onFavoriteScenes = { showFavoriteScenes() },
                        onShortcutsChanged = { selected ->
                            val validated = runCatching {
                                PlayerShortcutPolicy.parse(PlayerShortcutPolicy.normalize(
                                    selected.joinToString(",") { it.key },
                                ))
                            }.getOrNull()
                            if (validated != null) {
                                playerShortcuts = validated
                                persistCanonicalPlayerSetting(
                                    "player.shortcuts", validated.joinToString(",") { it.key },
                                )
                                syncComposePlayerUiState()
                            }
                        },
                        onSubtitleAppearance = { showSubtitleAppearance() },
                        onAudioOnlyChanged = { enabled -> setAudioOnly(enabled) },
                        onAutoplayChanged = { enabled ->
                            autoplayNext = enabled
                            persistCanonicalPlayerSetting("player.autoplay_next", enabled.toString())
                            touchControls()
                            syncComposePlayerUiState()
                        },
                        onGesturesChanged = { enabled ->
                            horizontalSeekEnabled = enabled
                            persistCanonicalPlayerSetting("gestures.horizontal_seek", enabled.toString())
                            touchControls()
                            syncComposePlayerUiState()
                        },
                        onBrightnessVolumeChanged = { enabled ->
                            volumeGesturesEnabled = enabled
                            brightnessGesturesEnabled = enabled
                            persistCanonicalPlayerSetting("gestures.volume", enabled.toString())
                            persistCanonicalPlayerSetting("gestures.brightness", enabled.toString())
                            touchControls()
                            syncComposePlayerUiState()
                        },
                        onZoomChanged = { enabled ->
                            zoomEnabled = enabled
                            persistCanonicalPlayerSetting("player.zoom_enabled", enabled.toString())
                            if (!enabled) {
                                findViewByTag<GestureLayer>("reianix_gesture_layer")?.resetZoomToFit()
                                // Disabling pinch must not discard the selected fixed aspect mode.
                                if (::playerView.isInitialized) {
                                    playerView.resizeMode = resizeModeFromSetting(
                                        PlayerAspectMode.fromLabel(aspectModeLabel).setting,
                                    )
                                }
                            }
                            touchControls()
                            syncComposePlayerUiState()
                        },
                        onDoubleTapChanged = { enabled ->
                            doubleTapEnabled = enabled
                            persistCanonicalPlayerSetting("gestures.double_tap", enabled.toString())
                            touchControls()
                            syncComposePlayerUiState()
                        },
                        onLongPressChanged = { enabled ->
                            longPressEnabled = enabled
                            persistCanonicalPlayerSetting("gestures.long_press", enabled.toString())
                            touchControls()
                            syncComposePlayerUiState()
                        },
                        onOpenWithExternal = { openCurrentVideoExternally() },
                        onPictureInPicture = { requestPictureInPicture() },
                        onHistory = { showPlaybackHistory() },
                        onInformation = { showTechnicalInfo() },
                    )
                }
            }
        }
        val availablePanelWidth = (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(240))
        val panelWidth = minOf(dp(410), availablePanelWidth)
        val availablePanelHeight = (resources.displayMetrics.heightPixels - dp(72)).coerceAtLeast(dp(220))
        val panelHeight = minOf(dp(380), availablePanelHeight)
        // A transparent, clickable layer consumes outside taps without triggering
        // seek/pause gestures on the video underneath. It stays independent from
        // the controls layer and is only visible while the options menu is open.
        menuDismissScrim = View(this).apply {
            tag = "reianix_more_dismiss_scrim"
            visibility = View.GONE
            isClickable = true
            contentDescription = getString(R.string.tail_close_player_menu)
            setOnClickListener { setMorePanelVisible(false) }
        }.also { scrim ->
            root.addView(
                scrim,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        root.addView(morePanel, FrameLayout.LayoutParams(
            panelWidth,
            panelHeight,
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
        findViewByTag<View>("reianix_marker_row")?.visibility = View.GONE
        installComposePlayerControls()
        installSmartSkipButton()
        // Menu and dismiss scrim are brought above other overlays when opened.
    }

    private fun updateMorePanelBounds() {
        // The panel was moved from controls to root; preserve the old gesture
        // inset and resize bounds when orientation/configuration changes.
        val panel = findViewByTag<View>("reianix_more_panel") ?: return
        val params = panel.layoutParams as? FrameLayout.LayoutParams ?: return
        val widthPixels = resources.displayMetrics.widthPixels
        val heightPixels = resources.displayMetrics.heightPixels
        val desiredWidth = minOf(dp(410), (widthPixels - dp(24)).coerceAtLeast(dp(240)))
        val desiredHeight = minOf(dp(380), (heightPixels - dp(72)).coerceAtLeast(dp(220)))
        val desiredRightMargin = dp(8) + max(dp(4), gestureSafeRight)
        // Avoid requesting another layout on every insets delivery.
        if (params.width != desiredWidth || params.height != desiredHeight ||
            params.rightMargin != desiredRightMargin
        ) {
            params.width = desiredWidth
            params.height = desiredHeight
            params.rightMargin = desiredRightMargin
            panel.layoutParams = params
        }
    }

    /**
     * Delegate playback to an app chosen through Android's own "Abrir com".
     * ReiAnix grants access to only this video; no TV discovery or Google Cast
     * connection is involved. The local video remains intact.
     */
    private fun openCurrentVideoExternally() {
        if (favoriteClipSession != null) return
        setMorePanelVisible(false)
        if (!::uri.isInitialized || !::player.isInitialized || player.currentMediaItem == null) {
            Toast.makeText(this, getString(R.string.native_video_before_app), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val chooser = ExternalVideoChooser.createIntent(this, uri)
            startActivity(chooser)
            player.pause()
            playbackWasRequestedBeforeStop = false
            saveProgress("player_progress", force = true)
            syncComposePlayerUiState()
        } catch (error: Exception) {
            Log.w("NativePlayerActivity", "EXTERNAL_VIDEO_OPEN_FAILED", error)
            Toast.makeText(
                this,
                "Não foi possível abrir o vídeo em outro aplicativo",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun installComposePlayerControls() {
        composeControlsEnabled = true

        composeTopView = ComposeView(this).apply {
            tag = "reianix_compose_player_top"
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
            tag = "reianix_compose_player_center"
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ReiAnixComposeTheme {
                    ReiAnixNativePlayerCenterControls(
                        state = composePlayerUiState.value,
                        seekSeconds = (effectiveSeekMs / 1_000L).coerceAtLeast(1L),
                        onPlayPause = { togglePlayPause() },
                        onSeekRelative = { seekBy(it) },
                        accumulatedSeekSeconds = accumulatedSeekSeconds.value,
                        rewindAlpha = { rewindControlAlpha.value },
                        playAlpha = { playControlAlpha.value },
                        forwardAlpha = { forwardControlAlpha.value },
                        onSeekIconPositioned = relativeSeekOverlay::updateIconCenter,
                    )
                }
            }
        }
        composeBottomView = ComposeView(this).apply {
            tag = "reianix_compose_player_bottom"
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ReiAnixComposeTheme {
                    ReiAnixNativePlayerBottomControls(
                        state = composePlayerUiState.value,
                        mediaUri = if (composePlayerUiState.value.favoriteClipMode) favoriteClipSession?.scene?.mediaId
                            else if (::uri.isInitialized) uri.toString() else null,
                        onScrubInteraction = ::setSeekScrubActive,
                        onSeekTo = { targetMs ->
                            val duration = if (::player.isInitialized) player.duration else 0L
                            if (duration > 0L && !episodeChangePending) {
                                val safeTarget = targetMs.coerceIn(0L, duration)
                                player.seekTo(safeTarget)
                                saveProgress("player_progress", force = true)
                                touchControls()
                            }
                        },
                        onToggleLock = { setLocked(!locked) },
                        onResize = { showEpisodePlaylist() },
                        onSource = { toggleMorePanel() },
                        onPlaybackSpeed = { showSpeedSelection(TextView(this@NativePlayerActivity)) },
                        onCast = { showTvPicker() },
                        onAspect = ::toggleAspectMode,
                        onAudioTracks = { showTrackSelection(C.TRACK_TYPE_AUDIO, getString(R.string.ui_audio_tracks)) },
                        onManualSkip = ::skipOpeningOrEndingManually,
                        onNext = {
                            if (!episodeChangePending && intent.getBooleanExtra("canNext", false)) {
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
                dp(ReiAnixTokens.PlayerDimensions.topOverlayPortraitHeight),
                Gravity.TOP,
            ),
        )
        controls.addView(
            composeCenterView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(ReiAnixTokens.PlayerDimensions.centerOverlayPortraitHeight),
                Gravity.CENTER,
            ),
        )
        controls.addView(
            composeBottomView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(ReiAnixTokens.PlayerDimensions.bottomOverlayPortraitHeight),
                Gravity.BOTTOM,
            ),
        )
        updateComposeOverlayBounds()
        syncComposePlayerUiState()
    }

    private fun updateComposeOverlayBounds() {
        if (!composeControlsEnabled) return
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val topHeight = dp(
            if (landscape) ReiAnixTokens.PlayerDimensions.topOverlayLandscapeHeight
            else ReiAnixTokens.PlayerDimensions.topOverlayPortraitHeight,
        )
        val centerHeight = dp(
            if (landscape) ReiAnixTokens.PlayerDimensions.centerOverlayLandscapeHeight
            else ReiAnixTokens.PlayerDimensions.centerOverlayPortraitHeight,
        )
        val bottomHeight = dp(
            if (landscape) ReiAnixTokens.PlayerDimensions.bottomOverlayLandscapeHeight
            else ReiAnixTokens.PlayerDimensions.bottomOverlayPortraitHeight,
        )
        if (::composeTopView.isInitialized) {
            composeTopView.layoutParams = (composeTopView.layoutParams as FrameLayout.LayoutParams).apply {
                width = FrameLayout.LayoutParams.MATCH_PARENT
                height = topHeight
                gravity = Gravity.TOP
            }
        }
        if (::composeCenterView.isInitialized) {
            composeCenterView.layoutParams = (composeCenterView.layoutParams as FrameLayout.LayoutParams).apply {
                width = FrameLayout.LayoutParams.MATCH_PARENT
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
        val episodeNumber = intent.getStringExtra("episodeNumber")?.takeIf { it.isNotBlank() }
            ?: Regex("(?i)epis(?:ó|o)dio\\s+([0-9]+(?:[.,][0-9]+)?)")
                .find(titleValue)
                ?.groupValues?.getOrNull(1)
                ?.replace(',', '.')
                ?.let { raw ->
                    val normalized = raw.toDoubleOrNull()
                    if (normalized != null && normalized % 1.0 == 0.0) {
                        normalized.toLong().toString()
                    } else {
                        raw
                    }
                }
        val episodeLabel = episodeNumber?.let { "Episódio $it" } ?: "Episódio"

        composePlayerUiState.value = ReiAnixNativePlayerUiState(
            title = titleValue,
            episodeLabel = listOfNotNull(if (inFavoriteClip) "Cena favorita" else episodeLabel, resolution).joinToString(" · "),
            technicalLine = buildPlayerTechnicalLine(),
            positionMs = currentPosition.coerceAtLeast(0L),
            durationMs = currentDuration.coerceAtLeast(0L),
            bufferedPositionMs = if (::player.isInitialized && currentDuration > 0L) {
                player.bufferedPosition.coerceIn(0L, currentDuration)
            } else {
                0L
            },
            isPlaying = ::player.isInitialized && player.isPlaying,
            isBuffering = ::player.isInitialized && player.playbackState == Player.STATE_BUFFERING,
            hasRenderedFirstFrame = firstFrameRenderedForTesting,
            ended = ::player.isInitialized && player.playbackState == Player.STATE_ENDED,
            errorVisible = errorVisible,
            controlsVisible = controlsVisible,
            locked = locked,
            canNext = favoriteClipSession == null && intent.getBooleanExtra("canNext", false),
            canPrevious = favoriteClipSession == null && intent.getBooleanExtra("canPrevious", false),
            episodeTransitionInProgress = episodeChangePending || favoriteClipSession?.restoring == true,
            aspectLabel = aspectModeLabel,
            sleepTimerLabel = if (sleepTimerDeadlineMs > 0L) formatTime(sleepTimerRemainingMs()) else getString(R.string.choice_disabled),
            playbackSpeed = if (::player.isInitialized) player.playbackParameters.speed else 1f,
            audioTrackLabel = selectedPlayerTrackLabel(C.TRACK_TYPE_AUDIO),
            subtitleTrackLabel = selectedPlayerTrackLabel(C.TRACK_TYPE_TEXT),
            audioTrackSelectionEnabled = supportedTrackCount(C.TRACK_TYPE_AUDIO) > 0,
            subtitleTrackSelectionEnabled = supportedTrackCount(C.TRACK_TYPE_TEXT) > 0,
            autoplayNext = favoriteClipSession == null && autoplayNext,
            shortcuts = playerShortcuts,
            smartSkipEnabled = smartSkipEnabled,
            animePreferencesEnabled = activeAnimePreferences != null,
            horizontalSeekEnabled = horizontalSeekEnabled,
            volumeGesturesEnabled = volumeGesturesEnabled,
            brightnessGesturesEnabled = brightnessGesturesEnabled,
            zoomEnabled = zoomEnabled,
            doubleTapEnabled = doubleTapEnabled,
            longPressEnabled = longPressEnabled,
            safeTopPx = gestureSafeTop,
            safeBottomPx = gestureSafeBottom,
            skipSegments = currentSkipSegments(currentDuration),
            favoriteSceneTimes = if (favoriteClipSession == null) favoriteSceneNotes().map { it.timestampMs } else emptyList(),
            audioOnly = audioOnly,
            favoriteClipMode = inFavoriteClip,
            favoriteClipStartMs = favoriteClipSession?.takeUnless { it.restoring }?.scene?.note?.timestampMs ?: 0L,
            favoriteClipSourceDurationMs = favoriteClipSession?.takeUnless { it.restoring }?.sourceDurationMs ?: 0L,
        )
        val unlockedOverlaysVisible = controlsVisible && !locked && !errorVisible && !inPictureInPicture
        if (::relativeSeekOverlay.isInitialized) relativeSeekOverlay.showCircle = !unlockedOverlaysVisible
        updatePlayerControlsPresentation()
        syncBufferingIndicatorVisibility()
        updateSmartSkipButton()
    }

    /**
     * CloudStream FullScreenPlayer.animateLayoutChanges / PlayerGestureHelper:
     * fade scrim and controls in 100 ms; move top/bottom by -/+50dp in 200 ms.
     * Animate only real visibility transitions, not playback progress updates.
     * Lock, error and PiP retain their immediate visibility policy.
     */
    private fun updatePlayerControlsPresentation() {
        if (!::composeTopView.isInitialized || !::composeCenterView.isInitialized ||
            !::composeBottomView.isInitialized || !::playerControlScrim.isInitialized
        ) return
        val target = when {
            inPictureInPicture -> ControlsPresentation.PIP
            errorVisible -> ControlsPresentation.ERROR
            locked && controlsVisible -> ControlsPresentation.LOCKED
            locked -> ControlsPresentation.LOCKED_HIDDEN
            controlsVisible -> ControlsPresentation.FULL
            else -> ControlsPresentation.HIDDEN
        }
        val previous = playerControlsPresentation
        if (previous == target) return
        playerControlsPresentation = target
        val oldMotion = playerControlsMotion
        playerControlsMotion = null
        oldMotion?.cancel()

        val full = target == ControlsPresentation.FULL
        val lockedAffordance = target == ControlsPresentation.LOCKED
        val normal = target == ControlsPresentation.FULL || target == ControlsPresentation.HIDDEN
        val previousNormal = previous == ControlsPresentation.FULL ||
            previous == ControlsPresentation.HIDDEN
        val distance = dp(50).toFloat()
        val targetAlpha = if (full) 1f else 0f
        val topY = if (full) 0f else -distance
        val bottomY = if (full) 0f else distance
        val layers = listOf(playerControlScrim, composeTopView, composeBottomView)
        val centerAlphas = listOf(rewindControlAlpha, playControlAlpha, forwardControlAlpha)
        // Do not fade the parent as well: that would multiply the individual alphas.
        composeCenterView.alpha = 1f

        fun settle() {
            playerControlScrim.alpha = targetAlpha
            composeTopView.alpha = targetAlpha
            centerAlphas.forEach { it.value = targetAlpha }
            composeBottomView.alpha = if (full || lockedAffordance) 1f else 0f
            composeTopView.translationY = topY
            composeBottomView.translationY = if (lockedAffordance) 0f else bottomY
            playerControlScrim.visibility = if (full) View.VISIBLE else View.INVISIBLE
            composeTopView.visibility = if (full) View.VISIBLE else View.INVISIBLE
            composeCenterView.visibility = if (full) View.VISIBLE else View.INVISIBLE
            composeBottomView.visibility = if (full || lockedAffordance) View.VISIBLE else View.INVISIBLE
            controls.visibility = if (full || lockedAffordance || target == ControlsPresentation.ERROR) {
                View.VISIBLE
            } else View.INVISIBLE
        }

        if (!normal || !previousNormal) {
            settle()
            return
        }

        controls.visibility = View.VISIBLE
        layers.forEach { it.visibility = View.VISIBLE }
        composeCenterView.visibility = View.VISIBLE
        val animations = layers.map { layer ->
            ObjectAnimator.ofFloat(layer, View.ALPHA, targetAlpha).apply {
                duration = 100L
            }
        } + centerAlphas.map { controlAlpha ->
            // CloudStream creates a distinct 100ms fade for each center control.
            // Keep current alpha on reversal, and start all tracks in this same AnimatorSet.
            ValueAnimator.ofFloat(controlAlpha.value, targetAlpha).apply {
                duration = 100L
                addUpdateListener { controlAlpha.value = it.animatedValue as Float }
            }
        } + listOf(
            ObjectAnimator.ofFloat(composeTopView, View.TRANSLATION_Y, topY).apply {
                duration = 200L
            },
            ObjectAnimator.ofFloat(composeBottomView, View.TRANSLATION_Y, bottomY).apply {
                duration = 200L
            },
        )
        val motion = AnimatorSet().apply {
            playTogether(animations)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // An interrupted fade must never hide newly shown controls.
                    if (playerControlsMotion !== animation) return
                    playerControlsMotion = null
                    settle()
                }
            })
        }
        playerControlsMotion = motion
        motion.start()
    }

    // One persistent native spinner; control visibility never swaps its animation.
    private fun startLoadingIndicator() {
        handler.removeCallbacks(showRebufferingIndicator)
        bufferingStartedAt = -1L
        loadingFinished = false
        if (::preparingIndicator.isInitialized && preparingIndicator.visibility != View.VISIBLE) {
            preparingIndicator.visibility = View.VISIBLE
        }
    }

    private fun finishLoadingIndicator() {
        handler.removeCallbacks(showRebufferingIndicator)
        bufferingStartedAt = -1L
        loadingFinished = true
        if (::preparingIndicator.isInitialized && preparingIndicator.visibility != View.GONE) {
            preparingIndicator.visibility = View.GONE
            preparingIndicator.clearAnimation()
            (preparingIndicator.indeterminateDrawable as? android.graphics.drawable.Animatable)?.stop()
        }
    }

    private fun syncBufferingIndicatorVisibility() {
        if (!::preparingIndicator.isInitialized) return
        if (errorVisible || sessionState != SessionState.ACTIVE) {
            finishLoadingIndicator()
            return
        }
        if (!::player.isInitialized) return
        val state = player.playbackState
        if (state == Player.STATE_READY || state == Player.STATE_ENDED || player.isPlaying) {
            // During episode preflight the old item can still be READY.
            // The listener handles READY for the newly prepared item.
            if (loadingFinished || firstFrameRenderedForTesting) finishLoadingIndicator()
            return
        }
        if (state != Player.STATE_BUFFERING) return
        if (preparingIndicator.visibility == View.VISIBLE) return
        if (!loadingFinished) {
            preparingIndicator.visibility = View.VISIBLE
            return
        }
        // Filter brief READY -> BUFFERING -> READY oscillations so a completed
        // animation cannot flash back on screen. Sustained buffering still shows.
        val now = android.os.SystemClock.uptimeMillis()
        if (bufferingStartedAt < 0L) bufferingStartedAt = now
        val remaining = 250L - (now - bufferingStartedAt)
        handler.removeCallbacks(showRebufferingIndicator)
        if (remaining > 0L) {
            handler.postDelayed(showRebufferingIndicator, remaining)
        } else {
            preparingIndicator.visibility = View.VISIBLE
        }
    }

    private fun showTvPicker() {
        if (!::uri.isInitialized || !::player.isInitialized || episodeChangePending) return
        val source = if (inFavoriteClip) {
            favoriteClipSession?.scene?.mediaId?.let { Uri.parse(it) } ?: uri
        } else uri
        val sourceId = source.toString()
        if (tvPicker?.sourceId != sourceId) {
            tvPicker?.close()
            tvPicker = ReiAnixTvPicker(
                activity = this,
                sourceId = sourceId,
                sourceUri = source,
                title = titleValue.ifBlank { getString(R.string.flow_episode) },
                positionMs = player.currentPosition.coerceAtLeast(0L),
                onRemotePlaybackStarted = {
                    if (::player.isInitialized && !episodeChangePending) {
                        player.pause()
                        saveProgress("player_paused", force = true)
                        updatePlayPauseButton()
                    }
                },
            )
        }
        tvPicker?.show()
    }
    private fun setMorePanelVisible(visible: Boolean) {
        val canShow = visible && !locked && !inPictureInPicture && !errorVisible
        moreVisible = canShow
        val panel = findViewByTag<View>("reianix_more_panel")
        menuDismissScrim?.visibility = if (canShow) View.VISIBLE else View.GONE
        panel?.visibility = if (canShow) View.VISIBLE else View.GONE
        if (canShow) {
            optionsPanelResetSignal.value += 1
            menuDismissScrim?.bringToFront()
            panel?.bringToFront()
        }
        updateSmartSkipButton()
    }

    private fun toggleMorePanel() {
        findViewByTag<GestureLayer>("reianix_gesture_layer")?.cancelInteractions()
        val opening = !moreVisible
        touchControls()
        setMorePanelVisible(opening)
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
                    if (favoriteSceneDialogs.handleBack()) return
                    if (episodePlaylistOverlay != null) { closeEpisodePlaylist(); return }
                    logPlayer("PLAYER_BACK ANDROID_BACK requestId=" + requestId.ifEmpty { "-" })
                    if (moreVisible) {
                        setMorePanelVisible(false)
                        return
                    }
                    finishPlayer("back_button")
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
        updateMorePanelBounds()
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
        expireSleepTimerIfDue()
        sleepTimerPaused = false
        if (episodeChangePending) {
            logPlayer("PLAYER_COMMAND_IGNORED command=play_pause reason=episode_transition requestId=" + requestId.ifEmpty { "-" })
            return
        }
        when {
            player.playbackState == Player.STATE_ENDED -> {
                // Starting a new viewing session must not carry the previous
                // completion event into its exit checkpoint.
                completionReported = false
                // The UI exposes a replay affordance for an ended item, so tapping
                // it must explicitly rewind before playback.
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
                !::player.isInitialized -> "Reproduzir"
                player.playbackState == Player.STATE_ENDED -> "Repetir"
                player.playbackState == Player.STATE_BUFFERING -> "Carregando"
                player.isPlaying -> "Pausar"
                else -> "Reproduzir"
            }
        }
        syncComposePlayerUiState()
    }

    private fun supportedTrackCount(type: Int): Int {
        if (!::player.isInitialized) return 0
        return player.currentTracks.groups
            .filter { it.type == type && it.isSupported }
            .sumOf { group ->
                (0 until group.length).count { group.isTrackSupported(it) }
            }
    }

    private fun updateTrackButtons() {
        if (!::player.isInitialized) return

        val audioCount = supportedTrackCount(C.TRACK_TYPE_AUDIO)
        val subtitleCount = supportedTrackCount(C.TRACK_TYPE_TEXT)
        val audioAvailable = audioCount > 0
        val subtitleAvailable = subtitleCount > 0

        // Keep single-track selectors available so users can disable audio or subtitles.
        findViewByTag<View>("reianix_audio_button")?.isEnabled = audioAvailable
        findViewByTag<View>("reianix_subtitle_button")?.isEnabled = subtitleAvailable
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
            seekBar.secondaryProgress = ((player.bufferedPosition.toDouble() / duration.toDouble()) * SEEK_PROGRESS_MAX)
                .roundToInt().coerceIn(0, SEEK_PROGRESS_MAX)
        } else if (::seekBar.isInitialized) {
            seekBar.progress = 0
            seekBar.secondaryProgress = 0
        }
        if (::positionLabel.isInitialized) positionLabel.text = formatTime(position)
        if (::durationLabel.isInitialized) {
            durationLabel.text = if (duration > 0L) formatTime(duration) else "--:--"
        }
        updateMetadataControls(position)
        requestSmartSkipSegments(duration)
        syncComposePlayerUiState()
    }

    private fun startProgressReporting() {
        handler.removeCallbacks(progressReporter)
        handler.post(progressReporter)
    }

    private fun selectedPlayerTrackLabel(trackType: Int): String {
        if (!::player.isInitialized) return getString(R.string.choice_select)
        val parameters = player.trackSelectionParameters
        if (parameters.disabledTrackTypes.contains(trackType)) {
            return getString(R.string.choice_disabled)
        }
        val selectedFormat = player.currentTracks.groups
            .filter { it.type == trackType && it.isSupported }
            .flatMap { group ->
                (0 until group.length)
                    .filter { group.isTrackSupported(it) && group.isTrackSelected(it) }
                    .map { group.getTrackFormat(it) }
            }
            .firstOrNull()
        return selectedFormat?.label?.takeIf { it.isNotBlank() }
            ?: selectedFormat?.language?.takeIf { it.isNotBlank() }
            ?: if (trackType == C.TRACK_TYPE_TEXT) "Off" else getString(R.string.choice_automatic)
    }

    private fun showSpeedSelection(button: TextView) {
        findViewByTag<GestureLayer>("reianix_gesture_layer")?.cancelInteractions()
        if (!::player.isInitialized) return
        val speeds = floatArrayOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        val labels = speeds.map { String.format(java.util.Locale.US, "%.2fx", it) }.toTypedArray()
        val currentIndex = speeds.indices.minByOrNull { abs(speeds[it] - player.playbackParameters.speed) } ?: 2
        touchControls()
        AlertDialog.Builder(this, R.style.ReiAnixPlayerMenuDialogTheme)
            .setTitle(getString(R.string.ui_speed))
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                player.setPlaybackSpeed(speeds[which])
                persistCanonicalPlayerSetting("player.default_speed", speeds[which].toString())
                button.text = labels[which]
                button.isSelected = true
                touchControls()
                syncComposePlayerUiState()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.locale_cancel), null)
            .create().also { showPlayerMenuDialog(it) }
    }

    private var episodePlaylistOverlay: FrameLayout? = null
    private fun closeEpisodePlaylist() {
        episodePlaylistOverlay?.let { root.removeView(it) }
        episodePlaylistOverlay = null
    }

    private fun showEpisodePlaylist() {
        if (favoriteClipSession != null) return
        if (locked || episodeChangePending || episodePlaylistOverlay != null) return
        val sourceSession = playerSessionId
        val animeId = intent.getStringExtra("animeId")?.toLongOrNull() ?: return
        lifecycleScope.launch {
            try {
                val rows = ReiAnixAppDependencies.from(applicationContext).sqliteLibraryRepository
                    .loadLibraryCatalogRows(setOf(animeId))
                val anime = withContext(Dispatchers.IO) {
                    com.reianix.reianix_local.data.library.ReiAnixNativeLibraryCatalogProjector.project(rows)
                        .animes.firstOrNull()
                } ?: return@launch
                if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) || sourceSession != playerSessionId || isFinishing || episodeChangePending || episodePlaylistOverlay != null) return@launch
                val overlay = FrameLayout(this@NativePlayerActivity).apply {
                    setBackgroundColor(android.graphics.Color.argb(65, 0, 0, 0))
                    setOnClickListener { closeEpisodePlaylist() }
                }
                val panel = ComposeView(this@NativePlayerActivity).apply {
                    isClickable = true
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setContent {
                        ReiAnixComposeTheme {
                            com.reianix.reianix_local.ui.player.ReiAnixEpisodePlaylist(
                                episodes = anime.contentEpisodes, currentId = currentEpisodeId().toLongOrNull() ?: -1L,
                                onClose = { closeEpisodePlaylist() },
                                onSelect = { id ->
                                    closeEpisodePlaylist()
                                    if (id != currentEpisodeId().toLongOrNull()) requestEpisode("player_next_request", id)
                                },
                            )
                        }
                    }
                }
                overlay.addView(panel, FrameLayout.LayoutParams(
                    min(dp(420), (resources.displayMetrics.widthPixels * .72f).toInt()),
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT))
                episodePlaylistOverlay = overlay
                root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                touchControls()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (error: Exception) { showFeedback(getString(R.string.native_episodes_load_failed)) }
        }
    }

    private fun toggleAspectMode() {
        if (!::playerView.isInitialized || episodeChangePending || locked || errorVisible) return
        // Cycle the configured modes, independent of temporary pinch/pan transforms.
        val current = PlayerAspectMode.fromLabel(aspectModeLabel)
        applyAspectMode(current.next())
    }

    private fun applyAspectMode(mode: PlayerAspectMode) {
        if (!::playerView.isInitialized) return
        findViewByTag<GestureLayer>("reianix_gesture_layer")?.resetZoomToFit()
        playerView.resizeMode = resizeModeFromSetting(mode.setting)
        aspectModeLabel = mode.label
        intent.putExtra("setting_player_aspect_ratio", mode.setting)
        findViewByTag<TextView>("reianix_aspect_button")?.apply {
            text = aspectModeLabel
            isSelected = mode != PlayerAspectMode.FIT
        }
        persistCanonicalPlayerSetting("player.aspect_ratio", mode.setting)
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

    private fun showPlaybackHistory() {
        touchControls()
        val loadingDialog = AlertDialog.Builder(this, R.style.ReiAnixPlayerMenuDialogTheme)
            .setTitle(getString(R.string.ui_history))
            .setMessage(getString(R.string.tail_history_loading))
            .setNegativeButton(getString(R.string.ui_close), null)
            .create()
        showPlayerMenuDialog(loadingDialog)

        lifecycleScope.launch {
            try {
                val historyItems = withContext(Dispatchers.IO) {
                    val dependencies = ReiAnixAppDependencies.from(this@NativePlayerActivity)
                    dependencies.consumptionService.playbackHistory(limit = 36).mapNotNull { progress ->
                        val episode = dependencies.sqliteLibraryRepository.getEpisode(progress.episodeId)
                            ?: return@mapNotNull null
                        val anime = dependencies.sqliteLibraryRepository.getAnime(episode.animeId)
                        val animeTitle = anime?.title?.takeIf { it.isNotBlank() }
                            ?: episode.fileName
                        val episodeTitle = episode.episodeTitle?.takeIf { it.isNotBlank() }
                            ?: episode.number?.let { number ->
                                val numberLabel = if (number % 1.0 == 0.0) {
                                    number.toInt().toString()
                                } else {
                                    number.toString()
                                }
                                "Episódio " + numberLabel
                            }
                            ?: episode.fileName
                        animeTitle + " • " + episodeTitle
                    }.distinct()
                }

                if (loadingDialog.isShowing) loadingDialog.dismiss()
                if (isFinishing || isDestroyed) return@launch
                if (historyItems.isEmpty()) {
                    showFeedback(getString(R.string.native_history_empty))
                    return@launch
                }
                AlertDialog.Builder(this@NativePlayerActivity, R.style.ReiAnixPlayerMenuDialogTheme)
                    .setTitle(getString(R.string.ui_history))
                    .setItems(historyItems.toTypedArray(), null)
                    .setNegativeButton(getString(R.string.ui_close), null)
                    .create().also { showPlayerMenuDialog(it) }
            } catch (cancelled: CancellationException) {
                if (loadingDialog.isShowing) loadingDialog.dismiss()
                throw cancelled
            } catch (exception: Exception) {
                if (loadingDialog.isShowing) loadingDialog.dismiss()
                logPlayer("PLAYER_HISTORY_LOAD_FAILED", exception)
                if (!isFinishing && !isDestroyed) {
                    showFeedback(getString(R.string.native_history_load_failed))
                }
            }
        }
    }

    private fun showTechnicalInfo() {
        if (!::player.isInitialized) return
        touchControls()
        AlertDialog.Builder(this, R.style.ReiAnixPlayerMenuDialogTheme)
            .setTitle(getString(R.string.tail_technical_info))
            .setMessage(buildTechnicalInfo())
            .setPositiveButton(getString(R.string.ui_close), null)
            .create().also { showPlayerMenuDialog(it) }
    }
    private fun showTrackSelection(trackType: Int, label: String) {
        findViewByTag<GestureLayer>("reianix_gesture_layer")?.cancelInteractions()
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
                    val base = labelText ?: language ?: getString(R.string.native_track_number, groupIndex + 1, trackIndex + 1)
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

        if (realTracks.isEmpty()) {
            showFeedback(
                if (trackType == C.TRACK_TYPE_AUDIO) getString(R.string.native_no_audio_tracks)
                else getString(R.string.native_no_subtitles),
            )
            return
        }

        val options = buildList {
            add(TrackOption(getString(R.string.choice_automatic), null, -1))
            add(TrackOption(
                if (trackType == C.TRACK_TYPE_AUDIO) getString(R.string.native_disable_audio) else getString(R.string.native_disable_subtitles),
                null,
                -2,
            ))
            addAll(realTracks)
        }

        val parameters = player.trackSelectionParameters
        val disabled = parameters.disabledTrackTypes.contains(trackType)
        val overriddenGroups = parameters.overrides.keys
        val selectedIndex = when {
            disabled -> options.indexOfFirst { it.trackIndex == -2 }
            overriddenGroups.isNotEmpty() -> options.indexOfFirst { option ->
                option.group != null && option.group.mediaTrackGroup in overriddenGroups
            }
            else -> 0
        }.let { if (it >= 0) it else 0 }

        val labels = options.map { it.title }.toTypedArray()
        AlertDialog.Builder(this, R.style.ReiAnixPlayerMenuDialogTheme)
            .setTitle(label)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                val option = options.getOrNull(which) ?: return@setSingleChoiceItems
                runCatching {
                    val builder = player.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(trackType)
                    when {
                        option.trackIndex == -2 -> {
                            builder.setTrackTypeDisabled(trackType, true)
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
                    if (activeAnimePreferences != null) {
                        val format = option.group?.takeIf { option.trackIndex >= 0 }
                            ?.getTrackFormat(option.trackIndex)
                        val choice = when (option.trackIndex) {
                            -2 -> PlayerAnimeTrackChoice(mode = "off")
                            -1 -> PlayerAnimeTrackChoice()
                            else -> PlayerAnimeTrackChoice(
                                mode = "selected",
                                language = format?.language.orEmpty(),
                                label = format?.label.orEmpty(),
                                mimeType = format?.sampleMimeType.orEmpty(),
                            )
                        }
                        if (trackType == C.TRACK_TYPE_AUDIO) {
                            activeAnimePreferences = activeAnimePreferences?.copy(audioTrack = choice)
                            preferredAudioLanguage = choice.language
                        } else {
                            activeAnimePreferences = activeAnimePreferences?.copy(subtitleTrack = choice)
                            preferredSubtitleLanguage = choice.language
                            subtitleMode = when (choice.mode) {
                                "off" -> "never"
                                "selected" -> "always"
                                else -> "auto"
                            }
                        }
                        persistAnimePreferences()
                    }
                    // Publish the new semantic choice first: onTracksChanged
                    // must not restore a stale per-anime override in between.
                    player.trackSelectionParameters = builder.build()
                    updateTrackButtons()
                    captureTrackFormatSummaries()
                    syncComposePlayerUiState()
                    logPlayer(
                        "TRACK_SELECTION_APPLIED type=" + trackType + " index=" +
                            option.trackIndex + " label=" + option.title,
                    )
                }.onFailure { error ->
                    logPlayer("TRACK_SELECTION_FAILED type=" + trackType, error)
                    showFeedback(getString(R.string.native_track_change_failed))
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.locale_cancel), null)
            .create().also { showPlayerMenuDialog(it) }
    }

    private fun skipOpeningOrEndingManually() {
        if (episodeChangePending || locked || errorVisible ||
            inPictureInPicture || sessionState != SessionState.ACTIVE ||
            !::player.isInitialized || player.duration <= 0L
        ) return
        val target = PlayerGesturePolicy.seekTarget(
            currentPositionMs = player.currentPosition,
            deltaMs = PlayerShortcutPolicy.MANUAL_OP_ED_SKIP_MS,
            durationMs = player.duration,
        )
        // This is a manual jump; it never modifies detected OP/ED segments.
        seekToMarker(target)
    }

    private fun seekBy(deltaMs: Long, showControls: Boolean = true) {
        if (episodeChangePending) {
            logPlayer("PLAYER_COMMAND_IGNORED command=seek reason=episode_transition requestId=" + requestId.ifEmpty { "-" })
            return
        }
        if (!::player.isInitialized || player.duration <= 0L) return
        val target = PlayerGesturePolicy.seekTarget(
            currentPositionMs = player.currentPosition,
            deltaMs = deltaMs,
            durationMs = player.duration,
        )
        relativeSeekOverlay.showSeek(deltaMs)
        player.seekTo(target)
        // Relative seek gestures are frequent; the authoritative checkpoints remain
        // pause/completion/exit plus the periodic progress reporter.
        saveProgress("player_progress")
        if (showControls || controlsVisible) touchControls()
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
        // All ordinary hide paths, including delayed callbacks and taps, must
        // respect an active seekbar gesture. PiP/lock/error still take precedence.
        if (!visible && seekScrubVisibility.blocksHide(inPictureInPicture, locked, errorVisible)) {
            handler.removeCallbacks(controlsHider)
            return
        }
        controlsVisible = visible
        if (!visible) {
            handler.removeCallbacks(controlsHider)
        }
        if (inPictureInPicture) {
            handler.removeCallbacks(controlsHider)
            setMorePanelVisible(false)
            controls.visibility = View.INVISIBLE
            hideLegacyPrimaryControls()
            syncComposePlayerUiState()
            return
        }
        if (locked) {
            controls.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            hideLegacyPrimaryControls()
            topBar.visibility = View.GONE
            bottomBar.visibility = View.GONE
            centerControls.visibility = View.GONE
            setMorePanelVisible(false)
            findViewByTag<View>("reianix_back_button")?.visibility = View.GONE
            findViewByTag<View>("reianix_more_button")?.visibility = View.GONE
            if (visible) {
                handler.removeCallbacks(lockAffordanceHider)
                handler.postDelayed(lockAffordanceHider, LOCK_AFFORDANCE_TIMEOUT_MS)
            } else {
                handler.removeCallbacks(lockAffordanceHider)
            }
            syncComposePlayerUiState()
            return
        }

        if (composeControlsEnabled) {
            // Keep the parent mounted during the 200ms exit animation.
            hideLegacyPrimaryControls()
        } else {
            controls.visibility = if (visible || errorVisible) View.VISIBLE else View.INVISIBLE
            topBar.visibility = if (visible || errorVisible) View.VISIBLE else View.GONE
            bottomBar.visibility = if (visible || errorVisible) View.VISIBLE else View.GONE
            centerControls.visibility = if (visible || errorVisible) View.VISIBLE else View.GONE
        }
        findViewByTag<View>("reianix_back_button")?.visibility =
            if (composeControlsEnabled) View.GONE else View.VISIBLE
        findViewByTag<View>("reianix_more_button")?.visibility =
            if (composeControlsEnabled) View.GONE else View.VISIBLE
        // Never close the options menu just because the playback controls
        // auto-hide, or when a regular player interaction shows them again.
        syncComposePlayerUiState()
    }

    private fun setSeekScrubActive(active: Boolean) {
        if (active && (locked || errorVisible || inPictureInPicture ||
                sessionState != SessionState.ACTIVE || episodeChangePending)
        ) return
        if (!seekScrubVisibility.update(active)) return
        handler.removeCallbacks(controlsHider)
        // On release, start a complete new timeout instead of continuing the
        // timer that was active before the user touched the seekbar.
        if (sessionState == SessionState.ACTIVE && !locked && !errorVisible && !inPictureInPicture) {
            touchControls()
        }
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
        if (!seekScrubVisibility.active && ::player.isInitialized &&
            player.isPlaying && !errorVisible && autoHideTimeoutMs > 0L
        ) {
            handler.postDelayed(controlsHider, autoHideTimeoutMs)
        }
        syncComposePlayerUiState()
    }

    private fun scheduleControlsHide() {
        if (controlsVisible && !locked) touchControls()
    }

    private fun setLocked(value: Boolean, persist: Boolean = true) {
        if (value) seekScrubVisibility.update(false)
        locked = value
        if (persist) {
            persistCanonicalPlayerSetting("player.lock_mode", locked.toString())
        }
        findViewByTag<View>("reianix_gesture_layer")?.let {
            (it as? GestureLayer)?.cancelInteractions()
        }
        if (locked) {
            handler.removeCallbacks(controlsHider)
            setMorePanelVisible(false)
            findViewByTag<View>("reianix_back_button")?.visibility = View.GONE
            findViewByTag<View>("reianix_more_button")?.visibility = View.GONE
            controls.visibility = View.VISIBLE
            hideLegacyPrimaryControls()
            topBar.visibility = View.GONE
            centerControls.visibility = View.GONE
            bottomBar.visibility = View.GONE
            if (::feedback.isInitialized) feedback.visibility = View.GONE
            handler.removeCallbacks(lockAffordanceHider)
            handler.postDelayed(lockAffordanceHider, LOCK_AFFORDANCE_TIMEOUT_MS)
            controlsVisible = true
        } else {
            findViewByTag<View>("reianix_back_button")?.visibility = View.VISIBLE
            findViewByTag<View>("reianix_more_button")?.visibility = View.VISIBLE
            setControlsVisible(true)
        }
        updateLockUi()
        syncComposePlayerUiState()
    }

    private fun updateLockUi() {
        if (!::lockButton.isInitialized) return
        lockButton.text = if (locked) getString(R.string.native_unlock) else getString(R.string.ui_lock)
        lockButton.contentDescription = if (locked) getString(R.string.ui_unlock_controls) else getString(R.string.native_lock_controls)
    }

    private val playerSettingWriteLocks = ConcurrentHashMap<String, Mutex>()

    private fun persistCanonicalPlayerSetting(key: String, value: String): Boolean {
        if (activeAnimePreferences != null && key in setOf(
                "player.default_speed", "player.aspect_ratio", "player.autoplay_next",
                "player.zoom_enabled", "audio.subtitle_scale",
                "audio.subtitle_bottom_padding", "audio.subtitle_embedded_style",
                "audio.subtitle_color", "audio.subtitle_font",
                "audio.preferred_language", "audio.preferred_subtitle_language",
                "audio.subtitles",
            )
        ) {
            persistAnimePreferences()
            return true
        }
        val mutationRequestId = UUID.randomUUID().toString()
        val normalizedKey = key.trim()
        val normalizedValue = try {
            ReiAnixSettingsContract.normalize(normalizedKey, value)
        } catch (error: IllegalArgumentException) {
            logPlayer("PLAYER_SETTING_REJECTED key=" + normalizedKey, error)
            return false
        }
        lifecycleScope.launch(Dispatchers.IO) {
            playerSettingWriteLocks.computeIfAbsent(normalizedKey) { Mutex() }.withLock {
                try {
                    val persisted = ReiAnixSqliteSettingsRepository(applicationContext)
                        .setPreference(normalizedKey, normalizedValue)
                        .value
                    logPlayer(
                        "PLAYER_SETTING_PERSISTED key=" + normalizedKey +
                            " requestId=" + mutationRequestId +
                            " confirmed=true",
                    )
                    val published = publishLegacyPlayerEvent(
                        JSONObject()
                            .put("type", "compose_settings_set")
                            .put("requestId", mutationRequestId)
                            .put(
                                "payload",
                                JSONObject()
                                    .put("key", normalizedKey)
                                    .put("value", persisted)
                                    .put("requestId", mutationRequestId)
                                    .put("nativePersisted", true),
                            ),
                    )
                    logPlayer(
                        "PLAYER_SETTING_RUNTIME_NOTIFICATION key=" + normalizedKey +
                            " requestId=" + mutationRequestId +
                            " published=" + published,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logPlayer(
                        "PLAYER_SETTING_PERSIST_FAILED key=" + normalizedKey +
                            " requestId=" + mutationRequestId,
                        error,
                    )
                }
            }
        }
        logPlayer(
            "PLAYER_SETTING_PERSIST_REQUESTED key=" + normalizedKey +
                " requestId=" + mutationRequestId,
        )
        return true
    }

    private fun setSmartSkipEnabled(enabled: Boolean) {
        if (smartSkipEnabled == enabled) return
        smartSkipGeneration++ // Do not undo an explicit user tap with stale SQLite data.
        smartSkipEnabled = enabled
        if (!enabled) {
            skipLookupJob?.cancel()
            remoteSkipSegments = emptyList()
            skipLookupGeneration = -1L
            skipLookupDuration = -1L
        } else if (::player.isInitialized && player.duration > 0L) {
            requestSmartSkipSegments(player.duration)
        }
        persistCanonicalPlayerSetting("player.aniskip_enabled", enabled.toString())
        updateSmartSkipButton()
        syncComposePlayerUiState()
    }

    private fun currentAnimePreferencesKey(): String? =
        PlayerAnimePreferences.keyFor(intent.getStringExtra("animeId"))

    private fun currentTrackChoice(type: Int): PlayerAnimeTrackChoice {
        if (!::player.isInitialized) return PlayerAnimeTrackChoice()
        val params = player.trackSelectionParameters
        if (type in params.disabledTrackTypes) return PlayerAnimeTrackChoice(mode = "off")
        for (group in player.currentTracks.groups) {
            if (group.type != type || !group.isSupported) continue
            val override = params.overrides[group.mediaTrackGroup] ?: continue
            val index = override.trackIndices.firstOrNull { index ->
                index in 0 until group.length && group.isTrackSupported(index)
            } ?: continue
            val format = group.getTrackFormat(index)
            return PlayerAnimeTrackChoice(
                mode = "selected",
                language = format.language.orEmpty(),
                label = format.label.orEmpty(),
                mimeType = format.sampleMimeType.orEmpty(),
            )
        }
        return PlayerAnimeTrackChoice()
    }

    private fun snapshotCurrentAnimePreferences(): PlayerAnimePreferences {
        val previous = activeAnimePreferences
        return PlayerAnimePreferences(
            speed = if (::player.isInitialized) player.playbackParameters.speed else 1f,
            aspect = PlayerAspectMode.fromLabel(aspectModeLabel).setting,
            autoplay = autoplayNext,
            zoomEnabled = zoomEnabled,
            audioOnly = audioOnly,
            audioLanguage = preferredAudioLanguage,
            subtitleLanguage = preferredSubtitleLanguage,
            subtitleMode = subtitleMode,
            subtitleScale = subtitleScale,
            subtitlePadding = subtitleBottomPaddingPercent,
            subtitleEmbedded = subtitleEmbeddedStyle,
            subtitleColor = subtitleColor,
            subtitleFont = subtitleFont,
            audioTrack = previous?.audioTrack ?: PlayerAnimeTrackChoice(),
            subtitleTrack = previous?.subtitleTrack ?: PlayerAnimeTrackChoice(),
        )
    }

    private fun persistAnimePreferences() {
        val key = currentAnimePreferencesKey() ?: return
        if (activeAnimePreferences == null) return
        val snapshot = snapshotCurrentAnimePreferences()
        activeAnimePreferences = snapshot
        val version = ++animePreferenceWriteVersion
        lifecycleScope.launch(Dispatchers.IO) {
            animePreferencesMutex.withLock {
                if (version == animePreferenceWriteVersion) {
                    runCatching {
                        ReiAnixSqliteSettingsRepository(applicationContext)
                            .setPreference(key, snapshot.encode())
                    }.onFailure { logPlayer("ANIME_PREFERENCES_WRITE_FAILED", it) }
                }
            }
        }
        syncComposePlayerUiState()
    }

    private fun enableAnimePreferences() {
        if (currentAnimePreferencesKey() == null || favoriteClipSession != null) {
            showFeedback(getString(R.string.native_anime_required))
            return
        }
        // Prevent an older in-flight configuration read from overwriting the
        // opt-in choice made explicitly from the open menu.
        captionRevision++
        activeAnimePreferences = snapshotCurrentAnimePreferences().copy(
            // If the user enabled the anime profile AFTER choosing a track,
            // preserve that current explicit selection from the first save.
            audioTrack = currentTrackChoice(C.TRACK_TYPE_AUDIO),
            subtitleTrack = currentTrackChoice(C.TRACK_TYPE_TEXT),
        )
        activeAnimePreferencesKey = currentAnimePreferencesKey()
        persistAnimePreferences()
        showFeedback(getString(R.string.native_anime_prefs_on))
    }

    private fun resetAnimePreferences() {
        val key = currentAnimePreferencesKey() ?: return
        activeAnimePreferences = null
        activeAnimePreferencesKey = null
        captionRevision++
        val revision = captionRevision
        val version = ++animePreferenceWriteVersion
        syncComposePlayerUiState()
        lifecycleScope.launch {
            val values = withContext(Dispatchers.IO) {
                animePreferencesMutex.withLock {
                    runCatching {
                        val repository = ReiAnixSqliteSettingsRepository(applicationContext)
                        if (version == animePreferenceWriteVersion) repository.removePreference(key)
                        repository.allPreferences()
                    }.getOrDefault(emptyMap())
                }
            }
            if (revision == captionRevision && currentAnimePreferencesKey() == key &&
                sessionState == SessionState.ACTIVE
            ) {
                applyGlobalPlayerPreferences(values)
                showFeedback(getString(R.string.native_general_restored))
            }
        }
    }

    private fun applyGlobalPlayerPreferences(values: Map<String, String>) {
        if (!::player.isInitialized) return
        val setting: (String) -> String = { key ->
            values[key] ?: ReiAnixSettingsContract.defaultFor(key).orEmpty()
        }
        player.setPlaybackSpeed(
            PlayerGesturePolicy.normalizePlaybackSpeed(setting("player.default_speed").toFloatOrNull() ?: 1f),
        )
        autoplayNext = setting("player.autoplay_next") != "false"
        zoomEnabled = setting("player.zoom_enabled") != "false"
        val aspect = PlayerAspectMode.fromSetting(setting("player.aspect_ratio")).setting
        intent.putExtra("setting_player_aspect_ratio", aspect)
        playerView.resizeMode = resizeModeFromSetting(aspect)
        aspectModeLabel = aspectLabelFromSetting(aspect)
        preferredAudioLanguage = setting("audio.preferred_language")
        preferredSubtitleLanguage = setting("audio.preferred_subtitle_language")
        subtitleMode = setting("audio.subtitles").ifBlank { "auto" }
        subtitleScale = setting("audio.subtitle_scale").toFloatOrNull() ?: 1f
        subtitleBottomPaddingPercent = setting("audio.subtitle_bottom_padding").toIntOrNull() ?: 8
        subtitleEmbeddedStyle = setting("audio.subtitle_embedded_style") != "false"
        subtitleColor = setting("audio.subtitle_color").ifBlank { "system" }
        subtitleFont = setting("audio.subtitle_font").ifBlank { "system" }
        if (audioOnly) setAudioOnly(false)
        applyGlobalTrackPreferences()
        applySubtitlePreferences()
        syncComposePlayerUiState()
    }

    private fun applySavedAnimePreferences(preferences: PlayerAnimePreferences) {
        if (!::player.isInitialized || !::playerView.isInitialized) return
        player.setPlaybackSpeed(
            PlayerGesturePolicy.normalizePlaybackSpeed(preferences.speed),
        )
        autoplayNext = preferences.autoplay
        zoomEnabled = preferences.zoomEnabled
        preferredAudioLanguage = preferences.audioLanguage
        preferredSubtitleLanguage = preferences.subtitleLanguage
        subtitleMode = preferences.subtitleMode
        subtitleScale = preferences.subtitleScale
        subtitleBottomPaddingPercent = preferences.subtitlePadding
        subtitleEmbeddedStyle = preferences.subtitleEmbedded
        subtitleColor = preferences.subtitleColor
        subtitleFont = preferences.subtitleFont
        playerView.resizeMode = resizeModeFromSetting(preferences.aspect)
        aspectModeLabel = aspectLabelFromSetting(preferences.aspect)
        intent.putExtra("setting_player_aspect_ratio", preferences.aspect)
        applyGlobalTrackPreferences()
        applySavedAnimeTrackChoices()
        applySubtitlePreferences()
        // Changing the Media3 audio-only service is deferred until tracks
        // are available. Never switch playback into a failed state on start.
        if (preferences.audioOnly != audioOnly &&
            supportedTrackCount(C.TRACK_TYPE_AUDIO) > 0
        ) setAudioOnly(preferences.audioOnly)
        syncComposePlayerUiState()
    }

    private fun applySavedAnimeTrackChoices() {
        val preferences = activeAnimePreferences ?: return
        if (!::player.isInitialized) return
        val current = player.trackSelectionParameters
        val builder = current.buildUpon()
        fun applyTrack(type: Int, choice: PlayerAnimeTrackChoice) {
            builder.clearOverridesOfType(type)
            val disabled = choice.mode == "off" ||
                (type == C.TRACK_TYPE_TEXT && preferences.subtitleMode == "never")
            builder.setTrackTypeDisabled(type, disabled)
            if (disabled || choice.mode != "selected") return
            val candidates = player.currentTracks.groups
                .filter { it.type == type && it.isSupported }
                .flatMap { group ->
                    (0 until group.length)
                        .filter { group.isTrackSupported(it) }
                        .map { index -> group to index }
                }
            val match = candidates.firstOrNull { (group, index) ->
                val format = group.getTrackFormat(index)
                choice.label.isNotBlank() && choice.label.equals(format.label.orEmpty(), true) &&
                    (choice.language.isBlank() || choice.language.equals(format.language.orEmpty(), true)) &&
                    (choice.mimeType.isBlank() || choice.mimeType == format.sampleMimeType)
            } ?: candidates.firstOrNull { (group, index) ->
                val format = group.getTrackFormat(index)
                choice.language.isNotBlank() && choice.language.equals(format.language.orEmpty(), true) &&
                    (choice.mimeType.isBlank() || choice.mimeType == format.sampleMimeType)
            }
            // No matching track in this episode: the preferred language or
            // the Media3 automatic selector remains the safe fallback.
            if (match != null) {
                builder.setOverrideForType(TrackSelectionOverride(match.first.mediaTrackGroup, match.second))
            }
        }
        applyTrack(C.TRACK_TYPE_AUDIO, preferences.audioTrack)
        applyTrack(C.TRACK_TYPE_TEXT, preferences.subtitleTrack)
        val selected = builder.build()
        if (selected != current) player.trackSelectionParameters = selected
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
            showFeedback(getString(R.string.native_brightness, percent), 900L)
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
            showFeedback(getString(R.string.native_volume, percent), 900L)
        }.onFailure { error ->
            logPlayer("GESTURE_VOLUME_IGNORED", error)
        }
    }

    private fun retryCurrentMedia() {
        if (favoriteClipSession != null) { restoreOriginalEpisode(); return }
        if (!::player.isInitialized) {
            logPlayer("PLAYER_RETRY_UNAVAILABLE requestId=" + requestId.ifEmpty { "-" })
            return
        }
        if (!currentFailureRetryable) {
            logPlayer(
                "PLAYER_RETRY_REJECTED requestId=" + requestId.ifEmpty { "-" } +
                    " kind=" + currentFailureKind.name,
            )
            showFeedback(getString(R.string.native_retry_unavailable), 1600L)
            return
        }
        if (retryCount >= MAX_RETRY_ATTEMPTS) {
            showFeedback(getString(R.string.native_retry_limit), 1400L)
            return
        }

        retryCount += 1
        errorVisible = false
        playbackErrorForGeneration = false
        setMorePanelVisible(false)
        findViewByTag<View>("reianix_error_panel")?.visibility = View.GONE
        startLoadingIndicator()
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

        }
        invalidateTransition("player_error")
        // A fatal playback error takes precedence over the floating menu.
        setMorePanelVisible(false)
        setLocked(false, persist = true)
        errorVisible = true
        finishLoadingIndicator()
        if (::player.isInitialized) player.pause()
        setControlsVisible(true)
        findViewByTag<View>("reianix_error_text")?.let { (it as TextView).text = userMessageForFailure(resolvedFailureKind, fallback = message) }
        findViewByTag<View>("reianix_error_reason")?.let {
            (it as TextView).text = getString(R.string.native_detail, userFailureDetailForFailure(resolvedFailureKind))
        }
        findViewByTag<View>("reianix_error_retry")?.visibility =
            if (::player.isInitialized && resolvedRetryable) View.VISIBLE else View.GONE
        findViewByTag<View>("reianix_error_panel")?.visibility = View.VISIBLE
        findViewByTag<View>("reianix_error_panel")?.bringToFront()
        findViewByTag<View>("reianix_error_back")?.requestFocus()
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
        PlayerMediaPolicy.PlaybackFailureKind.MEDIA_NOT_FOUND -> getString(R.string.native_failure_missing)
        PlayerMediaPolicy.PlaybackFailureKind.PERMISSION -> getString(R.string.native_failure_permission)
        PlayerMediaPolicy.PlaybackFailureKind.SOURCE -> getString(R.string.native_failure_source)
        PlayerMediaPolicy.PlaybackFailureKind.PARSER -> getString(R.string.native_failure_parser)
        PlayerMediaPolicy.PlaybackFailureKind.DECODER -> getString(R.string.native_failure_decoder)
        PlayerMediaPolicy.PlaybackFailureKind.CODEC -> getString(R.string.native_failure_codec)
        PlayerMediaPolicy.PlaybackFailureKind.RENDERER -> getString(R.string.native_failure_renderer)
        PlayerMediaPolicy.PlaybackFailureKind.TIMEOUT -> getString(R.string.native_failure_timeout)
        PlayerMediaPolicy.PlaybackFailureKind.LIFECYCLE -> getString(R.string.native_failure_lifecycle)
        PlayerMediaPolicy.PlaybackFailureKind.STALE -> getString(R.string.native_failure_stale)
        PlayerMediaPolicy.PlaybackFailureKind.UNKNOWN -> getString(R.string.native_failure_unknown)
    }

    private fun userMessageForFailure(
        kind: PlayerMediaPolicy.PlaybackFailureKind,
        fallback: String? = null,
    ): String = when (kind) {
        PlayerMediaPolicy.PlaybackFailureKind.MEDIA_NOT_FOUND ->
            getString(R.string.native_message_missing)
        PlayerMediaPolicy.PlaybackFailureKind.PERMISSION ->
            getString(R.string.native_message_permission)
        PlayerMediaPolicy.PlaybackFailureKind.SOURCE ->
            getString(R.string.native_message_source)
        PlayerMediaPolicy.PlaybackFailureKind.PARSER ->
            getString(R.string.native_message_parser)
        PlayerMediaPolicy.PlaybackFailureKind.DECODER ->
            getString(R.string.native_message_decoder)
        PlayerMediaPolicy.PlaybackFailureKind.CODEC ->
            getString(R.string.native_message_codec)
        PlayerMediaPolicy.PlaybackFailureKind.RENDERER ->
            getString(R.string.native_message_renderer)
        PlayerMediaPolicy.PlaybackFailureKind.TIMEOUT ->
            getString(R.string.native_message_timeout)
        PlayerMediaPolicy.PlaybackFailureKind.LIFECYCLE,
        PlayerMediaPolicy.PlaybackFailureKind.STALE ->
            fallback ?: getString(R.string.native_message_session)
        PlayerMediaPolicy.PlaybackFailureKind.UNKNOWN ->
            fallback ?: getString(R.string.native_message_unknown)
    }

    private fun publishPlayerError(message: String, payload: JSONObject = JSONObject()) {
        if (errorPublishedForGeneration) return
        errorPublishedForGeneration = true
        val ok = publishLegacyPlayerEvent(
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
        val original = favoriteClipSession
        val rawDuration = original?.originalDurationMs ?: if (::player.isInitialized) player.duration else 0L
        val currentDuration = if (rawDuration > 0L) rawDuration else 0L
        val rawPosition = original?.originalPositionMs ?: if (::player.isInitialized) player.currentPosition.coerceAtLeast(0L) else 0L
        val currentPosition = if (currentDuration > 0L) rawPosition.coerceAtMost(currentDuration) else rawPosition
        val exitCapturedAt = nextPlayerEventCreatedAt()
        val payload = JSONObject()
            .put("uri", if (::uri.isInitialized) uri.toString() else intent.getStringExtra("uri").orEmpty())
            .put("mediaId", currentMediaId())
            .put("episodeId", currentEpisodeId())
            .put("animeId", intent.getStringExtra("animeId").orEmpty())
            .put("positionMs", currentPosition)
            .put("durationMs", currentDuration)
            .put("completion", original?.originalCompletion ?: completionReported)
            .put("reason", reason)
            .put("classification", exitClassification)
            .put("traceId", traceId)
            .put("timestamp", exitCapturedAt)
            .put("playerSessionId", playerSessionId)
            .put("activityInstanceId", activityInstanceId)
            .put("playerGeneration", playerGeneration)
            .put("transitionGeneration", transitionGeneration)
            .put("originTransitionGeneration", originTransitionGeneration)
            .put("originPlayerSessionId", originPlayerSessionId)
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
            // Serialize this terminal checkpoint behind periodic/pause checkpoints.
            // Publish the event only after the canonical native write was attempted.
            progressWorker.submit {
                val canonicalEpisodeId = payload.optString("episodeId").toLongOrNull()
                val exitPositionMs = payload.optLong("positionMs", -1L)
                val exitDurationMs = payload.optLong("durationMs", 0L)
                var persistedNatively = false
                if (canonicalEpisodeId != null && canonicalEpisodeId > 0L && exitPositionMs >= 0L) {
                    try {
                        kotlinx.coroutines.runBlocking {
                            consumptionService.saveProgress(
                                episodeId = canonicalEpisodeId,
                                positionMs = exitPositionMs,
                                durationMs = exitDurationMs.takeIf { it > 0L },
                                playedAtEpochSeconds = exitCapturedAt / 1000.0,
                                completed = payload.optBoolean("completion", false),
                            )
                        }
                        persistedNatively = true
                        payload.put("nativeProgressPersisted", true)
                        com.reianix.reianix_local.data.sqlite.ReiAnixConsumptionService.publishCommittedProgress(
                            episodeId = canonicalEpisodeId,
                            eventType = "player_exited",
                            playerSessionId = playerSessionId,
                            playerGeneration = playerGeneration,
                            transitionGeneration = transitionGeneration,
                            eventCreatedAtEpochMs = exitCapturedAt,
                        )
                    } catch (error: Exception) {
                        logPlayer(
                            "NATIVE_EXIT_PROGRESS_PERSIST_FAILED episodeId=" + canonicalEpisodeId +
                                " playerSessionId=" + playerSessionId,
                            error,
                        )
                    }
                }
                // Compose observes the committed progress through PlaybackChange and
                // rereads canonical SQLite. The durable Python mailbox is legacy-only here.
                if (!intent.getBooleanExtra("nativeCompose", false)) {
                    val ok = NativeMailbox.write(this@NativePlayerActivity, exitEvent)
                    if (!ok) {
                        logPlayer(
                            "FAILED_TO_PUBLISH player_exited requestId=" + requestId.ifEmpty { "-" } +
                                " nativeProgressPersisted=" + persistedNatively,
                        )
                    }
                } else if (!persistedNatively) {
                    logPlayer(
                        "NATIVE_COMPOSE_EXIT_PROGRESS_NOT_PERSISTED episodeId=" +
                            (canonicalEpisodeId?.toString() ?: "-") +
                            " requestId=" + requestId.ifEmpty { "-" },
                    )
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
        if (favoriteRestoreWaitingForFrame && reason in setOf("back_button", "user_button", "android_back")) return
        if (inFavoriteClip && reason in setOf("back_button", "user_button", "android_back", "player_error_back")) {
            exitFavoriteClip()
            return
        }
        // A player Back/finish request is a single terminal transition. Ignore
        // re-entrant requests while EXITING or after destruction so one Android
        // Back event can never enqueue two exits or two Activity finishes.
        if (sessionState == SessionState.EXITING || sessionState == SessionState.DESTROYED) return
        val exitClassification = classifyPlayerExit(reason)
        logPlayer(
            "PLAYER_FINISH_REQUEST reason=" + reason +
                " classification=" + exitClassification +
                " requestId=" + requestId.ifEmpty { "-" } +
                " activityInstanceId=" + activityInstanceId +
                " playerSessionId=" + playerSessionId,
        )
        sessionState = SessionState.EXITING

        invalidateTransition("finish_player")
        pendingPreparation?.cancel(true)

        findViewByTag<GestureLayer>("reianix_gesture_layer")?.cancelInteractions()
        handler.removeCallbacks(controlsHider)
        handler.removeCallbacks(lockAffordanceHider)
        handler.removeCallbacks(feedbackHider)
        restoreSystemUiBeforeExit()

        reportPlayerExit(reason)
        setResult(
            RESULT_OK,
            Intent()
                .putExtra("requestId", requestId)
                .putExtra("reason", reason)
                .putExtra("activityInstanceId", activityInstanceId),
        )
        val animateReturn = !isInPictureInPictureMode
        if (animateReturn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                R.anim.reianix_player_open_enter,
                R.anim.reianix_player_open_exit,
            )
        }
        finish()
        if (animateReturn && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(
                R.anim.reianix_player_open_enter,
                R.anim.reianix_player_open_exit,
            )
        }
    }

    private fun updateEpisodeNavigationButtons() {
        val enabled = favoriteClipSession == null && !episodeChangePending && !errorVisible
        findViewByTag<TextView>("reianix_next_episode")?.isEnabled =
            enabled && intent.getBooleanExtra("canNext", false)
        findViewByTag<TextView>("reianix_previous_episode")?.isEnabled =
            enabled && intent.getBooleanExtra("canPrevious", false)
        syncComposePlayerUiState()
    }





    private fun invalidateTransition(reason: String) {
        val wasNext = nextTransitionActive
        val wasPrevious = previousTransitionActive
        val staleAgeMs = transitionStartedAtMs.takeIf { it > 0L }?.let { System.currentTimeMillis() - it } ?: 0L
        transitionGeneration += 1L
        transitionPublishFuture?.cancel(true)
        transitionPublishFuture = null
        handler.removeCallbacks(episodeChangeTimeout)
        if (wasNext) {


        }
        if (wasPrevious) {


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

    private fun requestEpisode(eventType: String, selectedEpisodeId: Long? = null) {
        if (favoriteClipSession != null) return
        if (expireSleepTimerIfDue()) return
        val isNextRequest = eventType == "player_next_request"
        val isPreviousRequest = eventType == "player_previous_request"
        if (!isNextRequest && !isPreviousRequest) return
        fun requestEvent(nextEvent: String, previousEvent: String): String =
            if (isNextRequest) nextEvent else previousEvent

        if (!::player.isInitialized) {

            return
        }
        if (sessionState != SessionState.ACTIVE) {

            return
        }
        if (episodeChangePending) {


            return
        }
        if (errorVisible) {

            return
        }
        val canNavigate = intent.getBooleanExtra(if (isNextRequest) "canNext" else "canPrevious", false)
        if (!canNavigate && selectedEpisodeId == null) {

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
        if (::relativeSeekOverlay.isInitialized) relativeSeekOverlay.reset()
        setTransitionPhase(TransitionPhase.REQUESTED, "request_accepted")
        nextTransitionActive = isNextRequest
        previousTransitionActive = isPreviousRequest
        transitionReadyGeneration = -1L


        handler.removeCallbacks(episodeChangeTimeout)
        armEpisodeChangeTimeout("request_episode")
        updateEpisodeNavigationButtons()

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
            // Carry the effective session settings with the transition. The
            // SettingsStore remains the durable source of truth, while this
            // handoff removes the race where Python reads the old value before
            // the canonical mailbox mutation is consumed.
            .put("playerSettings", currentPlayerSettingsSnapshot())

        if (commandSequence < lastPlayerCommandSequence) {
            true
        }
        lastPlayerCommandSequence = commandSequence

        // The Compose/native library has no embedded Python mailbox consumer.
        // Resolve its adjacent episode directly through the canonical SQLite
        // repository; keep the mailbox bridge untouched for the desktop/Flet path.
        if (intent.getBooleanExtra("nativeCompose", false)) {
            dispatchNativeEpisodeTransition(
                isNextRequest = isNextRequest,
                generation = generation,
                sourceRequestId = requestId,
                sourceUri = uri.toString(),
                sourcePlayerSessionId = playerSessionId,
                startedAtMs = startedAtMs,
                monotonicNs = monotonicNs,
                playerSettings = payload.getJSONObject("playerSettings"),
                selectedEpisodeId = selectedEpisodeId,
            )
            return
        }



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

                            showFeedback(getString(R.string.native_episode_change_failed), 1800L)
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

                        episodeChangePending = false
                        invalidateTransition("mailbox_publish_exception")
                    }
                }
            }
        } catch (error: Exception) {

            episodeChangePending = false
            invalidateTransition("executor_submit_failed")
        }
    }

    /**
     * Native Compose Next/Previous implementation. All catalog reads use the existing
     * canonical SQLite repository; the Activity remains the same player session and
     * its onNewIntent() transition-origin gate still validates the successor request.
     *
     * The legacy Flet/Desktop path deliberately continues through NativeMailbox below.
     */
    private fun dispatchNativeEpisodeTransition(
        isNextRequest: Boolean,
        generation: Long,
        sourceRequestId: String,
        sourceUri: String,
        sourcePlayerSessionId: String,
        startedAtMs: Long,
        monotonicNs: Long,
        playerSettings: JSONObject,
        selectedEpisodeId: Long? = null,
    ) {
        val direction = if (isNextRequest) "NEXT" else "PREVIOUS"
        lifecycleScope.launch {
            try {
                if (!isCurrentTransition(
                        generation = generation,
                        expectedRequestId = sourceRequestId,
                        expectedUri = sourceUri,
                        expectedSessionId = sourcePlayerSessionId,
                    )
                ) return@launch

                val episodeId = currentEpisodeId().toLongOrNull()
                    ?: throw IllegalStateException("O episódio atual não possui um ID local válido.")
                val animeId = intent.getStringExtra("animeId")?.trim()?.toLongOrNull()
                    ?: throw IllegalStateException("O anime atual não possui um ID local válido.")
                val dependencies = ReiAnixAppDependencies.from(applicationContext)
                val navigation = withContext(Dispatchers.IO) {
                    dependencies.sqliteLibraryRepository.playerNavigation(episodeId)
                } ?: throw NoSuchElementException("O episódio atual não existe no catálogo local.")

                // Match main.py's sqlite_current_row_mismatch guard. Resolve the active
                // media URI back to the canonical row instead of trusting episodeId alone:
                // a file may have been moved/replaced while this player session stayed open.
                val sourceValidation = validateNativePlayerTransitionSource(
                    dependencies = dependencies,
                    sourceUri = sourceUri,
                    expectedEpisodeId = episodeId,
                    expectedAnimeId = animeId,
                )
                // The canonical lookup suspends. Do not let an old completion invalidate
                // a newer transition that replaced this request while SQLite was queried.
                if (!isCurrentTransition(
                        generation = generation,
                        expectedRequestId = sourceRequestId,
                        expectedUri = sourceUri,
                        expectedSessionId = sourcePlayerSessionId,
                    )
                ) return@launch
                if (
                    !sourceValidation.isCurrent ||
                    navigation.current.id != episodeId ||
                    navigation.current.animeId != animeId
                ) {
                    val reason = sourceValidation.reason ?: "sqlite_current_row_mismatch"


                    invalidateTransition(reason)
                    return@launch
                }
                if (!isCurrentTransition(
                        generation = generation,
                        expectedRequestId = sourceRequestId,
                        expectedUri = sourceUri,
                        expectedSessionId = sourcePlayerSessionId,
                    )
                ) return@launch

                val selectedNavigation = selectedEpisodeId?.let {
                    dependencies.sqliteLibraryRepository.playerNavigation(it)
                }
                val target = if (selectedEpisodeId != null) selectedNavigation?.current
                    else if (isNextRequest) navigation.next else navigation.previous
                if (target == null) {
                    // Preserve the legacy NEXT/PREVIOUS_TRANSITION_FAILED signal before
                    // invalidating the in-flight request, so diagnostics distinguish
                    // "no adjacent item" from a successful transition that was cancelled.

                    invalidateTransition("no_adjacent_episode")
                    showFeedback(getString(R.string.native_no_next_local), 2_200L)
                    return@launch
                }
                require(target.id > 0L && target.animeId == animeId) {
                    "O episódio vizinho não pertence à série atual."
                }
                require(target.missing != true && target.path.isNotBlank()) {
                    "O episódio vizinho não possui uma mídia local disponível."
                }

                val persistedPreferences = withContext(Dispatchers.IO) {
                    dependencies.sqliteSettingsRepository.allPreferences()
                }
                fun preferenceValue(key: String): String =
                    persistedPreferences[key] ?: ReiAnixSettingsContract.defaultFor(key).orEmpty()

                val resumeEnabled = preferenceValue("player.resume")
                    .toBooleanStrictOrNull() ?: true
                val targetPositionMs = resolveNativePlaybackStartPosition(
                    progressSeconds = target.progress,
                    durationSeconds = target.duration,
                    watched = target.watched,
                    resumeEnabled = resumeEnabled,
                ).positionMsOverride

                val rawTarget = target.path.trim()
                val parsedTarget = Uri.parse(rawTarget)
                val targetUri = when (parsedTarget.scheme?.lowercase(Locale.ROOT)) {
                    "content", "file" -> parsedTarget
                    null -> {
                        require(rawTarget.startsWith("/")) {
                            "A referência do episódio vizinho não é uma URI local autorizada."
                        }
                        Uri.fromFile(File(rawTarget))
                    }
                    else -> throw IllegalArgumentException(
                        "O ReiAnix aceita somente mídias locais autorizadas.",
                    )
                }
                val targetTitle = target.episodeTitle?.trim()?.takeIf { it.isNotEmpty() }
                    ?: target.fileName.trim().takeIf { it.isNotEmpty() }
                    ?: "Episódio local"
                val destinationCanNext = if (selectedNavigation != null) {
                    selectedNavigation.next != null
                } else if (isNextRequest) {
                    navigation.nextCanNext
                } else {
                    navigation.previousCanNext
                }
                val destinationCanPrevious = if (selectedNavigation != null) {
                    selectedNavigation.previous != null
                } else if (isNextRequest) {
                    navigation.nextCanPrevious
                } else {
                    navigation.previousCanPrevious
                }
                val createdAtMs = System.currentTimeMillis()
                val successorRequestId = UUID.randomUUID().toString()
                val successorUri = Uri.parse("reianix://native").buildUpon()
                    .appendQueryParameter("action", "play")
                    .appendQueryParameter("request_id", successorRequestId)
                    .appendQueryParameter("protocol_version", "2")
                    .appendQueryParameter("created_at", createdAtMs.toString())
                    .appendQueryParameter("uri", targetUri.toString())
                    .appendQueryParameter("episode_id", target.id.toString())
                    .appendQueryParameter("anime_id", target.animeId.toString())
                    .appendQueryParameter("player_session_id", sourcePlayerSessionId)
                    .appendQueryParameter("title", targetTitle)
                    .appendQueryParameter("can_next", destinationCanNext.toString())
                    .appendQueryParameter("can_previous", destinationCanPrevious.toString())
                    .appendQueryParameter(
                        "autoplay",
                        playerSettings.opt("player.autoplay_next")?.toString()?.takeIf { it.isNotBlank() }
                            ?: "true",
                    )
                    .appendQueryParameter("native_compose", "true")
                    .appendQueryParameter("origin_request_id", sourceRequestId)
                    .appendQueryParameter("origin_created_at", startedAtMs.toString())
                    .appendQueryParameter("origin_transition_generation", generation.toString())
                    .appendQueryParameter("origin_player_session_id", sourcePlayerSessionId)
                    .appendQueryParameter("origin_monotonic_ns", monotonicNs.toString())
                    .appendQueryParameter("transition_direction", direction)
                if (targetPositionMs != null) {
                    successorUri.appendQueryParameter("position_ms", targetPositionMs.toString())
                }

                val settingsParameters = listOf(
                    "player.default_speed" to "setting_player_default_speed",
                    "player.aspect_ratio" to "setting_player_aspect_ratio",
                    "player.zoom_enabled" to "setting_player_zoom_enabled",
                    "player.immersive" to "setting_player_immersive",
                    "player.rotation" to "setting_player_rotation",
                    "player.lock_mode" to "setting_player_lock_mode",
                    "player.auto_hide_seconds" to "setting_player_auto_hide_seconds",
                    "player.double_tap_seek_seconds" to "setting_player_double_tap_seek_seconds",
                    "player.long_press_speed" to "setting_player_long_press_speed",
                    "player.max_video_resolution" to "setting_player_max_video_resolution",
                    "player.max_video_frame_rate" to "setting_player_max_video_frame_rate",
                    "player.max_audio_channels" to "setting_player_max_audio_channels",
                    "player.pip" to "setting_player_pip",
                    "gestures.volume" to "setting_gestures_volume",
                    "gestures.brightness" to "setting_gestures_brightness",
                    "gestures.horizontal_seek" to "setting_gestures_horizontal_seek",
                    "gestures.double_tap" to "setting_gestures_double_tap",
                    "gestures.long_press" to "setting_gestures_long_press",
                    "audio.preferred_language" to "setting_audio_preferred_language",
                    "audio.preferred_subtitle_language" to "setting_audio_preferred_subtitle_language",
                    "audio.subtitles" to "setting_audio_subtitles",
                    "audio.subtitle_scale" to "setting_audio_subtitle_scale",
                    "audio.subtitle_bottom_padding" to "setting_audio_subtitle_bottom_padding",
                    "audio.subtitle_embedded_style" to "setting_audio_subtitle_embedded_style",
                )
                settingsParameters.forEach { (key, parameter) ->
                    val value = playerSettings.opt(key)
                    if (value != null && value != JSONObject.NULL) {
                        successorUri.appendQueryParameter(parameter, value.toString())
                    }
                }

                val request = NativePlayerRequest.fromBridgeUri(successorUri.build())
                val handoffDispatchedAtMs = System.currentTimeMillis()
                val successorIntent = request.toIntent(this@NativePlayerActivity, targetUri)
                    .putExtra("nativeCompose", true)
                    .putExtra("episodeTitle", targetTitle)
                    .putExtra("commandReceivedAtMs", startedAtMs)
                    .putExtra("handoffDispatchedAtMs", handoffDispatchedAtMs)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                target.number?.takeIf { it.isFinite() }?.let { number ->
                    val label = if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
                    successorIntent.putExtra("episodeNumber", label)
                }

                if (!isCurrentTransition(
                        generation = generation,
                        expectedRequestId = sourceRequestId,
                        expectedUri = sourceUri,
                        expectedSessionId = sourcePlayerSessionId,
                    )
                ) return@launch

                logPlayer(
                    "NATIVE_PLAYER_TRANSITION_DISPATCH direction=$direction" +
                        " requestId=$successorRequestId originRequestId=$sourceRequestId" +
                        " episodeId=${target.id} transitionGeneration=$generation",
                )
                startActivity(successorIntent)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val errorMessage = error.message?.takeIf { it.isNotBlank() }
                    ?: error::class.java.simpleName
                Log.e(
                    "NativePlayerActivity",
                    "Native adjacent episode transition failed requestId=$sourceRequestId direction=$direction",
                    error,
                )

                if (isCurrentTransition(
                        generation = generation,
                        expectedRequestId = sourceRequestId,
                        expectedUri = sourceUri,
                        expectedSessionId = sourcePlayerSessionId,
                    )
                ) {

                    invalidateTransition("native_adjacent_transition_failed")
                    showFeedback(
                        "Não foi possível abrir o episódio local.",
                        2_600L,
                    )
                }
            }
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
        if (favoriteClipSession != null || !::player.isInitialized) return null
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
                    .put("playerGeneration", playerGeneration)
                    .put("transitionGeneration", transitionGeneration)
                    .put("originTransitionGeneration", originTransitionGeneration)
                    .put("originPlayerSessionId", originPlayerSessionId)
            )
    }

    private fun saveProgress(
        eventType: String,
        force: Boolean = false,
        notifyUiAfterCommit: Boolean = false,
    ) {
        val event = buildProgressEvent(eventType, force) ?: return
        val durable = force || eventType in setOf("player_paused", "player_completed", "player_exited")
        try {
            progressWorker.submit {
                val payload = event.optJSONObject("payload")
                val episodeId = payload?.optString("episodeId").orEmpty().toLongOrNull()
                val positionMs = payload?.optLong("positionMs", -1L) ?: -1L
                val durationMs = payload?.optLong("durationMs", 0L) ?: 0L
                var persistedNatively = false
                if (episodeId != null && episodeId > 0L && positionMs >= 0L) {
                    try {
                        // The event snapshot is created on the current Media3 generation;
                        // its canonical episode ID is the only write key.
                        kotlinx.coroutines.runBlocking {
                            consumptionService.saveProgress(
                                episodeId = episodeId,
                                positionMs = positionMs,
                                durationMs = durationMs.takeIf { it > 0L },
                                playedAtEpochSeconds = event.optLong("createdAt", 0L)
                                    .takeIf { it > 0L }?.div(1000.0)
                                    ?: System.currentTimeMillis() / 1000.0,
                                completed = eventType == "player_completed",
                            )
                        }
                        persistedNatively = true
                        // A failed or rejected write must not suppress the next retry.
                        // An old episode's asynchronous receipt cannot move a new session.
                        val committedSession = payload?.optString("playerSessionId").orEmpty()
                        val committedGeneration = payload?.optLong("playerGeneration", -1L) ?: -1L
                        handler.post {
                            if (playerSessionId == committedSession && playerGeneration == committedGeneration) {
                                lastSavedPosition = positionMs
                            }
                        }
                    } catch (error: Exception) {
                        logPlayer(
                            "NATIVE_PROGRESS_PERSIST_FAILED event=" + eventType +
                                " episodeId=" + episodeId +
                                " playerSessionId=" + payload?.optString("playerSessionId").orEmpty(),
                            error,
                        )
                    }
                }
                // Exactly one writer per event: Python validates the same event and
                // publishes UI state, but skips the mutation after a native commit.
                if (persistedNatively) {
                    // The opt-in account queue is independent from playback and the SQLite writer.
                    // Its coroutine owns all remote access and catches its own failures.
                    if (episodeId != null) runCatching {
                        ReiAnixAppDependencies.from(applicationContext).aniListAccountService
                            .offerProgress(episodeId, positionMs, durationMs)
                    }
                    payload?.put("nativeProgressPersisted", true)
                    if (episodeId != null &&
                        (notifyUiAfterCommit || eventType in setOf(
                            "player_paused",
                            "player_completed",
                            "player_exited",
                        ))
                    ) {
                        com.reianix.reianix_local.data.sqlite.ReiAnixConsumptionService.publishCommittedProgress(
                            episodeId = episodeId,
                            eventType = eventType,
                            playerSessionId = payload?.optString("playerSessionId").orEmpty(),
                            playerGeneration = payload?.optLong("playerGeneration", playerGeneration)
                                ?: playerGeneration,
                            transitionGeneration = payload?.optLong("transitionGeneration", transitionGeneration)
                                ?: transitionGeneration,
                            eventCreatedAtEpochMs = event.optLong("createdAt", 0L),
                        )
                    }
                }
                // Compose sessions persist and notify the native ViewModel directly.
                // Do not enqueue the same checkpoint for a Python consumer that is absent
                // from the native APK. Preserve the mailbox contract for legacy sessions.
                if (!intent.getBooleanExtra("nativeCompose", false)) {
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
                } else if (!persistedNatively) {
                    logPlayer(
                        "NATIVE_COMPOSE_PROGRESS_NOT_PERSISTED event=" + eventType +
                            " episodeId=" + (episodeId?.toString() ?: "-") +
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

        logPlayer("onStart requestId=" + requestId.ifEmpty { "-" })
        if (!inPictureInPicture) applyImmersiveAfterLayout()
    }

    override fun onResume() {
        super.onResume()
        expireSleepTimerIfDue()
        if (!isPictureInPictureActive()) pictureInPictureSession = false

        logPlayer("onResume requestId=" + requestId.ifEmpty { "-" })
        if (!inPictureInPicture) applyImmersiveAfterLayout()
        findViewByTag<GestureLayer>("reianix_gesture_layer")?.refreshZoomForLayout()
        if (::player.isInitialized && !errorVisible && !playbackErrorForGeneration) {
            if (!sleepTimerPaused && !isPictureInPictureActive() && playbackWasRequestedBeforeStop &&
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

            }
        }
    }

    override fun onPause() {
        if (::relativeSeekOverlay.isInitialized) relativeSeekOverlay.reset()
        dismissFavoriteSceneUi()


        if (sessionState != SessionState.EXITING || !exitProgressPublished) {
            saveProgress("player_paused", force = true)
        }
        logPlayer("onPause requestId=" + requestId.ifEmpty { "-" })
        super.onPause()
    }

    override fun onStop() {
        seekScrubVisibility.update(false)
        handler.removeCallbacks(controlsHider)
        closeEpisodePlaylist()

        findViewByTag<GestureLayer>("reianix_gesture_layer")?.cancelInteractions()
        val screenInteractive = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
        val screenLocked = (getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked
        if (pictureInPictureSession && !isChangingConfigurations && screenInteractive && !screenLocked) {
            // A visible PiP is PAUSED, not STOPPED. Android can retain the
            // dismissed Activity without destroying it, leaving its session
            // registered and causing MainActivity to reject the next handoff.
            // Use the normal exit path now, including progress and identity
            // invalidation, rather than waiting for a possibly delayed destroy.
            finishPlayer("pip_dismissed")
        }
        if (isFinishing && !isChangingConfigurations) {
            invalidateTransition("onStop_finishing")
        }
        if (::player.isInitialized && !isPictureInPictureActive() && sessionState == SessionState.ACTIVE &&
            !(audioOnly && !isFinishing)
        ) {
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
            // Playback can continue between onPause and onStop (especially PiP).
            // Capture that final position even when it is below the periodic threshold.
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
            seekScrubVisibility.update(false)
            pictureInPictureSession = true
            handler.removeCallbacks(controlsHider)
            findViewByTag<GestureLayer>("reianix_gesture_layer")?.cancelInteractions()
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
        updateMorePanelBounds()
        logPlayer("onConfigurationChanged orientation=" + newConfig.orientation)
        // GestureLayer can still contain a pre-rotation TextureView scale or a
        // cancelled pinch. Keeping that transform while the viewport changes
        // produces the small centered video shown after portrait -> landscape.
        findViewByTag<GestureLayer>("reianix_gesture_layer")?.resetZoomForViewportChange()
        playerView.requestLayout()
        playerView.videoSurfaceView?.requestLayout()
        root.requestLayout()
        ViewCompat.requestApplyInsets(root)
        applyImmersiveAfterLayout()
        root.post {
            if (!isFinishing && ::playerView.isInitialized) {
                findViewByTag<GestureLayer>("reianix_gesture_layer")?.refreshZoomForLayout()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("sleep_timer_deadline_ms", sleepTimerDeadlineMs)
        outState.putBoolean("sleep_timer_paused", sleepTimerPaused)
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
            outState.putLong("position_ms", favoriteClipSession?.originalPositionMs ?: player.currentPosition.coerceAtLeast(0L))
            outState.putLong("duration_ms", favoriteClipSession?.originalDurationMs ?: player.duration.coerceAtLeast(0L))
            outState.putFloat("playback_speed", player.playbackParameters.speed)
            outState.putBoolean("play_when_ready", favoriteClipSession == null && player.playWhenReady)
            outState.putBundle("track_selection_parameters", (favoriteClipSession?.originalTracks ?: player.trackSelectionParameters).toBundle())
        }
        if (::playerView.isInitialized) {
            outState.putInt("resize_mode", playerView.resizeMode)
        }
        outState.putBoolean("autoplay_next", autoplayNext)
        outState.putBoolean("lock_mode", locked)
        outState.putBoolean("controls_visible", controlsVisible)
        outState.putFloat("window_brightness", window.attributes.screenBrightness)
        outState.putString("aspect_mode_label", aspectModeLabel)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        val motion = playerControlsMotion
        playerControlsMotion = null
        motion?.cancel()
        handler.removeCallbacks(showRebufferingIndicator)
        tvPicker?.close()
        tvPicker = null
        dismissFavoriteSceneUi()
        handler.removeCallbacks(sleepTimerTick)
        sleepTimerDialog?.dismiss()
        sleepTimerDialog = null

        val shouldReportExit = isFinishing && !suppressExitEvent && !exitReported && !isChangingConfigurations
        if (shouldReportExit) {
            reportPlayerExit("activity_finish")
        }
        MainActivity.notePlayerActivityDestroyed(activityInstanceId, playerSessionId)
        invalidateTransition("destroy")
        transitionGeneration += 1L
        sessionState = SessionState.DESTROYED
        seekScrubVisibility.update(false)
        findViewByTag<GestureLayer>("reianix_gesture_layer")?.resetZoomToFit()
        findViewByTag<GestureLayer>("reianix_gesture_layer")?.dispose()
        handler.removeCallbacks(progressReporter)
        handler.removeCallbacks(controlsHider)
        handler.removeCallbacks(lockAffordanceHider)
        handler.removeCallbacks(feedbackHider)
        handler.removeCallbacks(episodeChangeTimeout)

        restoreSystemUiBeforeExit()
        pendingPreparation?.cancel(true)
        playbackWorker.shutdown()
        progressWorker.shutdown()
        mediaMetadataWorker.shutdownNow()
        mediaControls?.release()
        mediaControls = null
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
        PlayerAspectMode.fromSetting(value).label

    private fun resizeModeFromSetting(value: String?): Int = when (PlayerAspectMode.fromSetting(value)) {
        PlayerAspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        PlayerAspectMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        PlayerAspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
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

    private fun installSmartSkipButton() {
        val button = actionButton("Pular abertura", 0) {
            if (::player.isInitialized && !episodeChangePending && !locked) {
                currentSkipSegments(player.duration).firstOrNull {
                    player.currentPosition in it.startMs until it.endMs
                }?.let { segment -> seekToMarker(segment.endMs) }
            }
        }.apply {
            tag = "reianix_smart_skip"
            textSize = 14f
            setPadding(dp(16), dp(4), dp(16), dp(4))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xED19191C.toInt())
            }
            visibility = View.GONE
        }
        smartSkipButton = button
        root.addView(button, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, dp(44), Gravity.BOTTOM or Gravity.END,
        ))
        updateSmartSkipButton()
    }

    private fun updateSmartSkipButton() {
        val button = smartSkipButton ?: return
        val active = if (::player.isInitialized && sessionState == SessionState.ACTIVE &&
            !locked && !errorVisible && !episodeChangePending && !inPictureInPicture &&
            !moreVisible && !seekScrubVisibility.active && episodePlaylistOverlay == null
        ) currentSkipSegments(player.duration).firstOrNull {
            player.currentPosition in it.startMs until it.endMs
        } else null
        if (active == null) {
            if (button.visibility != View.GONE) button.visibility = View.GONE
            return
        }
        if (button.text != active.label) {
            button.text = active.label
            button.contentDescription = active.label
        }
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val overlay = if (landscape) ReiAnixTokens.PlayerDimensions.bottomOverlayLandscapeHeight
            else ReiAnixTokens.PlayerDimensions.bottomOverlayPortraitHeight
        val bottom = (if (controlsVisible) dp(overlay) else gestureSafeBottom + dp(16)) + dp(8)
        // Match the final Compose shortcut slot, including custom shortcut counts,
        // five-slot insets and the gesture-safe horizontal padding of controls.
        val safeLeft = max(dp(4), gestureSafeLeft)
        val safeRight = max(dp(4), gestureSafeRight)
        val rootWidth = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val horizontalPadding = dp(ReiAnixTokens.PlayerDimensions.bottomHorizontalPadding)
        val rowWidth = (rootWidth - safeLeft - safeRight - 2 * horizontalPadding).coerceAtLeast(1)
        val optionsCenter = safeLeft + horizontalPadding +
            (rowWidth * PlayerShortcutPolicy.optionsCenterFraction(playerShortcuts)).roundToInt()
        val maxWidth = (rootWidth - safeLeft - safeRight).coerceAtLeast(1)
        val buttonWidth = (kotlin.math.ceil(button.paint.measureText(active.label).toDouble()).toInt() +
            button.paddingLeft + button.paddingRight).coerceIn(1, maxWidth)
        val left = (optionsCenter - buttonWidth / 2).coerceIn(safeLeft, max(safeLeft, rootWidth - safeRight - buttonWidth))
        val right = (rootWidth - left - buttonWidth).coerceAtLeast(0)
        val params = button.layoutParams as FrameLayout.LayoutParams
        if (params.bottomMargin != bottom || params.rightMargin != right || params.width != buttonWidth) {
            params.gravity = Gravity.BOTTOM or Gravity.RIGHT
            params.width = buttonWidth
            params.bottomMargin = bottom
            params.rightMargin = right
            button.layoutParams = params
        }
        if (button.visibility != View.VISIBLE) button.visibility = View.VISIBLE
    }

    private fun currentSkipSegments(durationMs: Long): List<PlayerSkipSegment> {
        if (favoriteClipSession != null) return emptyList()
        val manual = if (::uri.isInitialized && localMetadata.mediaId == uri.toString()) localMetadata
            else PlayerLocalMetadataStore.Metadata.empty()
        return PlayerEnhancementPolicy.segments(
            manual, if (smartSkipEnabled) remoteSkipSegments else emptyList(), durationMs,
        )
    }

    private fun requestSmartSkipSegments(durationMs: Long) {
        if (!smartSkipEnabled || favoriteClipSession != null) return
        if (!::player.isInitialized || !::uri.isInitialized || durationMs <= 0L ||
            errorVisible || episodeChangePending || sessionState != SessionState.ACTIVE ||
            (skipLookupGeneration == playerGeneration && skipLookupDuration == durationMs)) return
        val episodeId = currentEpisodeId().toLongOrNull() ?: return
        val generation = playerGeneration
        val source = uri
        skipLookupGeneration = generation
        skipLookupDuration = durationMs
        skipLookupJob?.cancel()
        remoteSkipSegments = emptyList()
        val lookupSettingGeneration = smartSkipGeneration
        skipLookupJob = lifecycleScope.launch {
            val segments = withContext(Dispatchers.IO) {
                try {
                    val repository = ReiAnixAppDependencies.from(applicationContext).sqliteLibraryRepository
                    val episode = repository.getEpisode(episodeId) ?: return@withContext emptyList<PlayerSkipSegment>()
                    val anime = repository.getAnime(episode.animeId) ?: return@withContext emptyList<PlayerSkipSegment>()
                    val number = PlayerEnhancementPolicy.aniSkipEpisodeNumber(
                        episode.season, episode.number, episode.absoluteNumber, episode.episodeType,
                    )
                    val anilistId = anime.anilistId?.takeIf {
                        anime.anilistMatchManual == true || anime.anilistMatchStatus == "matched"
                    }
                    if (episode.season < 1 || episode.episodeType?.lowercase(java.util.Locale.ROOT) in
                        setOf("special", "ova", "oad", "ona", "extra", "movie")) return@withContext emptyList<PlayerSkipSegment>()
                    // Remote queries use only catalog IDs, episode numbers and duration.
                    val results = coroutineScope {
                        val ani = async {
                            if (anilistId == null || number == null) SkipProviderResult()
                            else SkipProviderResult(AniSkipSegments(applicationContext)
                                .fetch(anilistId, number, durationMs).map {
                                    SkipEvidence(SkipProvider.ANISKIP, it, true)
                                })
                        }
                        val other = async {
                            if (anilistId == null || number == null || anime.format?.equals("TV", true) != true)
                                emptyList()
                            else ComplementarySkipSources().fetch(anilistId, episode.season, number, anime.episodesCount, durationMs)
                        }
                        listOf(ani.await()) + other.await()
                    }
                    val remote = SkipConsensus.choose(results, durationMs)
                    // Confirm single-source suggestions acoustically when possible,
                    // and fill genuine gaps. Never decode when two sources agree.
                    val missingKinds = SkipConsensus.needsLocal(results, remote)
                    if (missingKinds.isEmpty()) return@withContext remote
                    val currentNumber = PlayerEnhancementPolicy.episodeNumber(episode.number)
                        ?: return@withContext remote
                    val references = repository.listEpisodes(episode.animeId)
                        .asSequence()
                        .filter { it.id != episode.id && it.season == episode.season && it.missing != true &&
                            (it.episodeType == null || it.episodeType.equals("regular", true)) }
                        .mapNotNull { row ->
                            val n = PlayerEnhancementPolicy.episodeNumber(row.number) ?: return@mapNotNull null
                            if (n == currentNumber || kotlin.math.abs(n.toLong() - currentNumber) > 4L) null else row to n
                        }
                        .sortedBy { kotlin.math.abs(it.second.toLong() - currentNumber) }
                        .distinctBy { it.second }
                        .mapNotNull { (row, _) ->
                            val candidate = normalizeLocalReference(row.path) ?: return@mapNotNull null
                            if (candidate == source) null else LocalSkipAudio.Reference(
                                candidate, localMetadataStore.get(candidate.toString()))
                        }.distinctBy { it.uri }.take(2).toList()
                    val local = try {
                        LocalSkipAudio(applicationContext).detect(source, references, durationMs, missingKinds)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { emptyList() }
                    SkipConsensus.addLocal(remote, local, results, durationMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { emptyList<PlayerSkipSegment>() }
            }
            if (generation == playerGeneration && source == uri && ::player.isInitialized &&
                player.duration == durationMs && sessionState == SessionState.ACTIVE &&
                smartSkipEnabled && lookupSettingGeneration == smartSkipGeneration &&
                favoriteClipSession == null) {
                remoteSkipSegments = segments
                syncComposePlayerUiState()
            }
        }
    }

    private fun favoriteSceneNotes(): List<PlayerLocalMetadataStore.Note> =
        if (::uri.isInitialized && localMetadata.mediaId == uri.toString())
            localMetadata.notes.filter { it.favorite } else emptyList()

    private fun favoriteScenesAvailable(): Boolean =
        ::uri.isInitialized && ::player.isInitialized && !episodeChangePending &&
            !isFinishing && !isDestroyed && sessionState == SessionState.ACTIVE

    private fun showFavoriteScenes() {
        if (!favoriteScenesAvailable()) return
        if (inFavoriteClip) { exitFavoriteClip(); return }
        if (favoriteClipSession != null) return
        val revision = ++favoriteSceneUiRevision
        val source = uri.toString()
        setMorePanelVisible(false)
        player.pause()
        // The gallery is independent of the episode currently in the player.
        lifecycleScope.launch {
            val scenes = withContext(Dispatchers.IO) {
                localMetadataStore.favoriteScenes()
            }
            if (revision != favoriteSceneUiRevision || !favoriteScenesAvailable() || uri.toString() != source) return@launch
            cachedFavoriteScenes = scenes
            presentFavoriteGallery(scenes, player.duration > 0L && player.isCurrentMediaItemSeekable)
        }
    }

    private fun presentFavoriteGallery(scenes: List<PlayerLocalMetadataStore.FavoriteScene>, canCreate: Boolean) {
        favoriteSceneDialogs.gallery(
            scenes = scenes,
            canCreate = canCreate,
            onCreate = { editFavoriteScene(null) },
            onPlay = ::playFavoriteClip,
            onEdit = ::editFavoriteScene,
            onDelete = ::confirmDeleteFavoriteScene,
            onBack = {
                favoriteSceneUiRevision += 1L
                favoriteSceneDialogs.dismiss()
                touchControls()
            },
        )
    }

    private fun editFavoriteScene(scene: PlayerLocalMetadataStore.FavoriteScene?) {
        if (!favoriteScenesAvailable() || favoriteClipSession != null || favoriteRestoreWaitingForFrame) return
        val revision = ++favoriteSceneUiRevision
        val media = scene?.mediaId ?: uri.toString()
        val sameMedia = media == uri.toString()
        val position = scene?.note?.timestampMs ?: player.currentPosition.coerceAtLeast(0L)
        val knownDuration = if (sameMedia && player.isCurrentMediaItemSeekable) player.duration else 0L
        val animeName = scene?.note?.animeTitle?.takeIf { it.isNotBlank() }
            ?: if (sameMedia) titleValue else ""
        val episode = scene?.note?.episodeLabel?.takeIf { it.isNotBlank() }
            ?: if (sameMedia) currentEpisodeLabel() else "Episódio local"
        val initialFrame = if (scene == null && sameMedia) {
            val texture = playerView.videoSurfaceView as? android.view.TextureView
            if (texture?.isAvailable == true && texture.width > 0 && texture.height > 0) {
                val scale = minOf(1f, 720f / maxOf(texture.width, texture.height))
                runCatching { texture.getBitmap((texture.width * scale).toInt().coerceAtLeast(1),
                    (texture.height * scale).toInt().coerceAtLeast(1)) }.getOrNull()
            } else null
        } else null
        favoriteSceneDialogs.setBusy(true)
        lifecycleScope.launch {
            val duration = if (knownDuration > 0L) knownDuration else favoriteSceneDialogs.durationOf(media)
            if (revision != favoriteSceneUiRevision || !favoriteScenesAvailable()) return@launch
            if (duration <= 0L) {
                Toast.makeText(this@NativePlayerActivity, getString(R.string.native_scene_no_duration), Toast.LENGTH_SHORT).show()
                showFavoriteScenes()
                return@launch
            }
            // Pause the original episode; editing has its own short-lived preview.
            player.pause()
            val previewItem = if (sameMedia) player.currentMediaItem ?: MediaItem.fromUri(Uri.parse(media))
                else MediaItem.fromUri(Uri.parse(media))
            favoriteSceneDialogs.editor(
                mediaId = media,
                previewItem = previewItem,
                trackSelection = if (sameMedia) player.trackSelectionParameters else null,
                durationMs = duration,
                positionMs = position,
                existing = scene?.note,
                initialFrame = initialFrame,
                onSave = { start, end, name ->
                    val updated = withContext(Dispatchers.IO) {
                        localMetadataStore.saveFavoriteClip(
                            mediaId = media,
                            startMs = start,
                            endMs = end,
                            durationMs = duration,
                            title = name,
                            animeTitle = animeName,
                            episodeLabel = episode,
                            existingId = scene?.note?.id,
                        )
                    }
                    if (favoriteScenesAvailable() && media == uri.toString()) {
                        localMetadata = updated
                        syncComposePlayerUiState()
                    }
                },
                onSaved = {
                    if (revision == favoriteSceneUiRevision) showFavoriteScenes()
                },
                onBack = { showFavoriteScenes() },
            )
        }
    }

    private fun confirmDeleteFavoriteScene(scene: PlayerLocalMetadataStore.FavoriteScene) {
        if (!favoriteScenesAvailable() || favoriteClipSession != null || favoriteRestoreWaitingForFrame) return
        favoriteSceneDialogs.confirmDelete(scene.note.text, onCancel = { showFavoriteScenes() }) {
            favoriteSceneDialogs.setBusy(true)
            val revision = ++favoriteSceneUiRevision
            lifecycleScope.launch {
                try {
                    val updated = withContext(Dispatchers.IO) {
                        localMetadataStore.deleteNote(scene.mediaId, scene.note.id)
                    }
                    if (favoriteScenesAvailable() && scene.mediaId == uri.toString()) {
                        localMetadata = updated
                        syncComposePlayerUiState()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Toast.makeText(this@NativePlayerActivity, getString(R.string.native_scene_delete_failed), Toast.LENGTH_SHORT).show()
                }
                if (revision == favoriteSceneUiRevision && favoriteScenesAvailable()) showFavoriteScenes()
            }
        }
    }

    /** Play clipped media using the existing ExoPlayer and the normal player UI. */
    private fun playFavoriteClip(scene: PlayerLocalMetadataStore.FavoriteScene) {
        if (!favoriteScenesAvailable() || favoriteClipSession != null || favoriteRestoreWaitingForFrame || errorVisible ||
            player.duration <= 0L || player.playbackState == Player.STATE_IDLE) return
        val end = scene.note.clipEndMs ?: return
        val start = scene.note.timestampMs
        if (start < 0L || end <= start) return
        val media = Uri.parse(scene.mediaId)
        if (media.scheme != "content" && media.scheme != "file") return
        val revision = ++favoriteSceneUiRevision
        val source = uri
        player.pause()
        val originalItem = player.currentMediaItem ?: return
        val sameMedia = media == source
        val originalPosition = player.currentPosition.coerceAtLeast(0L)
        val originalDuration = player.duration
        val tracks = player.trackSelectionParameters
        val originalCompleted = completionReported
        favoriteSceneDialogs.setBusy(true)
        lifecycleScope.launch {
            val duration = if (sameMedia) originalDuration else favoriteSceneDialogs.durationOf(scene.mediaId)
            val sidecars = if (sameMedia) emptyList() else withContext(Dispatchers.IO) {
                runCatching { LocalSubtitleResolver.resolve(applicationContext, media) }.getOrDefault(emptyList())
            }
            if (revision != favoriteSceneUiRevision || !favoriteScenesAvailable() || uri != source ||
                favoriteClipSession != null || player.currentMediaItem != originalItem ||
                player.currentPosition != originalPosition || player.isPlaying) {
                if (revision == favoriteSceneUiRevision) favoriteSceneDialogs.setBusy(false)
                return@launch
            }
            if (duration <= 0L || end > duration) {
                Toast.makeText(this@NativePlayerActivity, getString(R.string.native_scene_original_invalid), Toast.LENGTH_LONG).show()
                showFavoriteScenes()
                return@launch
            }
            val baseItem = if (sameMedia) originalItem else buildMediaItem(media, null, sidecars)
            val item = baseItem.buildUpon()
                .setMediaId("favorite-clip:" + scene.note.id)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(start)
                    .setEndPositionMs(end)
                    .build())
                .build()
            // Checkpoint the episode before replacing its source. The clip may
            // not produce any episode-progress/completion/account-sync writes.
            saveProgress("player_paused", force = true)
            favoriteClipSession = FavoriteClipSession(scene, originalItem, originalPosition,
                originalDuration, tracks, originalCompleted, duration)
            skipLookupJob?.cancel()
            remoteSkipSegments = emptyList()
            try {
                val selection = tracks.buildUpon()
                if (!sameMedia) selection.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                favoriteOpeningWaitingForFrame = true
                replaceFavoritePlaybackItem(item, 0L, selection.build(), false)
                val opening = favoriteClipSession
                handler.postDelayed({
                    if (favoriteOpeningWaitingForFrame && favoriteClipSession === opening && inFavoriteClip) {
                        Toast.makeText(this@NativePlayerActivity, getString(R.string.native_scene_load_failed), Toast.LENGTH_SHORT).show()
                        exitFavoriteClip()
                    }
                }, 10_000L)
            } catch (error: Exception) {
                logPlayer("FAVORITE_CLIP_START_FAILED", error)
                Toast.makeText(this@NativePlayerActivity, getString(R.string.native_scene_open_failed), Toast.LENGTH_SHORT).show()
                restoreOriginalEpisode()
            }
        }
    }

    private fun replaceFavoritePlaybackItem(item: MediaItem, position: Long,
        tracks: TrackSelectionParameters, play: Boolean) {
        findViewByTag<GestureLayer>("reianix_gesture_layer")?.cancelInteractions()
        setMorePanelVisible(false)
        closeEpisodePlaylist()
        beginPlayerGeneration("favorite_scene")
        openedReported = true
        initialSeekApplied = true
        initialPositionMsForGeneration = position
        firstFrameRenderedForTesting = false
        completionReported = false
        errorVisible = false
        findViewByTag<View>("reianix_error_panel")?.visibility = View.GONE
        player.pause()
        startLoadingIndicator()
        playerView.setKeepContentOnPlayerReset(false)
        player.trackSelectionParameters = tracks
        player.setMediaItem(item, position)
        player.playWhenReady = play && !sleepTimerPaused && !expireSleepTimerIfDue()
        player.prepare()
        touchControls()
        updateProgressUi()
        updatePictureInPictureParams()
    }

    private fun revealFavoriteClip() {
        favoriteOpeningWaitingForFrame = false
        playerView.setKeepContentOnPlayerReset(true)
        favoriteSceneDialogs.dismiss()
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
            !sleepTimerPaused && !expireSleepTimerIfDue() && player.playbackState != Player.STATE_ENDED) player.play()
        touchControls()
    }

    private fun restoreOriginalEpisode() {
        val session = favoriteClipSession ?: return
        favoriteOpeningWaitingForFrame = false
        favoriteRestoreWaitingForFrame = false
        session.restoring = true
        try {
            // Immediately display the last gallery, while the original episode
            // is restored underneath it. Never expose the old clip or a blank screen.
            cachedFavoriteScenes?.let { presentFavoriteGallery(it, session.originalDurationMs > 0L) }
            favoriteSceneDialogs.setBusy(true)
            replaceFavoritePlaybackItem(session.originalItem, session.originalPositionMs,
                session.originalTracks.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly).build(), false)
            val generation = playerGeneration
            handler.postDelayed({
                if (generation == playerGeneration && (favoriteRestoreWaitingForFrame || favoriteClipSession?.restoring == true)) {
                    favoriteRestoreWaitingForFrame = false
                    favoriteSceneDialogs.dismiss()
                    playerView.setKeepContentOnPlayerReset(true)
                    showPlayerError("Não foi possível restaurar o episódio.", "media3_error")
                }
            }, 10_000L)
        } catch (error: Exception) {
            favoriteSceneDialogs.dismiss()
            playerView.setKeepContentOnPlayerReset(true)
            logPlayer("FAVORITE_CLIP_RESTORE_FAILED", error)
            showPlayerError("Não foi possível restaurar o episódio.", "media3_error")
        }
    }

    private fun exitFavoriteClip() {
        if (favoriteClipSession?.restoring != true) restoreOriginalEpisode()
    }

    private fun dismissFavoriteSceneUi() {
        favoriteSceneUiRevision += 1L
        favoriteSceneDialogs.dismiss()
    }

    private fun loadCaptionAppearance() {
        val revision = ++captionRevision
        val skipGenerationAtLoad = smartSkipGeneration
        val animeKey = currentAnimePreferencesKey()
        lifecycleScope.launch {
            val values = withContext(Dispatchers.IO) {
                runCatching { ReiAnixSqliteSettingsRepository(applicationContext).allPreferences() }
                    .getOrDefault(emptyMap())
            }
            if (revision != captionRevision || sessionState != SessionState.ACTIVE ||
                animeKey != currentAnimePreferencesKey()
            ) return@launch
            playerShortcuts = PlayerShortcutPolicy.parse(values["player.shortcuts"])
            if (skipGenerationAtLoad == smartSkipGeneration) {
                val enableSkip = values["player.aniskip_enabled"] != "false"
                if (smartSkipEnabled != enableSkip) {
                    smartSkipEnabled = enableSkip
                    skipLookupJob?.cancel()
                    remoteSkipSegments = emptyList()
                    skipLookupGeneration = -1L
                    skipLookupDuration = -1L
                    if (enableSkip && ::player.isInitialized && player.duration > 0L) {
                        requestSmartSkipSegments(player.duration)
                    }
                }
            }
            // On a same-anime handoff the already-applied in-memory snapshot
            // is more recent than an in-flight SQLite write. Do not apply
            // stale preferences or repeat Media3 track changes unnecessarily.
            val autoSaveEnabled = values["player.anime_preferences_enabled"] != "false"
            val reuse = autoSaveEnabled && animeKey != null &&
                activeAnimePreferences != null && activeAnimePreferencesKey == animeKey
            if (!reuse) {
                subtitleColor = values["audio.subtitle_color"] ?: "system"
                subtitleFont = values["audio.subtitle_font"] ?: "system"
                // Disabling per-anime preferences only stops applying/saving:
                // previously saved per-anime records remain intact in SQLite.
                val saved = if (autoSaveEnabled) {
                    PlayerAnimePreferences.decode(animeKey?.let(values::get))
                } else null
                activeAnimePreferences = null // Never copy a different anime’s track identity.
                activeAnimePreferencesKey = null
                activeAnimePreferences = saved ?: if (autoSaveEnabled && animeKey != null &&
                    favoriteClipSession == null) snapshotCurrentAnimePreferences() else null
                activeAnimePreferencesKey = animeKey.takeIf { activeAnimePreferences != null }
                // An anime with no saved record starts with global defaults and
                // acquires its own profile automatically on the first change.
                saved?.let(::applySavedAnimePreferences) ?: applySubtitlePreferences()
            }
            updateSmartSkipButton()
            syncComposePlayerUiState()
        }
    }

    private fun showSubtitleAppearance() {
        if (episodeChangePending || isFinishing || isDestroyed) return

        // The radio dialog is deliberately not dismissed by a selection.
        // Android's single-choice list updates the checked row immediately.
        fun choose(title: String, labels: Array<String>, currentIndex: Int, selected: (Int) -> Unit) {
            val dialog = AlertDialog.Builder(this, R.style.ReiAnixPlayerMenuDialogTheme)
                .setTitle(title)
                .setSingleChoiceItems(labels, currentIndex) { _, index ->
                    captionRevision += 1
                    selected(index)
                    applySubtitlePreferences()
                }
                .setNegativeButton(getString(R.string.ui_back)) { _, _ -> showSubtitleAppearance() }
                .setPositiveButton(getString(R.string.ui_close), null)
                .setOnCancelListener { showSubtitleAppearance() }
                .create()
            showPlayerMenuDialog(dialog)
        }
        val sizes = floatArrayOf(0.75f, 1f, 1.25f, 1.5f)
        val positions = intArrayOf(4, 8, 12, 16)
        val colors = arrayOf("system", "white", "yellow", "cyan")
        val fonts = arrayOf("system", "sans", "serif", "mono")

        val dialog = AlertDialog.Builder(this, R.style.ReiAnixPlayerMenuDialogTheme)
            .setTitle(getString(R.string.ui_custom_subtitles))
            .setItems(arrayOf(getString(R.string.common_size), getString(R.string.native_vertical_position), getString(R.string.native_text_color), getString(R.string.native_font), getString(R.string.native_original_styles), getString(R.string.native_restore_default)), null)
            .setNegativeButton(getString(R.string.ui_close), null)
            .create()
        // Override the default item click so resetting the settings keeps this
        // page open. Entering a category dismisses only the parent dialog.
        dialog.setOnShowListener {
            dialog.listView.setOnItemClickListener { _, _, index, _ ->
                if (index != 5) dialog.dismiss()
                when (index) {
                    0 -> choose(getString(R.string.common_size), arrayOf(getString(R.string.choice_small), getString(R.string.native_normal), getString(R.string.choice_large), getString(R.string.native_very_large)),
                        sizes.indexOfFirst { it == subtitleScale }) { i ->
                        subtitleScale = sizes[i]
                        persistCanonicalPlayerSetting("audio.subtitle_scale", subtitleScale.toString())
                    }
                    1 -> choose(getString(R.string.native_vertical_position), arrayOf(getString(R.string.native_lower), getString(R.string.native_normal), getString(R.string.native_higher), getString(R.string.native_high)),
                        positions.indexOf(subtitleBottomPaddingPercent)) { i ->
                        subtitleBottomPaddingPercent = positions[i]
                        persistCanonicalPlayerSetting("audio.subtitle_bottom_padding", subtitleBottomPaddingPercent.toString())
                    }
                    2 -> choose(getString(R.string.native_text_color), arrayOf(getString(R.string.choice_system), getString(R.string.choice_white), getString(R.string.choice_yellow), getString(R.string.choice_cyan)),
                        colors.indexOf(subtitleColor)) { i ->
                        subtitleColor = colors[i]
                        persistCanonicalPlayerSetting("audio.subtitle_color", subtitleColor)
                    }
                    3 -> choose(getString(R.string.native_font), arrayOf(getString(R.string.choice_system), getString(R.string.choice_sans), getString(R.string.choice_serif), getString(R.string.choice_mono)),
                        fonts.indexOf(subtitleFont)) { i ->
                        subtitleFont = fonts[i]
                        persistCanonicalPlayerSetting("audio.subtitle_font", subtitleFont)
                    }
                    4 -> choose(getString(R.string.native_original_styles), arrayOf(getString(R.string.native_keep), getString(R.string.native_ignore)),
                        if (subtitleEmbeddedStyle) 0 else 1) { i ->
                        subtitleEmbeddedStyle = i == 0
                        persistCanonicalPlayerSetting("audio.subtitle_embedded_style", subtitleEmbeddedStyle.toString())
                    }
                    5 -> {
                        captionRevision += 1
                        subtitleColor = "system"
                        subtitleFont = "system"
                        subtitleScale = 1f
                        subtitleBottomPaddingPercent = 8
                        subtitleEmbeddedStyle = true
                        persistCanonicalPlayerSetting("audio.subtitle_color", "system")
                        persistCanonicalPlayerSetting("audio.subtitle_font", "system")
                        persistCanonicalPlayerSetting("audio.subtitle_scale", "1.0")
                        persistCanonicalPlayerSetting("audio.subtitle_bottom_padding", "8")
                        persistCanonicalPlayerSetting("audio.subtitle_embedded_style", "true")
                        applySubtitlePreferences()
                    }
                }
            }
        }
        showPlayerMenuDialog(dialog)
    }

    private fun setAudioOnly(enabled: Boolean) {
        if (!::player.isInitialized || errorVisible || episodeChangePending || inPictureInPicture) return
        if (enabled && supportedTrackCount(C.TRACK_TYPE_AUDIO) <= 0) {
            showFeedback(getString(R.string.native_no_audio_track))
            return
        }
        if (mediaControls?.setBackgroundAudioEnabled(enabled) != true) {
            showFeedback(getString(R.string.native_audio_only_failed))
            return
        }
        audioOnly = enabled
        if (activeAnimePreferences != null) persistAnimePreferences()
        player.setWakeMode(if (enabled) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NONE)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, enabled).build()
        playerView.keepScreenOn = !enabled
        if (enabled) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playerView.videoSurfaceView?.visibility = if (enabled) View.INVISIBLE else View.VISIBLE
        updatePictureInPictureParams()
        syncComposePlayerUiState()
        touchControls()
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
                    syncComposePlayerUiState()
                    updateMetadataControls(if (::player.isInitialized) player.currentPosition else 0L)
                }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            logPlayer("PLAYER_METADATA_LOAD_REJECTED uri=" + localUri, error)
        }
    }

    private fun updateMetadataControls(positionMs: Long) {
        if (!::root.isInitialized) return
        if (favoriteClipSession != null) {
            findViewByTag<TextView>("reianix_skip_opening")?.visibility = View.GONE
            findViewByTag<TextView>("reianix_skip_ending")?.visibility = View.GONE
            return
        }
        val position = positionMs.coerceAtLeast(0L)
        val duration = if (::player.isInitialized) player.duration.takeIf { it > 0L } else null
        val opening = localMetadata.opening?.takeIf { duration == null || it.endMs <= duration }
        val ending = localMetadata.ending?.takeIf { duration == null || it.endMs <= duration }
        findViewByTag<TextView>("reianix_skip_opening")?.visibility =
            if (opening != null && position >= opening.startMs && position < opening.endMs) View.VISIBLE else View.GONE
        findViewByTag<TextView>("reianix_skip_ending")?.visibility =
            if (ending != null && position >= ending.startMs && position < ending.endMs) View.VISIBLE else View.GONE
    }

    private fun seekToMarker(targetMs: Long) {
        if (!::player.isInitialized || player.duration <= 0L) return
        val safeTarget = targetMs.coerceIn(0L, player.duration)
        player.seekTo(safeTarget)
        saveProgress("player_progress", force = true)
        touchControls()
    }

    private fun showLocalMetadataEditor() {
        if (favoriteClipSession != null) return
        if (!::localMetadataStore.isInitialized || !::uri.isInitialized) return
        findViewByTag<GestureLayer>("reianix_gesture_layer")?.cancelInteractions()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        fun field(label: String, valueMs: Long?): android.widget.EditText =
            android.widget.EditText(this).apply {
                hint = label + " (MM:SS)"
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_DATETIME or android.text.InputType.TYPE_DATETIME_VARIATION_TIME
                setTextColor(Color.WHITE)
                setHintTextColor(0xFFBDBDBD.toInt())
                backgroundTintList = ColorStateList.valueOf(ReiAnixTokens.Colors.primary.toArgb())
                setText(valueMs?.let { formatTime(it) }.orEmpty())
            }

        val openingStart = field("Abertura início", localMetadata.opening?.startMs)
        val openingEnd = field("Abertura fim", localMetadata.opening?.endMs)
        val endingStart = field("Encerramento início", localMetadata.ending?.startMs)
        val endingEnd = field("Encerramento fim", localMetadata.ending?.endMs)

        container.addView(android.widget.TextView(this).apply {
            text = getString(R.string.tail_local_markers)
            setTextColor(Color.WHITE)
            textSize = 13f
        })
        container.addView(openingStart)
        container.addView(openingEnd)
        container.addView(endingStart)
        container.addView(endingEnd)

        if (localMetadata.notes.any { !it.favorite }) {
            container.addView(android.widget.TextView(this).apply {
                text = getString(R.string.tail_episode_notes)
                setTextColor(0xFFF5F5F5.toInt())
                setPadding(0, dp(10), 0, dp(4))
            })
            localMetadata.notes.filterNot { it.favorite }.sortedBy { it.timestampMs }.forEach { note ->
                val item = actionButton(formatTime(note.timestampMs) + " • " + (if (note.favorite) "★ " else "") + note.text, 260) {
                    seekToMarker(note.timestampMs)
                }
                item.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                item.setOnLongClickListener {
                    try {
                        localMetadata = localMetadataStore.deleteNote(uri.toString(), note.id)
                    } catch (_: Exception) {
                        showFeedback(getString(R.string.native_note_delete_failed))
                        return@setOnLongClickListener true
                    }
                    showLocalMetadataEditor()
                    true
                }
                container.addView(item, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(44),
                ).apply { topMargin = dp(4) })
            }
        }

        AlertDialog.Builder(this, R.style.ReiAnixPlayerMenuDialogTheme)
            .setTitle(getString(R.string.tail_markers_notes))
            .setView(container)
            .setNeutralButton(getString(R.string.tail_note_current)) { _, _ ->
                handler.post { showAddLocalNoteDialog() }
            }
            .setNegativeButton(getString(R.string.locale_cancel), null)
            .setPositiveButton(getString(R.string.tail_save_markers)) { _, _ ->
                val openingStartMs = parseTimestampMs(openingStart.text?.toString())
                val openingEndMs = parseTimestampMs(openingEnd.text?.toString())
                val endingStartMs = parseTimestampMs(endingStart.text?.toString())
                val endingEndMs = parseTimestampMs(endingEnd.text?.toString())
                val durationMs = if (::player.isInitialized) player.duration.takeIf { it > 0L } else null
                val fields = listOf(openingStart, openingEnd, endingStart, endingEnd)
                val parsed = listOf(openingStartMs, openingEndMs, endingStartMs, endingEndMs)
                if (fields.zip(parsed).any { (field, value) -> !field.text.isNullOrBlank() && value == null } ||
                    !PlayerLocalMetadataStore.isValidSegment(openingStartMs, openingEndMs, durationMs) ||
                    !PlayerLocalMetadataStore.isValidSegment(endingStartMs, endingEndMs, durationMs)
                ) {
                    showFeedback(getString(R.string.native_markers_invalid))
                    return@setPositiveButton
                }
                try {
                    localMetadata = localMetadataStore.setSegments(
                        uri.toString(),
                        openingStartMs,
                        openingEndMs,
                        endingStartMs,
                        endingEndMs,
                        durationMs,
                    )
                } catch (_: IllegalArgumentException) {
                    showFeedback(getString(R.string.native_markers_invalid))
                    return@setPositiveButton
                } catch (_: IllegalStateException) {
                    showFeedback(getString(R.string.native_markers_save_failed))
                    return@setPositiveButton
                }
                updateMetadataControls(if (::player.isInitialized) player.currentPosition else 0L)
            }
            .create().also { showPlayerMenuDialog(it) }
    }

    private fun showAddLocalNoteDialog() {
        if (!::localMetadataStore.isInitialized || !::uri.isInitialized ||
            !::player.isInitialized || episodeChangePending || favoriteClipSession != null ||
            sessionState != SessionState.ACTIVE || errorVisible
        ) return
        findViewByTag<GestureLayer>("reianix_gesture_layer")?.cancelInteractions()
        val noteUri = uri
        val noteGeneration = transitionGeneration
        val notePositionMs = player.currentPosition.coerceAtLeast(0L)
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.native_note_hint)
            setSingleLine(false)
            maxLines = 5
            setTextColor(Color.WHITE)
            setHintTextColor(0xFFBDBDBD.toInt())
            backgroundTintList = ColorStateList.valueOf(ReiAnixTokens.Colors.primary.toArgb())
            setTextSize(14f)
        }
        val dialog = AlertDialog.Builder(this, R.style.ReiAnixPlayerMenuDialogTheme)
            .setTitle(getString(R.string.tail_note_at, formatTime(notePositionMs)))
            .setView(input)
            .setNegativeButton(getString(R.string.locale_cancel), null)
            .setPositiveButton(getString(R.string.pref_save), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val noteText = input.text?.toString().orEmpty().trim()
                if (noteText.isBlank()) {
                    input.error = getString(R.string.native_note_required)
                    return@setOnClickListener
                }
                if (noteText.length > 500) {
                    input.error = getString(R.string.native_note_max)
                    return@setOnClickListener
                }
                try {
                    // Save to the episode and timestamp captured when opening,
                    // even if playback advances while the user is typing.
                    val updated = localMetadataStore.addNote(noteUri.toString(), notePositionMs, noteText)
                    if (::uri.isInitialized && uri == noteUri && transitionGeneration == noteGeneration &&
                        sessionState == SessionState.ACTIVE && favoriteClipSession == null
                    ) {
                        localMetadata = updated
                        updateMetadataControls(if (::player.isInitialized) player.currentPosition else 0L)
                    }
                    dialog.dismiss()
                } catch (_: Exception) {
                    input.error = getString(R.string.native_note_save_failed)
                }
            }
        }
        showPlayerMenuDialog(dialog)
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

    private fun actionButton(
        label: String,
        widthDp: Int,
        showPressFeedback: Boolean = true,
        action: (TextView) -> Unit,
    ): TextView {
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
            if (showPressFeedback) {
                val mask = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
                foreground = android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(0x80FFFFFF.toInt()), null, mask,
                )
            }
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

    private fun dp(value: Dp): Int =
        (value.value * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)

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
                if (!safAuthorized && !mediaStoreAuthorized && !intent.getBooleanExtra("externalVideo", false)) {
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
                    !hasBroadAccess && !intent.getBooleanExtra("externalVideo", false) -> SourceValidationFailure(
                        "O acesso amplo ao armazenamento não está disponível para este arquivo.",
                        "STORAGE_PERMISSION_MISSING",
                    )
                    !authorized && !intent.getBooleanExtra("externalVideo", false) -> SourceValidationFailure(
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
    private fun <T : View> findViewByTag(tagValue: String): T? {
        // onDestroy() can run after a partial onCreate() failure, before root exists.
        // Treat the view tree as unavailable in that state so cleanup can continue.
        if (!::root.isInitialized) return null
        return root.findViewWithTag(tagValue)
    }

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
        HORIZONTAL_SEEK,
        HORIZONTAL_IGNORED,
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
                    zoomAnimator?.cancel()
                    if (!zoomTransformActive) {
                        zoomScale = if (playerView.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) fillZoomLimit() else 1f
                    }
                    zoomTransformActive = true
                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    applyZoomTransform()
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
                    if (!zoomEnabled || !pinchActive || !gestureInteractionAllowed()) return true
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
                        fillZoomLimit(),
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
                    zoomTranslationX = 0f
                    zoomTranslationY = 0f

                    // Use a fill-sized surface so enlarging pixels cannot stay clipped
                    // inside the old FIT rectangle. The scale is relative to FIT.
                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    applyZoomTransform()
                    updateAspectButtonFromZoom()
                    setFillSnapVisible(nearFill())

                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    finishPinchGesture(cancelled = pinchEventCancelled)
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
                            seekBy(-effectiveSeekMs, showControls = false)
                        }

                        PlayerGesturePolicy.Side.RIGHT -> {
                            logPlayer("PLAYER_DOUBLE_TAP side=right requestId=" + requestId.ifEmpty { "-" })
                            seekBy(effectiveSeekMs, showControls = false)
                        }

                        PlayerGesturePolicy.Side.CENTER -> {
                            logPlayer("PLAYER_DOUBLE_TAP side=center requestId=" + requestId.ifEmpty { "-" })
                            togglePlayPause()
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
        private var pinchEventCancelled = false
        private var fillSnapVisible = false
        private val snapOutlinePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(192, 255, 255, 255)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(30).toFloat()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            if (fillSnapVisible) {
                val inset = snapOutlinePaint.strokeWidth * .5f
                canvas.drawRect(inset, inset, width - inset, height - inset, snapOutlinePaint)
            }
        }

        private fun setFillSnapVisible(visible: Boolean) {
            if (fillSnapVisible == visible) return
            fillSnapVisible = visible
            invalidate()
        }

        private fun nearFill(): Boolean = abs(zoomScale / fillZoomLimit() - 1f) < .07f

        private var lastVerticalY: Float? = null
        private var horizontalSeekStartPositionMs = 0L
        private var horizontalSeekTargetMs = 0L
        private var lastSeekPreviewAtMs = 0L
        private var lastPanX: Float? = null
        private var lastPanY: Float? = null
        private var zoomTransformActive = false
        private var zoomScale = 1f
        private var zoomTranslationX = 0f
        private var zoomTranslationY = 0f
        private var zoomAnimator: ValueAnimator? = null
        private var lastZoomFeedbackAtMs = 0L
        private val zoomMatrix = Matrix()
        init {
            setWillNotDraw(false)
            playerView.videoSurfaceView?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (zoomTransformActive) applyZoomTransform()
            }
        }

        private var longPressActive = false
        private var previousSpeedForLongPress = 1f
        private var gestureMode = GestureMode.IDLE

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (inPictureInPicture) return true
            pinchEventCancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
            scaleDetector.onTouchEvent(event)

            if (event.pointerCount > 1 || pinchActive || gestureMode == GestureMode.PINCH) {
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
                                // Keep fit/fill centered; no independent pan.
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
                    if (systemGestureEdge) return true
                    if (locked) return true
                    if (gestureConsumed &&
                        gestureMode != GestureMode.VERTICAL &&
                        gestureMode != GestureMode.HORIZONTAL_SEEK &&
                        gestureMode != GestureMode.PAN
                    ) return true

                    val dx = event.x - downX
                    val dy = event.y - downY

                    if (gestureMode == GestureMode.HORIZONTAL_SEEK) {
                        updateHorizontalSeekPreview(dx)
                        return true
                    }

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
                            val durationMs = if (::player.isInitialized) player.duration else 0L
                            if (!PlayerGesturePolicy.canSeekHorizontally(
                                    enabled = horizontalSeekEnabled,
                                    durationMs = durationMs,
                                    episodeChangePending = episodeChangePending,
                                )
                            ) {
                                gestureConsumed = true
                                if (!horizontalSeekEnabled) {
                                    gestureMode = GestureMode.HORIZONTAL_IGNORED
                                    cancelGestureDetector(event)
                                    restoreLongPressSpeed()
                                    touchControls()
                                    logPlayer("GESTURE_HORIZONTAL_SEEK_IGNORED reason=disabled requestId=" + requestId.ifEmpty { "-" })
                                } else {
                                    gestureMode = GestureMode.IDLE
                                    cancelGestureDetector(event)
                                    restoreLongPressSpeed()
                                    logPlayer("GESTURE_HORIZONTAL_SEEK_IGNORED reason=duration_or_transition requestId=" + requestId.ifEmpty { "-" })
                                }
                            } else {
                                gestureConsumed = true
                                gestureMode = GestureMode.HORIZONTAL_SEEK
                                horizontalSeekStartPositionMs = player.currentPosition.coerceIn(0L, player.duration)
                                horizontalSeekTargetMs = horizontalSeekStartPositionMs
                                cancelGestureDetector(event)
                                restoreLongPressSpeed()
                                touchControls()
                                updateHorizontalSeekPreview(dx)
                                logPlayer("GESTURE_START type=horizontal_seek requestId=" + requestId.ifEmpty { "-" })
                            }
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

                        GestureMode.HORIZONTAL_IGNORED -> {
                            logPlayer("GESTURE_END type=horizontal_seek_ignored requestId=" + requestId.ifEmpty { "-" })
                            resetTransientState()
                            return true
                        }

                        GestureMode.HORIZONTAL_SEEK -> {
                            if (::player.isInitialized && !episodeChangePending && player.duration > 0L) {
                                val target = horizontalSeekTargetMs.coerceIn(0L, player.duration)
                                player.seekTo(target)
                                saveProgress("player_progress")
                                logPlayer("GESTURE_END type=horizontal_seek positionMs=" + target + " requestId=" + requestId.ifEmpty { "-" })
                                touchControls()
                            }
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

        private fun updateHorizontalSeekPreview(deltaX: Float) {
            if (!::player.isInitialized || player.duration <= 0L || width <= 0) return
            horizontalSeekTargetMs = PlayerGesturePolicy.horizontalSeekTarget(
                startPositionMs = horizontalSeekStartPositionMs,
                deltaX = deltaX,
                viewportWidth = width.toFloat(),
                durationMs = player.duration,
            )
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastSeekPreviewAtMs >= SEEK_PREVIEW_INTERVAL_MS) {
                lastSeekPreviewAtMs = now
                val deltaSeconds = (horizontalSeekTargetMs - horizontalSeekStartPositionMs) / 1000L
                val deltaLabel = if (deltaSeconds < 0L) "−" else "+"
                showFeedback("Buscar " + formatTime(horizontalSeekTargetMs) + " (" + deltaLabel + abs(deltaSeconds) + "s)", 350L)
            }
        }

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
            if (!::playerView.isInitialized) return
            if (zoomTransformActive && zoomScale > 1.01f) {
                applyZoomTransform()
            } else {
                // A prior gesture can be cancelled with scale=1 while its
                // TextureView remains in ZOOM and scaled by 1/fillZoomLimit.
                zoomScale = 1f
                zoomTranslationX = 0f
                zoomTranslationY = 0f
                restoreConfiguredAspectMode()
            }
        }

        /** Orientation changes discard transient pinch/pan, not user FIT/FILL. */
        fun resetZoomForViewportChange() {
            cancelInteractions()
            setFillSnapVisible(false)
            zoomAnimator?.cancel()
            zoomAnimator = null
            zoomScale = 1f
            zoomTranslationX = 0f
            zoomTranslationY = 0f
            restoreConfiguredAspectMode()
        }

        fun cancelInteractions() {
            if (::relativeSeekOverlay.isInitialized) relativeSeekOverlay.reset()
            zoomAnimator?.cancel()
            zoomAnimator = null
            setFillSnapVisible(false)
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
            setFillSnapVisible(false)
            zoomTransformActive = false
            zoomAnimator?.cancel()
            zoomAnimator = null
            zoomScale = 1f
            zoomTranslationX = 0f
            zoomTranslationY = 0f
            if (::playerView.isInitialized) {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                val video = playerView.videoSurfaceView
                video?.apply {
                    scaleX = 1f
                    scaleY = 1f
                    translationX = 0f
                    translationY = 0f
                }
                updateAspectButtonFromZoom()
            }
        }

        private fun finishPinchGesture(cancelled: Boolean) {
            if (!pinchActive) return
            pinchActive = false
            setFillSnapVisible(false)
            gestureConsumed = true
            gestureMode = GestureMode.PINCH
            lastPanX = null
            lastPanY = null

            if (!cancelled) {
                logPlayer("GESTURE_END type=pinch requestId=" + requestId.ifEmpty { "-" })
                val fitSnap = min(ZOOM_SNAP_THRESHOLD, 1f + (fillZoomLimit() - 1f) * .15f)
                if (nearFill()) {
                    animateZoomTo(fillZoomLimit())
                } else if (zoomScale <= fitSnap) {
                    animateZoomToFit()
                } else {
                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    updateAspectButtonFromZoom()
                    applyZoomTransform()
                }
                touchControls()
            } else {
                logPlayer("GESTURE_CANCEL type=pinch requestId=" + requestId.ifEmpty { "-" })
            }
        }

        private fun animateZoomToFit() = animateZoomTo(1f)

        private fun animateZoomTo(target: Float) {
            zoomAnimator?.cancel()
            zoomAnimator = null
            val destination = target.coerceIn(1f, fillZoomLimit())
            fun settle() {
                zoomScale = destination
                zoomTranslationX = 0f
                zoomTranslationY = 0f
                if (destination == 1f) restoreConfiguredAspectMode()
                applyZoomTransform()
                updateAspectButtonFromZoom()
            }
            if (!ReiAnixMotionPolicy.systemAnimationsEnabled()) {
                settle()
                return
            }
            val startScale = zoomScale
            zoomAnimator = ValueAnimator.ofFloat(startScale, destination).apply {
                duration = 200L
                startDelay = 0L
                addUpdateListener { animator ->
                    zoomScale = animator.animatedValue as Float
                    applyZoomTransform()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (!cancelled) settle()
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

        private fun fillZoomLimit(): Float {
            val (fitWidth, fitHeight) = displayedVideoSize()
            return max(width / fitWidth, height / fitHeight).coerceAtLeast(1f)
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
            val button = findViewByTag<TextView>("reianix_aspect_button") ?: return
            if (zoomScale <= ZOOM_SNAP_THRESHOLD) {
                val label = aspectLabelFromSetting(intent.getStringExtra("setting_player_aspect_ratio"))
                button.text = label
                button.isSelected = label != "Ajustar"
            } else {
                button.text = "Zoom " + String.format(java.util.Locale.US, "%.2fx", zoomScale)
                button.isSelected = true
            }
        }

        private fun restoreConfiguredAspectMode() {
            zoomTransformActive = false
            playerView.videoSurfaceView?.apply {
                scaleX = 1f
                scaleY = 1f
                translationX = 0f
                translationY = 0f
            }
            val value = intent.getStringExtra("setting_player_aspect_ratio")
                ?: PlayerAspectMode.fromLabel(aspectModeLabel).setting
            val label = aspectLabelFromSetting(value)
            val configuredMode = resizeModeFromSetting(value)
            if (playerView.resizeMode != configuredMode) playerView.resizeMode = configuredMode
            aspectModeLabel = label
            findViewByTag<TextView>("reianix_aspect_button")?.apply {
                text = label
                isSelected = label != "Ajustar"
            }
        }

        private fun resetTransientState() {
            gestureMode = GestureMode.IDLE
            lastPanX = null
            lastPanY = null
            lastVerticalY = null
            horizontalSeekTargetMs = horizontalSeekStartPositionMs
            systemGestureEdge = false
        }

        private fun applyZoomTransform() {
            val video = playerView.videoSurfaceView ?: return
            val limit = fillZoomLimit()
            zoomScale = zoomScale.coerceIn(1f, limit)
            // CloudStream uses a ZOOM-sized surface and a View scale corrected
            // for its implicit fill factor. Never scale TextureView pixels inside FIT.
            val fillSurface = zoomTransformActive || zoomScale > 1f
            val mode = if (fillSurface) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            if (playerView.resizeMode != mode) playerView.resizeMode = mode
            val surfaceScale = if (fillSurface) zoomScale / limit else 1f
            video.pivotX = video.width * .5f
            video.pivotY = video.height * .5f
            video.scaleX = surfaceScale
            video.scaleY = surfaceScale
            // Respect the clamped pan accumulated by applyPanDelta instead of
            // overwriting it on every frame (previously pan always snapped back).
            video.translationX = zoomTranslationX
            video.translationY = zoomTranslationY
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

        fun normalizePlaybackSpeed(speed: Float): Float =
            if (speed.isFinite() && SUPPORTED_PLAYBACK_SPEEDS.any { it == speed }) speed else 1f

        /** Intent values are seconds both on initial open and Activity reuse. */
        fun seekIntervalMs(seconds: Long): Long = seconds.coerceIn(1L, 120L) * 1000L

        private val SUPPORTED_PLAYBACK_SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

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

        fun seekTarget(currentPositionMs: Long, deltaMs: Long, durationMs: Long): Long {
            if (durationMs <= 0L) return currentPositionMs.coerceAtLeast(0L)
            val current = currentPositionMs.coerceIn(0L, durationMs)
            val target = when {
                deltaMs > 0L && current > Long.MAX_VALUE - deltaMs -> Long.MAX_VALUE
                deltaMs < 0L && current < Long.MIN_VALUE - deltaMs -> Long.MIN_VALUE
                else -> current + deltaMs
            }
            return target.coerceIn(0L, durationMs)
        }

        fun canSeekHorizontally(
            enabled: Boolean,
            durationMs: Long,
            episodeChangePending: Boolean,
        ): Boolean = enabled && durationMs > 0L && !episodeChangePending

        fun horizontalSeekTarget(
            startPositionMs: Long,
            deltaX: Float,
            viewportWidth: Float,
            durationMs: Long,
            seekMsPerWidth: Long = 120_000L,
        ): Long {
            val upperBound = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
            val start = startPositionMs.coerceIn(0L, upperBound)
            if (!deltaX.isFinite() || !viewportWidth.isFinite() || viewportWidth <= 0f || seekMsPerWidth <= 0L) {
                return start
            }
            val rawDelta = (deltaX.toDouble() / viewportWidth.toDouble()) * seekMsPerWidth.toDouble()
            val boundedDelta = rawDelta.coerceIn(-start.toDouble(), (upperBound - start).toDouble())
            return seekTarget(start, boundedDelta.roundToLong(), upperBound)
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

        private const val TAG = "[REIANIX][PLAYER]"
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
        private const val SEEK_PREVIEW_INTERVAL_MS = 80L
        private const val MAX_RETRY_ATTEMPTS = 2
        private const val LOCK_AFFORDANCE_TIMEOUT_MS = 2_200L
    }
}
