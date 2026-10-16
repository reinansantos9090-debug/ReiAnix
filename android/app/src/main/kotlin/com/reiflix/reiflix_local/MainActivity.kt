package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.bridge.GoogleIdentity
import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.player.NativePlayerRequest
import com.reiflix.reiflix_local.bridge.NativeRequestState
import com.reiflix.reiflix_local.player.DeviceInteractionProfile
import com.reiflix.reiflix_local.player.SystemUiController
import com.reiflix.reiflix_local.scanner.BroadStorageScanner
import com.reiflix.reiflix_local.scanner.MediaStoreRetryScheduler
import com.reiflix.reiflix_local.scanner.MediaStoreScanner
import com.reiflix.reiflix_local.scanner.NativeScanController
import com.reiflix.reiflix_local.scanner.NativeScanRunner
import com.reiflix.reiflix_local.scanner.ScanLifecycleState
import com.reiflix.reiflix_local.scanner.SafScanner
import com.reiflix.reiflix_local.ReiAnixAppDependencies
import com.reiflix.reiflix_local.storage.NativeIndex
import com.reiflix.reiflix_local.storage.StorageAuthorization
import com.reiflix.reiflix_local.storage.StorageLifecycleState
import com.reiflix.reiflix_local.storage.NativeStorageOnboardingState
import com.reiflix.reiflix_local.storage.VideoThumbnailExtractor
import com.reiflix.reiflix_local.ui.host.ReiAnixComposeLibraryHost
import com.reiflix.reiflix_local.ui.navigation.ReiAnixRoutes
import com.reiflix.reiflix_local.data.settings.ReiAnixSettingsFileService
import com.reiflix.reiflix_local.data.backup.ReiAnixBackupService
import com.reiflix.reiflix_local.data.backup.ReiAnixDiagnosticsService

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModelFactory
import com.reiflix.reiflix_local.viewmodel.ReiAnixSettingsViewModel
import com.reiflix.reiflix_local.viewmodel.ReiAnixSettingsViewModelFactory
import io.flutter.embedding.android.FlutterFragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.LinkedHashSet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Flet's generated Android template must use this activity instead of its default
 * FlutterActivity. It owns SAF and Google identity because only Android has a
 * ContentResolver/Credential Manager.
 */
class MainActivity : FlutterFragmentActivity() {
    private val tag = "[REIFLIX][ANDROID]"
    private val MAX_SETTINGS_FILE_BYTES = 1024 * 1024
    private val SETTINGS_FILE_REQUEST_ID_REGEX = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private lateinit var systemUiController: SystemUiController
    private var broadStoragePermissionPending = false
    private var mediaPermissionRequestPending = false
    private var safPickerPending = false
    private enum class SafPickerPhase {
        IDLE, REQUESTED, LAUNCHING, WAITING_RESULT, COMPLETED, CANCELLED, FAILED, TIMEOUT
    }
    private var safPickerPhase = SafPickerPhase.IDLE
    private var safPickerStartedAtMs: Long = 0L
    private var safPickerFocusLost = false
    private var safPickerFocusRegainedAtMs: Long = 0L
    private var safPickerWatchdog: Runnable? = null
    private val safPickerWatchdogHandler = Handler(Looper.getMainLooper())
    private var activityResumed = false
    private var googleSignInJob: Job? = null
    private var googleSignOutJob: Job? = null
    private var pendingMediaRequestId: String? = null
    private var pendingBroadRequestId: String? = null
    private var pendingSafRequestId: String? = null
    private var pendingPlayUri: String? = null
    /** Original play deep-link preserved while MainActivity is paused. */
    private var pendingPlayIntentData: String? = null
    private var pendingPlayTitle: String? = null
    private var pendingPlayPositionMs: Long = 0L
    private var pendingPlayPositionSpecified: Boolean = false
    private var pendingPlayPositionInvalid: Boolean = false
    private var pendingPlayCanNext = false
    private var pendingPlayCanPrevious = false
    private var pendingPlayAutoplay = true
    private var pendingPlayEpisodeId: String? = null
    private var pendingPlayAnimeId: String? = null
    private var pendingPlayCommandCreatedAtMs: Long = 0L
    private var pendingPlayRequestId: String? = null
    private var activePlayerRequestId: String? = null
    private var activePlayerCommandCreatedAtMs: Long = 0L
    private val seenPlayerRequestIds = LinkedHashSet<String>()
    private var startupDiscoveryTriggered = false
    private var nativeBootstrapDecisionComplete = false
    private var automaticSafPickerAttempted = false
    private val pendingNativeIntentsBeforeBootstrap = ArrayList<Intent>()
    private var lastObservedMediaAccess: String? = null
    private val _nativeStorageOnboardingState = MutableStateFlow(NativeStorageOnboardingState())
    val nativeStorageOnboardingState: StateFlow<NativeStorageOnboardingState> = _nativeStorageOnboardingState.asStateFlow()
    private var storageOnboardingRefreshJob: Job? = null
    private var lastObservedBroadAccess: Boolean? = null
    private var interactionProfileFingerprint: String? = null
    private val nativeRequestState = NativeRequestState()

    /**
     * The Application owns the process-scoped native service graph. Screen projection
     * repositories remain ViewModel-scoped and are still closed with their lifecycle.
     */
    private val appDependencies by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ReiAnixAppDependencies.from(applicationContext)
    }

    private val libraryViewModel: ReiAnixLibraryViewModel by viewModels {
        ReiAnixLibraryViewModelFactory(applicationContext, appDependencies)
    }
    private val settingsViewModel: ReiAnixSettingsViewModel by viewModels {
        ReiAnixSettingsViewModelFactory(applicationContext, appDependencies)
    }

    private lateinit var composeLibraryHost: ReiAnixComposeLibraryHost

    private val playerActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            val requestId = result.data?.getStringExtra("requestId") ?: activePlayerRequestId
            val reason = result.data?.getStringExtra("reason").orEmpty()
            val activityInstanceId = result.data?.getStringExtra("activityInstanceId")
            val controlled = result.resultCode == RESULT_OK && reason.isNotBlank()
            Log.i(
                tag,
                "PLAYER_ACTIVITY_RESULT requestId=" + (requestId ?: "-") + " " +
                    "resultCode=" + result.resultCode + " controlled=" + controlled +
                    " reason=" + reason.ifBlank { "-" },
            )
            NativeMailbox.writeBestEffort(
                this,
                JSONObject()
                    .put("type", "diagnostic")
                    .put("requestId", requestId ?: "")
                    .put(
                        "payload",
                        JSONObject()
                            .put("event", "PLAYER_ACTIVITY_RESULT")
                            .put("resultCode", result.resultCode)
                            .put("controlled", controlled)
                            .put("reason", reason)
                            .put("activityInstanceId", activityInstanceId ?: "")
                            .put("unexpectedCancellation", result.resultCode == RESULT_CANCELED && !controlled),
                    ),
            )
            if (result.resultCode == RESULT_CANCELED && !controlled) {
                Log.e(
                    tag,
                    "PLAYER_ACTIVITY_UNEXPECTED_CANCELED requestId=" + (requestId ?: "-") +
                        " child ended without a controlled result; inspect NativePlayerActivity logcat for FATAL EXCEPTION/Media3 details.",
                )
            }
            val resultBelongsToCurrentActivity =
                activityInstanceId.isNullOrBlank() ||
                    activePlayerActivityInstanceId == activityInstanceId
            if (controlled && requestId != null && resultBelongsToCurrentActivity) {
                notePlayerExit(requestId, activityInstanceId = activityInstanceId)
            }
            if (
                requestId != null &&
                activePlayerRequestId == requestId &&
                resultBelongsToCurrentActivity
            ) {
                activePlayerRequestId = null
                activePlayerSessionId = null
                activePlayerActivityInstanceId = null
                activePlayerTransitionGeneration = 0L
                activePlayerCommandCreatedAtMs = 0L
            }
        }

    companion object {
        private const val BRIDGE_PROTOCOL_VERSION = 2
        private const val NATIVE_STORAGE_ONBOARDING_PREFS = "reiflix_native_storage_onboarding"
        private const val NATIVE_STORAGE_ONBOARDING_DISMISSED = "dismissed"
        private val SETTINGS_STORAGE_ACTIONS = setOf(
            "select_saf", "request_media_access", "check_storage_access", "open_broad_storage_settings",
        )

        @JvmStatic
        fun createNativeStorageActionIntent(
            context: Context,
            action: String,
            treeUri: String? = null,
            requestId: String? = null,
            reason: String? = null,
            triggerRequestId: String? = null,
        ): Intent {
            val normalized = action.trim()
            require(normalized in setOf(
                "select_tree", "request_media_access", "open_broad_storage_settings",
                "check_storage_access", "release_tree", "scan_library", "scan_tree",
                "scan_media_store", "scan_all_storage",
            )) {
                "Unsupported native storage action."
            }
            require(normalized != "release_tree" || !treeUri.isNullOrBlank()) {
                "The SAF tree URI is required for release_tree."
            }
            val builder = Uri.parse("reiflix://native").buildUpon()
                .appendQueryParameter("action", normalized)
                .appendQueryParameter("request_id", requestId?.trim()?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString())
                .appendQueryParameter("protocol_version", BRIDGE_PROTOCOL_VERSION.toString())
                .appendQueryParameter("created_at", System.currentTimeMillis().toString())
            treeUri?.trim()?.takeIf { it.isNotEmpty() }?.let { builder.appendQueryParameter("tree_uri", it) }
            reason?.trim()?.takeIf { it.isNotEmpty() }?.let { builder.appendQueryParameter("reason", it) }
            triggerRequestId?.trim()?.takeIf { it.isNotEmpty() }?.let {
                builder.appendQueryParameter("trigger_request_id", it)
            }
            if (normalized == "release_tree") builder.appendQueryParameter("native_folder_config_owner", "true")
            return Intent(Intent.ACTION_VIEW, builder.build(), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        private const val STATE_LAST_NATIVE_REQUEST_ID = "reiflix.lastNativeRequestId"
        private const val STATE_PENDING_LIFECYCLE_ACTION = "reiflix.pendingLifecycleAction"
        private const val STATE_BROAD_SETTINGS_PENDING = "reiflix.broadSettingsPending"
        private const val STATE_PENDING_LIFECYCLE_REQUEST_ID = "reiflix.pendingLifecycleRequestId"
        private const val STATE_PENDING_MEDIA_REQUEST_ID = "reiflix.pendingMediaRequestId"
        private const val STATE_PENDING_BROAD_REQUEST_ID = "reiflix.pendingBroadRequestId"
        private const val STATE_PENDING_SAF_REQUEST_ID = "reiflix.pendingSafRequestId"
        private const val STATE_PENDING_PLAY_URI = "reiflix.pendingPlayUri"
        private const val STATE_PENDING_PLAY_INTENT_DATA = "reiflix.pendingPlayIntentData"
        private const val STATE_PENDING_PLAY_TITLE = "reiflix.pendingPlayTitle"
        private const val STATE_PENDING_PLAY_POSITION_MS = "reiflix.pendingPlayPositionMs"
        private const val STATE_PENDING_PLAY_POSITION_SPECIFIED = "reiflix.pendingPlayPositionSpecified"
        private const val STATE_PENDING_PLAY_POSITION_INVALID = "reiflix.pendingPlayPositionInvalid"
        private const val STATE_PENDING_PLAY_CAN_NEXT = "reiflix.pendingPlayCanNext"
        private const val STATE_PENDING_PLAY_CAN_PREVIOUS = "reiflix.pendingPlayCanPrevious"
        private const val STATE_PENDING_PLAY_AUTOPLAY = "reiflix.pendingPlayAutoplay"
        private const val STATE_PENDING_PLAY_EPISODE_ID = "reiflix.pendingPlayEpisodeId"
        private const val STATE_PENDING_PLAY_ANIME_ID = "reiflix.pendingPlayAnimeId"
        private const val STATE_PENDING_PLAY_COMMAND_CREATED_AT_MS = "reiflix.pendingPlayCommandCreatedAtMs"
        private const val STATE_PENDING_PLAY_REQUEST_ID = "reiflix.pendingPlayRequestId"
        private const val STATE_ACTIVE_PLAYER_REQUEST_ID = "reiflix.activePlayerRequestId"
        private const val STATE_ACTIVE_PLAYER_COMMAND_CREATED_AT_MS = "reiflix.activePlayerCommandCreatedAtMs"
        private const val STATE_SAF_PICKER_PENDING = "reiflix.safPickerPending"
        private const val STATE_SAF_PICKER_STARTED_AT_MS = "reiflix.safPickerStartedAtMs"
        private const val STATE_SAF_PICKER_FOCUS_LOST = "reiflix.safPickerFocusLost"
        private const val STATE_SAF_PICKER_FOCUS_REGAINED_AT_MS = "reiflix.safPickerFocusRegainedAtMs"
        private const val STATE_SAF_PICKER_PHASE = "reiflix.safPickerPhase"
        private const val STATE_SEEN_NATIVE_REQUEST_IDS = "reiflix.seenNativeRequestIds"
        private const val STATE_STARTUP_DISCOVERY_TRIGGERED = "reiflix.startupDiscoveryTriggered"
        private const val STATE_AUTO_SAF_PICKER_ATTEMPTED = "reiflix.autoSafPickerAttempted"
        private const val STATE_LAST_OBSERVED_MEDIA_ACCESS = "reiflix.lastObservedMediaAccess"
        private const val STATE_LAST_OBSERVED_BROAD_ACCESS = "reiflix.lastObservedBroadAccess"
        private const val LOG_TAG = "[REIFLIX][ANDROID]"
        @Volatile
        private var lastPlayerExitRequestId: String? = null
        @Volatile
        private var lastPlayerExitAtMs: Long = 0L
        @Volatile
        private var activePlayerSessionId: String? = null
        @Volatile
        private var activePlayerRequestId: String? = null
        @Volatile
        private var activePlayerActivityInstanceId: String? = null
        @Volatile
        private var activePlayerTransitionGeneration: Long = 0L
        private val revokedPlayerTransitions = LinkedHashMap<String, String?>()
        private const val MAX_REVOKED_PLAYER_TRANSITIONS = 128

        @JvmStatic
        fun revokePlayerTransition(originRequestId: String, originPlayerSessionId: String? = null) {
            val request = originRequestId.trim()
            if (request.isBlank()) return
            synchronized(revokedPlayerTransitions) {
                revokedPlayerTransitions[request] = originPlayerSessionId?.trim()?.takeIf { it.isNotEmpty() }
                while (revokedPlayerTransitions.size > MAX_REVOKED_PLAYER_TRANSITIONS) {
                    revokedPlayerTransitions.remove(revokedPlayerTransitions.keys.first())
                }
            }
            Log.i(
                LOG_TAG,
                "PLAYER_TRANSITION_REVOKED originRequestId=" + request +
                    " originPlayerSessionId=" + (originPlayerSessionId ?: "-"),
            )
        }

        @JvmStatic
        fun isPlayerTransitionRevoked(originRequestId: String, originPlayerSessionId: String? = null): Boolean {
            val request = originRequestId.trim()
            if (request.isBlank()) return false
            synchronized(revokedPlayerTransitions) {
                val revokedSession = revokedPlayerTransitions[request] ?: return false
                val incomingSession = originPlayerSessionId?.trim().orEmpty()
                return revokedSession.isNullOrBlank() || incomingSession.isBlank() || revokedSession == incomingSession
            }
        }

        @JvmStatic
        fun notePlayerSession(requestId: String, sessionId: String, transitionGeneration: Long = 0L) {
            val normalizedRequest = requestId.trim().takeIf { it.isNotEmpty() }
            val normalizedSession = sessionId.trim().takeIf { it.isNotEmpty() }
            if (normalizedSession == null) return
            activePlayerRequestId = normalizedRequest
            activePlayerSessionId = normalizedSession
            activePlayerTransitionGeneration = transitionGeneration.coerceAtLeast(0L)
            Log.i(
                LOG_TAG,
                "PLAYER_SESSION_ACTIVE requestId=" + (normalizedRequest ?: "-") +
                    " playerSessionId=" + normalizedSession +
                    " transitionGeneration=" + activePlayerTransitionGeneration,
            )
        }

        @JvmStatic
        fun notePlayerActivityCreated(instanceId: String, requestId: String, sessionId: String, transitionGeneration: Long = 0L) {
            val normalizedInstance = instanceId.trim().takeIf { it.isNotEmpty() } ?: return
            val normalizedSession = sessionId.trim().takeIf { it.isNotEmpty() } ?: return
            activePlayerActivityInstanceId = normalizedInstance
            activePlayerRequestId = requestId.trim().takeIf { it.isNotEmpty() }
            activePlayerSessionId = normalizedSession
            activePlayerTransitionGeneration = transitionGeneration.coerceAtLeast(0L)
            Log.i(
                LOG_TAG,
                "PLAYER_ACTIVITY_INSTANCE_ACTIVE instanceId=" + normalizedInstance +
                    " requestId=" + (activePlayerRequestId ?: "-") +
                    " playerSessionId=" + normalizedSession +
                    " transitionGeneration=" + activePlayerTransitionGeneration,
            )
        }

        @JvmStatic
        fun notePlayerActivityDestroyed(instanceId: String, sessionId: String) {
            val normalizedInstance = instanceId.trim()
            val normalizedSession = sessionId.trim()
            if (
                normalizedInstance.isNotBlank() &&
                activePlayerActivityInstanceId == normalizedInstance &&
                (normalizedSession.isBlank() || activePlayerSessionId == normalizedSession)
            ) {
                activePlayerActivityInstanceId = null
                Log.i(
                    LOG_TAG,
                    "PLAYER_ACTIVITY_INSTANCE_INACTIVE instanceId=" + normalizedInstance +
                        " playerSessionId=" + (activePlayerSessionId ?: "-"),
                )
            }
        }

        @JvmStatic
        fun isCurrentPlayerHandoff(
            requestId: String,
            originRequestId: String,
            originCreatedAtMs: Long,
            playerSessionId: String,
            originPlayerSessionId: String,
            originTransitionGeneration: Long,
        ): Boolean {
            val request = requestId.trim()
            val origin = originRequestId.trim()
            val session = playerSessionId.trim()
            val originSession = originPlayerSessionId.trim()
            if (request.isBlank() || origin.isBlank() || session.isBlank() || originSession.isBlank()) return false
            if (request == origin) return false
            if (activePlayerRequestId != request) return false
            if (activePlayerSessionId != session || originSession != session) return false
            if (originTransitionGeneration <= 0L) return false
            if (originTransitionGeneration != activePlayerTransitionGeneration + 1L) return false
            if (lastPlayerExitAtMs >= originCreatedAtMs && originCreatedAtMs > 0L) return false
            if (isPlayerTransitionRevoked(origin, originSession)) return false
            return true
        }

        @JvmStatic
        fun notePlayerExit(
            requestId: String,
            atMs: Long = System.currentTimeMillis(),
            sessionId: String? = null,
            activityInstanceId: String? = null,
        ) {
            val normalizedRequest = requestId.trim().takeIf { it.isNotEmpty() }
            val normalizedSession = sessionId?.trim()?.takeIf { it.isNotEmpty() }
            lastPlayerExitRequestId = normalizedRequest
            lastPlayerExitAtMs = maxOf(lastPlayerExitAtMs, atMs)
            if (
                normalizedRequest == null ||
                (
                    activePlayerRequestId == normalizedRequest &&
                    (
                        activityInstanceId.isNullOrBlank() ||
                        activePlayerActivityInstanceId == activityInstanceId.trim()
                    )
                ) ||
                (
                    activePlayerRequestId == null &&
                    normalizedSession != null &&
                    activePlayerSessionId == normalizedSession &&
                    (
                        activityInstanceId.isNullOrBlank() ||
                        activePlayerActivityInstanceId == activityInstanceId.trim()
                    )
                )
            ) {
                activePlayerRequestId = null
                activePlayerSessionId = null
                activePlayerActivityInstanceId = null
                activePlayerTransitionGeneration = 0L
            }
            Log.i(
                LOG_TAG,
                "PLAYER_SESSION_EXIT requestId=" + (normalizedRequest ?: "-") +
                    " playerSessionId=" + (normalizedSession ?: "-") +
                    " activityInstanceId=" + (activityInstanceId ?: "-") +
                    " atMs=" + atMs,
            )
        }

        private val safInventoryInFlight = AtomicBoolean(false)
        private const val SAF_PICKER_LAUNCH_TIMEOUT_MS = 5000L
        private const val SAF_PICKER_RETURN_GRACE_MS = 2500L
        private const val SAF_PICKER_WATCHDOG_RETRY_MS = 250L

    }
    /**
     * Native lifecycle events dispatch directly to Kotlin scanners. The global
     * completion event includes terminal results for SAF roots and every authorized
     * MediaStore/BroadStorage source selected for this request. Python's ScanCoordinator
     * remains only for legacy Flet-originated requests.
     */
    private fun publishScanRequest(
        origin: String,
        source: String? = null,
        scopeRef: String? = null,
        full: Boolean = false,
        reason: String = "",
        triggerRequestId: String? = null,
    ) {
        val requestId = triggerRequestId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        Log.i(
            tag,
            "NATIVE_SCAN_REQUEST requestId=" + requestId +
                " origin=" + origin +
                " source=" + (source ?: "configured_saf_roots") +
                " scopeRef=" + (scopeRef ?: "-") +
                " full=" + full +
                " reason=" + (reason.ifBlank { "-" }),
        )
        when {
            source == "saf" && !scopeRef.isNullOrBlank() -> scanTree(scopeRef, requestId)
            source == "mediastore" -> scanMediaStore(requestId)
            source == "broad_storage" -> scanAllStorage(requestId)
            else -> scanConfiguredSafRoots(
                requestId = requestId,
                origin = origin,
                reason = reason,
                includeAuthorizedDiagnostics = source == null,
            )
        }
    }
    private val pendingSafReleaseWaiters = mutableMapOf<String, MutableList<String>>()
    private var storageReceiverRegistered = false
    private var systemBackDispatchPosted = false
    private var systemBackEventCount = 0
    private var externalSettingsKind: String? = null
    private var externalSettingsRequestId: String? = null
    private val externalSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            val kind = externalSettingsKind
            val requestId = externalSettingsRequestId
            externalSettingsKind = null
            externalSettingsRequestId = null
            Log.i(
                tag,
                "SETTINGS_RETURN kind=" + (kind ?: "-") +
                    " resultCode=" + result.resultCode +
                    " requestId=" + (requestId ?: "-") +
                    " broad=" + BroadStorageScanner.hasAccess(this) +
                    " media=" + MediaStoreScanner.accessLevel(this),
            )
            when (kind) {
                "broad_storage" -> handleBroadSettingsReturn(requestId, "activity_result")
                else -> {
                    // App Info and other Android permission surfaces must still
                    // converge to the authoritative native snapshot on return.
                    Log.i(tag, "SETTINGS_RETURN revalidating non-broad surface kind=" + (kind ?: "-"))
                    publishStorageStatus()
                    refreshNativeStorageOnboardingState("external_settings_return")
                }
            }
        }

    private fun scheduleMediaStoreIncrementalRescan() {
        MediaStoreRetryScheduler.schedule(
            applicationContext,
            reason = "content_observer_debounce",
        )
    }
    private val storageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            val action = intent.action ?: return
            val changes = NativeIndex.updateVolumeSnapshot(
                applicationContext,
                NativeIndex.volumeSnapshot(applicationContext),
            )
            val discoveryEvent = action == Intent.ACTION_MEDIA_MOUNTED ||
                action == Intent.ACTION_MEDIA_SCANNER_FINISHED
            if (changes.optBoolean("changed")) {
                val payload = JSONObject(changes.toString())
                    .put("source", "android_storage")
                    .put("reason", action)
                    .put("timestamp", System.currentTimeMillis())
                    .put("volumeEventUri", intent.data?.toString() ?: "")
                    .put("nativeScanDispatchOwner", true)
                lifecycleScope.launch {
                    try {
                        val volumeState = withContext(Dispatchers.IO) {
                            appDependencies.libraryService.recordNativeVolumeChange(payload)
                        }
                        payload.put("nativeStorageOwner", true)
                            .put("nativeVolumeStateIgnored", volumeState.optBoolean("ignored"))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (exception: Exception) {
                        // Preserve a safe compatibility fallback if the canonical write fails.
                        payload.put("nativeStorageOwner", false)
                            .put("nativeVolumePersistenceError", exception::class.java.simpleName)
                        Log.e(tag, "Could not persist native volume availability; delegating only this failed write", exception)
                    }
                    NativeMailbox.writeBestEffort(
                        this@MainActivity,
                        JSONObject().put("type", "volume_changed").put("payload", payload),
                    )
                    if (discoveryEvent && activityResumed) {
                        publishScanRequest(
                            "VOLUME_MOUNT",
                            source = null,
                            full = false,
                            reason = action,
                        )
                    }
                }
            } else if (discoveryEvent && activityResumed) {
                publishScanRequest(
                    "VOLUME_MOUNT",
                    source = null,
                    full = false,
                    reason = action,
                )
            }
            if (action == Intent.ACTION_MEDIA_UNMOUNTED ||
                action == Intent.ACTION_MEDIA_EJECT ||
                action == Intent.ACTION_MEDIA_REMOVED ||
                action == Intent.ACTION_MEDIA_BAD_REMOVAL) {
                publishStorageStatus()
            }
        }
    }

    private fun registerStorageReceiver() {
        if (storageReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_CHECKING)
            addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED)
            addDataScheme("file")
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(storageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(storageReceiver, filter)
            }
            storageReceiverRegistered = true
        } catch (exception: Exception) {
            Log.w(tag, "Unable to register storage volume receiver", exception)
        }
    }

    private fun unregisterStorageReceiver() {
        if (!storageReceiverRegistered) return
        runCatching { unregisterReceiver(storageReceiver) }
        storageReceiverRegistered = false
    }
    private val mediaPermissionRequester = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        mediaPermissionRequestPending = false
        val requestId = pendingMediaRequestId
        pendingMediaRequestId = null
        val access = MediaStoreScanner.accessLevel(this)
        // Android's callback map is only a notification; the current package permissions are authoritative.
        val granted = access != "denied"
        Log.i(tag, "MEDIA_PERMISSION_CALLBACK grants=" + grants + " access=" + access + " granted=" + granted)
        NativeMailbox.write(this, JSONObject().put("type", "diagnostic").put("payload", JSONObject().put("event", "PERMISSION_RESULT").put("source", MediaStoreScanner.SOURCE).put("access", access)))
        NativeMailbox.write(this, JSONObject().put("type", "mediastore_permission")
            .put("requestId", requestId ?: "")
            .put("payload", JSONObject()
                .put("granted", granted)
                .put("access", access)
                .put("source", MediaStoreScanner.SOURCE)
                .put("capabilities", storageCapabilitiesPayload(StorageLifecycleState.REVALIDATED))))
        if (granted) {
            completeComposeSettingsStorageAction(
                requestId,
                "SUCCESS",
                message = "Permissão de acesso aos vídeos confirmada.",
            )
            nativeRequestState.markOperationState(requestId, "request_media_access", NativeRequestState.OperationState.COMPLETED)
            scanMediaStore(requestId)
        } else {
            completeComposeSettingsStorageAction(
                requestId,
                "ERROR",
                error = "A permissão de acesso aos vídeos foi negada.",
            )
            nativeRequestState.markOperationState(requestId, "request_media_access", NativeRequestState.OperationState.FAILED)
            NativeMailbox.write(this, JSONObject().put("type", "mediastore_error")
                .put("requestId", requestId ?: "")
                .put("message", "A permissão para acessar os vídeos do dispositivo foi negada.")
                .put("payload", JSONObject().put("source", MediaStoreScanner.SOURCE).put("status", NativeIndex.STATUS_FAILED).put("access", access)))
        }
    }
    private val treePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        handleTreePickerResult(result)
    }

    private var pendingSettingsFileAction: String? = null
    private var pendingSettingsFileRequestId: String? = null
    private var settingsFileProcessing = false
    private val composeSettingsStorageActions = mutableMapOf<String, String>()

    private val settingsExportLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
            settingsExportResult@{ uri: Uri? ->
            val action = pendingSettingsFileAction
            val requestId = pendingSettingsFileRequestId
            pendingSettingsFileAction = null
            pendingSettingsFileRequestId = null
            if (action != "settings_export" || requestId.isNullOrBlank()) return@settingsExportResult
            if (uri == null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    writeSettingsFileActionResult(
                        requestId, action, "CANCELLED", "CANCELLED",
                        message = "Exportação cancelada.",
                    )
                }
                return@settingsExportResult
            }
            if (settingsFileProcessing) {
                lifecycleScope.launch(Dispatchers.IO) {
                    writeSettingsFileActionResult(
                        requestId, action, "ERROR", "ERROR",
                        error = "Outra operação de arquivo de configurações está em andamento.",
                    )
                }
                return@settingsExportResult
            }
            settingsFileProcessing = true
            lifecycleScope.launch {
                try {
                    val json = withContext(Dispatchers.IO) {
                        ReiAnixSettingsFileService(appDependencies.sqliteSettingsRepository).exportJson()
                    }
                    withContext(Dispatchers.IO) {
                        val output = contentResolver.openOutputStream(uri, "wt")
                            ?: throw IllegalStateException("O Android não abriu o destino da exportação.")
                        output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    }
                    withContext(Dispatchers.IO) {
                        writeSettingsFileActionResult(
                            requestId, action, "SUCCESS", "SUCCESS",
                            message = "Configurações exportadas.",
                        )
                    }
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        writeSettingsFileActionResult(
                            requestId, action, "CANCELLED", "CANCELLED",
                            error = "A exportação de configurações foi cancelada.",
                        )
                    }
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(tag, "SETTINGS_EXPORT_NATIVE_FAILED requestId=" + requestId, error)
                    withContext(Dispatchers.IO) {
                        writeSettingsFileActionResult(
                            requestId, action, "ERROR", "ERROR",
                            error = "Não foi possível exportar as configurações.",
                        )
                    }
                } finally {
                    settingsFileProcessing = false
                }
            }
            },
        )

    private val settingsImportLauncher =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
            settingsImportResult@{ uri: Uri? ->
            val action = pendingSettingsFileAction
            val requestId = pendingSettingsFileRequestId
            pendingSettingsFileAction = null
            pendingSettingsFileRequestId = null
            if (action != "settings_import" || requestId.isNullOrBlank()) return@settingsImportResult
            if (uri == null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    writeSettingsFileActionResult(
                        requestId, action, "CANCELLED", "CANCELLED",
                        message = "Importação cancelada.",
                    )
                }
                return@settingsImportResult
            }
            if (settingsFileProcessing) {
                lifecycleScope.launch(Dispatchers.IO) {
                    writeSettingsFileActionResult(
                        requestId, action, "ERROR", "ERROR",
                        error = "Outra operação de arquivo de configurações está em andamento.",
                    )
                }
                return@settingsImportResult
            }
            settingsFileProcessing = true
            lifecycleScope.launch {
                try {
                    val raw = withContext(Dispatchers.IO) {
                        val input = contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("O Android não abriu o arquivo selecionado.")
                        input.use { stream ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            var total = 0
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                total += count
                                require(total <= MAX_SETTINGS_FILE_BYTES) {
                                    "O arquivo de configurações excede o limite permitido."
                                }
                                output.write(buffer, 0, count)
                            }
                            output.toString(Charsets.UTF_8.name())
                        }
                    }
                    val result = ReiAnixSettingsFileService(appDependencies.sqliteSettingsRepository)
                        .importJson(raw)
                    settingsViewModel.refresh()
                    if (result.values.isNotEmpty()) {
                        val settingsPayload = JSONObject().apply {
                            result.values.forEach { (key, value) -> put(key, value) }
                        }
                        // Notify legacy Flet consumers only after Kotlin confirms the SQLite commit.
                        val notified = runCatching {
                            NativeMailbox.write(
                                this@MainActivity,
                                JSONObject()
                                    .put("type", "compose_settings_bulk_applied")
                                    .put("requestId", requestId)
                                    .put(
                                        "payload",
                                        JSONObject()
                                            .put("requestId", requestId)
                                            .put("settings", settingsPayload),
                                    ),
                            )
                        }.getOrElse { false }
                        Log.i(
                            tag,
                            "SETTINGS_IMPORT_NATIVE_COMMITTED requestId=" + requestId +
                                " imported=" + result.imported +
                                " unknown=" + result.unknownKeys.size +
                                " legacyRuntimeNotificationPublished=" + notified,
                        )
                    }
                    withContext(Dispatchers.IO) {
                        writeSettingsFileActionResult(
                            requestId, action, "SUCCESS", "SUCCESS",
                            message = "Configurações importadas: ${result.imported}.",
                        )
                    }
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        writeSettingsFileActionResult(
                            requestId, action, "CANCELLED", "CANCELLED",
                            error = "A importação de configurações foi cancelada.",
                        )
                    }
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(tag, "SETTINGS_IMPORT_NATIVE_FAILED requestId=" + requestId, error)
                    withContext(Dispatchers.IO) {
                        writeSettingsFileActionResult(
                            requestId, action, "ERROR", "ERROR",
                            error = error.message?.takeIf { it.isNotBlank() }
                                ?: "Não foi possível importar as configurações.",
                        )
                    }
                } finally {
                    settingsFileProcessing = false
                }
            }
            },
        )


    private val backupExportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
            finishNativeBackupExport(uri)
        }

    private val backupImportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            finishNativeBackupImport(uri)
        }

    private val diagnosticExportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            finishNativeDiagnosticExport(uri)
        }

    private fun cancelSafPickerWatchdog() {
        safPickerWatchdog?.let { safPickerWatchdogHandler.removeCallbacks(it) }
        safPickerWatchdog = null
    }

    private fun setSafPickerPhase(requestId: String?, phase: SafPickerPhase) {
        if (safPickerPhase == phase) return
        safPickerPhase = phase
        val now = System.currentTimeMillis()
        Log.i(tag, "SAF_PICKER_STATE requestId=" + (requestId ?: "-") +
            " phase=" + phase.name + " at=" + now)
        NativeMailbox.write(this, JSONObject().put("type", "diagnostic")
            .put("requestId", requestId ?: "")
            .put("payload", JSONObject().put("event", "SAF_PICKER_STATE")
                .put("state", phase.name).put("requestId", requestId ?: "").put("timestamp", now)))
    }

    private fun clearSafPickerPending(requestId: String?, terminalPhase: SafPickerPhase) {
        cancelSafPickerWatchdog()
        safPickerPending = false
        pendingSafRequestId = null
        safPickerStartedAtMs = 0L
        safPickerFocusLost = false
        safPickerFocusRegainedAtMs = 0L
        val terminalState = when (terminalPhase) {
            SafPickerPhase.COMPLETED -> NativeRequestState.OperationState.COMPLETED
            SafPickerPhase.CANCELLED -> NativeRequestState.OperationState.CANCELLED
            SafPickerPhase.TIMEOUT -> NativeRequestState.OperationState.TIMEOUT
            else -> NativeRequestState.OperationState.FAILED
        }
        nativeRequestState.markOperationState(requestId, "select_tree", terminalState)
        setSafPickerPhase(requestId, terminalPhase)
    }

    private fun publishSafPickerError(
        requestId: String?, message: String, stage: String, code: String,
        status: String = SafScanner.STATUS_FAILED, treeUri: Uri? = null,
    ) {
        _nativeStorageOnboardingState.value = NativeStorageOnboardingState(
            phase = NativeStorageOnboardingState.PHASE_ERROR,
            message = message,
            error = message,
            dismissed = false,
        )
        getSharedPreferences(NATIVE_STORAGE_ONBOARDING_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(NATIVE_STORAGE_ONBOARDING_DISMISSED, false)
            .apply()
        completeComposeSettingsStorageAction(
            requestId,
            "ERROR",
            error = message,
        )
        nativeRequestState.markOperationState(requestId, "select_tree", NativeRequestState.OperationState.FAILED)
        val payload = JSONObject().put("source", "saf").put("status", status)
            .put("stage", stage).put("code", code)
        if (treeUri != null && treeUri.scheme.equals("content", true)) {
            payload.put("treeUri", treeUri.toString())
        }
        NativeMailbox.write(this, JSONObject().put("type", "saf_error")
            .put("requestId", requestId ?: "").put("message", message).put("payload", payload))
    }

    private fun timeoutSafPicker(
        requestId: String?,
        stage: String = "picker_watchdog",
        code: String = "TIMEOUT",
        message: String = "Não foi possível abrir o seletor de pastas do Android. Tente novamente.",
    ) {
        if (!safPickerPending || pendingSafRequestId != requestId) return
        Log.e(tag, "SAF_PICKER_TIMEOUT requestId=" + (requestId ?: "-") +
            " stage=" + stage + " code=" + code +
            " phase=" + safPickerPhase.name +
            " startedAt=" + safPickerStartedAtMs + " now=" + System.currentTimeMillis() +
            " focusLost=" + safPickerFocusLost +
            " focusRegainedAt=" + safPickerFocusRegainedAtMs)
        clearSafPickerPending(requestId, SafPickerPhase.TIMEOUT)
        publishSafPickerError(requestId, message, stage = stage, code = code)
    }

    private fun scheduleSafPickerWatchdog(requestId: String?) {
        val correlationId = requestId?.trim().orEmpty()
        if (!safPickerPending || correlationId.isBlank()) return
        cancelSafPickerWatchdog()
        val runnable = object : Runnable {
            override fun run() {
                if (!safPickerPending || pendingSafRequestId != correlationId) return

                // The Android result callback normally completes the request before
                // focus returns. Keep the picker alive while DocumentsUI is foreground,
                // but recover if ReiAnix regains focus and ActivityResult never arrives.
                val phase = safPickerPhase
                val launchPending =
                    phase == SafPickerPhase.REQUESTED || phase == SafPickerPhase.LAUNCHING
                val resultPending = phase == SafPickerPhase.WAITING_RESULT
                if (!launchPending && !resultPending) {
                    cancelSafPickerWatchdog()
                    return
                }

                val now = System.currentTimeMillis()
                val focused = window?.decorView?.hasWindowFocus() == true
                if (!activityResumed || !focused) {
                    safPickerWatchdogHandler.postDelayed(this, SAF_PICKER_WATCHDOG_RETRY_MS)
                    return
                }

                if (resultPending) {
                    // Do not require a separate focus-loss callback: some providers omit
                    // it, otherwise recovery is cancelled indefinitely after the return.
                    if (safPickerFocusRegainedAtMs <= 0L) {
                        safPickerFocusRegainedAtMs = now
                    }
                    val elapsedAfterReturn = now - safPickerFocusRegainedAtMs
                    if (elapsedAfterReturn >= SAF_PICKER_RETURN_GRACE_MS) {
                        timeoutSafPicker(
                            correlationId,
                            stage = "picker_result_watchdog",
                            code = "RESULT_CALLBACK_TIMEOUT",
                            message = "O Android voltou do seletor, mas o ReiAnix não recebeu o resultado da pasta. Toque em ESCOLHER PASTA para tentar novamente.",
                        )
                    } else {
                        safPickerWatchdogHandler.postDelayed(
                            this,
                            maxOf(1L, SAF_PICKER_RETURN_GRACE_MS - elapsedAfterReturn),
                        )
                    }
                    return
                }

                val elapsed = now - safPickerStartedAtMs
                if (elapsed >= SAF_PICKER_LAUNCH_TIMEOUT_MS) {
                    timeoutSafPicker(correlationId)
                } else {
                    safPickerWatchdogHandler.postDelayed(
                        this,
                        SAF_PICKER_LAUNCH_TIMEOUT_MS - elapsed,
                    )
                }
            }
        }
        safPickerWatchdog = runnable
        safPickerWatchdogHandler.postDelayed(runnable, SAF_PICKER_WATCHDOG_RETRY_MS)
    }

    private fun handleTreePickerResult(result: androidx.activity.result.ActivityResult) {
        val resultIntent = result.data
        val uri = resultIntent?.data
        val requestId = pendingSafRequestId
        Log.i(tag, "SAF_PICKER_ACTIVITY_RESULT requestId=" + (requestId ?: "-") +
            " resultCode=" + result.resultCode + " hasUri=" + (uri != null) +
            " timestamp=" + System.currentTimeMillis())
        publishNativeDiagnostic(
            "SAF_PICKER_RESULT",
            requestId,
            action = "select_tree",
            result = if (result.resultCode == RESULT_OK && uri != null) "RESULT_OK" else "RESULT_CANCELED",
        )

        if (result.resultCode != RESULT_OK || uri == null) {
            publishNativeDiagnostic(
                "STORAGE_PICKER_CANCELLED",
                requestId,
                action = "select_tree",
                result = "picker_cancelled",
            )
            publishNativeDiagnostic(
                "STORAGE_CANCELLED",
                requestId,
                action = "select_tree",
                result = "user_cancelled_picker",
            )
            clearSafPickerPending(requestId, SafPickerPhase.CANCELLED)
            getSharedPreferences(NATIVE_STORAGE_ONBOARDING_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(NATIVE_STORAGE_ONBOARDING_DISMISSED, false)
                .apply()
            _nativeStorageOnboardingState.value = NativeStorageOnboardingState(
                phase = NativeStorageOnboardingState.PHASE_NEEDS_FOLDER,
                message = "Seleção de pasta cancelada. A biblioteca não foi alterada; você pode tentar novamente.",
            )
            // If this was an attempt to add/replace a source while another valid
            // root already existed, cancellation must not obscure that working root.
            refreshNativeStorageOnboardingState("saf_picker_cancelled")
            completeComposeSettingsStorageAction(
                requestId,
                "CANCELLED",
                message = "Seleção de pasta cancelada.",
            )
            Log.i(tag, "SAF selection cancelled resultCode=" + result.resultCode)
            NativeMailbox.write(this, JSONObject().put("type", "saf_cancelled")
                .put("requestId", requestId ?: "").put("payload", JSONObject()
                    .put("source", "saf").put("reason", "picker_cancelled")))
            return
        }

        setSafPickerPhase(requestId, SafPickerPhase.WAITING_RESULT)
        try {
            if (uri.scheme?.lowercase() != "content" || !DocumentsContract.isTreeUri(uri)) {
                clearSafPickerPending(requestId, SafPickerPhase.FAILED)
                publishSafPickerError(requestId, "A pasta selecionada não é uma árvore SAF válida.",
                    stage = "selection_validation", code = "INVALID_TREE_URI")
                return
            }
            Log.i(tag, "SAF_PICKER_RESULT_VALID requestId=" + (requestId ?: "-") +
                " authority=" + (uri.authority ?: "-"))
            publishNativeDiagnostic("STORAGE_ROOT_SELECTED", requestId, action = "select_tree", result = uri.toString())
            val transientInspection = SafScanner.inspectTree(this, uri, requirePersisted = false)
            val transientStatus = transientInspection.optString("status")
            if (transientStatus != SafScanner.STATUS_COMPLETED) {
                val status = if (transientStatus == SafScanner.STATUS_REVOKED) SafScanner.STATUS_REVOKED else SafScanner.STATUS_UNAVAILABLE
                clearSafPickerPending(requestId, SafPickerPhase.FAILED)
                publishSafPickerError(requestId, "O provedor não conseguiu abrir a pasta selecionada.",
                    stage = "selection_validation", code = "PROVIDER_UNAVAILABLE", status = status, treeUri = uri)
                return
            }
            val flags = resultIntent.flags
            SafScanner.persistPermission(this, uri, flags)
            publishNativeDiagnostic("STORAGE_PERMISSION_PERSISTED", requestId, action = "select_tree", result = uri.toString())
            publishNativeDiagnostic("SAF_PERMISSION_PERSISTED", requestId, action = "select_tree", result = uri.toString())
            val persistedInspection = SafScanner.inspectTree(this, uri, requirePersisted = true)
            val persistedStatus = persistedInspection.optString("status")
            if (persistedStatus != SafScanner.STATUS_COMPLETED) {
                val status = if (!SafScanner.hasPersistedReadPermission(this, uri)) SafScanner.STATUS_REVOKED else SafScanner.STATUS_UNAVAILABLE
                clearSafPickerPending(requestId, SafPickerPhase.FAILED)
                publishSafPickerError(requestId, "A autorização da pasta não pôde ser validada.",
                    stage = "persisted_validation", code = "PERSISTED_PERMISSION_INVALID",
                    status = status, treeUri = uri)
                return
            }
            publishNativeDiagnostic("STORAGE_ROOT_VALIDATED", requestId, action = "select_tree", result = uri.toString())
            publishNativeDiagnostic("STORAGE_READY", requestId, action = "select_tree", result = "persisted_and_validated")
            val selectedIdentity = SafScanner.identityPayload(uri)
            val selectedName = SafScanner.displayName(this, uri)
            clearSafPickerPending(requestId, SafPickerPhase.COMPLETED)
            lifecycleScope.launch {
                val configuredReference = try {
                    withContext(Dispatchers.IO) {
                        appDependencies.sqliteLibraryRepository.upsertSafFolder(
                            path = uri.toString(),
                            name = selectedName,
                            authorization = "granted",
                            safAuthority = selectedIdentity.optString("authority").takeIf { it.isNotBlank() },
                            safDocumentId = selectedIdentity.optString("documentId").takeIf { it.isNotBlank() },
                            safVolumeId = selectedIdentity.optString("volumeId").takeIf { it.isNotBlank() },
                            safIdentity = selectedIdentity.optString("identity").takeIf { it.isNotBlank() },
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    Log.e(tag, "SAF source registration failed; previous configured sources were preserved.", exception)
                    publishSafPickerError(requestId,
                        "A pasta recebeu permissão, mas não foi possível registrá-la na biblioteca. Os dados anteriores foram preservados.",
                        stage = "source_registration", code = "SOURCE_PERSISTENCE_FAILED",
                        status = SafScanner.STATUS_FAILED, treeUri = uri)
                    return@launch
                }
                getSharedPreferences(NATIVE_STORAGE_ONBOARDING_PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(NATIVE_STORAGE_ONBOARDING_DISMISSED, false)
                    .apply()
                _nativeStorageOnboardingState.value = NativeStorageOnboardingState(
                    phase = NativeStorageOnboardingState.PHASE_READY,
                )
                completeComposeSettingsStorageAction(
                    requestId,
                    "SUCCESS",
                    message = "Pasta adicionada à biblioteca.",
                )
                val payload = JSONObject(selectedIdentity.toString())
                    .put("treeUri", configuredReference).put("granted", true).put("selected", true)
                    .put("status", SafScanner.STATUS_COMPLETED).put("name", selectedName)
                    .put("nativeFolderConfigOwner", true)
                    .put("capabilities", storageCapabilitiesPayload(StorageLifecycleState.REVALIDATED))
                runCatching {
                    NativeMailbox.write(this@MainActivity, JSONObject().put("type", "saf_permission")
                        .put("requestId", requestId ?: "").put("payload", payload))
                }.onFailure { Log.w(tag, "Native SAF registration succeeded, but compatibility state publication failed.", it) }
                publishNativeDiagnostic("PERMISSION_CHANGE", requestId, action = "select_tree", result = "saf_granted")
                publishNativeDiagnostic("STARTUP_SCAN_REQUESTED", requestId, action = "scan", result = "permission_change")
                publishNativeDiagnostic("STORAGE_SCAN_STARTED", requestId, action = "select_tree", result = "permission_change")
                scanTree(configuredReference, requestId)
            }
        } catch (exception: IllegalArgumentException) {
            clearSafPickerPending(requestId, SafPickerPhase.FAILED)
            Log.e(tag, "Invalid SAF selection", exception)
            publishSafPickerError(requestId, "A pasta selecionada não é uma árvore SAF válida.",
                stage = "selection_validation", code = "INVALID_TREE_URI")
        } catch (exception: Exception) {
            clearSafPickerPending(requestId, SafPickerPhase.FAILED)
            Log.e(tag, "SAF selection failed", exception)
            publishSafPickerError(requestId,
                "Não foi possível autorizar esta pasta. Escolha-a novamente.",
                stage = "persist", code = "PERSISTENCE_FAILED",
                status = if (SafScanner.hasPersistedReadPermission(this, uri))
                    SafScanner.STATUS_UNAVAILABLE else SafScanner.STATUS_FAILED, treeUri = uri)
        }
    }
    private fun logLifecycle(event: String, intent: Intent? = null) {
        val data = intent?.data
        val action = data?.getQueryParameter("action")
        val requestId = data?.getQueryParameter("request_id")
        Log.i(
            tag,
            "LIFECYCLE $event instance=${System.identityHashCode(this)} task=$taskId resumed=$activityResumed " +
                "focused=${window?.decorView?.hasWindowFocus() == true} finishing=$isFinishing " +
                "action=${action ?: "-"} requestId=${requestId ?: "-"} flags=0x${intent?.flags?.toString(16) ?: "0"}"
        )
        NativeMailbox.writeBestEffort(
            this,
            JSONObject().put("type", "diagnostic")
                .put("requestId", requestId ?: "")
                .put("payload", JSONObject()
                    .put("event", "MAIN_ACTIVITY_LIFECYCLE")
                    .put("lifecycle", event)
                    .put("requestId", requestId ?: "")
                    .put("activeRequestId", activePlayerRequestId ?: "")
                    .put("activeCommandCreatedAtMs", activePlayerCommandCreatedAtMs)
                    .put("activityElapsedRealtimeNs", SystemClock.elapsedRealtimeNanos())
                    .put("resumed", activityResumed)
                    .put("focused", window?.decorView?.hasWindowFocus() == true)
                    .put("finishing", isFinishing)),
        )
    }

    private fun publishInteractionProfileIfChanged(force: Boolean = false) {
        val profile = DeviceInteractionProfile.detect(this)
        val fingerprint = profile.fingerprint()
        if (!force && fingerprint == interactionProfileFingerprint) return
        interactionProfileFingerprint = fingerprint
        NativeMailbox.writeBestEffort(
            this,
            JSONObject()
                .put("type", "device_interaction_profile")
                .put("payload", profile.toJson().put("fingerprint", fingerprint)),
        )
        Log.i(tag, "DEVICE_INTERACTION_PROFILE fingerprint=" + fingerprint + " profile=" + profile.toJson())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchIntent = intent
        appDependencies.bootstrap.start()
        PerformanceDiagnostics.attach(this)
        PerformanceDiagnostics.sampleMemory(this, "main_on_create")
        installSystemBackHandler()
        nativeRequestState.bind(this)
        // The process-scoped application bootstrap owns interrupted-scan recovery and command dispatch.
        // Repeated calls are idempotent and never create another service graph.
        nativeRequestState.restore(
            savedInstanceState?.getString(STATE_LAST_NATIVE_REQUEST_ID),
            savedInstanceState?.getString(STATE_PENDING_LIFECYCLE_ACTION),
            savedInstanceState?.getString(STATE_PENDING_LIFECYCLE_REQUEST_ID),
        )
        nativeRequestState.restoreSeenRequestIds(savedInstanceState?.getString(STATE_SEEN_NATIVE_REQUEST_IDS))
        broadStoragePermissionPending = savedInstanceState?.getBoolean(STATE_BROAD_SETTINGS_PENDING) ?: false
        externalSettingsKind = savedInstanceState?.getString("reiflix.externalSettingsKind")
        externalSettingsRequestId = savedInstanceState?.getString("reiflix.externalSettingsRequestId")
        pendingSettingsFileAction = savedInstanceState?.getString("reiflix.settingsFileAction")
        pendingSettingsFileRequestId = savedInstanceState?.getString("reiflix.settingsFileRequestId")
        settingsFileProcessing = false
        val restoredSettingsStorageIds = savedInstanceState?.getStringArrayList("reiflix.settingsStorageIds").orEmpty()
        val restoredSettingsStorageActions = savedInstanceState?.getStringArrayList("reiflix.settingsStorageActions").orEmpty()
        composeSettingsStorageActions.clear()
        restoredSettingsStorageIds.zip(restoredSettingsStorageActions).forEach { (id, action) ->
            if (SETTINGS_FILE_REQUEST_ID_REGEX.matches(id) && action in SETTINGS_STORAGE_ACTIONS) {
                composeSettingsStorageActions[id] = action
            }
        }
        pendingMediaRequestId = savedInstanceState?.getString(STATE_PENDING_MEDIA_REQUEST_ID)
        pendingBroadRequestId = savedInstanceState?.getString(STATE_PENDING_BROAD_REQUEST_ID)
        pendingSafRequestId = savedInstanceState?.getString(STATE_PENDING_SAF_REQUEST_ID)
        pendingPlayUri = savedInstanceState?.getString(STATE_PENDING_PLAY_URI)
        pendingPlayIntentData = savedInstanceState?.getString(STATE_PENDING_PLAY_INTENT_DATA)
        pendingPlayTitle = savedInstanceState?.getString(STATE_PENDING_PLAY_TITLE)
        pendingPlayPositionMs = savedInstanceState?.getLong(STATE_PENDING_PLAY_POSITION_MS, 0L) ?: 0L
        pendingPlayPositionSpecified = savedInstanceState?.getBoolean(STATE_PENDING_PLAY_POSITION_SPECIFIED) ?: false
        pendingPlayPositionInvalid = savedInstanceState?.getBoolean(STATE_PENDING_PLAY_POSITION_INVALID) ?: false
        pendingPlayCanNext = savedInstanceState?.getBoolean(STATE_PENDING_PLAY_CAN_NEXT) ?: false
        pendingPlayCanPrevious = savedInstanceState?.getBoolean(STATE_PENDING_PLAY_CAN_PREVIOUS) ?: false
        pendingPlayAutoplay = savedInstanceState?.getBoolean(STATE_PENDING_PLAY_AUTOPLAY) ?: true
        pendingPlayEpisodeId = savedInstanceState?.getString(STATE_PENDING_PLAY_EPISODE_ID)?.trim()?.takeIf { it.isNotEmpty() }
        pendingPlayAnimeId = savedInstanceState?.getString(STATE_PENDING_PLAY_ANIME_ID)?.trim()?.takeIf { it.isNotEmpty() }
        pendingPlayCommandCreatedAtMs = savedInstanceState?.getLong(STATE_PENDING_PLAY_COMMAND_CREATED_AT_MS, 0L) ?: 0L
        pendingPlayRequestId = savedInstanceState?.getString(STATE_PENDING_PLAY_REQUEST_ID)
        activePlayerRequestId = savedInstanceState?.getString(STATE_ACTIVE_PLAYER_REQUEST_ID)?.trim()?.takeIf { it.isNotEmpty() }
        activePlayerCommandCreatedAtMs = savedInstanceState?.getLong(STATE_ACTIVE_PLAYER_COMMAND_CREATED_AT_MS, 0L) ?: 0L
        safPickerPending = savedInstanceState?.getBoolean(STATE_SAF_PICKER_PENDING) ?: false
        safPickerStartedAtMs = savedInstanceState?.getLong(STATE_SAF_PICKER_STARTED_AT_MS, 0L) ?: 0L
        safPickerFocusLost = savedInstanceState?.getBoolean(STATE_SAF_PICKER_FOCUS_LOST) ?: false
        safPickerFocusRegainedAtMs = savedInstanceState?.getLong(STATE_SAF_PICKER_FOCUS_REGAINED_AT_MS, 0L) ?: 0L
        safPickerPhase = savedInstanceState?.getString(STATE_SAF_PICKER_PHASE)?.let {
            runCatching { SafPickerPhase.valueOf(it) }.getOrNull()
        } ?: if (safPickerPending) SafPickerPhase.WAITING_RESULT else SafPickerPhase.IDLE
        startupDiscoveryTriggered = savedInstanceState?.getBoolean(STATE_STARTUP_DISCOVERY_TRIGGERED) ?: false
        automaticSafPickerAttempted = savedInstanceState?.getBoolean(STATE_AUTO_SAF_PICKER_ATTEMPTED) ?: false
        lastObservedMediaAccess = savedInstanceState?.getString(STATE_LAST_OBSERVED_MEDIA_ACCESS)
        if (savedInstanceState?.containsKey(STATE_LAST_OBSERVED_BROAD_ACCESS) == true) {
            lastObservedBroadAccess = savedInstanceState.getBoolean(STATE_LAST_OBSERVED_BROAD_ACCESS)
        }
        logLifecycle("onCreate", intent)
        NativeMailbox.write(this, JSONObject().put("type", "diagnostic").put("payload", JSONObject().put("event", "APP_START").put("lifecycle", "onCreate")))
        systemUiController = SystemUiController(window)
        _nativeStorageOnboardingState.value = _nativeStorageOnboardingState.value.copy(
            dismissed = getSharedPreferences(NATIVE_STORAGE_ONBOARDING_PREFS, MODE_PRIVATE)
                .getBoolean(NATIVE_STORAGE_ONBOARDING_DISMISSED, false),
        )
        // Do not construct the Compose host or expose catalog consumers until the
        // process-owned bootstrap has decided whether the canonical SQLite database is usable.
        // The launch intent and any new intents received during validation are replayed only
        // after a safe normal route or Recovery Mode has been selected.
        lifecycleScope.launch {
            val recoveryRequired = appDependencies.bootstrap.validateCanonicalDatabase()
            if (isFinishing || isDestroyed) return@launch

            nativeBootstrapDecisionComplete = true
            composeLibraryHost = ReiAnixComposeLibraryHost(this@MainActivity, libraryViewModel, settingsViewModel)
            if (recoveryRequired) {
                Log.w(tag, "NATIVE_DATABASE_RECOVERY_REQUIRED route_opened=true")
                composeLibraryHost.show(ReiAnixRoutes.RECOVERY, resetBackStack = true)
            } else {
                composeLibraryHost.show(ReiAnixRoutes.HOME, resetBackStack = true)
                refreshNativeStorageOnboardingState("bootstrap_validated")
            }

            handleNativeIntent(launchIntent)
            handleNativeSettingsFileIntent(launchIntent)
            val deferredIntents = synchronized(pendingNativeIntentsBeforeBootstrap) {
                pendingNativeIntentsBeforeBootstrap.toList().also { pendingNativeIntentsBeforeBootstrap.clear() }
            }
            deferredIntents.forEach { deferredIntent ->
                handleNativeIntent(deferredIntent)
                handleNativeSettingsFileIntent(deferredIntent)
            }
            if (!recoveryRequired) {
                val hadPendingLifecycleAction = !nativeRequestState.pendingLifecycleAction.isNullOrBlank()
                if (activityResumed) {
                    if (broadStoragePermissionPending) {
                        handleBroadSettingsReturn(pendingBroadRequestId, "bootstrap_validated")
                    } else {
                        consumeAndDispatchPendingLifecycleAction()
                    }
                }
                if (!hadPendingLifecycleAction) maybeLaunchAutomaticSafPicker()
            }
        }
        // The existing SystemUiController remains the single Android system-bar
        // authority while the Compose shell and legacy Flet surfaces coexist.
        systemUiController.applyApplicationPolicy(useContextAppearance = false)
        // The initial intent is processed once, after the bootstrap selects the safe route.
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        logLifecycle("onNewIntent", intent)
        if (!nativeBootstrapDecisionComplete || !::composeLibraryHost.isInitialized) {
            synchronized(pendingNativeIntentsBeforeBootstrap) {
                pendingNativeIntentsBeforeBootstrap.add(intent)
            }
            Log.i(tag, "NATIVE_INTENT_DEFERRED reason=bootstrap_decision_pending")
            return
        }
        handleNativeIntent(intent)
        handleNativeSettingsFileIntent(intent)
    }

    private fun handleNativeSettingsFileIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.getStringExtra(ReiAnixSettingsFileService.EXTRA_ACTION)
            ?.trim()?.lowercase()
        val requestId = intent.getStringExtra(ReiAnixSettingsFileService.EXTRA_REQUEST_ID)
            ?.trim().orEmpty()
        val nativeActions = setOf(
            "settings_export", "settings_import", "backup_create", "backup_restore",
            "backup_recovery_restore", "backup_recovery_snapshot", "backup_integrity", "backup_reconcile", "diagnostic_export",
        )
        if (action == null || action !in nativeActions) return

        // Consume the one-shot request before opening UI or starting asynchronous work.
        intent.removeExtra(ReiAnixSettingsFileService.EXTRA_ACTION)
        intent.removeExtra(ReiAnixSettingsFileService.EXTRA_REQUEST_ID)
        setIntent(intent)

        if (!SETTINGS_FILE_REQUEST_ID_REGEX.matches(requestId)) {
            Log.w(tag, "SETTINGS_NATIVE_ACTION_REJECTED reason=invalid_request_id action=" + action)
            return
        }
        if (pendingSettingsFileAction != null || settingsFileProcessing) {
            lifecycleScope.launch(Dispatchers.IO) {
                writeSettingsFileActionResult(
                    requestId, action!!, "ERROR", "ERROR",
                    error = "Outra operação nativa de configurações está em andamento.",
                )
            }
            return
        }

        pendingSettingsFileAction = action
        pendingSettingsFileRequestId = requestId
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                writeSettingsFileActionResult(
                    requestId, action!!, "RUNNING", "RUNNING",
                    message = if (
                        action == "backup_integrity" ||
                        action == "backup_reconcile" ||
                        action == "backup_recovery_snapshot"
                    ) "Preparando a operação nativa." else "Aguardando o seletor de arquivos do Android.",
                )
            }
            lifecycle.whenStarted {
                try {
                    when (action) {
                        "settings_export" -> {
                            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
                            settingsExportLauncher.launch("reiflix-settings-" + stamp + ".json")
                        }
                        "settings_import" -> settingsImportLauncher.launch(
                            arrayOf("application/json", "text/json", "application/octet-stream"),
                        )
                        "backup_create" -> {
                            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
                            backupExportLauncher.launch("reiflix-backup-" + stamp + ".zip")
                        }
                        "backup_restore", "backup_recovery_restore" -> backupImportLauncher.launch(
                            arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
                        )
                        "diagnostic_export" -> {
                            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
                            diagnosticExportLauncher.launch("reiflix-diagnostic-" + stamp + ".json")
                        }
                        "backup_integrity", "backup_reconcile", "backup_recovery_snapshot" -> {
                            pendingSettingsFileAction = null
                            pendingSettingsFileRequestId = null
                            runNativeSettingsMaintenanceAction(action, requestId)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    pendingSettingsFileAction = null
                    pendingSettingsFileRequestId = null
                    withContext(NonCancellable + Dispatchers.IO) {
                        writeSettingsFileActionResult(
                            requestId, action!!, "CANCELLED", "CANCELLED",
                            error = "A operação nativa foi cancelada.",
                        )
                    }
                    throw cancelled
                } catch (error: Exception) {
                    pendingSettingsFileAction = null
                    pendingSettingsFileRequestId = null
                    Log.e(tag, "SETTINGS_NATIVE_ACTION_LAUNCH_FAILED requestId=" + requestId + " action=" + action, error)
                    withContext(Dispatchers.IO) {
                        writeSettingsFileActionResult(
                            requestId, action!!, "ERROR", "ERROR",
                            error = error.message?.takeIf { it.isNotBlank() }
                                ?: "Não foi possível iniciar a operação nativa.",
                        )
                    }
                }
            }
        }
    }

    /**
     * Writes the already validated archive to the user-selected SAF document and
     * verifies the persisted document by reading it back. A successful internal ZIP
     * build is not reported as a successful export if the provider truncated or
     * failed to persist the selected destination.
     */
    private fun copyBackupToDocumentAndVerify(archive: File, destination: Uri) {
        require(archive.isFile && archive.length() in 1..ReiAnixBackupService.MAX_ARCHIVE_BYTES) {
            "O backup temporário está ausente, vazio ou fora do limite suportado."
        }
        val expectedDigest = MessageDigest.getInstance("SHA-256")
        var writtenBytes = 0L
        val output = contentResolver.openOutputStream(destination, "w")
            ?: throw IllegalStateException("O Android não abriu o destino do backup.")
        FileInputStream(archive).use { source ->
            output.use { target ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    writtenBytes += read
                    require(writtenBytes <= ReiAnixBackupService.MAX_ARCHIVE_BYTES) {
                        "O backup excede o limite de tamanho suportado."
                    }
                    expectedDigest.update(buffer, 0, read)
                    target.write(buffer, 0, read)
                }
                target.flush()
            }
        }
        require(writtenBytes == archive.length()) {
            "A gravação do backup no destino foi incompleta."
        }

        val actualDigest = MessageDigest.getInstance("SHA-256")
        var actualBytes = 0L
        val input = contentResolver.openInputStream(destination)
            ?: throw IllegalStateException("O destino foi gravado, mas não pôde ser reaberto para validação.")
        input.use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                actualBytes += read
                require(actualBytes <= ReiAnixBackupService.MAX_ARCHIVE_BYTES) {
                    "O destino contém mais dados do que o limite de backup suportado."
                }
                actualDigest.update(buffer, 0, read)
            }
        }
        require(actualBytes == writtenBytes &&
            MessageDigest.isEqual(expectedDigest.digest(), actualDigest.digest())) {
            "O backup foi escrito, mas a validação do conteúdo no destino falhou."
        }
    }

    private fun finishNativeBackupExport(uri: Uri?) {
        val action = pendingSettingsFileAction
        val requestId = pendingSettingsFileRequestId
        pendingSettingsFileAction = null
        pendingSettingsFileRequestId = null
        if (action != "backup_create" || requestId.isNullOrBlank()) return
        if (uri == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                writeSettingsFileActionResult(requestId, action, "CANCELLED", "CANCELLED", message = "Exportação de backup cancelada.")
            }
            return
        }
        if (settingsFileProcessing) {
            lifecycleScope.launch(Dispatchers.IO) {
                writeSettingsFileActionResult(requestId, action, "ERROR", "ERROR", error = "Outra operação de arquivo está em andamento.")
            }
            return
        }
        settingsFileProcessing = true
        lifecycleScope.launch {
            var archive: File? = null
            try {
                archive = withContext(Dispatchers.IO) {
                    ReiAnixBackupService(applicationContext).createBackupFile()
                }
                withContext(Dispatchers.IO) {
                    copyBackupToDocumentAndVerify(
                        archive = requireNotNull(archive),
                        destination = uri,
                    )
                }
                withContext(Dispatchers.IO) {
                    writeSettingsFileActionResult(requestId, action, "SUCCESS", "SUCCESS", message = "Backup ReiAnix criado e validado.")
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    writeSettingsFileActionResult(requestId, action, "CANCELLED", "CANCELLED", error = "A exportação de backup foi cancelada.")
                }
                throw cancelled
            } catch (error: Exception) {
                Log.e(tag, "NATIVE_BACKUP_EXPORT_FAILED requestId=" + requestId, error)
                withContext(Dispatchers.IO) {
                    writeSettingsFileActionResult(requestId, action, "ERROR", "ERROR", error = error.message?.takeIf { it.isNotBlank() } ?: "Não foi possível criar o backup.")
                }
            } finally {
                archive?.delete()
                settingsFileProcessing = false
            }
        }
    }

    private fun finishNativeBackupImport(uri: Uri?) {
        val action = pendingSettingsFileAction
        val requestId = pendingSettingsFileRequestId
        pendingSettingsFileAction = null
        pendingSettingsFileRequestId = null
        if ((action != "backup_restore" && action != "backup_recovery_restore") || requestId.isNullOrBlank()) return
        if (uri == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                writeSettingsFileActionResult(requestId, action, "CANCELLED", "CANCELLED", message = "Restauração cancelada.")
            }
            return
        }
        if (settingsFileProcessing) {
            lifecycleScope.launch(Dispatchers.IO) {
                writeSettingsFileActionResult(requestId, action, "ERROR", "ERROR", error = "Outra operação de arquivo está em andamento.")
            }
            return
        }
        settingsFileProcessing = true
        lifecycleScope.launch {
            val inputCopy = File(cacheDir, "reianix-restore-" + UUID.randomUUID() + ".zip")
            var handedOff = false
            try {
                withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("O Android não abriu o backup selecionado.")
                    input.use { source ->
                        FileOutputStream(inputCopy).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = source.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                total += read
                                require(total <= ReiAnixBackupService.MAX_ARCHIVE_BYTES) {
                                    "O arquivo de backup excede o limite permitido."
                                }
                                output.write(buffer, 0, read)
                            }
                            output.fd.sync()
                        }
                    }
                }

                // Both restore modes inspect the selected archive and present its
                // manifest/counts before the user confirms this specific file. The
                // service validates it again immediately before changing active data.
                val preview = withContext(Dispatchers.IO) {
                    ReiAnixBackupService(applicationContext).inspectBackup(inputCopy)
                }
                showNativeBackupRestoreConfirmation(inputCopy, action, requestId, preview)
                handedOff = true
                return@launch
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    writeSettingsFileActionResult(
                        requestId, action, "CANCELLED", "CANCELLED",
                        error = "A seleção ou restauração do backup foi cancelada.",
                    )
                }
                throw cancelled
            } catch (error: Exception) {
                Log.e(tag, "NATIVE_BACKUP_IMPORT_PREPARATION_FAILED requestId=" + requestId, error)
                withContext(Dispatchers.IO) {
                    writeSettingsFileActionResult(
                        requestId, action, "ERROR", "ERROR",
                        error = error.message?.takeIf { it.isNotBlank() }
                            ?: "Não foi possível validar o backup selecionado.",
                    )
                }
            } finally {
                if (!handedOff) {
                    inputCopy.delete()
                    settingsFileProcessing = false
                }
            }
        }
    }

    private fun showNativeBackupRestoreConfirmation(
        inputCopy: File,
        action: String,
        requestId: String,
        preview: ReiAnixBackupService.BackupInspection,
    ) {
        require(action == "backup_restore" || action == "backup_recovery_restore") {
            "Ação de restauração nativa inválida."
        }
        val manifest = preview.manifest
        val counts = preview.counts
        val details = buildString {
            append("Formato: ")
            append(manifest.optString("format", "desconhecido"))
            append(" v")
            append(manifest.optInt("format_version", -1))
            append("\nSchema: ")
            append(manifest.optInt("schema_version", -1))
            append("\nVersão que criou o backup: ")
            append(manifest.optString("app_version", "não informada"))
            append("\nAnimes: ")
            append(counts.optLong("anime", 0L))
            append(" • episódios: ")
            append(counts.optLong("episodes", 0L))
            append("\nFavoritos: ")
            append(counts.optLong("favorites", 0L))
            append(" • com progresso/histórico: ")
            append(counts.optLong("progress", 0L))
            append("\n\n")
            if (action == "backup_recovery_restore") {
                append("O banco atual requer recuperação. Uma cópia bruta do SQLite e dos arquivos auxiliares disponíveis " +
                    "será preservada antes de promover o banco validado. ")
            } else {
                append("Os dados lógicos incluídos serão aplicados ao catálogo SQLite em uma transação, depois de criar " +
                    "um backup ZIP de segurança pré-restauração. ")
            }
            append("Vídeos externos, permissões SAF/MediaStore e sessão Google não são incluídos nem recriados.")
            if (preview.warnings.isNotEmpty()) {
                append("\n\nAvisos de validação:")
                preview.warnings.forEach { warning ->
                    append("\n• ")
                    append(warning)
                }
            }
        }
        val decisionHandled = AtomicBoolean(false)
        fun finishCancelled() {
            if (!decisionHandled.compareAndSet(false, true)) return
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        writeSettingsFileActionResult(
                            requestId,
                            action,
                            "CANCELLED",
                            "CANCELLED",
                            message = if (action == "backup_recovery_restore") {
                                "A recuperação foi cancelada antes de substituir o banco."
                            } else {
                                "A restauração foi cancelada antes de alterar a biblioteca."
                            },
                        )
                    }
                } finally {
                    inputCopy.delete()
                    settingsFileProcessing = false
                }
            }
        }

        android.app.AlertDialog.Builder(this)
            .setTitle(
                if (action == "backup_recovery_restore") {
                    "Confirmar recuperação da biblioteca"
                } else {
                    "Confirmar restauração do backup"
                },
            )
            .setMessage(details)
            .setNegativeButton("Cancelar") { _, _ -> finishCancelled() }
            .setPositiveButton(
                if (action == "backup_recovery_restore") "Recuperar biblioteca" else "Restaurar backup",
            ) { _, _ ->
                if (decisionHandled.compareAndSet(false, true)) {
                    completeNativeBackupRestore(inputCopy, action, requestId)
                }
            }
            .setOnCancelListener { finishCancelled() }
            .show()
    }

    private fun completeNativeBackupRestore(inputCopy: File, action: String, requestId: String) {
        lifecycleScope.launch {
            try {
                val backupService = ReiAnixBackupService(applicationContext)
                val result = withContext(Dispatchers.IO) {
                    if (action == "backup_recovery_restore") {
                        backupService.restoreRecoveryBackup(inputCopy)
                    } else {
                        backupService.restoreBackup(inputCopy)
                    }
                }
                if (action == "backup_recovery_restore") {
                    // The backup service validates/promotes the candidate and preserves its
                    // rollback snapshot. The process-owned bootstrap must independently accept
                    // the canonical database before any normal ViewModel projection is refreshed.
                    val recoveryRequired = appDependencies.bootstrap.validateCanonicalDatabase()
                    if (recoveryRequired) {
                        composeLibraryHost.show(ReiAnixRoutes.RECOVERY, resetBackStack = true)
                        throw IllegalStateException(
                            "A base restaurada não passou pela revalidação do bootstrap.",
                        )
                    }
                    settingsViewModel.refresh()
                    val catalogRefreshed = libraryViewModel.refreshCanonicalCatalogAfterBootstrapValidation()
                    if (!catalogRefreshed) {
                        composeLibraryHost.show(ReiAnixRoutes.RECOVERY, resetBackStack = true)
                        throw IllegalStateException(
                            "O catálogo restaurado não pôde ser carregado após a validação.",
                        )
                    }
                    // Recovery Mode exits only after both the canonical SQLite check and
                    // the local catalog projection have completed successfully.
                    composeLibraryHost.show(ReiAnixRoutes.HOME, resetBackStack = true)
                } else {
                    // Preserve the normal backup-restore contract; it is not the exceptional
                    // startup-recovery path and continues using the established refresh flow.
                    settingsViewModel.refresh()
                    libraryViewModel.refresh()
                }
                runCatching {
                    NativeMailbox.write(
                        this@MainActivity,
                        JSONObject()
                            .put("type", "compose_settings_native_backup_restored")
                            .put("requestId", requestId)
                            .put("payload", JSONObject().put("requestId", requestId)),
                    )
                }.onFailure { error ->
                    Log.w(tag, "NATIVE_RESTORE_RUNTIME_PROJECTION_FAILED requestId=" + requestId, error)
                }
                val accountMessage = when {
                    result.reauthenticationRequired ->
                        " O estado local da conta não pôde ser lido antes da recuperação; entre novamente se necessário."
                    action == "backup_recovery_restore" && result.accountStatePreserved ->
                        " O estado local da conta foi preservado."
                    else -> ""
                }
                val message = (if (action == "backup_recovery_restore") {
                    "Biblioteca recuperada. Snapshot de segurança: " + result.safetyBackup.name + ". "
                } else {
                    "Backup restaurado. "
                }) + "Itens: " + result.importedRows +
                    "; arquivos indisponíveis: " + result.missingFiles +
                    "; capas manuais reconectadas: " + result.manualArtworkReconnected + "." +
                    accountMessage
                withContext(Dispatchers.IO) {
                    writeSettingsFileActionResult(requestId, action, "SUCCESS", "SUCCESS", message = message)
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    writeSettingsFileActionResult(
                        requestId, action, "CANCELLED", "CANCELLED",
                        error = "A restauração foi cancelada.",
                    )
                }
                throw cancelled
            } catch (error: Exception) {
                if (action == "backup_recovery_restore" && ::composeLibraryHost.isInitialized) {
                    // Never leave the recovery route because of an exception after promotion.
                    runCatching {
                        composeLibraryHost.show(ReiAnixRoutes.RECOVERY, resetBackStack = true)
                    }
                }
                Log.e(
                    tag,
                    "NATIVE_BACKUP_RESTORE_FAILED requestId=" + requestId +
                        " category=" + error::class.java.simpleName,
                )
                withContext(Dispatchers.IO) {
                    writeSettingsFileActionResult(
                        requestId, action, "ERROR", "ERROR",
                        error = if (action == "backup_recovery_restore") {
                            "A recuperação foi interrompida porque a base restaurada não passou por todas as validações. O modo de recuperação foi mantido."
                        } else {
                            error.message?.takeIf { it.isNotBlank() }
                                ?: "Não foi possível restaurar o backup."
                        },
                    )
                }
            } finally {
                inputCopy.delete()
                settingsFileProcessing = false
            }
        }
    }

    private fun finishNativeDiagnosticExport(uri: Uri?) {
        val action = pendingSettingsFileAction
        val requestId = pendingSettingsFileRequestId
        pendingSettingsFileAction = null
        pendingSettingsFileRequestId = null
        if (action != "diagnostic_export" || requestId.isNullOrBlank()) return
        if (uri == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                writeSettingsFileActionResult(requestId, action, "CANCELLED", "CANCELLED", message = "Exportação de diagnóstico cancelada.")
            }
            return
        }
        if (settingsFileProcessing) {
            lifecycleScope.launch(Dispatchers.IO) {
                writeSettingsFileActionResult(requestId, action, "ERROR", "ERROR", error = "Outra operação de arquivo está em andamento.")
            }
            return
        }
        settingsFileProcessing = true
        lifecycleScope.launch {
            try {
                val report = withContext(Dispatchers.IO) { ReiAnixDiagnosticsService(applicationContext).exportJson() }
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(uri, "w")
                        ?: throw IllegalStateException("O Android não abriu o destino do diagnóstico.")
                    output.use { it.write(report) }
                    writeSettingsFileActionResult(requestId, action, "SUCCESS", "SUCCESS", message = "Diagnóstico nativo exportado.")
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    writeSettingsFileActionResult(requestId, action, "CANCELLED", "CANCELLED", error = "A exportação de diagnóstico foi cancelada.")
                }
                throw cancelled
            } catch (error: Exception) {
                Log.e(tag, "NATIVE_DIAGNOSTIC_EXPORT_FAILED requestId=" + requestId, error)
                withContext(Dispatchers.IO) {
                    writeSettingsFileActionResult(requestId, action, "ERROR", "ERROR", error = error.message?.takeIf { it.isNotBlank() } ?: "Não foi possível exportar o diagnóstico.")
                }
            } finally {
                settingsFileProcessing = false
            }
        }
    }

    private fun runNativeSettingsMaintenanceAction(action: String, requestId: String) {
        settingsFileProcessing = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    writeSettingsFileActionResult(requestId, action, "RUNNING", "RUNNING", message = "Executando verificação nativa.")
                }
                when (action) {
                    "backup_integrity" -> {
                        val report = withContext(Dispatchers.IO) { ReiAnixBackupService(applicationContext).integrityReport() }
                        if (!report.healthy) {
                            throw IllegalStateException(report.error ?: "O banco falhou na verificação de integridade.")
                        }
                        withContext(Dispatchers.IO) {
                            writeSettingsFileActionResult(
                                requestId, action, "SUCCESS", "SUCCESS",
                                message = "SQLite íntegro; schema " + report.schemaVersion +
                                    "; violações de foreign key: " + report.foreignKeyViolations + ".",
                            )
                        }
                    }
                    "backup_reconcile" -> {
                        val updated = withContext(Dispatchers.IO) {
                            ReiAnixBackupService(applicationContext).reconcileRestoredFileAvailability()
                        }
                        libraryViewModel.refresh()
                        withContext(Dispatchers.IO) {
                            writeSettingsFileActionResult(
                                requestId, action, "SUCCESS", "SUCCESS",
                                message = "Reconciliação concluída. Referências locais verificadas: " + updated +
                                    ". URIs SAF/MediaStore permanecem sob o scanner nativo.",
                            )
                        }
                    }
                    "backup_recovery_snapshot" -> {
                        val snapshot = withContext(Dispatchers.IO) {
                            ReiAnixBackupService(applicationContext).createRecoverySafetySnapshotForUser()
                        }
                        withContext(Dispatchers.IO) {
                            writeSettingsFileActionResult(
                                requestId, action, "SUCCESS", "SUCCESS",
                                message = "Snapshot bruto de segurança criado: " + snapshot.name +
                                    ". Ele preserva bytes/sidecars para recuperação, mas não é um ZIP de backup restaurável.",
                            )
                        }
                    }
                    else -> throw IllegalArgumentException("Ação de manutenção nativa desconhecida.")
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    writeSettingsFileActionResult(requestId, action, "CANCELLED", "CANCELLED", error = "A operação foi cancelada.")
                }
                throw cancelled
            } catch (error: Exception) {
                Log.e(tag, "NATIVE_SETTINGS_MAINTENANCE_FAILED requestId=" + requestId + " action=" + action, error)
                withContext(Dispatchers.IO) {
                    writeSettingsFileActionResult(
                        requestId, action, "ERROR", "ERROR",
                        error.message?.takeIf { it.isNotBlank() } ?: "A operação não pôde ser concluída.",
                    )
                }
            } finally {
                settingsFileProcessing = false
            }
        }
    }

    private fun writeSettingsFileActionResult(
        requestId: String,
        action: String,
        status: String,
        operationState: String,
        message: String? = null,
        error: String? = null,
    ) {
        if (!SETTINGS_FILE_REQUEST_ID_REGEX.matches(requestId)) {
            Log.w(tag, "SETTINGS_FILE_RESULT_REJECTED reason=invalid_request_id action=" + action)
            return
        }
        try {
            val directory = File(filesDir, "data/reianix-compose/command-results")
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IllegalStateException("Could not create Settings command result directory.")
            }
            val target = File(directory, "command-" + requestId + ".json")
            val temporary = File(directory, "." + target.name + "." + UUID.randomUUID() + ".tmp")
            val root = JSONObject()
                .put("requestId", requestId)
                .put("commandBridge", "settings")
                .put("action", action)
                .put("status", status)
                .put("operationState", operationState)
                .put("timestamp", System.currentTimeMillis())
            if (!message.isNullOrBlank()) root.put("message", message)
            if (!error.isNullOrBlank()) root.put("error", error)
            FileOutputStream(temporary).use { output ->
                output.write(root.toString().toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            if (target.exists() && !target.delete()) {
                throw IllegalStateException("Could not replace the previous Settings result.")
            }
            if (!temporary.renameTo(target)) {
                temporary.delete()
                throw IllegalStateException("Could not atomically publish the Settings result.")
            }
        } catch (exception: Exception) {
            Log.e(tag, "SETTINGS_FILE_RESULT_WRITE_FAILED requestId=" + requestId + " action=" + action, exception)
        }
    }

    private fun beginComposeSettingsStorageAction(requestId: String, action: String) {
        // Compose marks its operation RUNNING before dispatch. The Activity records
        // ownership here and writes only one terminal command result, avoiding a
        // race between concurrent RUNNING/SUCCESS files for the same request ID.
        composeSettingsStorageActions[requestId] = action
    }

    private fun completeComposeSettingsStorageAction(
        requestId: String?,
        status: String,
        message: String? = null,
        error: String? = null,
    ) {
        val id = requestId?.trim()?.takeIf { SETTINGS_FILE_REQUEST_ID_REGEX.matches(it) } ?: return
        val action = composeSettingsStorageActions.remove(id) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            writeSettingsFileActionResult(
                id,
                action,
                status,
                status,
                message = message,
                error = error,
            )
        }
    }

    private fun rejectComposeSettingsStorageAction(
        requestId: String,
        action: String,
        error: String,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            writeSettingsFileActionResult(
                requestId,
                action,
                "ERROR",
                "ERROR",
                error = error,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        registerStorageReceiver()
        MediaStoreScanner.startChangeObserver(this) { scheduleMediaStoreIncrementalRescan() }
        logLifecycle("onStart")
        applyApplicationSystemUi()
    }

    override fun onResume() {
        super.onResume()
        PerformanceDiagnostics.attach(this)
        PerformanceDiagnostics.sampleMemory(this, "main_on_resume")
        activityResumed = true
        logLifecycle("onResume")
        NativeMailbox.writeBestEffort(this, JSONObject().put("type", "diagnostic").put("payload", JSONObject().put("event", "ON_RESUME").put("lifecycle", "onResume")))
        applyApplicationSystemUi()
        if (safPickerPending) {
            // onResume marks return even when a provider omits the separate focus-loss callback.
            if (
                safPickerPhase == SafPickerPhase.WAITING_RESULT &&
                safPickerFocusRegainedAtMs <= 0L &&
                window?.decorView?.hasWindowFocus() == true
            ) {
                safPickerFocusRegainedAtMs = System.currentTimeMillis()
            }
            scheduleSafPickerWatchdog(pendingSafRequestId)
        }

        // A lifecycle-sensitive command may have been queued because the
        // Activity was not resumed when Python delivered the request. Do not
        // publish an intermediate "denied" snapshot first: Python could treat
        // that snapshot as the final result and close the onboarding while the
        // real Android permission/settings UI is only about to open.
        if (bootstrapAllowsNativeOperations()) {
            val hadPendingLifecycleAction = !nativeRequestState.pendingLifecycleAction.isNullOrBlank()
            consumeAndDispatchPendingLifecycleAction()
            if (hadPendingLifecycleAction) return
        }

        if (!bootstrapAllowsNativeOperations()) {
            Log.i(tag, "NATIVE_LIFECYCLE_WORK_DEFERRED reason=bootstrap_not_ready_or_recovery")
            return
        }

        // Settings may revoke access while this activity is paused. Always
        // republish the actual Android state after a real Settings return.
        if (broadStoragePermissionPending) {
            // Revalidate the real Android state on every Settings return. This is
            // safe even when ActivityResult is delivered afterward because the
            // return handler is idempotent once the pending flag is cleared.
            handleBroadSettingsReturn(pendingBroadRequestId, "onResume")
            return
        }

        refreshNativeStorageOnboardingState("activity_resume")
        val currentMediaAccess = MediaStoreScanner.accessLevel(this)
        val currentBroadAccess = BroadStorageScanner.hasAccess(this)
        val mediaAccessChangedToUsable = lastObservedMediaAccess != null &&
            lastObservedMediaAccess != currentMediaAccess &&
            currentMediaAccess != "denied"
        val broadBecameAvailable = lastObservedBroadAccess == false && currentBroadAccess
        // First-run permission UI must be visible without dispatching a discovery
        // request against an empty authorization set. In the canonical library model,
        // a persisted SAF tree is the configured folder source; media/broad permissions
        // alone do not add a LibraryStore source.
        val hasPersistedSafTree = hasPersistedSafTreeGrant()
        val shouldDiscover = !startupDiscoveryTriggered
        startupDiscoveryTriggered = true
        lastObservedMediaAccess = currentMediaAccess
        lastObservedBroadAccess = currentBroadAccess

        publishStorageStatus()

        // Activity resume is a lifecycle signal, not a scan command. Startup
        // discovery and permission-triggered reconciliation require a persisted SAF
        // tree. Explicit SAF selection publishes its own PERMISSION_CHANGE after the
        // grant has been persisted and validated.
        if (shouldDiscover) {
            // Opening the app is not authorization to enumerate storage. An existing
            // valid tree is retained, but discovery is started only by an explicit user
            // action or the already-established permission-change flow.
            Log.i(tag, "STARTUP_SCAN_SKIPPED reason=explicit_scan_required")
            publishNativeDiagnostic(
                "STARTUP_SCAN_SKIPPED",
                null,
                action = "scan",
                state = "SKIPPED",
                result = "explicit_scan_required",
            )
        } else if (
            hasPersistedSafTree &&
            (mediaAccessChangedToUsable || broadBecameAvailable)
        ) {
            val source = when {
                mediaAccessChangedToUsable && broadBecameAvailable -> null
                mediaAccessChangedToUsable -> "mediastore"
                else -> "broad_storage"
            }
            publishScanRequest(
                "PERMISSION_CHANGE",
                source = source,
                full = false,
                reason = "permission_available_after_resume",
            )
        }
    }

    private fun bootstrapAllowsNativeOperations(): Boolean {
        val state = appDependencies.bootstrap.state.value
        return nativeBootstrapDecisionComplete &&
            !state.recoveryRequired &&
            state.stage == ReiAnixBootstrapStage.READY &&
            state.persistenceStatus in setOf(
                ReiAnixBootstrapPersistenceStatus.HEALTHY,
                ReiAnixBootstrapPersistenceStatus.NOT_CREATED,
            )
    }

    /** Process persisted lifecycle work only after the canonical DB decision is healthy. */
    private fun consumeAndDispatchPendingLifecycleAction() {
                val pending = nativeRequestState.consumeLifecycleAction()
                if (pending != null) {
                    val pendingRequestId = nativeRequestState.consumedLifecycleRequestId()
                    // Restore only the correlation id owned by the queued lifecycle action.
                    // Keeping one request id in unrelated permission fields can make a later
                    // Settings/SAF callback look like it belongs to the wrong operation.
                    when (pending) {
                        "select_tree" -> {
                            pendingSafRequestId = pendingRequestId
                            if (window?.decorView?.hasWindowFocus() == true) {
                                openTreePicker(pendingRequestId)
                            } else {
                                Log.i(tag, "SAF_PICKER_WAITING_FOR_FOCUS requestId=" + (pendingRequestId ?: "-"))
                                nativeRequestState.queueLifecycleAction("select_tree", pendingRequestId)
                            }
                        }
                        "request_media_access" -> {
                            pendingMediaRequestId = pendingRequestId
                            requestMediaAccess()
                        }
                        "open_broad_storage_settings" -> {
                            pendingBroadRequestId = pendingRequestId
                            openBroadStorageSettings()
                        }
                        "scan_library" -> {
                            nativeRequestState.markOperationState(
                                pendingRequestId,
                                "scan_library",
                                NativeRequestState.OperationState.RUNNING,
                            )
                            publishNativeDiagnostic(
                                "OPERATION_STARTED",
                                pendingRequestId,
                                "scan_library",
                                NativeRequestState.OperationState.RUNNING.name,
                            )
                            scanConfiguredSafRoots(
                                pendingRequestId,
                                "USER_REFRESH",
                                "compose_library_refresh",
                                includeAuthorizedDiagnostics = true,
                            )
                        }
                        "play" -> {
                            val uri = pendingPlayUri
                            if (!uri.isNullOrBlank()) {
                                val pendingRequestId = pendingPlayRequestId
                                nativeRequestState.markOperationState(
                                    pendingRequestId,
                                    "play",
                                    NativeRequestState.OperationState.RUNNING,
                                )
                                publishNativeDiagnostic(
                                    "OPERATION_STARTED",
                                    pendingRequestId,
                                    "play",
                                    NativeRequestState.OperationState.RUNNING.name,
                                )
        
                                // Reuse the exact command that arrived from Python whenever possible.
                                // This preserves playerSessionId, origin request/generation fencing,
                                // monotonic correlation and all player settings across onResume.
                                val preservedPlayData = pendingPlayIntentData
                                    ?.trim()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { raw -> runCatching { Uri.parse(raw) }.getOrNull() }
                                val restoredPlayerSessionId = pendingPlayIntentData
                                    ?.let { raw -> runCatching { Uri.parse(raw).getQueryParameter("player_session_id") }.getOrNull() }
                                    ?.trim()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?: activePlayerSessionId
                                if (restoredPlayerSessionId.isNullOrBlank()) {
                                    nativeRequestState.markOperationState(
                                        pendingRequestId,
                                        "play",
                                        NativeRequestState.OperationState.FAILED,
                                    )
                                    publishNativeDiagnostic(
                                        "PLAYER_HANDOFF_REJECTED",
                                        pendingRequestId,
                                        "play",
                                        NativeRequestState.OperationState.FAILED.name,
                                        result = "PLAYER_SESSION_INVALID",
                                    )
                                    clearPendingPlay()
                                    return
                                }
                                if (preservedPlayData == null && pendingPlayPositionInvalid) {
                                    publishNativeCommandError(
                                        pendingRequestId,
                                        "play",
                                        "request_validation",
                                        "INVALID_POSITION_MS",
                                        "A posição inicial enviada para o player não é válida.",
                                    )
                                    clearPendingPlay()
                                    return
                                }
                                val playData = preservedPlayData ?: Uri.parse("reiflix://native").buildUpon()
                                    .appendQueryParameter("action", "play")
                                    .appendQueryParameter("request_id", pendingRequestId.orEmpty())
                                    .appendQueryParameter("uri", uri)
                                    .appendQueryParameter("episode_id", pendingPlayEpisodeId.orEmpty())
                                    .appendQueryParameter("anime_id", pendingPlayAnimeId.orEmpty())
                                    .appendQueryParameter("title", pendingPlayTitle ?: "Episódio")
                                    .apply {
                                        if (pendingPlayPositionSpecified) {
                                            appendQueryParameter("position_ms", pendingPlayPositionMs.toString())
                                        }
                                    }
                                    .appendQueryParameter("can_next", pendingPlayCanNext.toString())
                                    .appendQueryParameter("can_previous", pendingPlayCanPrevious.toString())
                                    .appendQueryParameter("autoplay", pendingPlayAutoplay.toString())
                                    .appendQueryParameter("created_at", pendingPlayCommandCreatedAtMs.toString())
                                    .appendQueryParameter("player_session_id", restoredPlayerSessionId)
                                    .build()
                                clearPendingPlay()
                                if (openPlayer(playData, commandReceivedAtMs = System.currentTimeMillis())) {
                                    publishNativeDiagnostic(
                                        "COMMAND_DISPATCHED",
                                        pendingRequestId,
                                        "play",
                                        nativeRequestState.operationState(pendingRequestId)?.name,
                                        result = if (preservedPlayData != null) "dispatched_preserved_request" else "dispatched_legacy_restore",
                                    )
                                }
                            } else {
                                clearPendingPlay()
                            }
                        }
                    }
                    // Established host contract: "request_media_access" -> requestMediaAccess()
                    return
                }
    }

    override fun onPause() {
        activityResumed = false
        if (safPickerPending) {
            // Keep the watchdog polling while DocumentsUI owns the foreground.
            // Its foreground gate prevents a timeout while the user is choosing.
            safPickerFocusLost = true
            safPickerFocusRegainedAtMs = 0L
        }
        logLifecycle("onPause")
        super.onPause()
    }

    override fun onStop() {
        logLifecycle("onStop")
        unregisterStorageReceiver()
        MediaStoreScanner.stopChangeObserver(this)
        super.onStop()
    }

    override fun onDestroy() {
        PerformanceDiagnostics.sampleMemory(this, "main_on_destroy")
        PerformanceDiagnostics.detach()
        cancelSafPickerWatchdog()
        if (::composeLibraryHost.isInitialized) composeLibraryHost.dispose()
        googleSignInJob?.cancel()
        googleSignInJob = null
        googleSignOutJob?.cancel()
        googleSignOutJob = null
        logLifecycle("onDestroy")
        if (isFinishing) NativeScanController.cancelAll()
        unregisterStorageReceiver()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_LAST_NATIVE_REQUEST_ID, nativeRequestState.lastHandledRequestId)
        outState.putString(STATE_PENDING_LIFECYCLE_ACTION, nativeRequestState.pendingLifecycleAction)
        outState.putString(STATE_PENDING_LIFECYCLE_REQUEST_ID, nativeRequestState.pendingLifecycleRequestId)
        outState.putString(STATE_PENDING_MEDIA_REQUEST_ID, pendingMediaRequestId)
        outState.putString(STATE_PENDING_BROAD_REQUEST_ID, pendingBroadRequestId)
        outState.putString(STATE_PENDING_SAF_REQUEST_ID, pendingSafRequestId)
        outState.putString(STATE_PENDING_PLAY_URI, pendingPlayUri)
        outState.putString(STATE_PENDING_PLAY_INTENT_DATA, pendingPlayIntentData)
        outState.putString(STATE_PENDING_PLAY_TITLE, pendingPlayTitle)
        outState.putLong(STATE_PENDING_PLAY_POSITION_MS, pendingPlayPositionMs)
        outState.putBoolean(STATE_PENDING_PLAY_POSITION_SPECIFIED, pendingPlayPositionSpecified)
        outState.putBoolean(STATE_PENDING_PLAY_POSITION_INVALID, pendingPlayPositionInvalid)
        outState.putBoolean(STATE_PENDING_PLAY_CAN_NEXT, pendingPlayCanNext)
        outState.putBoolean(STATE_PENDING_PLAY_CAN_PREVIOUS, pendingPlayCanPrevious)
        outState.putBoolean(STATE_PENDING_PLAY_AUTOPLAY, pendingPlayAutoplay)
        outState.putString(STATE_PENDING_PLAY_EPISODE_ID, pendingPlayEpisodeId)
        outState.putString(STATE_PENDING_PLAY_ANIME_ID, pendingPlayAnimeId)
        outState.putLong(STATE_PENDING_PLAY_COMMAND_CREATED_AT_MS, pendingPlayCommandCreatedAtMs)
        outState.putString(STATE_PENDING_PLAY_REQUEST_ID, pendingPlayRequestId)
        outState.putString(STATE_ACTIVE_PLAYER_REQUEST_ID, activePlayerRequestId)
        outState.putLong(STATE_ACTIVE_PLAYER_COMMAND_CREATED_AT_MS, activePlayerCommandCreatedAtMs)
        outState.putBoolean(STATE_BROAD_SETTINGS_PENDING, broadStoragePermissionPending)
        outState.putBoolean(STATE_SAF_PICKER_PENDING, safPickerPending)
        outState.putLong(STATE_SAF_PICKER_STARTED_AT_MS, safPickerStartedAtMs)
        outState.putBoolean(STATE_SAF_PICKER_FOCUS_LOST, safPickerFocusLost)
        outState.putLong(STATE_SAF_PICKER_FOCUS_REGAINED_AT_MS, safPickerFocusRegainedAtMs)
        outState.putString(STATE_SAF_PICKER_PHASE, safPickerPhase.name)
        outState.putString("reiflix.externalSettingsKind", externalSettingsKind)
        outState.putString("reiflix.externalSettingsRequestId", externalSettingsRequestId)
        outState.putString("reiflix.settingsFileAction", pendingSettingsFileAction)
        outState.putString("reiflix.settingsFileRequestId", pendingSettingsFileRequestId)
        outState.putStringArrayList("reiflix.settingsStorageIds", ArrayList(composeSettingsStorageActions.keys))
        outState.putStringArrayList("reiflix.settingsStorageActions", ArrayList(composeSettingsStorageActions.values))
        outState.putBoolean(STATE_STARTUP_DISCOVERY_TRIGGERED, startupDiscoveryTriggered)
        outState.putBoolean(STATE_AUTO_SAF_PICKER_ATTEMPTED, automaticSafPickerAttempted)
        outState.putString(STATE_LAST_OBSERVED_MEDIA_ACCESS, lastObservedMediaAccess)
        lastObservedBroadAccess?.let { outState.putBoolean(STATE_LAST_OBSERVED_BROAD_ACCESS, it) }
        outState.putString(STATE_SEEN_NATIVE_REQUEST_IDS, nativeRequestState.seenRequestIdsState())
        super.onSaveInstanceState(outState)
    }

    /**
     * Android owns the physical Back dispatch. The active Compose App Shell gets
     * first refusal while its Navigation Compose stack contains a previous
     * destination. At the shell root, the existing Flet/navigation bridge keeps
     * ownership of the legacy transition and exit semantics.
     */
    private fun installSystemBackHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    systemBackEventCount += 1
                    val backId = systemBackEventCount
                    Log.i(tag, "BACK_PHYSICAL_RECEIVED id=" + backId)
                    if (systemBackDispatchPosted) {
                        Log.i(tag, "BACK_PHYSICAL_RECEIVED duplicate_dispatch id=" + backId)
                        Log.i(tag, "SYSTEM_BACK duplicate_dispatch_suppressed")
                        return
                    }
                    // Guard the complete Android Back dispatch, including the Compose
                    // branch. This prevents a re-entrant/same-loop dispatch from
                    // producing a second pop before the first event settles.
                    systemBackDispatchPosted = true
                    try {
                        // Compose owns Back while the visible route is an internal
                        // destination. At the true Home root, handleBack() returns
                        // false and the canonical Flet navigation bridge below runs.
                        if (::composeLibraryHost.isInitialized && composeLibraryHost.isVisible) {
                            if (composeLibraryHost.handleBack()) {
                                Log.i(tag, "BACK_COMPOSE_SHELL_HANDLED id=" + backId)
                                return
                            }
                        }

                        val engine = flutterEngine
                        if (engine == null) {
                            Log.w(tag, "SYSTEM_BACK ignored reason=flutter_engine_unavailable")
                            return
                        }
                        Log.i(tag, "BACK_FLUTTER_POP_SENT id=" + backId)
                        engine.navigationChannel.popRoute()
                    } finally {
                        // Reset only after the current dispatcher turn has returned so
                        // the same Android Back event cannot enter the handler twice.
                        window.decorView.post { systemBackDispatchPosted = false }
                    }
                }
            },
        )
    }

    private fun persistedSafTreeUris(): List<String> =
        contentResolver.persistedUriPermissions
            .asSequence()
            .filter { it.isReadPermission && it.uri.scheme == "content" && DocumentsContract.isTreeUri(it.uri) }
            .map { it.uri.toString() }
            .distinct()
            .sorted()
            .toList()

    private fun applyApplicationSystemUi() {
        if (::systemUiController.isInitialized) {
            // MainActivity owns the immersive application surface. External Android
            // Activities may temporarily reveal their own system UI; focus/resume
            // re-applies this single host policy when ReiAnix returns foreground.
            systemUiController.applyApplicationImmersivePolicy(useContextAppearance = false)
            publishInteractionProfileIfChanged(force = true)
            ViewCompat.requestApplyInsets(window.decorView)
        }
    }

    private fun storageCapabilitiesPayload(
        lifecycleState: StorageLifecycleState = StorageLifecycleState.REVALIDATED
    ): JSONObject {
        val broadSnapshot = BroadStorageScanner.accessSnapshot(this)
        val mediaState = MediaStoreScanner.accessLevelValue(this)
        val broadState = BroadStorageScanner.accessLevel(this)
        val safRoots = persistedSafTreeUris()
        val removableVolumes = mutableListOf<String>()
        val volumes = broadSnapshot.optJSONArray("volumes")
        if (volumes != null) {
            for (i in 0 until volumes.length()) {
                val volume = volumes.optJSONObject(i) ?: continue
                if (volume.optBoolean("removable", false)) {
                    val id = volume.optString("volumeId").ifBlank { volume.optString("uuid") }
                    if (id.isNotBlank()) removableVolumes += id
                }
            }
        }
        val capabilities = StorageAuthorization.capabilities(
            mediaState, broadState, safRoots, removableVolumes, lifecycleState
        )
        return JSONObject()
            .put("mediaReadState", capabilities.mediaReadState.name.lowercase())
            .put("broadStorageState", capabilities.broadStorageState.name.lowercase())
            .put("safRoots", JSONArray(capabilities.safRoots))
            .put("removableVolumes", JSONArray(capabilities.removableVolumes))
            .put("scannerCapabilities", JSONArray(capabilities.scannerCapabilities.toList()))
            .put("reconciliationCapabilities", JSONArray(capabilities.reconciliationCapabilities.toList()))
            .put("lifecycleState", capabilities.lifecycleState.name.lowercase())
            .put("api", Build.VERSION.SDK_INT)
    }

    private fun publishStorageCapabilities(
        lifecycleState: StorageLifecycleState = StorageLifecycleState.REVALIDATED
    ) {
        NativeMailbox.write(
            this,
            JSONObject().put("type", "storage_capabilities")
                .put("payload", storageCapabilitiesPayload(lifecycleState))
        )
    }

    private fun publishNativeDiagnostic(
        event: String,
        requestId: String?,
        action: String? = null,
        state: String? = null,
        result: String? = null,
        error: String? = null,
        protocolVersion: Int? = null,
        commandCreatedAt: Long? = null,
        parameterNames: String? = null,
        playerSessionId: String? = null,
        transitionDirection: String? = null,
    ) {
        val payload = JSONObject()
            .put("event", event)
            .put("requestId", requestId ?: "")
            .put("action", action ?: "")
            .put("timestamp", System.currentTimeMillis())
        if (state != null) payload.put("state", state)
        if (result != null) payload.put("result", result)
        if (error != null) payload.put("error", error)
        if (protocolVersion != null) payload.put("protocolVersion", protocolVersion)
        if (commandCreatedAt != null) payload.put("commandCreatedAt", commandCreatedAt)
        if (parameterNames != null) payload.put("parameterNames", parameterNames)
        if (!playerSessionId.isNullOrBlank()) payload.put("playerSessionId", playerSessionId)
        if (!transitionDirection.isNullOrBlank()) payload.put("transitionDirection", transitionDirection.uppercase())
        NativeMailbox.write(
            this,
            JSONObject().put("type", "diagnostic").put("requestId", requestId ?: "").put("payload", payload),
        )
    }

    private fun publishPlayerSessionDiagnostic(
        requestId: String?,
        event: String,
        sessionId: String? = activePlayerSessionId,
    ) {
        publishNativeDiagnostic(
            event,
            requestId,
            action = "play",
            playerSessionId = sessionId,
        )
    }

    private fun publishNativeCommandError(
        requestId: String?,
        action: String?,
        stage: String,
        code: String,
        message: String,
    ) {
        completeComposeSettingsStorageAction(
            requestId,
            "ERROR",
            error = message,
        )
        nativeRequestState.markOperationState(
            requestId, action, NativeRequestState.OperationState.FAILED,
        )
        NativeMailbox.write(
            this,
            JSONObject().put("type", "native_error").put("requestId", requestId ?: "")
                .put("message", message)
                .put("payload", JSONObject().put("stage", stage).put("code", code)
                    .put("action", action ?: "").put("timestamp", System.currentTimeMillis())),
        )
        publishNativeDiagnostic(
            "COMMAND_FAILED", requestId, action, NativeRequestState.OperationState.FAILED.name,
            error = code,
        )
    }
    /**
     * Existing UI/service boundary for native storage actions. Compose never
     * touches Android permissions or scanners directly.
     */
    fun requestNativeStorageAction(action: String): Boolean {
        val normalized = action.trim()
        val allowed = setOf(
            "select_tree",
            "request_media_access",
            "open_broad_storage_settings",
            "check_storage_access",
            "scan_library",
        )
        if (normalized !in allowed) return false
        handleNativeIntent(createNativeStorageActionIntent(this, normalized))
        return true
    }

    private fun handleNativeIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "reiflix" || data.host != "native") {
            Log.w(tag, "Ignoring unsupported native intent scheme/host")
            return
        }
        val action = data.getQueryParameter("action")?.trim()
        val requestId = data.getQueryParameter("request_id")?.trim()?.takeIf { it.isNotEmpty() }
        val protocolRaw = data.getQueryParameter("protocol_version")?.trim()
        val protocolVersion = protocolRaw?.toIntOrNull()
        val commandCreatedAt = data.getQueryParameter("created_at")?.trim()?.toLongOrNull()
        val parameterNames = data.queryParameterNames.toList().sorted().joinToString(",")

        if (action.isNullOrBlank()) {
            publishNativeCommandError(requestId, action, "command_validation", "MISSING_OPERATION",
                "O comando Android não informou uma operação válida.")
            return
        }
        if (!NativeRequestState.isSupportedAction(action)) {
            publishNativeCommandError(requestId, action, "command_validation", "UNSUPPORTED_OPERATION",
                "A operação Android solicitada não é suportada.")
            return
        }
        if (requestId.isNullOrBlank()) {
            publishNativeCommandError(requestId, action, "command_validation", "MISSING_REQUEST_ID",
                "O comando Android não possui um identificador de requisição.")
            return
        }
        if (protocolRaw != null && (protocolVersion == null || protocolVersion != BRIDGE_PROTOCOL_VERSION)) {
            publishNativeCommandError(requestId, action, "command_validation", "UNSUPPORTED_PROTOCOL_VERSION",
                "A versão do protocolo nativo não é compatível com este ReiAnix.")
            return
        }
        if (protocolRaw != null && (commandCreatedAt == null || commandCreatedAt <= 0L)) {
            publishNativeCommandError(requestId, action, "command_validation", "INVALID_CREATED_AT",
                "O comando Android possui um timestamp inválido.")
            return
        }
        if (!nativeRequestState.acceptRequest(requestId, action, commandCreatedAt ?: System.currentTimeMillis())) {
            Log.i(tag, "COMMAND_DUPLICATE action=" + action + " requestId=" + requestId)
            publishNativeDiagnostic("COMMAND_DUPLICATE", requestId, action, result = "ignored_duplicate")
            return
        }
        val settingsAction = data.getQueryParameter("settings_action")?.trim()?.lowercase()
        if (!settingsAction.isNullOrBlank()) {
            val expectedSettingsAction = when (action) {
                "select_tree" -> "select_saf"
                "request_media_access", "check_storage_access", "open_broad_storage_settings" -> action
                else -> null
            }
            val expectedNativeAccountAction = when (action) {
                "google_sign_in" -> "login"
                "google_sign_out" -> "logout"
                "google_account_switch" -> "switch"
                else -> null
            }
            val validNativeAccountAction =
                data.getQueryParameter("native_account_owner") == "true" &&
                    expectedNativeAccountAction != null &&
                    settingsAction == expectedNativeAccountAction
            if (validNativeAccountAction) {
                // The native account handler writes one terminal Settings result.
                // Do not classify account actions as storage picker operations.
            } else if (settingsAction !in SETTINGS_STORAGE_ACTIONS || settingsAction != expectedSettingsAction) {
                rejectComposeSettingsStorageAction(
                    requestId,
                    settingsAction,
                    "A associação da ação de armazenamento não é válida.",
                )
                publishNativeCommandError(
                    requestId,
                    action,
                    "command_validation",
                    "INVALID_SETTINGS_STORAGE_ACTION",
                    "A operação de armazenamento do Compose não é compatível com a ação nativa.",
                )
                return
            } else {
                beginComposeSettingsStorageAction(requestId, settingsAction)
            }
        }
        val commandReceivedAtMs = System.currentTimeMillis()
        Log.i(tag, "COMMAND_RECEIVED action=" + action + " requestId=" + requestId +
            " receivedAtMs=" + commandReceivedAtMs +
            " task=" + taskId + " resumed=" + activityResumed +
            " focus=" + (window?.decorView?.hasWindowFocus() == true) +
            " protocol=" + (protocolVersion ?: "legacy") +
            " createdAt=" + (commandCreatedAt ?: "-") +
            " parameterNames=" + parameterNames +
            " flags=0x" + intent.flags.toString(16))
        publishNativeDiagnostic("COMMAND_RECEIVED", requestId, action,
            NativeRequestState.OperationState.RECEIVED.name,
            protocolVersion = protocolVersion ?: 1,
            commandCreatedAt = commandCreatedAt,
            parameterNames = parameterNames)

        if (
            appDependencies.bootstrap.state.value.recoveryRequired &&
            action in setOf("open_library", "open_organize", "open_storage_settings", "open_settings")
        ) {
            composeLibraryHost.show(ReiAnixRoutes.RECOVERY, resetBackStack = true)
            nativeRequestState.markOperationState(
                requestId, action, NativeRequestState.OperationState.COMPLETED,
            )
            publishNativeDiagnostic(
                "OPERATION_ROUTED_TO_RECOVERY",
                requestId,
                action,
                NativeRequestState.OperationState.COMPLETED.name,
                result = "canonical_database_recovery_required",
            )
            return
        }
        try {
            when (action) {
                "open_library" -> {
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    val requestedDestination = data.getQueryParameter("start_destination")?.trim()
                    val destination = when (requestedDestination) {
                        ReiAnixRoutes.HOME,
                        ReiAnixRoutes.LIBRARY,
                        ReiAnixRoutes.SEARCH,
                        ReiAnixRoutes.MY_LIST,
                        ReiAnixRoutes.ORGANIZE,
                        ReiAnixRoutes.SETTINGS,
                        ReiAnixRoutes.STORAGE,
                        ReiAnixRoutes.RECOVERY,
                        -> requestedDestination
                        else -> ReiAnixRoutes.LIBRARY
                    }
                    composeLibraryHost.show(destination, resetBackStack = true)
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.COMPLETED)
                    publishNativeDiagnostic("OPERATION_COMPLETED", requestId, action, NativeRequestState.OperationState.COMPLETED.name)
                }
                "open_organize" -> {
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    composeLibraryHost.show(ReiAnixRoutes.ORGANIZE, resetBackStack = true)
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.COMPLETED)
                    publishNativeDiagnostic("OPERATION_COMPLETED", requestId, action, NativeRequestState.OperationState.COMPLETED.name)
                }
                "hide_library" -> {
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    composeLibraryHost.hide()
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.COMPLETED)
                    publishNativeDiagnostic("OPERATION_COMPLETED", requestId, action, NativeRequestState.OperationState.COMPLETED.name)
                }
                "open_storage_settings" -> {
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    composeLibraryHost.show(ReiAnixRoutes.STORAGE, resetBackStack = true)
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.COMPLETED)
                    publishNativeDiagnostic("OPERATION_COMPLETED", requestId, action, NativeRequestState.OperationState.COMPLETED.name)
                }
                "open_settings" -> {
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    composeLibraryHost.show(ReiAnixRoutes.SETTINGS, resetBackStack = true)
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.COMPLETED)
                    publishNativeDiagnostic("OPERATION_COMPLETED", requestId, action, NativeRequestState.OperationState.COMPLETED.name)
                }
                "hide_settings" -> {
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    composeLibraryHost.hide()
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.COMPLETED)
                    publishNativeDiagnostic("OPERATION_COMPLETED", requestId, action, NativeRequestState.OperationState.COMPLETED.name)
                }
                "select_tree" -> {
                    if (!activityResumed) {
                        if (nativeRequestState.queueLifecycleAction("select_tree", requestId)) {
                            Log.i(tag, "COMMAND_QUEUED action=select_tree requestId=" + requestId)
                            publishNativeDiagnostic("COMMAND_QUEUED", requestId, action, NativeRequestState.OperationState.QUEUED.name)
                        } else {
                            publishNativeCommandError(requestId, action, "lifecycle_queue", "LIFECYCLE_QUEUE_BUSY",
                                "Não foi possível iniciar a seleção da pasta agora. Tente novamente.")
                        }
                        return
                    }
                    if (safPickerPending) {
                        publishNativeCommandError(requestId, action, "dispatch", "OPERATION_BUSY",
                            "Já existe uma seleção de pasta em andamento.")
                        return
                    }
                    pendingSafRequestId = requestId
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    publishNativeDiagnostic("OPERATION_STARTED", requestId, action, NativeRequestState.OperationState.RUNNING.name)
                    openTreePicker(requestId)
                }
                "scan_library" -> {
                    if (!activityResumed) {
                        if (nativeRequestState.queueLifecycleAction(action, requestId)) {
                            publishNativeDiagnostic("COMMAND_QUEUED", requestId, action, NativeRequestState.OperationState.QUEUED.name)
                        } else {
                            publishNativeCommandError(
                                requestId, action, "lifecycle_queue", "LIFECYCLE_QUEUE_BUSY",
                                "Não foi possível atualizar a biblioteca agora. Tente novamente.",
                            )
                        }
                        return
                    }
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    publishNativeDiagnostic("OPERATION_STARTED", requestId, action, NativeRequestState.OperationState.RUNNING.name)
                    scanConfiguredSafRoots(
                        requestId,
                        "USER_REFRESH",
                        "compose_library_refresh",
                        includeAuthorizedDiagnostics = true,
                    )
                }
                "scan_tree" -> {
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    publishNativeDiagnostic("OPERATION_STARTED", requestId, action, NativeRequestState.OperationState.RUNNING.name)
                    scanTree(intent.data?.getQueryParameter("tree_uri"), requestId)
                }
                "verify_tree" -> {
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    publishNativeDiagnostic("OPERATION_STARTED", requestId, action, NativeRequestState.OperationState.RUNNING.name)
                    verifyTree(intent.data?.getQueryParameter("tree_uri"), requestId)
                }
                "release_tree" -> {
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    publishNativeDiagnostic("OPERATION_STARTED", requestId, action, NativeRequestState.OperationState.RUNNING.name)
                    releaseTree(
                        intent.data?.getQueryParameter("tree_uri"),
                        requestId,
                        nativeFolderConfigOwner = intent.data?.getQueryParameter("native_folder_config_owner") == "true",
                    )
                }
                "scan_media_store" -> {
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    publishNativeDiagnostic("OPERATION_STARTED", requestId, action, NativeRequestState.OperationState.RUNNING.name)
                    scanMediaStore(requestId)
                }
                "request_media_access" -> {
                    if (!activityResumed) {
                        if (nativeRequestState.queueLifecycleAction(action, requestId)) {
                            publishNativeDiagnostic("COMMAND_QUEUED", requestId, action, NativeRequestState.OperationState.QUEUED.name)
                        } else {
                            publishNativeCommandError(requestId, action, "lifecycle_queue", "LIFECYCLE_QUEUE_BUSY",
                                "Não foi possível iniciar a solicitação de acesso aos vídeos agora. Tente novamente.")
                        }
                        return
                    }
                    if (mediaPermissionRequestPending) {
                        publishNativeCommandError(requestId, action, "dispatch", "OPERATION_BUSY",
                            "Já existe uma solicitação de acesso aos vídeos em andamento.")
                        return
                    }
                    pendingMediaRequestId = requestId
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    publishNativeDiagnostic("OPERATION_STARTED", requestId, action, NativeRequestState.OperationState.RUNNING.name)
                    requestMediaAccess()
                }
                "open_broad_storage_settings" -> {
                    if (!activityResumed) {
                        if (nativeRequestState.queueLifecycleAction(action, requestId)) {
                            publishNativeDiagnostic("COMMAND_QUEUED", requestId, action, NativeRequestState.OperationState.QUEUED.name)
                        } else {
                            publishNativeCommandError(requestId, action, "lifecycle_queue", "LIFECYCLE_QUEUE_BUSY",
                                "Não foi possível abrir as configurações de armazenamento agora. Tente novamente.")
                        }
                        return
                    }
                    pendingBroadRequestId = requestId
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    publishNativeDiagnostic("OPERATION_STARTED", requestId, action, NativeRequestState.OperationState.RUNNING.name)
                    openBroadStorageSettings()
                }
                "check_storage_access" -> {
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    publishNativeDiagnostic("OPERATION_STARTED", requestId, action, NativeRequestState.OperationState.RUNNING.name)
                    publishStorageStatus(requestId)
                    completeComposeSettingsStorageAction(
                        requestId,
                        "SUCCESS",
                        message = "Estado das permissões de armazenamento atualizado.",
                    )
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.COMPLETED)
                    publishNativeDiagnostic("OPERATION_COMPLETED", requestId, action, NativeRequestState.OperationState.COMPLETED.name)
                }
                "scan_all_storage" -> {
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    publishNativeDiagnostic("OPERATION_STARTED", requestId, action, NativeRequestState.OperationState.RUNNING.name)
                    scanAllStorage(requestId)
                }
                "extract_thumbnail" -> {
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    publishNativeDiagnostic("OPERATION_STARTED", requestId, action, NativeRequestState.OperationState.RUNNING.name)
                    requestThumbnail(intent.data, requestId)
                }
                "cancel_scan" -> {
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    publishNativeDiagnostic("OPERATION_STARTED", requestId, action, NativeRequestState.OperationState.RUNNING.name)
                    cancelNativeScans(requestId)
                }
                "google_sign_in" -> {
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    publishNativeDiagnostic("OPERATION_STARTED", requestId, action, NativeRequestState.OperationState.RUNNING.name)
                    val nativeOwner = intent.data?.getQueryParameter("native_account_owner") == "true"
                    val settingsAction = intent.data?.getQueryParameter("settings_action")?.trim()
                    if (nativeOwner && settingsAction == "login") {
                        runNativeComposeAccountAction("login", requestId)
                    } else {
                        signInWithGoogle(intent.data?.getQueryParameter("server_client_id"), requestId)
                    }
                }
                "google_sign_out" -> {
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    publishNativeDiagnostic("OPERATION_STARTED", requestId, action, NativeRequestState.OperationState.RUNNING.name)
                    val nativeOwner = intent.data?.getQueryParameter("native_account_owner") == "true"
                    val settingsAction = intent.data?.getQueryParameter("settings_action")?.trim()
                    if (nativeOwner && settingsAction == "logout") {
                        runNativeComposeAccountAction("logout", requestId)
                    } else {
                        signOutWithGoogle(requestId)
                    }
                }
                "google_account_switch" -> {
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    publishNativeDiagnostic("OPERATION_STARTED", requestId, action, NativeRequestState.OperationState.RUNNING.name)
                    if (
                        intent.data?.getQueryParameter("native_account_owner") == "true" &&
                        intent.data?.getQueryParameter("settings_action")?.trim() == "switch"
                    ) {
                        runNativeComposeAccountAction("switch", requestId)
                    } else {
                        publishNativeCommandError(
                            requestId, action, "dispatch", "UNSUPPORTED_ACCOUNT_ACTION",
                            "A troca de conta precisa ser iniciada pelas configurações nativas.",
                        )
                    }
                }
                "cancel_player_transition" -> {
                    val originRequestId = data.getQueryParameter("origin_request_id")?.trim().orEmpty()
                    val originPlayerSessionId = data.getQueryParameter("origin_player_session_id")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    nativeRequestState.markOperationState(
                        requestId,
                        action,
                        NativeRequestState.OperationState.RUNNING,
                    )
                    publishNativeDiagnostic(
                        "OPERATION_STARTED",
                        requestId,
                        action,
                        NativeRequestState.OperationState.RUNNING.name,
                        playerSessionId = originPlayerSessionId,
                    )
                    if (originRequestId.isBlank()) {
                        publishNativeCommandError(
                            requestId,
                            action,
                            "cancel",
                            "MISSING_ORIGIN_REQUEST_ID",
                            "A invalidação da transição não informou a requisição original.",
                        )
                        return
                    }
                    revokePlayerTransition(originRequestId, originPlayerSessionId)
                    nativeRequestState.markOperationState(
                        requestId,
                        action,
                        NativeRequestState.OperationState.COMPLETED,
                    )
                    publishNativeDiagnostic(
                        "PLAYER_TRANSITION_CANCELLED",
                        requestId,
                        action,
                        NativeRequestState.OperationState.COMPLETED.name,
                        result = originRequestId,
                        playerSessionId = originPlayerSessionId,
                    )
                }
                "play" -> {
                    if (!activityResumed) {
                        val existingPendingAction = nativeRequestState.pendingLifecycleAction
                        val existingPendingId = nativeRequestState.pendingLifecycleRequestId
                        if (existingPendingAction == "play" && !existingPendingId.isNullOrBlank()) {
                            val existingCreatedAt = nativeRequestState.requestSnapshot(existingPendingId)?.createdAt ?: 0L
                            if (commandCreatedAt != null && existingCreatedAt > 0L &&
                                commandCreatedAt <= existingCreatedAt
                            ) {
                                nativeRequestState.markOperationState(
                                    requestId,
                                    action,
                                    NativeRequestState.OperationState.FAILED,
                                )
                                publishNativeDiagnostic(
                                    "PLAYER_HANDOFF_REJECTED",
                                    requestId,
                                    action,
                                    NativeRequestState.OperationState.FAILED.name,
                                    result = "stale_pending_request",
                                )
                                return
                            }
                            nativeRequestState.markOperationState(
                                existingPendingId,
                                "play",
                                NativeRequestState.OperationState.CANCELLED,
                            )
                            publishNativeDiagnostic(
                                "PLAYER_REQUEST_REPLACED",
                                existingPendingId,
                                "play",
                                NativeRequestState.OperationState.CANCELLED.name,
                                result = requestId,
                            )
                            nativeRequestState.consumeLifecycleRequest()
                            clearPendingPlay()
                        }
                        // Preserve the complete original command. Reconstructing a play URI here
                        // used to drop session/generation/origin metadata and could turn a valid
                        // Assistir request into an unscoped native-player launch after resume.
                        pendingPlayIntentData = data.toString()
                        pendingPlayUri = data.getQueryParameter("uri")
                        pendingPlayEpisodeId = data.getQueryParameter("episode_id")?.trim()?.takeIf { it.isNotEmpty() }
                        pendingPlayAnimeId = data.getQueryParameter("anime_id")?.trim()?.takeIf { it.isNotEmpty() }
                        pendingPlayTitle = data.getQueryParameter("title") ?: "Episódio"
                        val rawPositionMs = data.getQueryParameter("position_ms")
                        val parsedPositionMs = rawPositionMs?.toLongOrNull()
                        pendingPlayPositionMs = parsedPositionMs?.coerceAtLeast(0L) ?: 0L
                        pendingPlayPositionSpecified = rawPositionMs != null && parsedPositionMs != null
                        pendingPlayPositionInvalid = rawPositionMs != null && parsedPositionMs == null
                        pendingPlayCanNext = data.getQueryParameter("can_next")?.toBooleanStrictOrNull() ?: false
                        pendingPlayCanPrevious = data.getQueryParameter("can_previous")?.toBooleanStrictOrNull() ?: false
                        pendingPlayAutoplay = data.getQueryParameter("autoplay")?.toBooleanStrictOrNull() ?: true
                        pendingPlayCommandCreatedAtMs = commandCreatedAt ?: 0L
                        pendingPlayRequestId = requestId
                        if (nativeRequestState.queueLifecycleAction("play", requestId)) {
                            publishNativeDiagnostic("COMMAND_QUEUED", requestId, action, NativeRequestState.OperationState.QUEUED.name)
                            return
                        }
                        clearPendingPlay()
                        publishNativeCommandError(requestId, action, "lifecycle_queue", "LIFECYCLE_QUEUE_BUSY",
                            "Não foi possível iniciar a reprodução agora. Tente novamente.")
                        return
                    } else {
                        nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                        publishNativeDiagnostic("OPERATION_STARTED", requestId, action, NativeRequestState.OperationState.RUNNING.name)
                        if (!openPlayer(data, commandReceivedAtMs = commandReceivedAtMs)) {
                            return
                        }
                    }
                }
            }
            publishNativeDiagnostic("COMMAND_DISPATCHED", requestId, action,
                nativeRequestState.operationState(requestId)?.name, result = "dispatched")
        } catch (exception: Exception) {
            Log.e(tag, "NATIVE_DISPATCH_FAILED requestId=" + requestId + " action=" + action, exception)
            publishNativeCommandError(requestId, action, "dispatch", "DISPATCH_EXCEPTION",
                "Não foi possível executar a operação Android solicitada. Tente novamente.")
        }
    }
    /**
     * Aggregates every source included in one library refresh. The terminal event is
     * emitted only after each requested source has a terminal NativeScanController snapshot.
     * MediaStore/BroadStorage are included only when their current permissions authorize them.
     */
    private fun scanConfiguredSafRoots(
        requestId: String?,
        origin: String,
        reason: String,
        includeAuthorizedDiagnostics: Boolean = false,
    ) {
        if (!activityResumed) {
            nativeRequestState.markOperationState(
                requestId,
                "scan_library",
                NativeRequestState.OperationState.FAILED,
            )
            NativeMailbox.writeBestEffort(
                this,
                JSONObject()
                    .put("type", "saf_library_scan_complete")
                    .put("requestId", requestId ?: "")
                    .put(
                        "payload",
                        JSONObject()
                            .put("origin", origin)
                            .put("reason", reason)
                            .put("status", "FAILED")
                            .put("errorCode", "ACTIVITY_NOT_RESUMED")
                            .put("configuredScopes", 0)
                            .put("requestedSources", 0)
                            .put("successfulScopes", 0)
                            .put("failedScopes", 1)
                            .put("cancelledScopes", 0)
                            .put("sourceResults", JSONArray())
                            .put("noSourcesAvailable", false),
                    ),
            )
            return
        }
        lifecycleScope.launch {
            val repository = appDependencies.sqliteLibraryRepository
            val scanIds = mutableListOf<String>()
            val pendingSources = mutableListOf<Pair<String, String>>()
            val sourceResults = JSONArray()
            var successfulScopes = 0
            var failedScopes = 0
            var cancelledScopes = 0
            var configuredScopes = 0
            var requestedSources = 0

            suspend fun awaitAndRecordSource(source: String, scanId: String) {
                scanIds += scanId
                val snapshot = NativeScanController.progress.first { snapshots ->
                    snapshots[scanId]?.state?.isTerminal == true
                }[scanId]
                val terminal = snapshot?.state
                val sourceStatus = when (terminal) {
                    ScanLifecycleState.SUCCEEDED -> {
                        successfulScopes++
                        "COMPLETED"
                    }
                    ScanLifecycleState.PARTIAL -> {
                        successfulScopes++
                        failedScopes++
                        "PARTIAL"
                    }
                    ScanLifecycleState.CANCELLED -> {
                        cancelledScopes++
                        "CANCELLED"
                    }
                    else -> {
                        failedScopes++
                        "FAILED"
                    }
                }
                val result = JSONObject()
                    .put("source", source)
                    .put("scanId", scanId)
                    .put("status", sourceStatus)
                snapshot?.errorCategory?.takeIf { it.isNotBlank() }?.let { result.put("error", it) }
                sourceResults.put(result)
            }

            fun recordSourceStartFailure(source: String, error: String) {
                failedScopes++
                sourceResults.put(
                    JSONObject()
                        .put("source", source)
                        .put("status", "FAILED")
                        .put("error", error),
                )
            }

            try {
                val folders = withContext(Dispatchers.IO) {
                    repository.listFolders()
                        .filter { it.kind.equals("saf", ignoreCase = true) && it.path.isNotBlank() }
                        .distinctBy { it.safIdentity?.takeIf { identity -> identity.isNotBlank() } ?: it.path }
                        .sortedBy { it.path }
                }
                configuredScopes = folders.size
                for (folder in folders) {
                    requestedSources++
                    val reference = folder.path.trim()
                    val sourceKey = "saf:$reference"
                    val treeUri = runCatching { Uri.parse(reference) }.getOrNull()
                    val uriValid = treeUri != null &&
                        treeUri.scheme.equals("content", ignoreCase = true) &&
                        DocumentsContract.isTreeUri(treeUri)
                    val grantValid = uriValid && SafScanner.hasPersistedReadPermission(this@MainActivity, treeUri!!)
                    if (!grantValid) {
                        withContext(Dispatchers.IO) {
                            repository.updateSafFolderAccess(
                                reference,
                                "revoked",
                                "A autorização persistente desta pasta não está mais disponível.",
                            )
                        }
                        val payload = (treeUri?.let { SafScanner.identityPayload(it) } ?: JSONObject())
                            .put("treeUri", reference)
                            .put("granted", false)
                            .put("status", SafScanner.STATUS_REVOKED)
                            .put("nativeFolderConfigOwner", true)
                            .put("error", "persisted_permission_missing")
                        NativeMailbox.writeBestEffort(
                            this@MainActivity,
                            JSONObject().put("type", "saf_permission").put("requestId", requestId ?: "").put("payload", payload),
                        )
                        recordSourceStartFailure(sourceKey, "persisted_permission_missing")
                        continue
                    }

                    val inspection = SafScanner.inspectTree(this@MainActivity, treeUri!!, requirePersisted = true)
                    val inspectionStatus = inspection.optString("status")
                    if (inspectionStatus != SafScanner.STATUS_COMPLETED) {
                        val revoked = inspectionStatus == SafScanner.STATUS_REVOKED
                        val authorization = if (revoked) "revoked" else "unavailable"
                        val error = inspection.optString("error", if (revoked) "persisted_permission_missing" else "provider_unavailable")
                        withContext(Dispatchers.IO) {
                            repository.updateSafFolderAccess(reference, authorization, error)
                        }
                        val identity = SafScanner.identityPayload(treeUri)
                            .put("treeUri", reference)
                            .put("status", if (revoked) SafScanner.STATUS_REVOKED else SafScanner.STATUS_UNAVAILABLE)
                            .put("error", error)
                            .put("nativeFolderConfigOwner", true)
                        if (revoked) {
                            NativeMailbox.writeBestEffort(
                                this@MainActivity,
                                JSONObject().put("type", "saf_permission")
                                    .put("requestId", requestId ?: "")
                                    .put("payload", identity.put("granted", false)),
                            )
                        } else {
                            NativeMailbox.writeBestEffort(
                                this@MainActivity,
                                JSONObject().put("type", "saf_error")
                                    .put("requestId", requestId ?: "")
                                    .put("message", "O provedor desta pasta está indisponível no momento.")
                                    .put("payload", identity),
                            )
                        }
                        recordSourceStartFailure(sourceKey, error)
                        continue
                    }
                    publishNativeDiagnostic(
                        "NATIVE_SAF_SCAN_REQUESTED",
                        requestId,
                        action = "scan",
                        state = "QUEUED",
                        result = "$origin:$reason",
                    )
                    val scanId = scanTree(reference, requestId)
                    if (scanId == null) {
                        recordSourceStartFailure(sourceKey, "scan_not_started")
                        continue
                    }
                    pendingSources += sourceKey to scanId
                }

                if (includeAuthorizedDiagnostics) {
                    if (MediaStoreScanner.hasReadPermission(this@MainActivity)) {
                        requestedSources++
                        val mediaScanId = scanMediaStore(requestId)
                        if (mediaScanId == null) {
                            recordSourceStartFailure(MediaStoreScanner.SOURCE, "scan_not_started")
                        } else {
                            pendingSources += MediaStoreScanner.SOURCE to mediaScanId
                        }
                    }
                    if (BroadStorageScanner.hasAccess(this@MainActivity)) {
                        requestedSources++
                        val broadScanId = scanAllStorage(requestId)
                        if (broadScanId == null) {
                            recordSourceStartFailure(BroadStorageScanner.SOURCE, "scan_not_started")
                        } else {
                            pendingSources += BroadStorageScanner.SOURCE to broadScanId
                        }
                    }
                }

                // Start each eligible source before awaiting any of them. A global
                // cancellation can then reach every active generation, while each source
                // retains its independent terminal result.
                for ((source, scanId) in pendingSources) {
                    awaitAndRecordSource(source, scanId)
                }

                val status = when {
                    requestedSources == 0 -> "FAILED"
                    cancelledScopes == requestedSources && successfulScopes == 0 && failedScopes == 0 -> "CANCELLED"
                    successfulScopes == requestedSources && failedScopes == 0 && cancelledScopes == 0 -> "COMPLETED"
                    successfulScopes > 0 -> "PARTIAL"
                    else -> "FAILED"
                }
                val terminalRequestState = when (status) {
                    "FAILED" -> NativeRequestState.OperationState.FAILED
                    "CANCELLED" -> NativeRequestState.OperationState.CANCELLED
                    else -> NativeRequestState.OperationState.COMPLETED
                }
                nativeRequestState.markOperationState(requestId, "scan_library", terminalRequestState)
                NativeMailbox.writeOrThrow(
                    this@MainActivity,
                    JSONObject()
                        .put("type", "saf_library_scan_complete")
                        .put("requestId", requestId ?: "")
                        .put(
                            "payload",
                            JSONObject()
                                .put("origin", origin)
                                .put("reason", reason)
                                .put("status", status)
                                .put("scanIds", JSONArray(scanIds))
                                .put("sourceResults", sourceResults)
                                .put("configuredScopes", configuredScopes)
                                .put("requestedSources", requestedSources)
                                .put("successfulScopes", successfulScopes)
                                .put("failedScopes", failedScopes)
                                .put("cancelledScopes", cancelledScopes)
                                .put("noSourcesAvailable", requestedSources == 0),
                        ),
                )
                publishNativeDiagnostic(
                    if (terminalRequestState == NativeRequestState.OperationState.COMPLETED) "OPERATION_COMPLETED"
                    else if (terminalRequestState == NativeRequestState.OperationState.CANCELLED) "OPERATION_CANCELLED"
                    else "OPERATION_FAILED",
                    requestId,
                    "scan_library",
                    terminalRequestState.name,
                    result = status,
                )
            } catch (cancelled: CancellationException) {
                nativeRequestState.markOperationState(requestId, "scan_library", NativeRequestState.OperationState.CANCELLED)
                publishNativeDiagnostic("OPERATION_CANCELLED", requestId, "scan_library", NativeRequestState.OperationState.CANCELLED.name)
                throw cancelled
            } catch (exception: Exception) {
                Log.e(tag, "Native storage discovery failed origin=$origin reason=$reason", exception)
                nativeRequestState.markOperationState(requestId, "scan_library", NativeRequestState.OperationState.FAILED)
                publishNativeDiagnostic(
                    "OPERATION_FAILED",
                    requestId,
                    "scan_library",
                    NativeRequestState.OperationState.FAILED.name,
                    result = exception::class.java.simpleName,
                )
                NativeMailbox.writeBestEffort(
                    this@MainActivity,
                    JSONObject()
                        .put("type", "saf_library_scan_complete")
                        .put("requestId", requestId ?: "")
                        .put(
                            "payload",
                            JSONObject()
                                .put("origin", origin)
                                .put("reason", reason)
                                .put("status", "FAILED")
                                .put("configuredScopes", configuredScopes)
                                .put("requestedSources", requestedSources)
                                .put("successfulScopes", successfulScopes)
                                .put("failedScopes", failedScopes + 1)
                                .put("cancelledScopes", cancelledScopes)
                                .put("sourceResults", sourceResults)
                                .put("noSourcesAvailable", requestedSources == 0)
                                .put("errorType", exception::class.java.simpleName),
                        ),
                )
            }
        }
    }

    private fun scanTree(reference: String?, requestId: String? = null): String? {
        val scanId = UUID.randomUUID().toString()
        if (reference.isNullOrBlank()) {
            NativeMailbox.write(this, JSONObject().put("type", "saf_error")
                .put("requestId", requestId ?: "")
                .put("message", "A pasta SAF não foi informada corretamente.")
                .put("payload", JSONObject().put("scanId", scanId)))
            return null
        }
        val treeUri = Uri.parse(reference)
        val persistedInspection = SafScanner.inspectTree(this, treeUri, requirePersisted = true)
        val persistedStatus = persistedInspection.optString("status")
        if (persistedStatus == SafScanner.STATUS_REVOKED) {
            Log.w(tag, "SAF permission revoked")
            val error = persistedInspection.optString("error", "persisted_permission_missing")
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        appDependencies.sqliteLibraryRepository.updateSafFolderAccess(reference, "revoked", error)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    Log.e(tag, "Could not persist revoked SAF state", exception)
                }
                NativeMailbox.writeBestEffort(
                    this@MainActivity,
                    JSONObject().put("type", "saf_permission")
                        .put("requestId", requestId ?: "")
                        .put("payload", SafScanner.identityPayload(treeUri)
                            .put("granted", false).put("status", SafScanner.STATUS_REVOKED)
                            .put("error", error).put("nativeFolderConfigOwner", true)),
                )
                NativeMailbox.writeBestEffort(
                    this@MainActivity,
                    JSONObject().put("type", "saf_error")
                        .put("requestId", requestId ?: "")
                        .put("message", "A permissão desta pasta foi removida. Escolha a pasta novamente.")
                        .put("payload", SafScanner.identityPayload(treeUri).put("scanId", scanId)
                            .put("status", SafScanner.STATUS_REVOKED).put("nativeFolderConfigOwner", true)),
                )
            }
            return null
        }
        if (persistedStatus != SafScanner.STATUS_COMPLETED) {
            val status = if (persistedStatus == SafScanner.STATUS_UNAVAILABLE) SafScanner.STATUS_UNAVAILABLE else SafScanner.STATUS_FAILED
            val authorization = if (status == SafScanner.STATUS_UNAVAILABLE) "unavailable" else "unknown"
            val error = persistedInspection.optString("error", "provider_unavailable")
            val message = if (status == SafScanner.STATUS_UNAVAILABLE) {
                "O provedor desta pasta está indisponível no momento."
            } else {
                "A árvore SAF não pôde ser validada."
            }
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        appDependencies.sqliteLibraryRepository.updateSafFolderAccess(reference, authorization, error)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    Log.e(tag, "Could not persist unavailable SAF state", exception)
                }
                NativeMailbox.writeBestEffort(
                    this@MainActivity,
                    JSONObject().put("type", "saf_error")
                        .put("requestId", requestId ?: "")
                        .put("message", message)
                        .put("payload", SafScanner.identityPayload(treeUri).put("scanId", scanId)
                            .put("status", status).put("error", error).put("nativeFolderConfigOwner", true)),
                )
            }
            return null
        }
        val scanKey = "saf:$reference"
        if (!NativeScanController.begin(scanId, scanKey)) {
            val activeScanId = NativeScanController.activeScanId(scanKey)
            NativeMailbox.write(this, JSONObject().put("type", "saf_scan_progress")
                .put("requestId", requestId ?: "")
                .put("payload", JSONObject().put("treeUri", reference).put("requestId", requestId ?: "").put("phase", "already_running")))
            return activeScanId
        }
        val generationId = NativeIndex.startGeneration(
            this, NativeIndex.SOURCE_SAF, scanKey,
            SafScanner.identityPayload(treeUri).put("scopeKind", "root").put("scopeRef", SafScanner.treeIdentity(treeUri).identity)
                .put("scanId", scanId).put("requestId", requestId ?: "")
        )
        val appContext = applicationContext
        if (!NativeScanController.launch(scanId) {
            NativeScanRunner.runSafScan(
                applicationContext,
                treeUri,
                reference,
                scanId,
                requestId,
                scanKey,
                generationId,
            )
        }) {
            NativeScanController.finish(scanId)
            return null
        }
        return scanId
    }

    private fun requestMediaAccess() {
        if (!activityResumed) {
            // queueLifecycleAction("request_media_access") remains the lifecycle contract;
            // the overload additionally carries the request correlation id.
            nativeRequestState.queueLifecycleAction("request_media_access", pendingMediaRequestId)
            Log.i(tag, "Deferring media permission request until Activity is resumed")
            return
        }
        if (mediaPermissionRequestPending) {
            Log.i(tag, "Media permission request already pending")
            return
        }
        val currentAccess = MediaStoreScanner.accessLevel(this)
        if (currentAccess != "denied") {
        completeComposeSettingsStorageAction(
            pendingMediaRequestId,
            "SUCCESS",
            message = "Permissão de acesso aos vídeos confirmada.",
        )
        nativeRequestState.markOperationState(pendingMediaRequestId, "request_media_access", NativeRequestState.OperationState.COMPLETED)
            Log.i(tag, "Media access already present; continuing directly to scan access=" + currentAccess)
            NativeMailbox.write(this, JSONObject().put("type", "mediastore_permission")
                .put("requestId", pendingMediaRequestId ?: "")
                .put("payload", JSONObject()
                    .put("granted", true)
                    .put("access", currentAccess)
                    .put("source", MediaStoreScanner.SOURCE)
                    .put("capabilities", storageCapabilitiesPayload(StorageLifecycleState.REVALIDATED))))
            // Existing access must converge to the same permission -> scan -> index -> mailbox path.
            val requestId = pendingMediaRequestId
            pendingMediaRequestId = null
            scanMediaStore(requestId)
            return
        }
        val permissions = MediaStoreScanner.requiredPermissions()
        if (permissions.isEmpty()) {
            completeComposeSettingsStorageAction(
                pendingMediaRequestId,
                "ERROR",
                error = "Este Android não disponibiliza permissão de leitura de vídeos.",
            )
            NativeMailbox.write(this, JSONObject().put("type", "mediastore_error")
                .put("requestId", pendingMediaRequestId ?: "")
                .put("message", "Este Android não disponibiliza permissão de leitura de vídeos.")
                .put("payload", JSONObject().put("source", MediaStoreScanner.SOURCE)))
            return
        }
        mediaPermissionRequestPending = true
        publishNativeDiagnostic(
            "STORAGE_PERMISSION_REQUESTED",
            pendingMediaRequestId,
            action = "request_media_access",
            state = "REQUESTED",
            result = "media_store_permission",
        )
        NativeMailbox.write(this, JSONObject().put("type", "mediastore_permission_request")
            .put("requestId", pendingMediaRequestId ?: "")
            .put("payload", JSONObject()
                .put("source", MediaStoreScanner.SOURCE)
                .put("state", "requesting")
                .put("capabilities", storageCapabilitiesPayload(StorageLifecycleState.REQUESTING))))
        try {
            mediaPermissionRequester.launch(permissions)
        } catch (exception: Exception) {
            mediaPermissionRequestPending = false
            completeComposeSettingsStorageAction(
                pendingMediaRequestId,
                "ERROR",
                error = "Não foi possível abrir a solicitação de permissão para vídeos.",
            )
            nativeRequestState.markOperationState(pendingMediaRequestId, "request_media_access", NativeRequestState.OperationState.FAILED)
            pendingMediaRequestId = null
            Log.e(tag, "Media permission launcher failed", exception)
            NativeMailbox.write(this, JSONObject().put("type", "mediastore_error")
                .put("message", "Não foi possível abrir a solicitação de permissão para vídeos.")
                .put("payload", JSONObject().put("source", MediaStoreScanner.SOURCE)))
        }
    }

    /**
     * Dismissal changes only the onboarding presentation; it never grants access,
     * deletes a configured root, or marks a missing folder as ready.
     */
    fun dismissNativeStorageOnboarding() {
        storageOnboardingRefreshJob?.cancel()
        val current = _nativeStorageOnboardingState.value
        if (
            current.phase == NativeStorageOnboardingState.PHASE_NEEDS_FOLDER ||
            (current.phase != NativeStorageOnboardingState.PHASE_READY && !hasPersistedSafTreeGrant())
        ) {
            getSharedPreferences(NATIVE_STORAGE_ONBOARDING_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(NATIVE_STORAGE_ONBOARDING_DISMISSED, false)
                .apply()
            _nativeStorageOnboardingState.value = current.copy(
                dismissed = false,
                phase = NativeStorageOnboardingState.PHASE_NEEDS_FOLDER,
                message = "A biblioteca ainda não tem uma pasta SAF configurada. Escolha uma pasta para continuar.",
            )
            maybeLaunchAutomaticSafPicker()
            return
        }
        getSharedPreferences(NATIVE_STORAGE_ONBOARDING_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(NATIVE_STORAGE_ONBOARDING_DISMISSED, true)
            .apply()
        _nativeStorageOnboardingState.value = current.copy(dismissed = true)
    }

    /** Launches the existing SAF picker once after bootstrap and lifecycle validation. */
    private fun maybeLaunchAutomaticSafPicker() {
        if (
            !nativeBootstrapDecisionComplete ||
            !activityResumed ||
            window?.decorView?.hasWindowFocus() != true ||
            isFinishing ||
            isDestroyed ||
            automaticSafPickerAttempted ||
            safPickerPending ||
            pendingMediaRequestId != null ||
            broadStoragePermissionPending ||
            externalSettingsKind != null ||
            nativeRequestState.pendingLifecycleAction == "select_tree" ||
            appDependencies.bootstrap.state.value.recoveryRequired ||
            appDependencies.bootstrap.state.value.stage != ReiAnixBootstrapStage.READY ||
            _nativeStorageOnboardingState.value.phase != NativeStorageOnboardingState.PHASE_NEEDS_FOLDER
        ) return

        // Set the idempotence guard before dispatching into the existing command/picker flow.
        automaticSafPickerAttempted = true
        getSharedPreferences(NATIVE_STORAGE_ONBOARDING_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(NATIVE_STORAGE_ONBOARDING_DISMISSED, false)
            .apply()
        Log.i(tag, "SAF_PICKER_AUTO_REQUESTED origin=first_run_no_configured_root")
        requestNativeStorageAction("select_tree")
    }

    /**
     * The canonical folder table decides which SAF trees are configured; Android's
     * persisted URI grants and a live provider query decide whether they remain usable.
     * No filesystem path or Python projection is used to establish this state.
     */
    private fun refreshNativeStorageOnboardingState(origin: String) {
        val bootstrap = appDependencies.bootstrap.state.value
        if (
            !nativeBootstrapDecisionComplete ||
            bootstrap.recoveryRequired ||
            bootstrap.stage != ReiAnixBootstrapStage.READY ||
            bootstrap.persistenceStatus !in setOf(
                ReiAnixBootstrapPersistenceStatus.HEALTHY,
                ReiAnixBootstrapPersistenceStatus.NOT_CREATED,
            )
        ) {
            Log.i(tag, "NATIVE_STORAGE_ONBOARDING_REFRESH_DEFERRED origin=" + origin + " reason=bootstrap_not_ready")
            return
        }
        // The ActivityResult callback owns the outcome while DocumentsUI is open.
        // A lifecycle refresh must not overwrite its in-flight state.
        if (
            safPickerPending ||
            _nativeStorageOnboardingState.value.phase == NativeStorageOnboardingState.PHASE_FOLDER_PICKER_OPEN
        ) return

        val previous = _nativeStorageOnboardingState.value
        if (previous.phase != NativeStorageOnboardingState.PHASE_READY) {
            _nativeStorageOnboardingState.value = previous.copy(
                phase = NativeStorageOnboardingState.PHASE_CHECKING,
                message = "Verificando o acesso à pasta da biblioteca…",
                error = null,
            )
        }
        storageOnboardingRefreshJob?.cancel()
        storageOnboardingRefreshJob = lifecycleScope.launch {
            val prefs = getSharedPreferences(NATIVE_STORAGE_ONBOARDING_PREFS, MODE_PRIVATE)
            val wasDismissed = prefs.getBoolean(NATIVE_STORAGE_ONBOARDING_DISMISSED, false)
            val result = try {
                withContext(Dispatchers.IO) {
                    val roots = appDependencies.sqliteLibraryRepository.listFolders()
                        .filter { it.kind.equals("saf", ignoreCase = true) }
                    if (roots.isEmpty()) {
                        NativeStorageOnboardingState(
                            phase = NativeStorageOnboardingState.PHASE_NEEDS_FOLDER,
                            message = "Selecione uma pasta para criar sua biblioteca. A permissão de mídia ou o acesso amplo não substitui uma pasta SAF configurada.",
                            dismissed = false,
                        )
                    } else {
                        var revoked = false
                        var providerUnavailable = false
                        var accessibleRootFound = false
                        for (folder in roots) {
                            val uri = runCatching { Uri.parse(folder.path) }.getOrNull()
                            if (uri == null || !uri.scheme.equals("content", ignoreCase = true) ||
                                !DocumentsContract.isTreeUri(uri)
                            ) {
                                revoked = true
                                continue
                            }
                            val persisted = SafScanner.hasPersistedReadPermission(applicationContext, uri)
                            val inspection = SafScanner.inspectTree(applicationContext, uri, requirePersisted = true)
                            val status = inspection.optString("status")
                            if (persisted && status in setOf(SafScanner.STATUS_COMPLETED, SafScanner.STATUS_EMPTY_COMPLETE)) {
                                accessibleRootFound = true
                                break
                            }
                            if (!persisted || status == SafScanner.STATUS_REVOKED) revoked = true
                            else providerUnavailable = true
                        }
                        when {
                            accessibleRootFound -> NativeStorageOnboardingState(
                                phase = NativeStorageOnboardingState.PHASE_READY,
                            )
                            revoked -> NativeStorageOnboardingState(
                                phase = NativeStorageOnboardingState.PHASE_ERROR,
                                message = "A pasta configurada não está mais autorizada. Selecione novamente a pasta para recuperar o acesso; os dados da biblioteca foram preservados.",
                                error = "A concessão SAF foi revogada ou a URI configurada deixou de ser válida.",
                                dismissed = wasDismissed,
                            )
                            providerUnavailable -> NativeStorageOnboardingState(
                                phase = NativeStorageOnboardingState.PHASE_ERROR,
                                message = "A pasta configurada existe, mas o Android não conseguiu abri-la agora. Verifique o provedor ou escolha novamente a pasta.",
                                error = "O provedor de documentos está indisponível ou não conseguiu consultar a raiz.",
                                dismissed = wasDismissed,
                            )
                            else -> NativeStorageOnboardingState(
                                phase = NativeStorageOnboardingState.PHASE_ERROR,
                                message = "Não foi possível validar a pasta configurada. Tente novamente ou escolha outra pasta; os dados existentes foram preservados.",
                                error = "A validação da fonte SAF não produziu um estado de acesso utilizável.",
                                dismissed = wasDismissed,
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                Log.w(tag, "Native storage onboarding validation failed origin=" + origin, exception)
                NativeStorageOnboardingState(
                    phase = NativeStorageOnboardingState.PHASE_ERROR,
                    message = "Não foi possível verificar o acesso ao armazenamento. Tente novamente em Storage; os dados existentes foram preservados.",
                    error = "A verificação nativa da configuração de armazenamento falhou.",
                    dismissed = wasDismissed,
                )
            }

            // The picker can be launched while the IO query is running. Let its
            // state/result own the surface rather than publishing a stale snapshot.
            if (
                safPickerPending ||
                _nativeStorageOnboardingState.value.phase == NativeStorageOnboardingState.PHASE_FOLDER_PICKER_OPEN
            ) return@launch
            if (result.phase == NativeStorageOnboardingState.PHASE_READY ||
                result.phase == NativeStorageOnboardingState.PHASE_NEEDS_FOLDER
            ) {
                prefs.edit().putBoolean(
                    NATIVE_STORAGE_ONBOARDING_DISMISSED,
                    result.phase != NativeStorageOnboardingState.PHASE_NEEDS_FOLDER && result.dismissed,
                ).apply()
            }
            _nativeStorageOnboardingState.value = result
            Log.i(tag, "NATIVE_STORAGE_ONBOARDING_REVALIDATED origin=" + origin + " phase=" + result.phase)
            if (result.phase == NativeStorageOnboardingState.PHASE_NEEDS_FOLDER) {
                maybeLaunchAutomaticSafPicker()
            }
        }
    }

    /**
     * A media or broad-storage permission is not, by itself, a configured library
     * folder. Startup discovery may only run when Android still holds a persisted
     * read grant for a SAF tree; explicit selection has its own scan path.
     */
    private fun hasPersistedSafTreeGrant(): Boolean = runCatching {
        contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && DocumentsContract.isTreeUri(permission.uri)
        }
    }.getOrDefault(false)

    private fun publishStorageStatus(requestId: String? = null) {
        val broadAccess = BroadStorageScanner.accessSnapshot(this)
        val mediaAccess = MediaStoreScanner.accessLevel(this)
        val broadGranted = BroadStorageScanner.hasAccess(this)
        val capabilities = storageCapabilitiesPayload(StorageLifecycleState.REVALIDATED)
        NativeMailbox.write(
            this,
            JSONObject().put("type", "broad_storage_status")
                .put("requestId", requestId ?: "")
                .put("payload", broadAccess)
                .put("diagnostics", JSONObject()
                    .put("activity", javaClass.name)
                    .put("taskId", taskId)
                    .put("lifecycle", if (activityResumed) "RESUMED" else "PAUSED")
                    .put("permissionState", if (broadGranted) "BROAD_STORAGE_AVAILABLE" else "BROAD_STORAGE_UNAVAILABLE")
                    .put("capabilities", capabilities))
        )
        NativeMailbox.write(
            this,
            JSONObject().put("type", "mediastore_permission")
                .put("requestId", requestId ?: "")
                .put("payload", JSONObject()
                    .put("granted", mediaAccess != "denied")
                    .put("access", mediaAccess)
                    .put("source", MediaStoreScanner.SOURCE)
                    .put("capabilities", capabilities))
                .put("diagnostics", JSONObject()
                    .put("activity", javaClass.name)
                    .put("taskId", taskId)
                    .put("lifecycle", if (activityResumed) "RESUMED" else "PAUSED")
                    .put("permissionState", when (mediaAccess) {
                        "full" -> "MEDIA_FULL"
                        "partial" -> "MEDIA_PARTIAL"
                        else -> "MEDIA_DENIED"
                    }))
        )
        val volumeChanges = NativeIndex.updateVolumeSnapshot(this, NativeIndex.volumeSnapshot(this))
        if (volumeChanges.optBoolean("changed")) {
            NativeMailbox.write(this, JSONObject().put("type","volume_changed").put("payload",
                JSONObject(volumeChanges.toString())
                    .put("source", "android_storage")
                    .put("reason", "lifecycle")
                    .put("timestamp", System.currentTimeMillis())))
        }
        NativeMailbox.write(this, JSONObject().put("type", "storage_capabilities").put("requestId", requestId ?: "").put("payload", capabilities))
        publishSafInventory()
    }

    /**
     * Publish the authoritative SAF grant set after Activity resume/recreation.
     *
     * Python previously tried to verify every persisted tree by launching the
     * app's own reiflix://native intent during startup. That is an avoidable
     * task/lifecycle transition: with MainActivity singleTask, Android may
     * bring the existing task to the foreground and route the new intent to
     * onNewIntent(). The native Activity already owns the persisted grant set,
     * so publish it directly through the existing NativeMailbox instead.
     */
    private fun publishSafInventory() {
        if (!safInventoryInFlight.compareAndSet(false, true)) {
            Log.i(tag, "SAF inventory already running; lifecycle recreation will reuse the in-flight result")
            return
        }
        val appContext = applicationContext
        val lifecycleSnapshot = if (activityResumed) "RESUMED" else "PAUSED"
        val inventoryStartedAtMs = System.currentTimeMillis()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val trees = JSONArray()
                val permissions = appContext.contentResolver.persistedUriPermissions
                    .asSequence()
                    .filter { it.isReadPermission }
                    .map { it.uri }
                    .filter { it.scheme == "content" && DocumentsContract.isTreeUri(it) }
                    .distinct()
                    .sortedBy { it.toString() }
                    .toList()
                for (uri in permissions) {
                    val inspection = SafScanner.inspectTree(appContext, uri, requirePersisted = true)
                    trees.put(inspection.put("persisted", true))
                }
                NativeMailbox.write(appContext, JSONObject().put("type", "saf_inventory")
                    .put("payload", JSONObject()
                        .put("trees", trees)
                        .put("count", trees.length())
                        .put("inventoryComplete", true)
                        .put("inventoryStartedAtMs", inventoryStartedAtMs)
                        .put("inventoryCompletedAtMs", System.currentTimeMillis())
                        .put("lifecycle", lifecycleSnapshot)))
            } catch (exception: Exception) {
                Log.e(LOG_TAG, "SAF inventory failed", exception)
                NativeMailbox.write(appContext, JSONObject().put("type", "saf_error")
                    .put("message", "Não foi possível validar as pastas SAF persistidas.")
                    .put("payload", JSONObject().put("source", "saf").put("status", SafScanner.STATUS_UNAVAILABLE)
                        .put("stage", "inventory")))
            } finally {
                safInventoryInFlight.set(false)
            }
        }
    }
    private fun handleBroadSettingsReturn(requestId: String?, origin: String) {
        if (!broadStoragePermissionPending) {
            Log.i(tag, "SETTINGS_RETURN ignored kind=broad_storage origin=" + origin + " reason=no_pending_request")
            return
        }
        broadStoragePermissionPending = false
        val resolvedRequestId = requestId ?: pendingBroadRequestId
        pendingBroadRequestId = null
        val granted = BroadStorageScanner.hasAccess(this)
        Log.i(
            tag,
            "SETTINGS_RETURN kind=broad_storage origin=" + origin +
                " granted=" + granted +
                " requestId=" + (resolvedRequestId ?: "-"),
        )
        NativeMailbox.write(
            this,
            JSONObject().put("type", "broad_storage_permission")
                .put("requestId", resolvedRequestId ?: "")
                .put("payload", JSONObject()
                    .put("granted", granted)
                    .put("source", BroadStorageScanner.SOURCE)
                    .put("revalidatedAfterSettings", true)
                    .put("capabilities", storageCapabilitiesPayload(StorageLifecycleState.RETURNED)))
        )
        if (granted) {
            publishScanRequest("PERMISSION_CHANGE", "broad_storage", null, false, "broad_storage_settings_return", resolvedRequestId)
        }
        publishStorageCapabilities(StorageLifecycleState.REVALIDATED)
        refreshNativeStorageOnboardingState("broad_settings_return")
    }

    private fun launchExternalSettings(kind: String, requestId: String?, intents: List<Pair<String, Intent>>): Boolean {
        if (!activityResumed) {
            Log.i(tag, "Deferring external Settings launch kind=" + kind + " until Activity is resumed")
            return false
        }
        if (externalSettingsKind != null) {
            Log.i(tag, "External Settings already active kind=" + externalSettingsKind)
            return false
        }
        for ((label, intent) in intents) {
            externalSettingsKind = kind
            externalSettingsRequestId = requestId
            var launched = false
            try {
                Log.i(tag, "SETTINGS_LAUNCH kind=" + kind + " label=" + label + " requestId=" + (requestId ?: "-"))
                externalSettingsLauncher.launch(intent)
                launched = true
                return true
            } catch (exception: ActivityNotFoundException) {
                Log.w(tag, "Settings intent unavailable label=" + label, exception)
            } catch (exception: SecurityException) {
                Log.w(tag, "Settings intent blocked label=" + label, exception)
            } catch (exception: Exception) {
                Log.w(tag, "Settings intent failed label=" + label, exception)
            } finally {
                if (!launched) {
                    externalSettingsKind = null
                    externalSettingsRequestId = null
                }
            }
        }
        return false
    }

    private fun openBroadStorageSettings() {
        if (!activityResumed) {
            nativeRequestState.queueLifecycleAction("open_broad_storage_settings", pendingBroadRequestId)
            Log.i(tag, "Deferring broad-storage Settings launch until Activity is resumed")
            return
        }
        val requestId = pendingBroadRequestId
        if (BroadStorageScanner.hasAccess(this)) {
            completeComposeSettingsStorageAction(
                requestId,
                "SUCCESS",
                message = "O acesso amplo ao armazenamento já está autorizado.",
            )
            pendingBroadRequestId = null
            NativeMailbox.write(
                this,
                JSONObject().put("type", "broad_storage_permission")
                    .put("requestId", requestId ?: "")
                    .put("payload", JSONObject()
                        .put("granted", true)
                        .put("source", BroadStorageScanner.SOURCE)
                        .put("revalidatedAfterSettings", true)
                        .put("capabilities", storageCapabilitiesPayload(StorageLifecycleState.REVALIDATED)))
            )
            scanAllStorage(requestId)
            publishStorageCapabilities(StorageLifecycleState.REVALIDATED)
            return
        }
        if (Build.VERSION.SDK_INT < 30) {
            completeComposeSettingsStorageAction(
                requestId,
                "ERROR",
                error = "O acesso amplo ao armazenamento exige Android 11 ou superior.",
            )
            broadStoragePermissionPending = false
            pendingBroadRequestId = null
            NativeMailbox.write(this, JSONObject().put("type", "broad_storage_error")
                .put("message", "A varredura Broad Storage exige Android 11 (API 30) ou superior, onde o Android expõe a autoridade MANAGE_EXTERNAL_STORAGE.")
                .put("payload", JSONObject()
                    .put("source", BroadStorageScanner.SOURCE)
                    .put("status", "UNAVAILABLE")
                    .put("reason", "manage_external_storage_not_available")
                    .put("api", Build.VERSION.SDK_INT)
                    .put("permissionAuthority", "Environment.isExternalStorageManager")))
            return
        }

        broadStoragePermissionPending = true
        NativeMailbox.write(
            this,
            JSONObject().put("type", "broad_storage_permission_request")
                .put("requestId", requestId ?: "")
                .put("payload", JSONObject()
                    .put("source", BroadStorageScanner.SOURCE)
                    .put("state", "requesting")
                    .put("capabilities", storageCapabilitiesPayload(StorageLifecycleState.REQUESTING)))
        )

        val launched = launchExternalSettings(
            "broad_storage",
            requestId,
            listOf(
                "app_specific_all_files" to Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:$packageName")),
                "global_all_files" to Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                "app_details" to Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName")),
            ),
        )
        if (!launched) {
            completeComposeSettingsStorageAction(
                requestId,
                "ERROR",
                error = "O Android não conseguiu abrir as configurações de acesso amplo.",
            )
            broadStoragePermissionPending = false
            pendingBroadRequestId = null
            NativeMailbox.write(this, JSONObject().put("type", "broad_storage_error")
                .put("message", "O Android não conseguiu abrir diretamente a tela de acesso amplo. Abra as configurações do aplicativo e procure por acesso a todos os arquivos.")
                .put("payload", JSONObject()
                    .put("api", Build.VERSION.SDK_INT)
                    .put("specificIntent", Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .put("globalIntent", Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .put("appDetailsIntent", Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .put("hasAccess", BroadStorageScanner.hasAccess(this))
                    .put("fallbackOpened", false)
                    .put("source", BroadStorageScanner.SOURCE)))
        } else {
            completeComposeSettingsStorageAction(
                requestId,
                "SUCCESS",
                message = "As configurações de acesso amplo foram abertas.",
            )
            Log.i(tag, "SETTINGS_LAUNCH accepted kind=broad_storage requestId=" + (requestId ?: "-"))
        }
    }

    private fun scanAllStorage(requestId: String? = null): String? {
        val appContext = applicationContext
        val scanId = UUID.randomUUID().toString()
        if (!BroadStorageScanner.hasAccess(this)) {
            publishStorageStatus()
            NativeMailbox.write(appContext, JSONObject().put("type", "broad_storage_permission")
                .put("requestId", requestId ?: "")
                .put("payload", JSONObject().put("granted", false).put("source", BroadStorageScanner.SOURCE)))
            return null
        }
        NativeMailbox.write(appContext, JSONObject().put("type", "broad_storage_permission")
            .put("requestId", requestId ?: "")
            .put("payload", JSONObject().put("granted", true).put("source", BroadStorageScanner.SOURCE)))
        if (!NativeScanController.begin(scanId, BroadStorageScanner.SOURCE)) {
            val activeScanId = NativeScanController.activeScanId(BroadStorageScanner.SOURCE)
            NativeMailbox.write(appContext, JSONObject().put("type", "broad_storage_scan_progress")
                .put("requestId", requestId ?: "")
                .put("payload", JSONObject()
                    .put("source", BroadStorageScanner.SOURCE)
                    .put("requestId", requestId ?: "")
                    .put("phase", "already_running")
                    .put("activeScanId", activeScanId ?: "")))
            return activeScanId
        }
        val launched = NativeScanController.launch(scanId) {
            NativeScanRunner.runBroadScan(appContext, scanId, requestId)
        }
        if (launched || NativeScanController.snapshot(scanId)?.state?.isTerminal == true) return scanId
        NativeScanController.finish(scanId)
        return NativeScanController.activeScanId(BroadStorageScanner.SOURCE)
    }

    private fun scanMediaStore(requestId: String? = null): String? {
        val appContext = applicationContext
        val scanId = UUID.randomUUID().toString()
        if (!MediaStoreScanner.hasReadPermission(this)) {
            publishStorageStatus()
            NativeMailbox.write(appContext, JSONObject().put("type", "mediastore_error")
                .put("requestId", requestId ?: "")
                .put("message", "A permissão para ler vídeos ainda não foi concedida.")
                .put("payload", JSONObject().put("source", MediaStoreScanner.SOURCE).put("status", "DENIED").put("access", "denied")))
            return null
        }
        if (!NativeScanController.begin(scanId, MediaStoreScanner.SOURCE)) {
            val activeScanId = NativeScanController.activeScanId(MediaStoreScanner.SOURCE)
            NativeMailbox.write(appContext, JSONObject().put("type", "mediastore_scan_progress")
                .put("requestId", requestId ?: "")
                .put("payload", JSONObject()
                    .put("source", MediaStoreScanner.SOURCE)
                    .put("requestId", requestId ?: "")
                    .put("phase", "already_running")
                    .put("activeScanId", activeScanId ?: "")))
            return activeScanId
        }
        // Consume incremental change hints only when this request owns a new full scan.
        MediaStoreScanner.clearChangeNotification()
        val launched = NativeScanController.launch(scanId) {
            NativeScanRunner.runMediaStoreScan(appContext, scanId, requestId)
        }
        if (launched || NativeScanController.snapshot(scanId)?.state?.isTerminal == true) return scanId
        NativeScanController.finish(scanId)
        return NativeScanController.activeScanId(MediaStoreScanner.SOURCE)
    }

    private fun cancelNativeScans(requestId: String? = null) {
        val ids = NativeScanController.cancelAll()
        // Do not cancel the coroutine immediately: scanners need to observe the flag,
        // emit a final CANCELLED generation, and keep the last good snapshot intact.
        NativeMailbox.write(this, JSONObject().put("type", "scan_cancel_requested").put("requestId", requestId ?: "")
            .put("payload", JSONObject().put("scanIds", JSONArray(ids)).put("count", ids.size)
                .put("status", NativeIndex.STATUS_CANCELLED)))
    }
    private fun requestThumbnail(data: Uri?, requestId: String?) {
        val source = data ?: return
        val raw = source.getQueryParameter("uri")?.trim().orEmpty()
        if (raw.isBlank()) return
        val size = source.getQueryParameter("size")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val modifiedAt = source.getQueryParameter("modified_at")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val mediaIdentity = source.getQueryParameter("media_identity")?.trim().orEmpty()
        val localUri = runCatching {
            val parsed = Uri.parse(raw)
            if (parsed.scheme.isNullOrBlank() && raw.startsWith("/")) {
                Uri.fromFile(File(raw).canonicalFile)
            } else {
                parsed
            }
        }.getOrNull()
        if (localUri == null || !(
            (localUri.scheme == "content" &&
                (SafScanner.isAuthorizedDocument(this, localUri) || MediaStoreScanner.isAuthorizedDocument(this, localUri))) ||
            (localUri.scheme == "file" && BroadStorageScanner.isAuthorizedFile(this, localUri))
        )) {
            NativeMailbox.write(this, JSONObject().put("type", "thumbnail_error")
                .put("requestId", requestId ?: "")
                .put("message", "A mídia local não está autorizada para extração de capa.")
                .put("payload", JSONObject()
                    .put("uri", raw)
                    .put("size", size)
                    .put("modifiedAt", modifiedAt)
                    .put("mediaIdentity", mediaIdentity)
                    .put("status", "UNAUTHORIZED")))
            return
        }

        val appContext = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = VideoThumbnailExtractor.extract(appContext, localUri, size, modifiedAt, mediaIdentity)
                if (result == null) {
                    NativeMailbox.write(appContext, JSONObject().put("type", "thumbnail_error")
                        .put("requestId", requestId ?: "")
                        .put("message", "Não foi possível extrair uma miniatura deste vídeo.")
                        .put("payload", JSONObject()
                            .put("uri", raw)
                            .put("size", size)
                            .put("modifiedAt", modifiedAt)
                            .put("mediaIdentity", mediaIdentity)
                            .put("status", "EXTRACTION_FAILED")))
                    return@launch
                }
                NativeMailbox.write(appContext, JSONObject().put("type", "thumbnail_ready")
                    .put("requestId", requestId ?: "")
                    .put("payload", JSONObject()
                        .put("uri", raw)
                        .put("thumbnailPath", result.path)
                        .put("size", size)
                        .put("modifiedAt", modifiedAt)
                        .put("mediaIdentity", mediaIdentity)
                        .put("durationMs", result.durationMs)
                        .put("width", result.width)
                        .put("height", result.height)
                        .put("rotation", result.rotation)
                        .put("title", result.title ?: "")
                        .put("mimeType", result.mimeType ?: "")
                        .put("canonicalArtworkRegistered", result.canonicalArtworkRegistered)
                        .put("source", "media_metadata_retriever")))
            } catch (exception: Exception) {
                Log.e(LOG_TAG, "Thumbnail extraction failed", exception)
                val sourceMissing = localUri.scheme == "file" &&
                    !(localUri.path?.let { File(it).isFile } ?: false)
                val errorStatus = if (sourceMissing) "SOURCE_MISSING" else "EXTRACTION_FAILED"
                NativeMailbox.write(appContext, JSONObject().put("type", "thumbnail_error")
                    .put("requestId", requestId ?: "")
                    .put("message", "Não foi possível gerar a miniatura do vídeo.")
                    .put("payload", JSONObject()
                        .put("uri", raw)
                        .put("size", size)
                        .put("modifiedAt", modifiedAt)
                        .put("mediaIdentity", mediaIdentity)
                        .put("status", errorStatus)
                        .put("error", exception.message ?: "MediaMetadataRetriever não conseguiu obter um frame válido.")))
            }
        }
    }

    private fun releaseTree(
        reference: String?,
        requestId: String? = null,
        nativeFolderConfigOwner: Boolean = false,
    ) {
        if (reference.isNullOrBlank()) {
            publishNativeCommandError(
                requestId,
                "release_tree",
                "command_validation",
                "MISSING_TREE_URI",
                "A pasta SAF não foi informada corretamente.",
            )
            return
        }
        val normalizedReference = reference.trim()
        val treeUri = runCatching { Uri.parse(normalizedReference) }.getOrNull()
        if (treeUri == null || treeUri.scheme?.lowercase() != "content" || !DocumentsContract.isTreeUri(treeUri)) {
            publishNativeCommandError(
                requestId,
                "release_tree",
                "command_validation",
                "INVALID_TREE_URI",
                "A referência da pasta SAF é inválida.",
            )
            if (nativeFolderConfigOwner) {
                libraryViewModel.onSafRemovalTerminal(normalizedReference)
                refreshNativeStorageOnboardingState("saf_release_invalid_uri")
            }
            return
        }

        val ownsRelease = synchronized(pendingSafReleaseWaiters) {
            val existing = pendingSafReleaseWaiters[normalizedReference]
            if (existing != null) {
                requestId?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it !in existing) existing.add(it) }
                false
            } else {
                pendingSafReleaseWaiters[normalizedReference] = mutableListOf<String>().apply {
                    requestId?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                }
                true
            }
        }
        if (!ownsRelease) {
            nativeRequestState.markOperationState(
                requestId,
                "release_tree",
                NativeRequestState.OperationState.RUNNING,
            )
            publishNativeDiagnostic(
                "SAF_RELEASE_DUPLICATE_JOINED",
                requestId,
                action = "release_tree",
                state = NativeRequestState.OperationState.RUNNING.name,
                result = "same_tree_uri",
            )
            return
        }

        fun takeWaitingRequestIds(): List<String> {
            val pending = synchronized(pendingSafReleaseWaiters) {
                pendingSafReleaseWaiters.remove(normalizedReference)?.toList().orEmpty()
            }
            val ids = pending.filter { it.isNotBlank() }.distinct()
            return ids.ifEmpty { requestId?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(it) }.orEmpty() }
        }

        fun publishReleaseError(
            message: String,
            code: String,
            stage: String,
            permissionReleased: Boolean,
            accessRecoveryPersisted: Boolean? = null,
            recoveryError: String? = null,
        ) {
            nativeRequestState.markOperationState(
                requestId,
                "release_tree",
                NativeRequestState.OperationState.FAILED,
            )
            Log.e(tag, "SAF release failed stage=$stage code=$code treeUri=$normalizedReference")
            if (nativeFolderConfigOwner) {
                libraryViewModel.onSafRemovalTerminal(normalizedReference)
                refreshNativeStorageOnboardingState("saf_release_failed")
            }
            val waiters = takeWaitingRequestIds()
            (waiters.ifEmpty { listOf(requestId.orEmpty()) }).forEach { waiterId ->
                nativeRequestState.markOperationState(
                    waiterId,
                    "release_tree",
                    NativeRequestState.OperationState.FAILED,
                )
                val payload = JSONObject()
                    .put("treeUri", normalizedReference)
                    .put("status", SafScanner.STATUS_FAILED)
                    .put("stage", stage)
                    .put("code", code)
                    .put("nativeFolderConfigOwner", nativeFolderConfigOwner)
                    .put("permissionReleased", permissionReleased)
                accessRecoveryPersisted?.let { payload.put("sourceAccessRecoveryPersisted", it) }
                recoveryError?.takeIf { it.isNotBlank() }?.let { payload.put("sourceAccessRecoveryError", it) }
                NativeMailbox.writeBestEffort(
                    this,
                    JSONObject()
                        .put("type", "saf_error")
                        .put("requestId", waiterId)
                        .put("message", message)
                        .put("payload", payload),
                )
            }
        }

        fun matchingPersistedTreeGrants(): List<android.content.UriPermission> {
            val expectedIdentity = SafScanner.treeIdentity(treeUri)
            return contentResolver.persistedUriPermissions.filter { grant ->
                val grantIdentity = runCatching { SafScanner.treeIdentity(grant.uri) }.getOrNull()
                    ?: return@filter false
                grantIdentity.authority == expectedIdentity.authority &&
                    grantIdentity.documentId == expectedIdentity.documentId &&
                    (grant.isReadPermission || grant.isWritePermission)
            }
        }

        try {
            // A SAF tree can retain both read and write grants. Release exactly the
            // modes Android says are persisted, including grants whose URI string is
            // equivalent but not byte-for-byte identical to the configured reference.
            val persistedGrants = matchingPersistedTreeGrants()
            persistedGrants.forEach { grant ->
                var modeFlags = 0
                if (grant.isReadPermission) {
                    modeFlags = modeFlags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                if (grant.isWritePermission) {
                    modeFlags = modeFlags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                }
                if (modeFlags != 0) {
                    contentResolver.releasePersistableUriPermission(grant.uri, modeFlags)
                }
            }
            val remainingGrants = matchingPersistedTreeGrants()
            if (remainingGrants.isNotEmpty()) {
                val remainingModes = remainingGrants.joinToString { grant ->
                    buildList {
                        if (grant.isReadPermission) add("read")
                        if (grant.isWritePermission) add("write")
                    }.joinToString("+")
                }
                throw IllegalStateException(
                    "Android still reports persisted SAF permissions for this tree: $remainingModes",
                )
            }
            val permissionAlreadyAbsent = persistedGrants.isEmpty()

            fun publishReleased(sourceAlreadyAbsent: Boolean) {
                val waiters = takeWaitingRequestIds()
                if (nativeFolderConfigOwner) {
                    libraryViewModel.onSafRemovalTerminal(normalizedReference)
                    refreshNativeStorageOnboardingState("saf_release_terminal")
                }
                (waiters.ifEmpty { listOf(requestId.orEmpty()) }).forEach { waiterId ->
                    nativeRequestState.markOperationState(
                        waiterId,
                        "release_tree",
                        NativeRequestState.OperationState.COMPLETED,
                    )
                    NativeMailbox.writeBestEffort(
                        this,
                        JSONObject()
                            .put("type", "saf_released")
                            .put("requestId", waiterId)
                            .put(
                                "payload",
                                JSONObject()
                                    .put("treeUri", normalizedReference)
                                    .put("alreadyAbsent", permissionAlreadyAbsent)
                                    .put("sourceAlreadyAbsent", sourceAlreadyAbsent)
                                    .put("duplicateRequest", waiterId != requestId)
                                    .put("nativeFolderConfigOwner", nativeFolderConfigOwner),
                            ),
                    )
                }
            }

            if (nativeFolderConfigOwner) {
                lifecycleScope.launch {
                    try {
                        val sourceExists = withContext(Dispatchers.IO) {
                            appDependencies.sqliteLibraryRepository.listFolders().any { folder ->
                                folder.kind.equals("saf", ignoreCase = true) &&
                                    folder.path.trim() == normalizedReference
                            }
                        }
                        var sourceAlreadyAbsent = !sourceExists
                        if (sourceExists) {
                            val removed = appDependencies.sqliteLibraryRepository.removeSafFolder(normalizedReference)
                            if (!removed) {
                                val stillConfigured = withContext(Dispatchers.IO) {
                                    appDependencies.sqliteLibraryRepository.listFolders().any { folder ->
                                        folder.kind.equals("saf", ignoreCase = true) &&
                                            folder.path.trim() == normalizedReference
                                    }
                                }
                                if (stillConfigured) {
                                    throw IllegalStateException("The canonical SAF source remained configured after removeSafFolder.")
                                }
                                sourceAlreadyAbsent = true
                            }
                        }
                        publishReleased(sourceAlreadyAbsent)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (exception: Exception) {
                        var accessRecoveryPersisted = false
                        var recoveryError: String? = null
                        try {
                            accessRecoveryPersisted = appDependencies.sqliteLibraryRepository.updateSafFolderAccess(
                                normalizedReference,
                                "unavailable",
                                "Permission released but canonical source removal failed.",
                            )
                        } catch (recoveryException: CancellationException) {
                            throw recoveryException
                        } catch (recoveryException: Exception) {
                            recoveryError = recoveryException.message ?: recoveryException::class.java.simpleName
                            Log.e(tag, "Could not persist unavailable state after SAF permission release", recoveryException)
                        }
                        Log.e(tag, "SAF permission was released but canonical source removal failed", exception)
                        publishReleaseError(
                            message = "A permissão foi liberada, mas a pasta não pôde ser removida da biblioteca.",
                            code = "SQLITE_SOURCE_REMOVAL_FAILED",
                            stage = "sqlite_remove",
                            permissionReleased = true,
                            accessRecoveryPersisted = accessRecoveryPersisted,
                            recoveryError = recoveryError,
                        )
                    }
                }
            } else {
                publishReleased(sourceAlreadyAbsent = false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            Log.e(tag, "Failed to release SAF permission", exception)
            publishReleaseError(
                message = "Não foi possível liberar a permissão desta pasta.",
                code = "PERSISTED_PERMISSION_RELEASE_FAILED",
                stage = "permission_release",
                permissionReleased = false,
            )
        }
    }
    private fun verifyTree(reference: String?, requestId: String? = null) {
        if (reference.isNullOrBlank()) return
        val uri = runCatching { Uri.parse(reference) }.getOrNull()
        if (uri == null) {
            publishNativeCommandError(requestId, "verify_tree", "command_validation", "INVALID_TREE_URI",
                "A referência da pasta SAF é inválida.")
            return
        }
        val inspection = SafScanner.inspectTree(this, uri, requirePersisted = true)
        val status = inspection.optString("status")
        Log.i(tag, "SAF permission verification: status=" + status + " uri=" + reference)
        if (status == SafScanner.STATUS_COMPLETED) {
            nativeRequestState.markOperationState(requestId, "verify_tree", NativeRequestState.OperationState.COMPLETED)
            NativeMailbox.write(this, JSONObject().put("type", "saf_permission")
                .put("requestId", requestId ?: "")
                .put("payload", inspection.put("granted", true).put("selected", false).put("status", status)))
        } else {
            nativeRequestState.markOperationState(requestId, "verify_tree", NativeRequestState.OperationState.FAILED)
            NativeMailbox.write(this, JSONObject().put("type", "saf_error")
                .put("requestId", requestId ?: "")
                .put("message", when (status) {
                    SafScanner.STATUS_REVOKED -> "A autorização desta pasta foi removida."
                    SafScanner.STATUS_UNAVAILABLE -> "O provedor desta pasta está indisponível no momento."
                    else -> "Não foi possível validar esta pasta SAF."
                })
                .put("payload", inspection.put("status", status)))
        }
    }
    private fun openPlayer(data: Uri?, commandReceivedAtMs: Long = 0L): Boolean {
        val source = data ?: return false
        val playerRequest = NativePlayerRequest.fromBridgeUri(source)
        val episodeUri = playerRequest.episodeUri
        val requestId = playerRequest.requestId
        if (requestId.isBlank()) {
            Log.e(tag, "PLAY_HANDOFF_FAILED requestId=- reason=missing_request_id")
            publishNativeCommandError(null, "play", "handoff", "MISSING_REQUEST_ID",
                "Não foi possível iniciar o player porque a requisição não possui identificador.")
            return false
        }
        if (playerRequest.positionParameterInvalid) {
            Log.e(tag, "PLAY_HANDOFF_FAILED requestId=" + requestId + " reason=invalid_position_ms")
            publishNativeCommandError(
                requestId,
                "play",
                "request_validation",
                "INVALID_POSITION_MS",
                "A posição inicial enviada para o player não é válida.",
            )
            return false
        }
        if (episodeUri.isBlank()) {
            Log.e(tag, "PLAY_HANDOFF_FAILED requestId=" + requestId + " reason=missing_uri")
            nativeRequestState.markOperationState(requestId, "play", NativeRequestState.OperationState.FAILED)
            NativeMailbox.writeBestEffort(this, JSONObject().put("type", "player_error")
                .put("requestId", requestId)
                .put("message", "Este episódio não possui uma referência local válida.")
                .put("payload", JSONObject().put("stage", "handoff").put("failureStage", "OPEN_REQUEST").put("reason", "missing_uri")))
            publishNativeDiagnostic("PLAYER_HANDOFF_FAILED", requestId, "play",
                NativeRequestState.OperationState.FAILED.name, error = "MISSING_URI")
            return false
        }

        val localUri = runCatching { Uri.parse(episodeUri) }.getOrNull()
        if (localUri == null || localUri.scheme?.lowercase() !in setOf("content", "file")) {
            Log.e(tag, "PLAY_HANDOFF_FAILED requestId=" + requestId + " uri=" + episodeUri + " reason=unsupported_scheme")
            nativeRequestState.markOperationState(requestId, "play", NativeRequestState.OperationState.FAILED)
            NativeMailbox.writeBestEffort(this, JSONObject().put("type", "player_error")
                .put("requestId", requestId)
                .put("message", "O ReiAnix aceita somente mídias locais autorizadas.")
                .put("payload", JSONObject().put("uri", episodeUri).put("stage", "handoff").put("failureStage", "OPEN_REQUEST").put("reason", "unsupported_scheme")))
            publishNativeDiagnostic("PLAYER_HANDOFF_FAILED", requestId, "play",
                NativeRequestState.OperationState.FAILED.name, error = "UNSUPPORTED_SCHEME")
            return false
        }

        val authority = localUri.authority.orEmpty()

        // MainActivity is only the handoff coordinator. Provider authorization and
        // file-open preflight belong to NativePlayerActivity, where they execute
        // off the UI thread before Media3 preparation.
        val mediaSource = when {
            localUri.scheme.equals("content", true) && authority == MediaStore.AUTHORITY -> "mediastore"
            localUri.scheme.equals("content", true) -> "saf_or_local_provider"
            else -> "broad_storage"
        }
        val sanitizedUri = localUri.scheme + "://" + (authority.ifEmpty { "local" }) + "/[REDACTED]"
        Log.i(tag, "PLAY_HANDOFF requestId=" + requestId.ifEmpty { "-" } +
            " uri_sanitized=" + sanitizedUri +
            " scheme=" + localUri.scheme + " authority=" + authority.ifEmpty { "-" } +
            " source=" + mediaSource + " activityResumed=" + activityResumed + " task=" + taskId)

        val previousActiveRequestId = activePlayerRequestId
        val previousActiveSessionId = activePlayerSessionId
        val previousActiveCommandCreatedAtMs = activePlayerCommandCreatedAtMs
        val originRequestId = playerRequest.originRequestId
        val originCreatedAtMs = playerRequest.originCreatedAtMs
        val incomingPlayerSessionId = playerRequest.playerSessionId
        val originPlayerSessionId = playerRequest.originPlayerSessionId
        val reusingPlayerActivity =
            !activePlayerActivityInstanceId.isNullOrBlank() &&
                !previousActiveSessionId.isNullOrBlank() &&
                previousActiveSessionId == incomingPlayerSessionId
        if (incomingPlayerSessionId.isBlank()) {
            nativeRequestState.markOperationState(
                requestId,
                "play",
                NativeRequestState.OperationState.FAILED,
            )
            publishNativeDiagnostic(
                "PLAYER_HANDOFF_REJECTED",
                requestId,
                "play",
                NativeRequestState.OperationState.FAILED.name,
                result = "PLAYER_SESSION_INVALID",
            )
            NativeMailbox.writeBestEffort(
                this,
                JSONObject()
                    .put("type", "player_error")
                    .put("requestId", requestId)
                    .put("message", "A sessão do player não é válida para esta abertura.")
                    .put(
                        "payload",
                        JSONObject()
                            .put("stage", "handoff")
                            .put("failureStage", "OPEN_REQUEST")
                            .put("reason", "PLAYER_SESSION_INVALID")
                            .put("errorCode", "PLAYER_SESSION_INVALID"),
                    ),
            )
            return false
        }
        if (originRequestId.isNotBlank() && originPlayerSessionId.isBlank()) {
            nativeRequestState.markOperationState(
                requestId,
                "play",
                NativeRequestState.OperationState.FAILED,
            )
            publishNativeDiagnostic(
                "PLAYER_HANDOFF_REJECTED",
                requestId,
                "play",
                NativeRequestState.OperationState.FAILED.name,
                result = "PLAYER_SESSION_INVALID",
            )
            return false
        }
        if (originRequestId.isBlank() && incomingPlayerSessionId.isNotBlank()) {
            if (
                activePlayerActivityInstanceId != null &&
                activePlayerSessionId != null &&
                activePlayerSessionId != incomingPlayerSessionId
            ) {
                nativeRequestState.markOperationState(
                    requestId,
                    "play",
                    NativeRequestState.OperationState.FAILED,
                )
                publishNativeDiagnostic(
                    "PLAYER_HANDOFF_REJECTED",
                    requestId,
                    "play",
                    NativeRequestState.OperationState.FAILED.name,
                    result = "stale_session",
                )
                return false
            }
            activePlayerSessionId = incomingPlayerSessionId
            activePlayerRequestId = requestId
        }
        val staleOriginDiagnosticEvent =
            if (playerRequest.transitionDirection == "PREVIOUS") "PLAYER_PREVIOUS_STALE_REJECTED" else "PLAYER_NEXT_STALE_REJECTED"
        if (originRequestId.isNotBlank() && isPlayerTransitionRevoked(originRequestId, originPlayerSessionId)) {
            nativeRequestState.markOperationState(
                requestId,
                "play",
                NativeRequestState.OperationState.FAILED,
            )
            Log.w(
                tag,
                "PLAYER_HANDOFF_REJECTED requestId=" + requestId +
                    " reason=revoked_origin originRequestId=" + originRequestId,
            )
            publishNativeDiagnostic(
                "PLAYER_HANDOFF_REJECTED",
                requestId,
                "play",
                NativeRequestState.OperationState.FAILED.name,
                result = "revoked_origin",
            )
            NativeMailbox.writeBestEffort(
                this,
                JSONObject()
                    .put("type", "diagnostic")
                    .put("requestId", requestId)
                    .put(
                        "payload",
                        JSONObject()
                            .put("event", staleOriginDiagnosticEvent)
                            .put("requestId", requestId)
                            .put("originRequestId", originRequestId)
                            .put("originPlayerSessionId", originPlayerSessionId)
                            .put("currentPlayerSessionId", activePlayerSessionId ?: "")
                            .put("reason", "revoked_origin"),
                    ),
            )
            return false
        }
        if (originRequestId.isNotBlank()) {
            val activeOriginMismatch = !previousActiveRequestId.isNullOrBlank() &&
                previousActiveRequestId != originRequestId
            val sessionMismatch = originPlayerSessionId.isNotBlank() &&
                !activePlayerSessionId.isNullOrBlank() &&
                activePlayerSessionId != originPlayerSessionId
            val exitRace = originCreatedAtMs > 0L &&
                lastPlayerExitAtMs >= originCreatedAtMs
            if (activeOriginMismatch || sessionMismatch || exitRace) {
                nativeRequestState.markOperationState(
                    requestId,
                    "play",
                    NativeRequestState.OperationState.FAILED,
                )
                val result = when {
                    exitRace -> "stale_after_player_exit"
                    sessionMismatch -> "stale_origin_player_session"
                    else -> "stale_origin_session"
                }
                Log.w(
                    tag,
                    "PLAYER_HANDOFF_REJECTED requestId=" + requestId +
                        " reason=" + result +
                        " originRequestId=" + originRequestId +
                        " active=" + (previousActiveRequestId ?: "-") +
                        " lastPlayerExitAtMs=" + lastPlayerExitAtMs +
                        " originCreatedAtMs=" + originCreatedAtMs,
                )
                publishNativeDiagnostic(
                    "PLAYER_HANDOFF_REJECTED",
                    requestId,
                    "play",
                    NativeRequestState.OperationState.FAILED.name,
                    result = result,
                )
                NativeMailbox.writeBestEffort(
                    this,
                    JSONObject()
                        .put("type", "diagnostic")
                        .put("requestId", requestId)
                        .put(
                            "payload",
                            JSONObject()
                                .put("event", staleOriginDiagnosticEvent)
                                .put("requestId", requestId)
                                .put("originRequestId", originRequestId)
                                .put("originPlayerSessionId", originPlayerSessionId)
                                .put("currentPlayerSessionId", activePlayerSessionId ?: "")
                                .put("originCreatedAtMs", originCreatedAtMs)
                                .put("lastPlayerExitAtMs", lastPlayerExitAtMs)
                                .put("reason", result),
                        ),
                )
                return false
            }
        }
        if (playerRequest.commandCreatedAtMs > 0L &&
            previousActiveCommandCreatedAtMs > 0L &&
            playerRequest.commandCreatedAtMs <= previousActiveCommandCreatedAtMs
        ) {
            nativeRequestState.markOperationState(
                requestId,
                "play",
                NativeRequestState.OperationState.FAILED,
            )
            Log.w(
                tag,
                "PLAYER_HANDOFF_REJECTED requestId=" + requestId +
                    " reason=stale_created_at incoming=" + playerRequest.commandCreatedAtMs +
                    " active=" + previousActiveCommandCreatedAtMs,
            )
            publishNativeDiagnostic(
                "PLAYER_HANDOFF_REJECTED",
                requestId,
                "play",
                NativeRequestState.OperationState.FAILED.name,
                result = "stale_request",
            )
            return false
        }
        if (requestId.isNotBlank() && seenPlayerRequestIds.contains(requestId)) {
            nativeRequestState.markOperationState(
                requestId,
                "play",
                NativeRequestState.OperationState.COMPLETED,
            )
            Log.i(
                tag,
                "PLAY_HANDOFF_DUPLICATE requestId=" + requestId +
                    " ignored=true reason=same_request",
            )
            publishNativeDiagnostic(
                "PLAYER_HANDOFF_DUPLICATE",
                requestId,
                "play",
                NativeRequestState.OperationState.COMPLETED.name,
                result = "ignored_same_request",
            )
            return true
        }
        activePlayerRequestId = requestId.takeIf { it.isNotBlank() }
        activePlayerSessionId = playerRequest.playerSessionId.takeIf { it.isNotBlank() } ?: activePlayerSessionId
        activePlayerCommandCreatedAtMs = playerRequest.commandCreatedAtMs
        Log.i(
            tag,
            "PLAY_HANDOFF_ACCEPTED requestId=" + requestId.ifEmpty { "-" } +
                " reusePlayerActivity=" + reusingPlayerActivity,
        )

        try {
            val handoffDispatchedAtMs = System.currentTimeMillis()
            val intent = playerRequest.toIntent(this, localUri)
                .putExtra("commandReceivedAtMs", commandReceivedAtMs)
                .putExtra("handoffDispatchedAtMs", handoffDispatchedAtMs)
                .putExtra("originRequestId", playerRequest.originRequestId)
                .putExtra("originCreatedAtMs", playerRequest.originCreatedAtMs)
                .putExtra("originTransitionGeneration", playerRequest.originTransitionGeneration)
                .putExtra("playerSessionId", playerRequest.playerSessionId)
                .putExtra("originPlayerSessionId", playerRequest.originPlayerSessionId)
                .putExtra("originMonotonicNs", playerRequest.originMonotonicNs)
                .putExtra("transitionDirection", playerRequest.transitionDirection)

            val resolvedActivity = intent.resolveActivity(packageManager)
            if (resolvedActivity == null) {
                activePlayerRequestId = previousActiveRequestId
                activePlayerSessionId = previousActiveSessionId
                activePlayerCommandCreatedAtMs = previousActiveCommandCreatedAtMs
                nativeRequestState.markOperationState(requestId, "play", NativeRequestState.OperationState.FAILED)
                publishNativeDiagnostic("PLAYER_HANDOFF_FAILED", requestId, "play",
                    NativeRequestState.OperationState.FAILED.name, error = "ACTIVITY_NOT_RESOLVABLE")
                Log.e(
                    tag,
                    "PLAY_HANDOFF_FAILED requestId=" + requestId.ifEmpty { "-" } +
                        " reason=activity_not_resolvable component=" + intent.component,
                )
                NativeMailbox.writeBestEffort(
                    this,
                    JSONObject()
                        .put("type", "player_error")
                        .put("requestId", requestId)
                        .put("message", "O player nativo não está disponível nesta instalação.")
                        .put(
                            "payload",
                            JSONObject()
                                .put("stage", "resolve_intent")
                                .put("reason", "activity_not_resolvable")
                                .put("component", intent.component?.flattenToShortString() ?: ""),
                        ),
                )
                return false
            }
            Log.i(
                tag,
                "PLAY_INTENT_RESOLVED requestId=" + requestId.ifEmpty { "-" } +
                    " resolved=" + resolvedActivity.flattenToShortString() +
                    " flags=0x" + intent.flags.toString(16) +
                    " reuse=" + reusingPlayerActivity,
            )
            NativeMailbox.writeBestEffort(
                this,
                JSONObject()
                    .put("type", "diagnostic")
                    .put("requestId", requestId)
                    .put(
                        "payload",
                        JSONObject()
                            .put("event", "PLAYER_HANDOFF_START")
                            .put("stage", "start_activity")
                            .put("uri", localUri.toString())
                            .put("component", resolvedActivity.flattenToShortString())
                            .put("reuse", reusingPlayerActivity),
                    ),
            )
            Log.i(tag, "PLAY_HANDOFF_START requestId=" + requestId.ifEmpty { "-" } + " component=" + intent.component)

            if (reusingPlayerActivity) {
                startActivity(
                    intent.addFlags(
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                    ),
                )
                PerformanceDiagnostics.markPlayer(this, "handoff_dispatched", requestId, playerRequest.commandCreatedAtMs, reused = true)
                nativeRequestState.markOperationState(requestId, "play", NativeRequestState.OperationState.COMPLETED)
                publishNativeDiagnostic(
                    "PLAYER_HANDOFF_DISPATCHED",
                    requestId,
                    "play",
                    NativeRequestState.OperationState.COMPLETED.name,
                    result = "activity_direct",
                    transitionDirection = playerRequest.transitionDirection,
                )
                Log.i(
                    tag,
                    "PLAY_HANDOFF_DISPATCHED requestId=" + requestId.ifEmpty { "-" } +
                        " atMs=" + handoffDispatchedAtMs +
                        " launcher=activity_direct reuse=true",
                )
            } else {
                playerActivityLauncher.launch(intent)
                PerformanceDiagnostics.markPlayer(this, "handoff_dispatched", requestId, playerRequest.commandCreatedAtMs, reused = false)
                nativeRequestState.markOperationState(requestId, "play", NativeRequestState.OperationState.COMPLETED)
                publishNativeDiagnostic(
                    "PLAYER_HANDOFF_DISPATCHED",
                    requestId,
                    "play",
                    NativeRequestState.OperationState.COMPLETED.name,
                    result = "activity_result",
                    transitionDirection = playerRequest.transitionDirection,
                )
                publishPlayerSessionDiagnostic(requestId, "PLAYER_SESSION_CREATED")
                Log.i(
                    tag,
                    "PLAY_HANDOFF_DISPATCHED requestId=" + requestId.ifEmpty { "-" } +
                        " atMs=" + handoffDispatchedAtMs +
                        " launcher=activity_result",
                )
            }
            if (requestId.isNotBlank()) {
                seenPlayerRequestIds.add(requestId)
                while (seenPlayerRequestIds.size > 256) {
                    val oldest = seenPlayerRequestIds.iterator().next()
                    seenPlayerRequestIds.remove(oldest)
                }
            }
        } catch (exception: Exception) {
            activePlayerRequestId = previousActiveRequestId
            activePlayerSessionId = previousActiveSessionId
            activePlayerCommandCreatedAtMs = previousActiveCommandCreatedAtMs
            nativeRequestState.markOperationState(requestId, "play", NativeRequestState.OperationState.FAILED)
            publishNativeDiagnostic(
                "PLAYER_HANDOFF_FAILED",
                requestId,
                "play",
                NativeRequestState.OperationState.FAILED.name,
                error = "START_ACTIVITY_EXCEPTION",
            )
            Log.e(tag, "PLAY_HANDOFF_FAILED requestId=" + requestId.ifEmpty { "-" } + " reason=start_activity", exception)
            NativeMailbox.writeBestEffort(this, JSONObject().put("type", "player_error")
                .put("requestId", requestId)
                .put("message", "Não foi possível abrir o player local.")
                .put("payload", JSONObject()
                    .put("uri", localUri.toString())
                    .put("stage", "start_activity")
                    .put("error", exception.message ?: exception::class.java.simpleName)))
            return false
        }
        return true
    }

    private fun clearPendingPlay() {
        pendingPlayUri = null
        pendingPlayIntentData = null
        pendingPlayTitle = null
        pendingPlayPositionMs = 0L
        pendingPlayPositionSpecified = false
        pendingPlayPositionInvalid = false
        pendingPlayCanNext = false
        pendingPlayCanPrevious = false
        pendingPlayAutoplay = true
        pendingPlayEpisodeId = null
        pendingPlayAnimeId = null
        pendingPlayCommandCreatedAtMs = 0L
        pendingPlayRequestId = null
    }

    private fun openTreePicker(requestId: String? = pendingSafRequestId) {
        val correlationId = requestId?.trim().orEmpty()
        if (safPickerPending) { Log.i(tag, "SAF_PICKER_ALREADY_PENDING requestId=" + (pendingSafRequestId ?: "-")); return }
        if (correlationId.isBlank()) {
            Log.e(tag, "SAF_PICKER_REQUEST_REJECTED reason=missing_request_id")
            publishSafPickerError(null, "Não foi possível iniciar a seleção da pasta.", "request_validation", "MISSING_REQUEST_ID")
            return
        }
        pendingSafRequestId = correlationId
        nativeRequestState.markOperationState(correlationId, "select_tree", NativeRequestState.OperationState.RUNNING)
        val focused = window?.decorView?.hasWindowFocus() == true
        if (!activityResumed || !focused) {
            setSafPickerPhase(correlationId, SafPickerPhase.REQUESTED)
            val queued = nativeRequestState.queueLifecycleAction("select_tree", correlationId)
            if (!queued && !(nativeRequestState.pendingLifecycleAction == "select_tree" && nativeRequestState.pendingLifecycleRequestId == correlationId)) {
                clearSafPickerPending(correlationId, SafPickerPhase.FAILED)
                publishSafPickerError(correlationId, "Não foi possível iniciar o seletor de pastas agora. Tente novamente.",
                    "lifecycle_queue", "LIFECYCLE_QUEUE_BUSY")
            } else {
                Log.i(tag, "SAF_PICKER_QUEUED requestId=" + correlationId + " resumed=" + activityResumed + " focus=" + focused)
            }
            return
        }
        safPickerPending = true
        getSharedPreferences(NATIVE_STORAGE_ONBOARDING_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(NATIVE_STORAGE_ONBOARDING_DISMISSED, false)
            .apply()
        _nativeStorageOnboardingState.value = NativeStorageOnboardingState(
            phase = NativeStorageOnboardingState.PHASE_FOLDER_PICKER_OPEN,
            message = "Escolha a pasta da biblioteca na tela do Android.",
        )
        safPickerStartedAtMs = System.currentTimeMillis()
        safPickerFocusLost = false
        safPickerFocusRegainedAtMs = 0L
        setSafPickerPhase(correlationId, SafPickerPhase.REQUESTED)
        setSafPickerPhase(correlationId, SafPickerPhase.LAUNCHING)
        publishNativeDiagnostic(
            "STORAGE_PERMISSION_REQUESTED",
            correlationId,
            action = "select_tree",
            state = "REQUESTED",
            result = "saf_tree_selection",
        )
        publishNativeDiagnostic(
            "SAF_PICKER_REQUESTED",
            correlationId,
            action = "select_tree",
            state = "REQUESTED",
            result = "user_action",
        )
        NativeMailbox.write(this, JSONObject().put("type", "saf_permission_request")
            .put("requestId", correlationId).put("payload", JSONObject().put("source", "saf")
                .put("state", "requesting").put("status", "REQUESTED")
                .put("capabilities", storageCapabilitiesPayload(StorageLifecycleState.REQUESTING))))
        val pickerIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            // Do not set a MIME type/category: ACTION_OPEN_DOCUMENT_TREE is the directory contract.
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
        }
        try {
            Log.i(tag, "SAF_PICKER_DIRECT_LAUNCH requestId=" + correlationId +
                " action=" + Intent.ACTION_OPEN_DOCUMENT_TREE +
                " timestamp=" + System.currentTimeMillis())
            treePicker.launch(pickerIntent)
            setSafPickerPhase(correlationId, SafPickerPhase.WAITING_RESULT)
            // Keep polling throughout DocumentsUI. The runnable waits while this
            // Activity is paused/unfocused, then starts the short grace period only
            // after ReiAnix returns to foreground.
            scheduleSafPickerWatchdog(correlationId)
            Log.i(tag, "SAF_PICKER_DIRECT_LAUNCH_ACCEPTED requestId=" + correlationId +
                " timestamp=" + System.currentTimeMillis())
        } catch (exception: Exception) {
            clearSafPickerPending(correlationId, SafPickerPhase.FAILED)
            Log.e(tag, "SAF_PICKER_DIRECT_LAUNCH_FAILED requestId=" + correlationId, exception)
            publishSafPickerError(correlationId,
                "O Android não conseguiu abrir o seletor de pastas. Verifique se o app Arquivos do sistema está disponível.",
                "launch", "DOCUMENTS_UI_LAUNCH_FAILED")
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        logLifecycle("onWindowFocusChanged")
        if (hasFocus) {
            applyApplicationSystemUi()
         publishInteractionProfileIfChanged()
            ViewCompat.requestApplyInsets(window.decorView)
            if (activityResumed && safPickerPending) {
                if (
                    safPickerPhase == SafPickerPhase.WAITING_RESULT &&
                    safPickerFocusRegainedAtMs <= 0L
                ) {
                    safPickerFocusRegainedAtMs = System.currentTimeMillis()
                }
                scheduleSafPickerWatchdog(pendingSafRequestId)
            }
            if (activityResumed && nativeRequestState.pendingLifecycleAction == "select_tree") {
                val queued = nativeRequestState.consumeLifecycleRequest()
                if (queued?.action == "select_tree") {
                    pendingSafRequestId = queued.requestId
                    openTreePicker(queued.requestId)
                }
            }
            maybeLaunchAutomaticSafPicker()
        } else if (safPickerPending) {
            safPickerFocusLost = true
            safPickerFocusRegainedAtMs = 0L
            setSafPickerPhase(pendingSafRequestId, SafPickerPhase.WAITING_RESULT)
        }
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(tag, "CONFIGURATION_CHANGED orientation=${newConfig.orientation}")
        applyApplicationSystemUi()
        ViewCompat.requestApplyInsets(window.decorView)
    }
    private fun publishNativeComposeAccountChanged(requestId: String) {
        runCatching {
            NativeMailbox.write(
                this,
                JSONObject()
                    .put("type", "compose_settings_native_account_changed")
                    .put("requestId", requestId)
                    .put("payload", JSONObject().put("requestId", requestId)),
            )
        }.onFailure { error ->
            Log.w(tag, "NATIVE_ACCOUNT_RUNTIME_PROJECTION_FAILED requestId=" + requestId, error)
        }
    }

    private fun runNativeComposeAccountAction(action: String, requestId: String) {
        val safeActions = setOf("login", "logout", "switch")
        if (action !in safeActions || !SETTINGS_FILE_REQUEST_ID_REGEX.matches(requestId)) {
            Log.w(tag, "SETTINGS_ACCOUNT_ACTION_REJECTED action=" + action)
            return
        }
        lifecycleScope.launch {
            var terminalState = NativeRequestState.OperationState.FAILED
            var accountStateChanged = false
            try {
                val accountRepository = ReiAnixAppDependencies.from(applicationContext).sqliteAccountRepository
                val clientId = getString(R.string.reiflix_google_web_client_id).trim()
                val signedOut = action == "logout" || action == "switch"
                if (signedOut) {
                    val cleared = GoogleIdentity.signOut(
                        this@MainActivity,
                        requestId,
                        publishLegacyMailboxEvents = false,
                    )
                    if (!cleared) {
                        throw IllegalStateException("Não foi possível encerrar a sessão Google com segurança.")
                    }
                    withContext(Dispatchers.IO) { accountRepository.clearProfile() }
                    accountStateChanged = true
                }

                var signedInProfile: GoogleIdentity.AccountProfile? = null
                if (action == "login" || action == "switch") {
                    val result = GoogleIdentity.signIn(
                        this@MainActivity,
                        clientId,
                        requestId,
                        publishLegacyMailboxEvents = false,
                    )
                    signedInProfile = result.profile
                    if (signedInProfile == null) {
                        if (result.cancelled) {
                            terminalState = NativeRequestState.OperationState.CANCELLED
                            val message = "Login Google cancelado."
                            withContext(Dispatchers.IO) {
                                writeSettingsFileActionResult(
                                    requestId, action, "CANCELLED", "CANCELLED", message = message,
                                )
                            }
                            settingsViewModel.refresh()
                            if (accountStateChanged) publishNativeComposeAccountChanged(requestId)
                            publishNativeDiagnostic(
                                "GOOGLE_COMPOSE_ACCOUNT_CANCELLED", requestId, action,
                                NativeRequestState.OperationState.CANCELLED.name,
                            )
                            return@launch
                        }
                        throw IllegalStateException(
                            result.errorMessage ?: "Não foi possível validar a conta Google.",
                        )
                    }
                    withContext(Dispatchers.IO) {
                        accountRepository.saveProfile(
                            com.reiflix.reiflix_local.data.sqlite.ReiAnixAccountProfile(
                                id = signedInProfile!!.id,
                                name = signedInProfile!!.name,
                                email = signedInProfile!!.email,
                                picture = signedInProfile!!.picture,
                            ),
                        )
                    }
                    accountStateChanged = true
                }

                settingsViewModel.refresh()
                publishNativeComposeAccountChanged(requestId)
                terminalState = NativeRequestState.OperationState.COMPLETED
                withContext(Dispatchers.IO) {
                    writeSettingsFileActionResult(
                        requestId, action, "SUCCESS", "SUCCESS",
                        message = when (action) {
                            "login" -> "Conta Google conectada."
                            "logout" -> "Sessão Google encerrada."
                            else -> "Conta Google trocada."
                        },
                    )
                }
                publishNativeDiagnostic(
                    "GOOGLE_COMPOSE_ACCOUNT_COMPLETED", requestId, action, terminalState.name,
                )
            } catch (cancelled: CancellationException) {
                terminalState = NativeRequestState.OperationState.CANCELLED
                withContext(NonCancellable + Dispatchers.IO) {
                    writeSettingsFileActionResult(
                        requestId, action, "CANCELLED", "CANCELLED", error = "A operação da conta foi cancelada.",
                    )
                }
                throw cancelled
            } catch (error: Exception) {
                Log.e(tag, "NATIVE_COMPOSE_ACCOUNT_ACTION_FAILED requestId=" + requestId + " action=" + action, error)
                terminalState = NativeRequestState.OperationState.FAILED
                settingsViewModel.refresh()
                if (accountStateChanged) publishNativeComposeAccountChanged(requestId)
                withContext(Dispatchers.IO) {
                    writeSettingsFileActionResult(
                        requestId, action, "ERROR", "ERROR",
                        error = error.message?.takeIf { it.isNotBlank() } ?: "A operação da conta não foi concluída.",
                    )
                }
                publishNativeDiagnostic(
                    "GOOGLE_COMPOSE_ACCOUNT_FAILED", requestId, action, terminalState.name,
                    error = error::class.java.simpleName,
                )
            } finally {
                nativeRequestState.markOperationState(
                    requestId,
                    when (action) {
                        "login" -> "google_sign_in"
                        "logout" -> "google_sign_out"
                        else -> "google_account_switch"
                    },
                    terminalState,
                )
            }
        }
    }

    private fun signOutWithGoogle(requestId: String? = null) {
        googleSignInJob?.cancel()
        googleSignInJob = null
        googleSignOutJob?.cancel()
        val job = lifecycleScope.launch {
            try {
                val cleared = GoogleIdentity.signOut(this@MainActivity, requestId)
                if (cleared) {
                    nativeRequestState.markOperationState(
                        requestId,
                        "google_sign_out",
                        NativeRequestState.OperationState.COMPLETED,
                    )
                    publishNativeDiagnostic(
                        "GOOGLE_SIGN_OUT_COMPLETED",
                        requestId,
                        "google_sign_out",
                        NativeRequestState.OperationState.COMPLETED.name,
                    )
                } else {
                    nativeRequestState.markOperationState(
                        requestId,
                        "google_sign_out",
                        NativeRequestState.OperationState.FAILED,
                    )
                    publishNativeDiagnostic(
                        "GOOGLE_SIGN_OUT_FAILED",
                        requestId,
                        "google_sign_out",
                        NativeRequestState.OperationState.FAILED.name,
                        error = "CREDENTIAL_STATE_CLEAR_FAILED",
                    )
                }
            } finally {
                if (googleSignOutJob === coroutineContext[Job]) {
                    googleSignOutJob = null
                }
            }
        }
        googleSignOutJob = job
    }

    private fun signInWithGoogle(serverClientId: String?, requestId: String? = null) {
        if (serverClientId.isNullOrBlank()) {
            NativeMailbox.write(this, JSONObject().put("type", "google_error").put("requestId", requestId ?: "").put("message", "Configure o Web Client ID do Google."))
            return
        }
        googleSignInJob?.cancel()
        googleSignInJob = null
        googleSignOutJob?.cancel()
        googleSignOutJob = null
        val job = lifecycleScope.launch {
            try {
                GoogleIdentity.signIn(this@MainActivity, serverClientId, requestId)
            } finally {
                if (googleSignInJob === coroutineContext[Job]) {
                    googleSignInJob = null
                }
            }
        }
        googleSignInJob = job
    }
}
