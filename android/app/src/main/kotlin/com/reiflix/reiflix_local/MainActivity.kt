package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.bridge.GoogleIdentity
import com.reiflix.reiflix_local.bridge.NativeCommandDispatcher
import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.player.NativePlayerRequest
import com.reiflix.reiflix_local.bridge.NativeRequestState
import com.reiflix.reiflix_local.player.DeviceInteractionProfile
import com.reiflix.reiflix_local.player.SystemUiController
import com.reiflix.reiflix_local.scanner.BroadStorageScanner
import com.reiflix.reiflix_local.scanner.MediaStoreRetryScheduler
import com.reiflix.reiflix_local.scanner.MediaStoreScanner
import com.reiflix.reiflix_local.scanner.NativeScanController
import com.reiflix.reiflix_local.scanner.NativeScanPublisher
import com.reiflix.reiflix_local.scanner.SafScanner
import com.reiflix.reiflix_local.storage.NativeIndex
import com.reiflix.reiflix_local.storage.StorageAuthorization
import com.reiflix.reiflix_local.storage.StorageLifecycleState
import com.reiflix.reiflix_local.storage.VideoThumbnailExtractor
import com.reiflix.reiflix_local.ui.host.ReiAnixComposeLibraryHost
import com.reiflix.reiflix_local.ui.host.ReiAnixComposeSettingsHost
import com.reiflix.reiflix_local.ui.host.ReiAnixComposeStorageHost
import com.reiflix.reiflix_local.ui.navigation.ReiAnixRoutes

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
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import io.flutter.embedding.android.FlutterFragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Flet's generated Android template must use this activity instead of its default
 * FlutterActivity. It owns SAF and Google identity because only Android has a
 * ContentResolver/Credential Manager.
 */
class MainActivity : FlutterFragmentActivity() {
    private val tag = "[REIFLIX][ANDROID]"
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
    private var lastObservedMediaAccess: String? = null
    private var lastObservedBroadAccess: Boolean? = null
    private var interactionProfileFingerprint: String? = null
    private val nativeRequestState = NativeRequestState()
    private lateinit var composeLibraryHost: ReiAnixComposeLibraryHost
    private lateinit var composeStorageHost: ReiAnixComposeStorageHost
    private lateinit var composeSettingsHost: ReiAnixComposeSettingsHost

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
    /** Native lifecycle/observer events only request a logical scan. The Python
     * ScanCoordinator decides whether and when a scanner actually runs. */
    private fun publishScanRequest(
        origin: String,
        source: String? = null,
        scopeRef: String? = null,
        full: Boolean = false,
        reason: String = "",
        triggerRequestId: String? = null,
    ) {
        val requestId = UUID.randomUUID().toString()
        NativeMailbox.write(
            this,
            JSONObject()
                .put("type", "scan_request")
                .put("requestId", requestId)
                .put(
                    "payload",
                    JSONObject()
                        .put("origin", origin)
                        .put("source", source ?: "")
                        .put("scopeRef", scopeRef ?: "")
                        .put("full", full)
                        .put("reason", reason)
                        .put("triggerRequestId", triggerRequestId ?: ""),
                ),
        )
        Log.i(
            tag,
            "SCAN_REQUEST requestId=" + requestId +
                " origin=" + origin +
                " source=" + (source ?: "all") +
                " scopeRef=" + (scopeRef ?: "-") +
                " full=" + full +
                " reason=" + (reason.ifBlank { "-" }),
        )
    }
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
            if (changes.optBoolean("changed")) {
                val payload = JSONObject(changes.toString())
                    .put("source", "android_storage")
                    .put("reason", action)
                    .put("timestamp", System.currentTimeMillis())
                    .put("volumeEventUri", intent.data?.toString() ?: "")
                NativeMailbox.write(this@MainActivity, JSONObject()
                    .put("type", "volume_changed")
                    .put("payload", payload))
            }

            // Mirror Nova's media/volume lifecycle integration: a mount or the
            // completion of Android's MediaScanner is a discovery trigger. Never
            // launch a permission UI from a broadcast; only start scans when the
            // current Activity is RESUMED and the corresponding source is actually
            // authorized.
            val discoveryEvent = action == Intent.ACTION_MEDIA_MOUNTED ||
                action == Intent.ACTION_MEDIA_SCANNER_FINISHED
            if (discoveryEvent && activityResumed) {
                publishScanRequest(
                    "VOLUME_MOUNT",
                    source = null,
                    full = false,
                    reason = action,
                )
            } else if (action == Intent.ACTION_MEDIA_UNMOUNTED ||
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
            nativeRequestState.markOperationState(requestId, "request_media_access", NativeRequestState.OperationState.COMPLETED)
            publishScanRequest("PERMISSION_CHANGE", "mediastore", null, false, "media_permission_granted", requestId)
        } else {
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
        nativeRequestState.markOperationState(requestId, "select_tree", NativeRequestState.OperationState.FAILED)
        val payload = JSONObject().put("source", "saf").put("status", status)
            .put("stage", stage).put("code", code)
        if (treeUri != null && treeUri.scheme.equals("content", true)) {
            payload.put("treeUri", treeUri.toString())
        }
        NativeMailbox.write(this, JSONObject().put("type", "saf_error")
            .put("requestId", requestId ?: "").put("message", message).put("payload", payload))
    }

    private fun timeoutSafPicker(requestId: String?) {
        if (!safPickerPending || pendingSafRequestId != requestId) return
        Log.e(tag, "SAF_PICKER_TIMEOUT requestId=" + (requestId ?: "-") +
            " startedAt=" + safPickerStartedAtMs + " now=" + System.currentTimeMillis() +
            " focusLost=" + safPickerFocusLost)
        clearSafPickerPending(requestId, SafPickerPhase.TIMEOUT)
        publishSafPickerError(requestId,
            "Não foi possível abrir o seletor de pastas do Android. Tente novamente.",
            stage = "picker_watchdog", code = "TIMEOUT")
    }

    private fun scheduleSafPickerWatchdog(requestId: String?) {
        val correlationId = requestId?.trim().orEmpty()
        if (!safPickerPending || correlationId.isBlank()) return
        cancelSafPickerWatchdog()
        val runnable = object : Runnable {
            override fun run() {
                if (!safPickerPending || pendingSafRequestId != correlationId) return
                val now = System.currentTimeMillis()
                val focused = window?.decorView?.hasWindowFocus() == true
                if (!activityResumed || !focused) {
                    safPickerWatchdogHandler.postDelayed(this, SAF_PICKER_WATCHDOG_RETRY_MS)
                    return
                }
                if (safPickerFocusLost) {
                    if (safPickerFocusRegainedAtMs == 0L) safPickerFocusRegainedAtMs = now
                    val elapsed = now - safPickerFocusRegainedAtMs
                    if (elapsed >= SAF_PICKER_RETURN_GRACE_MS) timeoutSafPicker(correlationId)
                    else safPickerWatchdogHandler.postDelayed(this, SAF_PICKER_RETURN_GRACE_MS - elapsed)
                    return
                }
                val elapsed = now - safPickerStartedAtMs
                if (elapsed >= SAF_PICKER_LAUNCH_TIMEOUT_MS) timeoutSafPicker(correlationId)
                else safPickerWatchdogHandler.postDelayed(this, SAF_PICKER_LAUNCH_TIMEOUT_MS - elapsed)
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

        if (result.resultCode != RESULT_OK || uri == null) {
            clearSafPickerPending(requestId, SafPickerPhase.CANCELLED)
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
            val transientInspection = SafScanner.inspectTree(this, uri, requirePersisted = false)
            val transientStatus = transientInspection.optString("status")
            if (transientStatus != SafScanner.STATUS_COMPLETED) {
                val status = if (transientStatus == SafScanner.STATUS_REVOKED)
                    SafScanner.STATUS_REVOKED else SafScanner.STATUS_UNAVAILABLE
                clearSafPickerPending(requestId, SafPickerPhase.FAILED)
                publishSafPickerError(requestId, "O provedor não conseguiu abrir a pasta selecionada.",
                    stage = "selection_validation", code = "PROVIDER_UNAVAILABLE", status = status, treeUri = uri)
                return
            }
            val flags = resultIntent.flags
            SafScanner.persistPermission(this, uri, flags)
            val persistedInspection = SafScanner.inspectTree(this, uri, requirePersisted = true)
            val persistedStatus = persistedInspection.optString("status")
            if (persistedStatus != SafScanner.STATUS_COMPLETED) {
                val status = if (!SafScanner.hasPersistedReadPermission(this, uri))
                    SafScanner.STATUS_REVOKED else SafScanner.STATUS_UNAVAILABLE
                clearSafPickerPending(requestId, SafPickerPhase.FAILED)
                publishSafPickerError(requestId, "A autorização da pasta não pôde ser validada.",
                    stage = "persisted_validation", code = "PERSISTED_PERMISSION_INVALID",
                    status = status, treeUri = uri)
                return
            }
            val payload = SafScanner.identityPayload(uri)
                .put("granted", true).put("selected", true)
                .put("status", SafScanner.STATUS_COMPLETED)
                .put("name", SafScanner.displayName(this, uri))
                .put("capabilities", storageCapabilitiesPayload(StorageLifecycleState.REVALIDATED))
            clearSafPickerPending(requestId, SafPickerPhase.COMPLETED)
            NativeMailbox.write(this, JSONObject().put("type", "saf_permission")
                .put("requestId", requestId ?: "").put("payload", payload))
            try {
                publishScanRequest("PERMISSION_CHANGE", "saf", uri.toString(), false, "saf_granted", requestId)
            } catch (exception: Exception) {
                Log.e(tag, "SAF_PICKER_SCAN_TRIGGER_FAILED requestId=" + (requestId ?: "-"), exception)
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
        PerformanceDiagnostics.attach(this)
        PerformanceDiagnostics.sampleMemory(this, "main_on_create")
        installSystemBackHandler()
        nativeRequestState.bind(this)
        NativeCommandDispatcher.start(this)
        nativeRequestState.restore(
            savedInstanceState?.getString(STATE_LAST_NATIVE_REQUEST_ID),
            savedInstanceState?.getString(STATE_PENDING_LIFECYCLE_ACTION),
            savedInstanceState?.getString(STATE_PENDING_LIFECYCLE_REQUEST_ID),
        )
        nativeRequestState.restoreSeenRequestIds(savedInstanceState?.getString(STATE_SEEN_NATIVE_REQUEST_IDS))
        broadStoragePermissionPending = savedInstanceState?.getBoolean(STATE_BROAD_SETTINGS_PENDING) ?: false
        externalSettingsKind = savedInstanceState?.getString("reiflix.externalSettingsKind")
        externalSettingsRequestId = savedInstanceState?.getString("reiflix.externalSettingsRequestId")
        pendingMediaRequestId = savedInstanceState?.getString(STATE_PENDING_MEDIA_REQUEST_ID)
        pendingBroadRequestId = savedInstanceState?.getString(STATE_PENDING_BROAD_REQUEST_ID)
        pendingSafRequestId = savedInstanceState?.getString(STATE_PENDING_SAF_REQUEST_ID)
        pendingPlayUri = savedInstanceState?.getString(STATE_PENDING_PLAY_URI)
        pendingPlayIntentData = savedInstanceState?.getString(STATE_PENDING_PLAY_INTENT_DATA)
        pendingPlayTitle = savedInstanceState?.getString(STATE_PENDING_PLAY_TITLE)
        pendingPlayPositionMs = savedInstanceState?.getLong(STATE_PENDING_PLAY_POSITION_MS, 0L) ?: 0L
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
        lastObservedMediaAccess = savedInstanceState?.getString(STATE_LAST_OBSERVED_MEDIA_ACCESS)
        if (savedInstanceState?.containsKey(STATE_LAST_OBSERVED_BROAD_ACCESS) == true) {
            lastObservedBroadAccess = savedInstanceState.getBoolean(STATE_LAST_OBSERVED_BROAD_ACCESS)
        }
        logLifecycle("onCreate", intent)
        NativeMailbox.write(this, JSONObject().put("type", "diagnostic").put("payload", JSONObject().put("event", "APP_START").put("lifecycle", "onCreate")))
        systemUiController = SystemUiController(window)
        composeLibraryHost = ReiAnixComposeLibraryHost(this)
        composeStorageHost = ReiAnixComposeStorageHost(this)
        composeSettingsHost = ReiAnixComposeSettingsHost(this)
        // The existing SystemUiController remains the single Android system-bar
        // authority while the Compose shell and legacy Flet surfaces coexist.
        systemUiController.applyApplicationPolicy(useContextAppearance = false)
        // Permission-sensitive actions are queued until the Activity is resumed.
        handleNativeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        logLifecycle("onNewIntent", intent)
        handleNativeIntent(intent)
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
        if (safPickerPending) scheduleSafPickerWatchdog(pendingSafRequestId)

        // A lifecycle-sensitive command may have been queued because the
        // Activity was not resumed when Python delivered the request. Do not
        // publish an intermediate "denied" snapshot first: Python could treat
        // that snapshot as the final result and close the onboarding while the
        // real Android permission/settings UI is only about to open.
        val pending = nativeRequestState.consumeLifecycleAction()
        val pendingRequestId = nativeRequestState.consumedLifecycleRequestId()
        if (pending != null) {
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
                        val playData = preservedPlayData ?: Uri.parse("reiflix://native").buildUpon()
                            .appendQueryParameter("action", "play")
                            .appendQueryParameter("request_id", pendingRequestId.orEmpty())
                            .appendQueryParameter("uri", uri)
                            .appendQueryParameter("episode_id", pendingPlayEpisodeId.orEmpty())
                            .appendQueryParameter("anime_id", pendingPlayAnimeId.orEmpty())
                            .appendQueryParameter("title", pendingPlayTitle ?: "Episódio")
                            .appendQueryParameter("position_ms", pendingPlayPositionMs.toString())
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

        // Settings may revoke access while this activity is paused. Always
        // republish the actual Android state after a real Settings return.
        if (broadStoragePermissionPending) {
            // Revalidate the real Android state on every Settings return. This is
            // safe even when ActivityResult is delivered afterward because the
            // return handler is idempotent once the pending flag is cleared.
            handleBroadSettingsReturn(pendingBroadRequestId, "onResume")
            return
        }

        val currentMediaAccess = MediaStoreScanner.accessLevel(this)
        val currentBroadAccess = BroadStorageScanner.hasAccess(this)
        val mediaAccessChangedToUsable = lastObservedMediaAccess != null &&
            lastObservedMediaAccess != currentMediaAccess &&
            currentMediaAccess != "denied"
        val broadBecameAvailable = lastObservedBroadAccess == false && currentBroadAccess
        val shouldDiscover = !startupDiscoveryTriggered
        startupDiscoveryTriggered = true
        lastObservedMediaAccess = currentMediaAccess
        lastObservedBroadAccess = currentBroadAccess

        publishStorageStatus()

        // Activity resume is a lifecycle signal, not a scan command. Only the
        // first real startup or a permission transition produces a coordinator
        // request; ordinary player/background returns do nothing.
        if (shouldDiscover) {
            publishScanRequest(
                "STARTUP",
                source = null,
                full = false,
                reason = "first_activity_resume",
            )
        } else if (mediaAccessChangedToUsable || broadBecameAvailable) {
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

    override fun onPause() {
        activityResumed = false
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
        if (::composeStorageHost.isInitialized) composeStorageHost.dispose()
        if (::composeSettingsHost.isInitialized) composeSettingsHost.dispose()
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
        outState.putBoolean(STATE_STARTUP_DISCOVERY_TRIGGERED, startupDiscoveryTriggered)
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
                    // The promoted Compose host now owns the complete secondary
                    // navigation stack. Legacy settings/storage hosts remain only
                    // as compatibility fallbacks for older transitional callers.
                    if (::composeLibraryHost.isInitialized && composeLibraryHost.isVisible) {
                        if (composeLibraryHost.handleBack()) {
                            Log.i(tag, "BACK_COMPOSE_SHELL_HANDLED")
                            return
                        }
                    }
                    if (::composeSettingsHost.isInitialized && composeSettingsHost.isVisible) {
                        if (composeSettingsHost.handleBack()) {
                            Log.i(tag, "BACK_LEGACY_COMPOSE_SETTINGS_HANDLED")
                            return
                        }
                    }
                    if (::composeStorageHost.isInitialized && composeStorageHost.isVisible) {
                        if (composeStorageHost.handleBack()) {
                            Log.i(tag, "BACK_LEGACY_COMPOSE_STORAGE_HANDLED")
                            return
                        }
                    }
                    systemBackEventCount += 1
                    val backId = systemBackEventCount
                    Log.i(tag, "BACK_PHYSICAL_RECEIVED id=" + backId)
                    if (systemBackDispatchPosted) {
                        Log.i(tag, "BACK_PHYSICAL_RECEIVED duplicate_dispatch id=" + backId)
                        Log.i(tag, "SYSTEM_BACK duplicate_dispatch_suppressed")
                        return
                    }
                    systemBackDispatchPosted = true
                    val engine = flutterEngine
                    if (engine == null) {
                        systemBackDispatchPosted = false
                        Log.w(tag, "SYSTEM_BACK ignored reason=flutter_engine_unavailable")
                        return
                    }
                    Log.i(tag, "BACK_FLUTTER_POP_SENT id=" + backId)
                    engine.navigationChannel.popRoute()
                    // Keep the native guard only for same-loop/re-entrant dispatch.
                    // The longer human-visible debounce remains in Python's
                    // NavigationController boundary.
                    window.decorView.post { systemBackDispatchPosted = false }
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
        )
        if (normalized !in allowed) return false
        val requestId = UUID.randomUUID().toString()
        val uri = Uri.parse(
            "reiflix://native?action=" + Uri.encode(normalized) +
                "&request_id=" + Uri.encode(requestId) +
                "&protocol_version=" + BRIDGE_PROTOCOL_VERSION +
                "&created_at=" + System.currentTimeMillis(),
        )
        handleNativeIntent(Intent(Intent.ACTION_VIEW, uri))
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

        try {
            when (action) {
                "open_library" -> {
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    composeSettingsHost.hide()
                    composeStorageHost.hide()
                    composeLibraryHost.show(ReiAnixRoutes.LIBRARY, resetBackStack = true)
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.COMPLETED)
                    publishNativeDiagnostic("OPERATION_COMPLETED", requestId, action, NativeRequestState.OperationState.COMPLETED.name)
                }
                "open_organize" -> {
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    composeSettingsHost.hide()
                    composeStorageHost.hide()
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
                    composeSettingsHost.hide()
                    composeStorageHost.hide()
                    composeLibraryHost.show(ReiAnixRoutes.STORAGE, resetBackStack = true)
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.COMPLETED)
                    publishNativeDiagnostic("OPERATION_COMPLETED", requestId, action, NativeRequestState.OperationState.COMPLETED.name)
                }
                "open_settings" -> {
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    composeSettingsHost.hide()
                    composeStorageHost.hide()
                    composeLibraryHost.show(ReiAnixRoutes.SETTINGS, resetBackStack = true)
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.COMPLETED)
                    publishNativeDiagnostic("OPERATION_COMPLETED", requestId, action, NativeRequestState.OperationState.COMPLETED.name)
                }
                "hide_settings" -> {
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    composeLibraryHost.hide()
                    composeSettingsHost.hide()
                    composeStorageHost.hide()
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
                    releaseTree(intent.data?.getQueryParameter("tree_uri"), requestId)
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
                    signInWithGoogle(intent.data?.getQueryParameter("server_client_id"), requestId)
                }
                "google_sign_out" -> {
                    nativeRequestState.markOperationState(requestId, action, NativeRequestState.OperationState.RUNNING)
                    publishNativeDiagnostic("OPERATION_STARTED", requestId, action, NativeRequestState.OperationState.RUNNING.name)
                    signOutWithGoogle(requestId)
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
                        pendingPlayPositionMs = data.getQueryParameter("position_ms")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
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
    private fun scanTree(reference: String?, requestId: String? = null) {
        val scanId = UUID.randomUUID().toString()
        if (reference.isNullOrBlank()) {
            NativeMailbox.write(this, JSONObject().put("type", "saf_error")
                .put("requestId", requestId ?: "")
                .put("message", "A pasta SAF não foi informada corretamente.")
                .put("payload", JSONObject().put("scanId", scanId)))
            return
        }
        val treeUri = Uri.parse(reference)
        val persistedInspection = SafScanner.inspectTree(this, treeUri, requirePersisted = true)
        val persistedStatus = persistedInspection.optString("status")
        if (persistedStatus == SafScanner.STATUS_REVOKED) {
            Log.w(tag, "SAF permission revoked")
            NativeMailbox.write(this, JSONObject().put("type", "saf_permission")
                .put("requestId", requestId ?: "")
                .put("payload", SafScanner.identityPayload(treeUri)
                    .put("granted", false).put("status", SafScanner.STATUS_REVOKED)
                    .put("error", persistedInspection.optString("error", "persisted_permission_missing"))))
            NativeMailbox.write(this, JSONObject().put("type", "saf_error")
                .put("requestId", requestId ?: "")
                .put("message", "A permissão desta pasta foi removida. Escolha a pasta novamente.")
                .put("payload", SafScanner.identityPayload(treeUri).put("scanId", scanId).put("status", SafScanner.STATUS_REVOKED)))
            return
        }
        if (persistedStatus != SafScanner.STATUS_COMPLETED) {
            NativeMailbox.write(this, JSONObject().put("type", "saf_error")
                .put("requestId", requestId ?: "")
                .put("message", if (persistedStatus == SafScanner.STATUS_UNAVAILABLE) {
                    "O provedor desta pasta está indisponível no momento."
                } else {
                    "A árvore SAF não pôde ser validada."
                })
                .put("payload", SafScanner.identityPayload(treeUri).put("scanId", scanId)
                    .put("status", if (persistedStatus == SafScanner.STATUS_UNAVAILABLE) SafScanner.STATUS_UNAVAILABLE else SafScanner.STATUS_FAILED)
                    .put("error", persistedInspection.optString("error", "provider_unavailable"))))
            return
        }
        val scanKey = "saf:$reference"
        if (!NativeScanController.begin(scanId, scanKey)) {
            NativeMailbox.write(this, JSONObject().put("type", "saf_scan_progress")
                .put("payload", JSONObject().put("treeUri", reference).put("requestId", requestId ?: "").put("phase", "already_running")))
            return
        }
        val generationId = NativeIndex.startGeneration(
            this, NativeIndex.SOURCE_SAF, scanKey,
            SafScanner.identityPayload(treeUri).put("scopeKind", "root").put("scopeRef", SafScanner.treeIdentity(treeUri).identity).put("scanId", scanId)
        )
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NativeIndex.markGenerationRunning(appContext, NativeIndex.SOURCE_SAF, scanKey, generationId)
                NativeMailbox.write(appContext, JSONObject().put("type", "saf_scan_progress")
                    .put("requestId", requestId ?: "")
                    .put("payload", SafScanner.identityPayload(treeUri).put("scanId", scanId).put("phase", "started")
                        .put("source", "saf").put("generationId", NativeIndex.generationId(NativeIndex.SOURCE_SAF, scanKey, generationId))))
                val onScanProgress: (JSONObject) -> Unit = { progress ->
                    NativeMailbox.write(
                        appContext,
                        JSONObject()
                            .put("type", "saf_scan_progress")
                            .put(
                                "payload",
                                progress
                                    .put("treeUri", reference)
                                    .put("scanId", scanId)
                                    .put("requestId", requestId ?: "")
                                    .put("phase", "scanning")
                            )
                    )
                }
                val shouldCancelScan: () -> Boolean = { NativeScanController.isCancelled(scanId) }
                val result = SafScanner.scan(
                    appContext,
                    treeUri,
                    onScanProgress,
                    shouldCancelScan,
                    scanId,
                ) { batch ->
                    NativeScanPublisher.publish(
                        appContext,
                        "saf_scan_batch",
                        "saf",
                        scanId,
                        requestId,
                        "root",
                        reference,
                        scanKey,
                        generationId,
                        batch,
                    )
                }
                val partial = result.optBoolean("partial")
                val scanStatus = result.optString("status").uppercase()
                val status = when (scanStatus) {
                    SafScanner.STATUS_REVOKED, SafScanner.STATUS_UNAVAILABLE -> NativeIndex.STATUS_UNAVAILABLE
                    SafScanner.STATUS_CANCELLED -> NativeIndex.STATUS_CANCELLED
                    SafScanner.STATUS_PARTIAL -> NativeIndex.STATUS_PARTIAL
                    SafScanner.STATUS_EMPTY_COMPLETE -> NativeIndex.STATUS_EMPTY_COMPLETE
                    else -> NativeIndex.STATUS_COMPLETED
                }
                val finished = NativeIndex.finishGeneration(
                    appContext,
                    NativeIndex.SOURCE_SAF,
                    scanKey,
                    generationId,
                    status,
                    JSONObject()
                        .put("treeUri", reference)
                        .put("stats", result.optJSONObject("stats") ?: JSONObject())
                        .put("status", status)
                        .put("scanId", scanId),
                )
                result.put("scanGeneration", generationId)
                    .put("generationId", NativeIndex.generationId(NativeIndex.SOURCE_SAF, scanKey, generationId))
                    .put("generationStatus", status)
                    .put("batchCount", finished.optInt("batchCount", 0))
                    .put("processed", finished.optInt("processed", 0))
                    .put("nativeDuplicates", finished.optInt("duplicates", 0))
                    .put("requestId", requestId ?: "")
                    .put("scanId", scanId)
                    .put("scopeKind", "root")
                    .put("scopeRef", reference)
                    .put("source", "saf")
                    .put("scope", SafScanner.treeIdentity(treeUri).identity)
                    .put("volumeId", result.optString("volumeId"))
                    .put("status", scanStatus.ifBlank { SafScanner.STATUS_COMPLETED })
                NativeMailbox.writeOrThrow(appContext, JSONObject().put("type", "saf_scan").put("requestId", requestId ?: "").put("payload", result))
            } catch (exception: Exception) {
                Log.e(LOG_TAG, "SAF scan failed", exception)
                NativeIndex.failGeneration(appContext, NativeIndex.SOURCE_SAF, scanKey, generationId,
                    exception.message ?: "SAF scan failed",
                    JSONObject().put("treeUri", reference))
                NativeMailbox.write(appContext, JSONObject().put("type", "saf_error")
                    .put("requestId", requestId ?: "")
                    .put("message", "Não foi possível atualizar esta pasta autorizada.")
                    .put("payload", SafScanner.identityPayload(treeUri).put("scanId", scanId)
                        .put("generationId", "native:" + generationId).put("status", NativeIndex.STATUS_FAILED)
                        .put("source", "saf")))
            } finally {
                NativeScanController.finish(scanId)
            }
        }
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
            publishScanRequest("PERMISSION_CHANGE", "mediastore", null, false, "media_permission_already_granted", requestId)
            return
        }
        val permissions = MediaStoreScanner.requiredPermissions()
        if (permissions.isEmpty()) {
            NativeMailbox.write(this, JSONObject().put("type", "mediastore_error")
                .put("requestId", pendingMediaRequestId ?: "")
                .put("message", "Este Android não disponibiliza permissão de leitura de vídeos.")
                .put("payload", JSONObject().put("source", MediaStoreScanner.SOURCE)))
            return
        }
        mediaPermissionRequestPending = true
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
            nativeRequestState.markOperationState(pendingMediaRequestId, "request_media_access", NativeRequestState.OperationState.FAILED)
            pendingMediaRequestId = null
            Log.e(tag, "Media permission launcher failed", exception)
            NativeMailbox.write(this, JSONObject().put("type", "mediastore_error")
                .put("message", "Não foi possível abrir a solicitação de permissão para vídeos.")
                .put("payload", JSONObject().put("source", MediaStoreScanner.SOURCE)))
        }
    }

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
        CoroutineScope(Dispatchers.IO).launch {
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
            publishScanRequest("PERMISSION_CHANGE", "broad_storage", null, false, "broad_storage_already_granted", requestId)
            publishStorageCapabilities(StorageLifecycleState.REVALIDATED)
            return
        }
        if (Build.VERSION.SDK_INT < 30) {
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
            Log.i(tag, "SETTINGS_LAUNCH accepted kind=broad_storage requestId=" + (requestId ?: "-"))
        }
    }

    private fun scanAllStorage(requestId: String? = null) {
        val scanId = UUID.randomUUID().toString()
        if (!BroadStorageScanner.hasAccess(this)) {
            publishStorageStatus()
            NativeMailbox.write(this, JSONObject().put("type", "broad_storage_permission")
                .put("requestId", requestId ?: "")
                .put("payload", JSONObject().put("granted", false).put("source", BroadStorageScanner.SOURCE)))
            return
        }
        NativeMailbox.write(this, JSONObject().put("type", "broad_storage_permission")
            .put("requestId", requestId ?: "")
            .put("payload", JSONObject().put("granted", true).put("source", BroadStorageScanner.SOURCE)))
        if (!NativeScanController.begin(scanId, BroadStorageScanner.SOURCE)) {
            NativeMailbox.write(this, JSONObject().put("type", "broad_storage_scan_progress")
                .put("payload", JSONObject().put("source", BroadStorageScanner.SOURCE).put("requestId", requestId ?: "").put("phase", "already_running")))
            return
        }
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = BroadStorageScanner.scan(
                    appContext,
                    { progress ->
                        NativeMailbox.write(appContext, JSONObject().put("type", "broad_storage_scan_progress")
                            .put("payload", progress.put("scanId", scanId).put("requestId", requestId ?: "").put("scopeKind", "global")))
                    },
                    { NativeScanController.isCancelled(scanId) },
                    scanId,
                    onBatch = { batch ->
                    val volume = batch.optString("volumeId")
                    NativeScanPublisher.publish(
                        appContext,
                        "broad_storage_scan_batch",
                        "broad_storage",
                        scanId,
                        requestId,
                        "volume",
                        volume,
                        "broad-storage:" + volume,
                        batch.optLong("generation", 0L),
                        batch,
                    )
                    }
                )
                val partial = result.optBoolean("partial")
                result.put("requestId", requestId ?: "")
                    .put("scanId", scanId).put("scopeKind", "global").put("scopeRef", "broad-storage")
                    .put("generationId", "native-scoped")
                    .put("generationStatus", if (result.optBoolean("cancelled")) NativeIndex.STATUS_CANCELLED else if (partial) NativeIndex.STATUS_PARTIAL else NativeIndex.STATUS_COMPLETED)
                NativeMailbox.writeOrThrow(appContext, JSONObject().put("type", "broad_storage_scan")
                    .put("requestId", requestId ?: "").put("payload", result))
            } catch (exception: Exception) {
                Log.e(LOG_TAG, "Broad storage scan failed", exception)
                NativeIndex.failActiveGenerations(
                    appContext,
                    NativeIndex.SOURCE_BROAD,
                    exception.message ?: "Broad storage scan failed",
                )
                NativeMailbox.write(appContext, JSONObject().put("type", "broad_storage_error")
                    .put("requestId", requestId ?: "")
                    .put("message", "Não foi possível concluir a varredura do armazenamento local.")
                    .put("payload", JSONObject()
                        .put("source", BroadStorageScanner.SOURCE)
                        .put("scanId", scanId)
                        .put("status", NativeIndex.STATUS_FAILED)
                        .put("generationStatus", NativeIndex.STATUS_FAILED)
                        .put("partial", true)
                        .put("errorType", exception::class.java.simpleName)
                        .put("error", exception.message ?: "Broad storage scan failed")))
            } finally {
                NativeScanController.finish(scanId)
            }
        }
    }

    private fun scanMediaStore(requestId: String? = null) {
        MediaStoreScanner.clearChangeNotification()
        val scanId = UUID.randomUUID().toString()
        if (!MediaStoreScanner.hasReadPermission(this)) {
            publishStorageStatus()
            NativeMailbox.write(this, JSONObject().put("type", "mediastore_error")
                .put("requestId", requestId ?: "")
                .put("message", "A permissão para ler vídeos ainda não foi concedida.")
                .put("payload", JSONObject().put("source", MediaStoreScanner.SOURCE).put("status", "DENIED").put("access", "denied")))
            return
        }
        if (!NativeScanController.begin(scanId, MediaStoreScanner.SOURCE)) {
            NativeMailbox.write(this, JSONObject().put("type", "mediastore_scan_progress")
                .put("payload", JSONObject().put("source", MediaStoreScanner.SOURCE).put("requestId", requestId ?: "").put("phase", "already_running")))
            return
        }
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NativeMailbox.write(appContext, JSONObject().put("type", "mediastore_scan_progress")
                    .put("payload", JSONObject().put("source", MediaStoreScanner.SOURCE).put("scanId", scanId)
                        .put("requestId", requestId ?: "").put("phase", "started")))
                val result = MediaStoreScanner.scan(
                    appContext,
                    { progress ->
                        NativeMailbox.write(appContext, JSONObject().put("type", "mediastore_scan_progress")
                            .put("payload", progress.put("scanId", scanId).put("requestId", requestId ?: "").put("scopeKind", "global")))
                    },
                    { NativeScanController.isCancelled(scanId) },
                    scanId,
                    onBatch = { batch ->
                    val volume = batch.optString("volumeId")
                    NativeScanPublisher.publish(
                        appContext,
                        "mediastore_scan_batch",
                        "mediastore",
                        scanId,
                        requestId,
                        "volume",
                        volume,
                        "mediastore:" + volume,
                        batch.optLong("generation", NativeIndex.cachedGeneration(appContext, "mediastore:" + volume)),
                        batch,
                    )
                    }
                )
                result.put("requestId", requestId ?: "").put("scanId", scanId)
                    .put("scopeKind", "global").put("scopeRef", MediaStoreScanner.SOURCE)
                val finalStatus = result.optJSONObject("stats")?.optString("status").orEmpty().uppercase()
                if (finalStatus == NativeIndex.STATUS_WAITING_FOR_MEDIASTORE) {
                    NativeMailbox.write(appContext, JSONObject().put("type", "diagnostic")
                        .put("payload", JSONObject().put("event", "WAITING_FOR_MEDIASTORE").put("scanId", scanId).put("requestId", requestId ?: "")))
                    MediaStoreRetryScheduler.schedule(
                        appContext,
                        reason = "media_store_indexing_completed",
                        triggerRequestId = requestId,
                    )
                }
                NativeMailbox.writeOrThrow(appContext, JSONObject().put("type", "mediastore_scan")
                    .put("requestId", requestId ?: "").put("payload", result))
            } catch (exception: Exception) {
                Log.e(LOG_TAG, "MediaStore scan failed", exception)
                NativeIndex.failActiveGenerations(appContext, NativeIndex.SOURCE_MEDIASTORE, exception.message ?: "MediaStore scan failed")
                NativeMailbox.write(appContext, JSONObject().put("type", "mediastore_error")
                    .put("requestId", requestId ?: "")
                    .put("message", "Não foi possível atualizar os vídeos do dispositivo.")
                    .put("payload", JSONObject()
                        .put("source", MediaStoreScanner.SOURCE)
                        .put("scanId", scanId)
                        .put("status", NativeIndex.STATUS_FAILED)
                        .put("access", MediaStoreScanner.accessLevel(appContext))))
            } finally {
                NativeScanController.finish(scanId)
            }
        }
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
        CoroutineScope(Dispatchers.IO).launch {
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

    private fun releaseTree(reference: String?, requestId: String? = null) {
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
        val treeUri = runCatching { Uri.parse(reference) }.getOrNull()
        if (treeUri == null || treeUri.scheme?.lowercase() != "content" || !DocumentsContract.isTreeUri(treeUri)) {
            publishNativeCommandError(
                requestId,
                "release_tree",
                "command_validation",
                "INVALID_TREE_URI",
                "A referência da pasta SAF é inválida.",
            )
            return
        }
        try {
            val hadPersistedReadGrant = SafScanner.hasPersistedReadPermission(this, treeUri)
            if (hadPersistedReadGrant) {
                contentResolver.releasePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } else {
                Log.i(tag, "SAF permission already absent; treating release as completed")
            }
            val alreadyAbsent = !SafScanner.hasPersistedReadPermission(this, treeUri)
            nativeRequestState.markOperationState(
                requestId,
                "release_tree",
                NativeRequestState.OperationState.COMPLETED,
            )
            NativeMailbox.write(
                this,
                JSONObject().put("type", "saf_released")
                    .put("requestId", requestId ?: "")
                    .put(
                        "payload",
                        JSONObject()
                            .put("treeUri", reference)
                            .put("alreadyAbsent", alreadyAbsent),
                    ),
            )
        } catch (exception: Exception) {
            nativeRequestState.markOperationState(
                requestId,
                "release_tree",
                NativeRequestState.OperationState.FAILED,
            )
            Log.e(tag, "Failed to release SAF permission", exception)
            NativeMailbox.write(
                this,
                JSONObject().put("type", "saf_error")
                    .put("requestId", requestId ?: "")
                    .put("message", "Não foi possível liberar a permissão desta pasta.")
                    .put("payload", JSONObject().put("treeUri", reference)),
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
        Log.i(tag, "PLAY_HANDOFF requestId=" + requestId.ifEmpty { "-" } +
            " uri_original=" + episodeUri + " uri_normalized=" + localUri +
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
        safPickerStartedAtMs = System.currentTimeMillis()
        safPickerFocusLost = false
        safPickerFocusRegainedAtMs = 0L
        setSafPickerPhase(correlationId, SafPickerPhase.REQUESTED)
        setSafPickerPhase(correlationId, SafPickerPhase.LAUNCHING)
        NativeMailbox.write(this, JSONObject().put("type", "saf_permission_request")
            .put("requestId", correlationId).put("payload", JSONObject().put("source", "saf")
                .put("state", "requesting").put("status", "REQUESTED")
                .put("capabilities", storageCapabilitiesPayload(StorageLifecycleState.REQUESTING))))
        val pickerIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            // Do not set a MIME type/category: ACTION_OPEN_DOCUMENT_TREE is the directory contract.
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
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
                if (safPickerFocusLost) safPickerFocusRegainedAtMs = System.currentTimeMillis()
                scheduleSafPickerWatchdog(pendingSafRequestId)
            }
            if (activityResumed && nativeRequestState.pendingLifecycleAction == "select_tree") {
                val queued = nativeRequestState.consumeLifecycleRequest()
                if (queued?.action == "select_tree") {
                    pendingSafRequestId = queued.requestId
                    openTreePicker(queued.requestId)
                }
            }
        } else if (safPickerPending) {
            safPickerFocusLost = true
            safPickerFocusRegainedAtMs = 0L
            setSafPickerPhase(pendingSafRequestId, SafPickerPhase.WAITING_RESULT)
            cancelSafPickerWatchdog()
        }
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(tag, "CONFIGURATION_CHANGED orientation=${newConfig.orientation}")
        applyApplicationSystemUi()
        ViewCompat.requestApplyInsets(window.decorView)
    }
    private fun signOutWithGoogle(requestId: String? = null) {
        googleSignInJob?.cancel()
        googleSignInJob = null
        googleSignOutJob?.cancel()
        val job = CoroutineScope(Dispatchers.Main).launch {
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
        val job = CoroutineScope(Dispatchers.Main).launch {
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
