package com.reiflix.reiflix_local.bridge
import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Crash-safe, atomic queue between the native Android host and embedded Python. */
object NativeMailbox {
    private const val TAG = "[REIFLIX][ANDROID]"
    private const val QUEUE = "reiflix-native-events"
    private const val PREFIX = "event-"
    private const val EVENT_VERSION = 2
    private const val BEST_EFFORT_QUEUE_CAPACITY = 128
    private val bestEffortExecutor: ExecutorService = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(BEST_EFFORT_QUEUE_CAPACITY),
        { runnable ->
            Thread(runnable, "ReiFlix-MailboxTelemetry").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private fun operationState(event: JSONObject): String {
        val type = event.optString("type")
        val nested = event.optJSONObject("payload")
        if (type == "diagnostic") {
            return nested?.optString("state").orEmpty().uppercase()
        }
        return when (type) {
            "saf_permission_request", "mediastore_permission_request", "broad_storage_permission_request" -> "REQUESTED"
            "saf_permission", "saf_released", "mediastore_permission", "broad_storage_permission", "google_account", "google_signed_out" -> "COMPLETED"
            "saf_cancelled", "google_cancelled" -> "CANCELLED"
            "saf_error", "mediastore_error", "broad_storage_error", "google_error", "native_error" -> "FAILED"
            "saf_scan_progress", "mediastore_scan_progress", "broad_storage_scan_progress" -> "RUNNING"
            "saf_scan", "mediastore_scan", "broad_storage_scan" -> {
                when ((nested?.optString("status") ?: nested?.optString("generationStatus")).orEmpty().uppercase()) {
                    "CANCELLED" -> "CANCELLED"
                    "FAILED", "UNAVAILABLE", "REVOKED" -> "FAILED"
                    else -> "COMPLETED"
                }
            }
            else -> ""
        }
    }

    private fun eventType(event: JSONObject): String {
        val type = event.optString("type")
        val payload = event.optJSONObject("payload")
        return when {
            type in setOf("mediastore_permission_request","broad_storage_permission_request","saf_permission_request") ->
                "permission_requested"
            type == "saf_cancelled" -> "permission_cancelled"
            type == "saf_permission" ->
                if (payload?.optBoolean("granted", false) == true) "saf_granted" else "saf_revoked"
            type == "broad_storage_permission" ->
                if (payload?.optBoolean("granted", false) == true) "broad_granted" else "broad_denied"
            type == "mediastore_permission" || type == "broad_storage_status" -> "permission_changed"
            type in setOf("saf_scan_progress","mediastore_scan_progress","broad_storage_scan_progress") &&
                payload?.optString("phase") == "started" -> "scan_started"
            type == "saf_scan" -> when (payload?.optString("status")) {
                "REVOKED" -> "saf_revoked"
                "UNAVAILABLE" -> "saf_unavailable"
                "CANCELLED" -> "scan_cancelled"
                "PARTIAL" -> "scan_partial"
                "FAILED" -> "scan_failed"
                else -> "scan_completed"
            }
            type in setOf("mediastore_scan","broad_storage_scan") -> when (payload?.optString("status") ?: payload?.optString("generationStatus")) {
                "cancelled","CANCELLED" -> "scan_cancelled"
                "partial","PARTIAL" -> "scan_partial"
                "failed","FAILED" -> "scan_failed"
                else -> "scan_completed"
            }
            type == "scan_cancelled" -> "scan_cancelled"
            type in setOf("saf_error","mediastore_error","broad_storage_error") ->
                when (payload?.optString("status")) {
                    "REVOKED" -> "saf_revoked"
                    "UNAVAILABLE" -> "saf_unavailable"
                    "PARTIAL" -> "scan_partial"
                    "CANCELLED" -> "scan_cancelled"
                    else -> if (payload?.has("scanId") == true) "scan_failed" else "permission_failed"
                }
            else -> type.ifBlank { "unknown" }
        }
    }

    /**
     * Each mailbox event gets its own temporary/target filename and is promoted
     * atomically. No shared mutable envelope state exists, so global Java-level
     * synchronization is unnecessary and would couple progress I/O to control I/O.
     */
    fun writeOrThrow(context: Context, event: JSONObject) {
        check(write(context, event)) { "Could not publish native event to NativeMailbox" }
    }

    fun write(context: Context, event: JSONObject): Boolean =
        writeInternal(context, event, durable = true)

    /**
     * Player telemetry uses atomic mailbox publication without forcing a
     * durable storage flush on the UI thread. Control-plane events keep the
     * existing fsync-backed write() path.
     */
    /**
     * Best-effort telemetry is queued on a dedicated daemon thread so small
     * player diagnostics cannot perform filesystem I/O on Android's UI thread.
     * The event still uses the same temporary-file + atomic-promotion protocol.
     */
    fun writeBestEffort(context: Context, event: JSONObject): Boolean {
        val snapshot = JSONObject(event.toString())
        return try {
            bestEffortExecutor.execute {
                writeInternal(context, snapshot, durable = false)
            }
            true
        } catch (exception: RejectedExecutionException) {
            Log.w(TAG, "Native mailbox best-effort queue is full; event was discarded", exception)
            false
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to queue best-effort native event", exception)
            false
        }
    }

    private fun writeInternal(context: Context, event: JSONObject, durable: Boolean): Boolean {
        var temporary: File?=null
        try{
            val dataDirectory = File(context.filesDir, "data")
            check(dataDirectory.isDirectory||dataDirectory.mkdirs()){"Could not create Flet application data directory"}
            val queue = File(dataDirectory, QUEUE)
            check(queue.isDirectory||queue.mkdirs()){"Could not create native event queue directory"}
            val id=UUID.randomUUID().toString()
            val target = File(queue, "$PREFIX$id.json")
            val temp = File(queue, "$PREFIX$id.json.tmp")
            temporary = temp
            val writeStartedNs = SystemClock.elapsedRealtimeNanos()
            val now=System.currentTimeMillis()
            val capturedAt = event.optLong("createdAt", 0L).takeIf { it > 0L } ?: now
            val payload=JSONObject(event.toString())
                .put("eventId",id)
                .put("eventVersion",EVENT_VERSION)
                .put("eventType", eventType(event))
                .put("createdAt",capturedAt)
                .put("timestamp",capturedAt)
                .put("mailboxWriteStartedElapsedNs", writeStartedNs)
            val nested = payload.optJSONObject("payload")
            fun promote(name: String, vararg aliases: String) {
                if (payload.has(name) || nested == null) return
                for (alias in aliases) {
                    if (nested.has(alias)) {
                        payload.put(name, nested.get(alias))
                        return
                    }
                }
            }
            val requestId=payload.optString("requestId").ifBlank{
                nested?.optString("requestId").orEmpty()
            }.trim()
            if(requestId.isNotEmpty())payload.put("requestId",requestId)
            promote("scanId", "scanId")
            promote("source", "source")
            promote("scope", "scope", "scopeRef", "scopeKind")
            promote("volumeId", "volumeId", "volumeName")
            promote("state", "state", "status", "generationStatus")
            promote("counts", "counts", "stats")
            promote("errors", "errors")
            promote("generationId", "generationId")
            promote("batchId", "batchId")
            promote("batchNumber", "batchNumber")
            promote("batchSize", "batchSize")
            promote("processed", "processed")
            promote("discovered", "discovered")
            promote("inserted", "inserted", "new")
            promote("updated", "updated", "changed")
            promote("unchanged", "unchanged")
            promote("duplicates", "duplicates")
            promote("removed", "removed")
            promote("elapsedMs", "elapsedMs", "elapsed_ms")
            val operationState = operationState(payload)
            if (operationState.isNotBlank()) payload.put("operationState", operationState)
            FileOutputStream(temp).use { stream ->
                stream.write(payload.toString().toByteArray(Charsets.UTF_8))
                if (durable) {
                    stream.fd.sync()
                }
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            val committedNs = SystemClock.elapsedRealtimeNanos()
            Log.i(TAG,"EVENT_WRITTEN eventId=$id type=${event.optString("type")} eventType=${payload.optString("eventType")} operationState=${operationState.ifEmpty{"-"}} requestId=${requestId.ifEmpty{"-"}} createdAt=$now durable=$durable payloadBytes=${payload.toString().toByteArray(Charsets.UTF_8).size} writeDurationMs=${(committedNs - writeStartedNs) / 1_000_000.0}")
            if (event.optString("type") in setOf("player_next_request", "player_previous_request")) {
                Log.i(TAG,"MAILBOX_WRITE requestId=${requestId.ifEmpty{"-"}} writeDurationMs=${(committedNs - writeStartedNs) / 1_000_000.0} payloadBytes=${payload.toString().toByteArray(Charsets.UTF_8).size}")
            }
        }catch(exception:Exception){
            temporary?.delete()
            Log.e(TAG,"Unable to queue native event",exception)
            return false
        }
        return true
    }
}
