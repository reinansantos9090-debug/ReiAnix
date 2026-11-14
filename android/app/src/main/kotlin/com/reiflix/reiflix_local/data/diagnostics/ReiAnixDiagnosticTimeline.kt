package com.reiflix.reiflix_local.data.diagnostics

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

/**
 * Bounded, process-local diagnostic timeline for Android-owned operations.
 *
 * This is observational only: it does not persist to SQLite or own domain state.
 * Correlation ids are retained, while arbitrary paths, URLs, account values and
 * file contents are excluded from free-form context and error messages.
 */
data class ReiAnixDiagnosticEvent(
    val id: String,
    val sequence: Long,
    val name: String,
    val timestampMs: Long,
    val elapsedRealtimeNs: Long,
    val severity: String,
    val source: String?,
    val result: String?,
    val requestId: String?,
    val scanId: String?,
    val refreshId: String?,
    val sessionId: String?,
    val error: String?,
    val context: Map<String, String>,
)

object ReiAnixDiagnosticTimeline {
    const val RETENTION_LIMIT = 300
    private const val TAG = "ReiAnixDiagnostics"
    private val lock = Any()
    private val recent = ArrayDeque<ReiAnixDiagnosticEvent>()
    private var nextSequence = 0L
    private val mutableEvents = MutableStateFlow<List<ReiAnixDiagnosticEvent>>(emptyList())

    /** Newest first; the list itself is immutable to consumers. */
    val events: StateFlow<List<ReiAnixDiagnosticEvent>> = mutableEvents.asStateFlow()

    fun record(
        name: String,
        severity: String = "INFO",
        source: String? = null,
        result: String? = null,
        requestId: String? = null,
        scanId: String? = null,
        refreshId: String? = null,
        sessionId: String? = null,
        error: Throwable? = null,
        context: Map<String, Any?> = emptyMap(),
    ) {
        try {
            synchronized(lock) {
                nextSequence += 1L
                val event = ReiAnixDiagnosticEvent(
                    id = UUID.randomUUID().toString(),
                    sequence = nextSequence,
                    name = safeLabel(name, "UNKNOWN_EVENT") ?: "UNKNOWN_EVENT",
                    timestampMs = System.currentTimeMillis(),
                    elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                    severity = normalizeSeverity(severity),
                    source = safeLabel(source, null),
                    result = safeLabel(result, null),
                    requestId = safeIdentifier(requestId),
                    scanId = safeIdentifier(scanId),
                    refreshId = safeIdentifier(refreshId),
                    sessionId = safeIdentifier(sessionId),
                    error = safeError(error),
                    context = normalizeContext(context),
                )
                recent.addLast(event)
                while (recent.size > RETENTION_LIMIT) recent.removeFirst()
                mutableEvents.value = recent.toList().asReversed()
            }
        } catch (failure: Exception) {
            // A diagnostics failure must never escape into the observed operation.
            Log.w(TAG, "Diagnostic event was dropped (" + failure.javaClass.simpleName + ").")
        }
    }

    /** Clear only after an explicit user action; no synthetic event is emitted. */
    fun clear() {
        try {
            synchronized(lock) {
                recent.clear()
                mutableEvents.value = emptyList()
            }
        } catch (failure: Exception) {
            Log.w(TAG, "Could not clear diagnostic timeline (" + failure.javaClass.simpleName + ").")
        }
    }

    /** A bounded snapshot suitable for the native report; no SQLite writes occur. */
    fun snapshotJson(limit: Int = 100): JSONArray {
        val snapshot = synchronized(lock) {
            recent.toList().asReversed().take(limit.coerceIn(0, RETENTION_LIMIT))
        }
        return JSONArray().apply {
            snapshot.forEach { event ->
                val contextJson = JSONObject()
                event.context.forEach { (key, value) -> contextJson.put(key, value) }
                put(
                    JSONObject()
                        .put("id", event.id)
                        .put("sequence", event.sequence)
                        .put("name", event.name)
                        .put("timestamp_ms", event.timestampMs)
                        .put("elapsed_realtime_ns", event.elapsedRealtimeNs)
                        .put("severity", event.severity)
                        .put("source", event.source ?: JSONObject.NULL)
                        .put("result", event.result ?: JSONObject.NULL)
                        .put("request_id", event.requestId ?: JSONObject.NULL)
                        .put("scan_id", event.scanId ?: JSONObject.NULL)
                        .put("refresh_id", event.refreshId ?: JSONObject.NULL)
                        .put("session_id", event.sessionId ?: JSONObject.NULL)
                        .put("error", event.error ?: JSONObject.NULL)
                        .put("context", contextJson),
                )
            }
        }
    }

    private fun normalizeSeverity(raw: String): String {
        val value = raw.trim().uppercase(Locale.ROOT)
        return if (value in setOf("DEBUG", "INFO", "WARN", "ERROR")) value else "INFO"
    }

    private fun safeLabel(raw: String?, fallback: String?): String? {
        val value = raw?.trim()?.take(100)?.takeIf { it.isNotEmpty() } ?: return fallback
        return if (containsSensitiveValue(value)) "<redacted>" else value
    }

    private fun safeIdentifier(raw: String?): String? {
        val value = raw?.trim()?.take(120)?.takeIf { it.isNotEmpty() } ?: return null
        return if (containsSensitiveValue(value)) "<redacted-ref>" else value
    }

    private fun safeError(error: Throwable?): String? {
        if (error == null) return null
        val type = error.javaClass.simpleName.take(80).ifBlank { "Exception" }
        val message = error.message?.trim()?.takeIf { it.isNotEmpty() } ?: return type
        val safeMessage = if (containsSensitiveValue(message)) {
            "<redacted>"
        } else {
            message.take(180)
        }
        return type + ": " + safeMessage
    }

    private fun normalizeContext(raw: Map<String, Any?>): Map<String, String> {
        val output = linkedMapOf<String, String>()
        raw.entries.take(24).forEach { (rawKey, rawValue) ->
            val key = rawKey.trim().take(48)
            val normalizedKey = key.lowercase(Locale.ROOT)
            if (key.isBlank() || SENSITIVE_KEY.containsMatchIn(normalizedKey)) return@forEach
            val value = when (rawValue) {
                null -> null
                is Boolean, is Number -> rawValue.toString()
                is String -> rawValue.trim().take(160)
                else -> null
            } ?: return@forEach
            if (value.isNotEmpty()) {
                output[key] = if (containsSensitiveValue(value)) "<redacted>" else value
            }
        }
        return output
    }

    private fun containsSensitiveValue(value: String): Boolean =
        value.contains("://") ||
            value.contains('/') ||
            value.contains('\\') ||
            value.contains('@') ||
            SENSITIVE_VALUE.containsMatchIn(value)

    private val SENSITIVE_KEY = Regex(
        "(path|uri|url|token|password|cookie|authorization|secret|title|email|account|credential|content|directory|filename)",
        RegexOption.IGNORE_CASE,
    )
    private val SENSITIVE_VALUE = Regex(
        "(?i)(access[_-]?token|refresh[_-]?token|password|authorization|cookie)\\s*[:=]",
    )
}
