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
import com.reiflix.reiflix_local.scanner.NativeScanRunner
import com.reiflix.reiflix_local.scanner.ScanLifecycleState
import com.reiflix.reiflix_local.scanner.SafScanner
import com.reiflix.reiflix_local.ReiAnixAppDependencies
import com.reiflix.reiflix_local.storage.NativeIndex
import com.reiflix.reiflix_local.storage.StorageAuthorization
import com.reiflix.reiflix_local.storage.StorageLifecycleState
import com.reiflix.reiflix_local.storage.VideoThumbnailExtractor
import com.reiflix.reiflix_local.ui.host.ReiAnixComposeLibraryHost
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
import androidx.activity.viewModels
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModelFactory
import com.reiflix.reiflix_local.viewmodel.ReiAnixSettingsViewModel
import com.reiflix.reiflix_local.viewmodel.ReiAnixSettingsViewModelFactory
import io.flutter.embedding.android.FlutterFragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
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
    private var lastObservedMediaAccess: String? = null
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
     * Native lifecycle events dispatch directly to Kotlin scanners. Python's
     * ScanCoordinator remains only for legacy Flet-originated requests.
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
            source == "saf" || (source == null && origin.uppercase() != "PERMISSION_CHANGE") ->
                scanConfiguredSafRoots(requestId, origin)
            source == "mediastore" -> scanMediaStore(requestId)
            source == "broad_storage" -> scanAllStorage(requestId)
            source == null && origin.uppercase() == "PERMISSION_CHANGE" -> {
                scanMediaStore(requestId)
                scanAllStorage(requestId)
            }
            else -> Log.w(tag, "Ignoring unsupported native scan source=$source origin=$origin")
        }
    }

    private fun scanConfiguredSafRoots(requestId: String?, origin: String) {
        lifecycleScope.launch {
            val roots = try {
                ReiAnixAppDependencies.from(applicationContext).sqliteLibraryRepository.listFolders()
                    .asSequence()
                    .filter { it.kind.equals("saf", ignoreCase = true) && it.path.isNotBlank() }
                    .distinctBy { it.safIdentity?.takeIf { identity -> identity.isNotBlank() } ?: it.path }
                    .toList()
            } catch (exception: kotlinx.coroutines.CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(tag, "Could not read configured SAF roots for native refresh", exception)
                publishNativeLibraryScanComplete(requestId, origin, "FAILED", emptyList(), 0)
                return@launch
            }

            val scanIds = roots.mapNotNull { root -> scanTree(root.path, requestId) }
            if (scanIds.isEmpty()) {
                publishNativeLibraryScanComplete(requestId, origin, "FAILED", emptyList(), 0)
                return@launch
            }
            val terminalProgress = NativeScanController.progress.first { progress ->
                scanIds.all { scanId -> progress[scanId]?.state?.isTerminal == true }
            }
            val successful = scanIds.count { scanId ->
                val state = terminalProgress[scanId]?.state
                state == ScanLifecycleState.COMPLETED || state == ScanLifecycleState.PARTIAL
            }
            publishNativeLibraryScanComplete(
                requestId,
                origin,
                if (successful > 0) "COMPLETED" else "FAILED",
                scanIds,
                successful,
            )
        }
    }

    private fun publishNativeLibraryScanComplete(
        requestId: String?,
        origin: String,
        status: String,
        scanIds: List<String>,
        successfulScopes: Int,
    ) {
        NativeMailbox.write(
            this,
            JSONObject()
                .put("type", "saf_library_scan_complete")
                .put("requestId", requestId ?: "")
                .put(
                    "payload",
                    JSONObject()
                        .put("origin", origin)
                        .put("status", status)
                        .put("scanIds", JSONArray(scanIds))
                        .put("successfulScopes", successfulScopes),
                ),
        )
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
            NativeMailbox.write(this, JSONObject().put("type", "saf_permission")
                .put("requestId", requestId ?: "")
                .put("payload", SafScanner.identityPayload(treeUri)
                    .put("granted", false).put("status", SafScanner.STATUS_REVOKED)
                    .put("error", persistedInspection.optString("error", "persisted_permission_missing"))))
            NativeMailbox.write(this, JSONObject().put("type", "saf_error")
                .put("requestId", requestId ?: "")
                .put("message", "A permissão desta pasta foi removida. Escolha a pasta novamente.")
                .put("payload", SafScanner.identityPayload(treeUri).put("scanId", scanId).put("status", SafScanner.STATUS_REVOKED)))
            return null
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
            return null
        }
        val scanKey = "saf:$reference"
        if (!NativeScanController.begin(scanId, scanKey)) {
            NativeMailbox.write(this, JSONObject().put("type", "saf_scan_progress")
                .put("payload", JSONObject().put("treeUri", reference).put("requestId", requestId ?: "").put("phase", "already_running")))
            return NativeScanController.activeScanId(scanKey)
        }
        val generationId = NativeIndex.startGeneration(
            this, NativeIndex.SOURCE_SAF, scanKey,
            SafScanner.identityPayload(treeUri).put("scopeKind", "root").put("scopeRef", SafScanner.treeIdentity(treeUri).identity)
                .put("scanId", scanId).put("requestId", requestId ?: "")
        )
        NativeScanController.launch(scanId) {
            NativeScanRunner.runSafScan(
                applicationContext,
                treeUri,
                reference,
                scanId,
                requestId,
                scanKey,
                generationId,
            )
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
            nativeRequestState.markOperationState(pendingMediaRequestId, "request_media_access", NativeRequestState.OperationState.FAILED)
            pendingMediaRequestId = null
            Log.e(tag, "Media permission launcher failed", exception)
            NativeMailbox.write(this, JSONObject().put("type", "mediastore_error")
                .put("message", "Não foi possível abrir a solicitação de permissão para vídeos.")
                .put("payload", JSONObject().put("source", MediaStoreScanner.SOURCE)))
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
        NativeScanController.launch(scanId) {
            NativeScanRunner.runBroadScan(
                applicationContext,
                scanId,
                requestId,
            )
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
        NativeScanController.launch(scanId) {
            NativeScanRunner.runMediaStoreScan(
                applicationContext,
                scanId,
                requestId,
            )
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
