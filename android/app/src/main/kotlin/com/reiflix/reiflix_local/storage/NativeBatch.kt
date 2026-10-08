package com.reiflix.reiflix_local.storage
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Shared bounded batching for native scanners.
 *
 * The batch is deliberately small and short-lived. No scanner is allowed to
 * retain the complete library in one JSONArray.
 */
object NativeBatch {
    const val DEFAULT_SIZE = 250
    private const val MIN_SIZE = 25
    private const val MAX_SIZE = 1000

    fun normalizeSize(value: Int = DEFAULT_SIZE): Int = value.coerceIn(MIN_SIZE, MAX_SIZE)

    class Accumulator(
        batchSize: Int = DEFAULT_SIZE,
        private val onFlush: (JSONArray, String, Int) -> Unit,
    ) {
        private val maxSize = normalizeSize(batchSize)
        private var batch = JSONArray()
        private var sequence = 0

        fun add(document: JSONObject) {
            batch.put(document)
            if (batch.length() >= maxSize) flush()
        }

        fun flush() {
            if (batch.length() == 0) return
            sequence += 1
            val current = batch
            batch = JSONArray()
            onFlush(current, UUID.randomUUID().toString(), sequence)
        }

        fun size(): Int = batch.length()
        fun sequence(): Int = sequence
    }

    /**
     * Stream a committed NDJSON snapshot in bounded batches. The callback never
     * receives more than [batchSize] documents.
     */
    fun readNdjsonBatches(file: File, batchSize: Int = DEFAULT_SIZE, onBatch: (JSONArray) -> Unit): Int {
        if (!file.isFile) return 0
        val limit = normalizeSize(batchSize)
        var total = 0
        var batch = JSONArray()
        BufferedReader(InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                runCatching { JSONObject(line) }.getOrNull()?.let { document ->
                    batch.put(document)
                    total += 1
                    if (batch.length() >= limit) {
                        onBatch(batch)
                        batch = JSONArray()
                    }
                }
            }
        }
        if (batch.length() > 0) onBatch(batch)
        return total
    }

    fun appendNdjson(file: File, documents: JSONArray) {
        file.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        BufferedWriter(
            OutputStreamWriter(
                FileOutputStream(file, true),
                StandardCharsets.UTF_8,
            )
        ).use { writer ->
            for (i in 0 until documents.length()) {
                val document = documents.optJSONObject(i) ?: continue
                writer.write(document.toString())
                writer.newLine()
            }
            writer.flush()
        }
    }
}
