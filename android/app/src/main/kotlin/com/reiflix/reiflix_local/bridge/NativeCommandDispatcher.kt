package com.reiflix.reiflix_local.bridge

import com.reiflix.reiflix_local.scanner.BroadStorageScanner
import com.reiflix.reiflix_local.scanner.MediaStoreScanner
import com.reiflix.reiflix_local.scanner.SafScanner
import com.reiflix.reiflix_local.storage.VideoThumbnailExtractor
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.FileObserver
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Process-local Python -> Android command channel for operations that must not
 * resolve through MainActivity's singleTask deep-link.
 *
 * The queue lives beside NativeMailbox's event queue under the same Flet
 * application data directory. Commands are atomically promoted into *.json
 * files; FileObserver consumes them without polling or arbitrary delays.
 *
 * This dispatcher intentionally owns only internal player/thumbnail commands.
 * All existing MainActivity-routed commands keep their current deep-link path.
 */
object NativeCommandDispatcher {
    private const val TAG = "[REIANIX][NATIVE_COMMAND]"
    private const val QUEUE = "reiflix-native-commands"
    private const val TEMP_SUFFIX = ".tmp"
    private const val FILE_PREFIX = "command-"

    private val lock = Any()
    private var started = false
    private lateinit var appContext: Context
    private lateinit var queueDir: File
    private var observer: FileObserver? = null

    private val commandExecutor: ExecutorService = Executors.newSingleThreadExecutor(
        daemonFactory("ReiAnix-NativeCommand")
    )
    private val thumbnailExecutor: ExecutorService = Executors.newFixedThreadPool(
        2,
        daemonFactory("ReiAnix-ThumbnailCommand"),
    )

    private val requestState = NativeRequestState()

    data class Command(
        val action: String,
        val requestId: String,
        val createdAt: Long,
        val url: String,
    )

    private fun daemonFactory(name: String): ThreadFactory =
        ThreadFactory { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }

    @JvmStatic
    fun start(context: Context) {
        synchronized(lock) {
            if (started) return
            appContext = context.applicationContext
            queueDir = File(appContext.filesDir, "data/$QUEUE")
            check(queueDir.isDirectory || queueDir.mkdirs()) {
                "Could not create native command queue: ${queueDir.absolutePath}"
            }
            requestState.bind(appContext)

            observer = object : FileObserver(
                queueDir.absolutePath,
                FileObserver.CLOSE_WRITE or
                    FileObserver.MOVED_TO or
                    FileObserver.CREATE,
            ) {
                override fun onEvent(event: Int, path: String?) {
                    if (path.isNullOrBlank()) return
                    if (path.endsWith(TEMP_SUFFIX)) return
                    if (!path.endsWith(".json")) return
                    enqueue(path)
                }
            }
            observer?.startWatching()
            started = true
            commandExecutor.execute { drainPendingCommands() }
            Log.i(TAG, "COMMAND_DISPATCHER_STARTED queue=${queueDir.absolutePath}")
        }
    }

    private fun enqueue(fileName: String) {
        if (!started || fileName.isBlank()) return
        commandExecutor.execute { processFile(fileName) }
    }

    private fun drainPendingCommands() {
        queueDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.extension == "json" }
            ?.sortedBy { it.lastModified() }
            ?.forEach { processFile(it.name) }
    }

    private fun processFile(fileName: String) {
        val file = File(queueDir, fileName)
        if (!file.isFile) return
        try {
            val command = parse(file.readText(StandardCharsets.UTF_8))
            if (command == null) {
                Log.e(TAG, "COMMAND_REJECTED file=${file.name} reason=invalid_envelope")
                return
            }
            if (!NativeRequestState.isSupportedAction(command.action)) {
                publishFailure(
                    command,
                    "UNSUPPORTED_OPERATION",
                    "Operação nativa não suportada pelo canal interno.",
                )
                return
            }
            if (!requestState.acceptRequest(command.requestId, command.action, command.createdAt)) {
                val previousState = requestState.operationState(command.requestId)
                val terminal = previousState == NativeRequestState.OperationState.COMPLETED ||
                    previousState == NativeRequestState.OperationState.CANCELLED ||
                    previousState == NativeRequestState.OperationState.FAILED
                if (terminal) {
                    publishDiagnostic(
                        command,
                        "COMMAND_DUPLICATE",
                        result = "ignored_duplicate",
                    )
                    return
                }
                requestState.markOperationState(
                    command.requestId,
                    command.action,
                    NativeRequestState.OperationState.QUEUED,
                )
                publishDiagnostic(
                    command,
                    "COMMAND_RECOVERED",
                    state = NativeRequestState.OperationState.QUEUED.name,
                    result = previousState?.name ?: "unknown",
                )
            }

            publishDiagnostic(
                command,
                "COMMAND_RECEIVED",
                state = NativeRequestState.OperationState.RECEIVED.name,
            )
            when (command.action) {
                "play" -> dispatchPlay(command)
                "extract_thumbnail" -> dispatchThumbnail(command)
                "cancel_player_transition" -> dispatchCancelTransition(command)
                else -> {
                    publishFailure(
                        command,
                        "INVALID_INTERNAL_ACTION",
                        "Operação nativa interna não pertence ao canal do player.",
                    )
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "COMMAND_PROCESSING_FAILED file=${file.name}", error)
        } finally {
            file.delete()
        }
    }

    private fun dispatchPlay(command: Command) {
        val source = runCatching { Uri.parse(command.url) }.getOrNull()
        val request = source?.let { NativePlayerRequest.fromBridgeUri(it) }
        if (source == null || request == null || request.requestId.isBlank()) {
            publishFailure(command, "INVALID_PLAY_REQUEST", "O comando de reprodução não possui uma requisição válida.")
            return
        }

        val localUri = runCatching { Uri.parse(request.episodeUri) }.getOrNull()
        if (localUri == null || localUri.scheme?.lowercase() !in setOf("content", "file")) {
            publishFailure(command, "UNSUPPORTED_MEDIA_URI", "O player aceita somente mídias locais autorizadas.")
            return
        }

        try {
            val receivedAtMs = System.currentTimeMillis()
            val intent = request.toIntent(appContext, localUri).apply {
                putExtra("commandReceivedAtMs", receivedAtMs)
                putExtra("handoffDispatchedAtMs", System.currentTimeMillis())
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                )
            }
            appContext.startActivity(intent)
            requestState.markOperationState(
                command.requestId,
                command.action,
                NativeRequestState.OperationState.COMPLETED,
            )
            publishDurableDiagnostic(
                command,
                "PLAYER_HANDOFF_DISPATCHED",
                state = NativeRequestState.OperationState.COMPLETED.name,
                result = "direct_native_player",
            )
            Log.i(
                TAG,
                "PLAYER_HANDOFF_DISPATCHED requestId=${command.requestId} " +
                    "playerSessionId=${request.playerSessionId.ifBlank { "-" }} " +
                    "originRequestId=${request.originRequestId.ifBlank { "-" }}",
            )
        } catch (error: Exception) {
            requestState.markOperationState(
                command.requestId,
                command.action,
                NativeRequestState.OperationState.FAILED,
            )
            publishFailure(
                command,
                "START_ACTIVITY_EXCEPTION",
                "Não foi possível abrir o player nativo.",
                error.message,
            )
        }
    }

    private fun dispatchThumbnail(command: Command) {
        val source = runCatching { Uri.parse(command.url) }.getOrNull()
        if (source == null) {
            publishFailure(command, "INVALID_THUMBNAIL_REQUEST", "O comando de miniatura é inválido.")
            return
        }
        val raw = source.getQueryParameter("uri")?.trim().orEmpty()
        if (raw.isBlank()) {
            publishFailure(command, "MISSING_MEDIA_URI", "A miniatura não recebeu uma mídia local.")
            return
        }
        val localUri = runCatching {
            val parsed = Uri.parse(raw)
            if (parsed.scheme.isNullOrBlank() && raw.startsWith("/")) {
                Uri.fromFile(File(raw).canonicalFile)
            } else {
                parsed
            }
        }.getOrNull()
        if (localUri == null || !isAuthorizedLocalMedia(localUri)) {
            requestState.markOperationState(
                command.requestId,
                command.action,
                NativeRequestState.OperationState.FAILED,
            )
            NativeMailbox.writeBestEffort(
                appContext,
                JSONObject()
                    .put("type", "thumbnail_error")
                    .put("requestId", command.requestId)
                    .put(
                        "payload",
                        JSONObject()
                            .put("uri", raw)
                            .put("size", source.getQueryParameter("size")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L)
                            .put("modifiedAt", source.getQueryParameter("modified_at")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L)
                            .put("mediaIdentity", source.getQueryParameter("media_identity").orEmpty())
                            .put("status", "UNAUTHORIZED"),
                    ),
            )
            publishDiagnostic(
                command,
                "OPERATION_COMPLETED",
                state = NativeRequestState.OperationState.FAILED.name,
                result = "thumbnail_unauthorized",
            )
            return
        }

        requestState.markOperationState(
            command.requestId,
            command.action,
            NativeRequestState.OperationState.RUNNING,
        )
        publishDiagnostic(
            command,
            "OPERATION_STARTED",
            state = NativeRequestState.OperationState.RUNNING.name,
        )

        thumbnailExecutor.execute {
            try {
                val size = source.getQueryParameter("size")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                val modifiedAt = source.getQueryParameter("modified_at")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                val mediaIdentity = source.getQueryParameter("media_identity")?.trim().orEmpty()
                val result = VideoThumbnailExtractor.extract(
                    appContext,
                    localUri,
                    size,
                    modifiedAt,
                    mediaIdentity,
                )
                if (result == null) {
                    requestState.markOperationState(
                        command.requestId,
                        command.action,
                        NativeRequestState.OperationState.FAILED,
                    )
                    NativeMailbox.write(
                        appContext,
                        JSONObject()
                            .put("type", "thumbnail_error")
                            .put("requestId", command.requestId)
                            .put(
                                "payload",
                                JSONObject()
                                    .put("uri", raw)
                                    .put("size", size)
                                    .put("modifiedAt", modifiedAt)
                                    .put("mediaIdentity", mediaIdentity)
                                    .put("status", "EXTRACTION_FAILED"),
                            ),
                    )
                    return@execute
                }
                requestState.markOperationState(
                    command.requestId,
                    command.action,
                    NativeRequestState.OperationState.COMPLETED,
                )
                NativeMailbox.write(
                    appContext,
                    JSONObject()
                        .put("type", "thumbnail_ready")
                        .put("requestId", command.requestId)
                        .put(
                            "payload",
                            JSONObject()
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
                                .put("source", "media_metadata_retriever"),
                        ),
                )
            } catch (error: Exception) {
                requestState.markOperationState(
                    command.requestId,
                    command.action,
                    NativeRequestState.OperationState.FAILED,
                )
                NativeMailbox.writeBestEffort(
                    appContext,
                    JSONObject()
                        .put("type", "thumbnail_error")
                        .put("requestId", command.requestId)
                        .put(
                            "payload",
                            JSONObject()
                                .put("uri", raw)
                                .put("status", "EXTRACTION_FAILED")
                                .put("error", error.message ?: "MediaMetadataRetriever falhou."),
                        ),
                )
            }
        }
    }

    private fun isAuthorizedLocalMedia(uri: Uri): Boolean =
        (
            uri.scheme.equals("content", true) &&
                (
                    SafScanner.isAuthorizedDocument(appContext, uri) ||
                        MediaStoreScanner.isAuthorizedDocument(appContext, uri)
                    )
            ) ||
            (
                uri.scheme.equals("file", true) &&
                    BroadStorageScanner.isAuthorizedFile(appContext, uri)
                )

    private fun dispatchCancelTransition(command: Command) {
        val source = runCatching { Uri.parse(command.url) }.getOrNull()
        val originRequestId = source?.getQueryParameter("origin_request_id")?.trim().orEmpty()
        val originPlayerSessionId = source?.getQueryParameter("origin_player_session_id")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (originRequestId.isBlank()) {
            publishFailure(
                command,
                "MISSING_ORIGIN_REQUEST_ID",
                "A invalidação da transição não informou a requisição original.",
            )
            return
        }
        MainActivity.revokePlayerTransition(originRequestId, originPlayerSessionId)
        requestState.markOperationState(
            command.requestId,
            command.action,
            NativeRequestState.OperationState.COMPLETED,
        )
        publishDiagnostic(
            command,
            "PLAYER_TRANSITION_CANCELLED",
            state = NativeRequestState.OperationState.COMPLETED.name,
            result = originRequestId,
            playerSessionId = originPlayerSessionId,
        )
    }

    private fun publishDurableDiagnostic(
        command: Command,
        event: String,
        state: String? = null,
        result: String? = null,
        playerSessionId: String? = null,
    ) {
        val payload = JSONObject()
            .put("event", event)
            .put("requestId", command.requestId)
        if (!state.isNullOrBlank()) payload.put("state", state)
        if (!result.isNullOrBlank()) payload.put("result", result)
        if (!playerSessionId.isNullOrBlank()) payload.put("playerSessionId", playerSessionId)
        NativeMailbox.write(
            appContext,
            JSONObject()
                .put("type", "diagnostic")
                .put("requestId", command.requestId)
                .put("payload", payload),
        )
    }

    private fun publishDiagnostic(
        command: Command,
        event: String,
        state: String? = null,
        result: String? = null,
        playerSessionId: String? = null,
    ) {
        val payload = JSONObject()
            .put("event", event)
            .put("requestId", command.requestId)
        if (!state.isNullOrBlank()) payload.put("state", state)
        if (!result.isNullOrBlank()) payload.put("result", result)
        if (!playerSessionId.isNullOrBlank()) payload.put("playerSessionId", playerSessionId)
        NativeMailbox.writeBestEffort(
            appContext,
            JSONObject()
                .put("type", "diagnostic")
                .put("requestId", command.requestId)
                .put("payload", payload),
        )
    }

    private fun publishFailure(
        command: Command,
        errorCode: String,
        message: String,
        detail: String? = null,
    ) {
        requestState.markOperationState(
            command.requestId,
            command.action,
            NativeRequestState.OperationState.FAILED,
        )
        val payload = JSONObject()
            .put("stage", "native_command_dispatch")
            .put("reason", errorCode)
        if (!detail.isNullOrBlank()) payload.put("error", detail)
        NativeMailbox.write(
            appContext,
            JSONObject()
                .put("type", "diagnostic")
                .put("requestId", command.requestId)
                .put(
                    "payload",
                    JSONObject()
                        .put("event", if (command.action == "play") "PLAYER_HANDOFF_FAILED" else "COMMAND_FAILED")
                        .put("requestId", command.requestId)
                        .put("reason", errorCode)
                        .put("message", message)
                        .put("detail", detail ?: ""),
                ),
        )
        NativeMailbox.writeBestEffort(
            appContext,
            JSONObject()
                .put("type", "native_error")
                .put("requestId", command.requestId)
                .put("message", message)
                .put("payload", payload),
        )
        Log.e(TAG, "COMMAND_FAILED requestId=${command.requestId} action=${command.action} reason=$errorCode")
    }

    internal fun parse(serialized: String): Command? = runCatching {
        val json = JSONObject(serialized)
        val action = json.optString("action").trim()
        val requestId = json.optString("requestId").trim()
        val createdAt = json.optLong("createdAt", 0L)
        val url = json.optString("url").trim()
        if (action.isBlank() || requestId.isBlank() || createdAt <= 0L || url.isBlank()) {
            null
        } else {
            Command(action, requestId, createdAt, url)
        }
    }.getOrNull()
}
