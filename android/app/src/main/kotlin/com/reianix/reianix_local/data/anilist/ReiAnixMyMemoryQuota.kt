package com.reianix.reianix_local.data.anilist

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Anonymous MyMemory allowance: 5,000 characters/day. Count UTF-8 bytes instead
 * of characters conservatively, in a rolling 24-hour window per installation.
 * This cannot account for other clients sharing an IP; server limits still win.
 * The ledger is separate from translated text and written before every request.
 */
internal class ReiAnixMyMemoryQuota(
    private val file: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun reserve(bytes: Int): Boolean = synchronized(LOCK) {
        if (bytes !in 1..MAX_BYTES_PER_DAY) return@synchronized false
        runCatching {
            val now = nowMillis()
            val data = read()
            if (now < data.optLong("pausedUntil", 0L)) return@runCatching false
            val entries = data.getJSONArray("entries")
            val retained = JSONArray()
            var total = 0L
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val at = entry.getLong("at")
                val count = entry.getInt("bytes")
                require(at >= 0 && count in 1..MAX_BYTES_PER_DAY)
                // Preserve future entries after a backwards clock adjustment.
                if (at > now - WINDOW_MS) {
                    retained.put(entry)
                    total += count
                }
            }
            if (total + bytes > MAX_BYTES_PER_DAY) return@runCatching false
            retained.put(JSONObject().put("at", now).put("bytes", bytes))
            data.put("entries", retained)
            write(data)
            true
        }.getOrDefault(false)
    }

    fun pause(retryAfterSeconds: Long?) = synchronized(LOCK) {
        runCatching {
            val now = nowMillis()
            val wait = retryAfterSeconds?.takeIf { it > 0L }
                ?.coerceAtMost(7 * 24 * 60 * 60L)?.times(1000L) ?: WINDOW_MS
            val data = read()
            data.put("pausedUntil", maxOf(data.optLong("pausedUntil", 0L), now + wait))
            write(data)
        }
        Unit
    }

    private fun read(): JSONObject {
        val atomic = AtomicFile(file)
        return if (file.exists() || File(file.path + ".bak").exists()) {
            JSONObject(atomic.readFully().toString(Charsets.UTF_8))
        } else JSONObject().put("entries", JSONArray())
    }

    private fun write(data: JSONObject) {
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(data.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    companion object {
        const val MAX_BYTES_PER_DAY = 5_000
        const val WINDOW_MS = 24 * 60 * 60 * 1000L
        private val LOCK = Any()
    }
}
