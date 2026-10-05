package com.reiflix.reiflix_local.scanner

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide native scan coordination.
 *
 * Scan ownership must survive MainActivity recreation: the Activity is a
 * lifecycle object, while an Android scan may legitimately outlive one
 * Activity instance. A source key prevents a second Activity instance from
 * starting a duplicate scan for the same source.
 */
object NativeScanController {
    private data class Token(
        val cancelled: AtomicBoolean,
        val sourceKey: String?,
    )

    private val tokens = ConcurrentHashMap<String, Token>()
    private val sourceOwners = ConcurrentHashMap<String, String>()

    /**
     * Scanner work belongs to the process, not an Activity instance. Jobs are
     * therefore launched from this process-owned scope while cancellation remains
     * cooperative through each scan token, preserving final CANCELLED publication.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    @Synchronized
    fun begin(scanId: String, sourceKey: String? = null): Boolean {
        if (tokens.containsKey(scanId)) return false
        if (sourceKey != null && sourceOwners.containsKey(sourceKey)) return false
        tokens[scanId] = Token(AtomicBoolean(false), sourceKey)
        if (sourceKey != null) sourceOwners[sourceKey] = scanId
        return true
    }

    fun isCancelled(scanId: String): Boolean =
        tokens[scanId]?.cancelled?.get() == true

    @Synchronized
    fun launch(scanId: String, block: suspend CoroutineScope.() -> Unit): Boolean {
        if (!tokens.containsKey(scanId) || jobs.containsKey(scanId)) return false
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        jobs[scanId] = job
        job.start()
        return true
    }


    fun isRunning(sourceKey: String): Boolean =
        sourceOwners.containsKey(sourceKey)

    fun cancelAll(): List<String> {
        val ids = tokens.keys.toList()
        ids.forEach { tokens[it]?.cancelled?.set(true) }
        return ids
    }

    @Synchronized
    fun finish(scanId: String) {
        val token = tokens.remove(scanId) ?: return
        jobs.remove(scanId)
        token.sourceKey?.let { key ->
            sourceOwners.remove(key, scanId)
        }
    }
}
