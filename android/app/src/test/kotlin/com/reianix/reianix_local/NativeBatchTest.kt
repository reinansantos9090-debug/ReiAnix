package com.reianix.reianix_local
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import com.reianix.reianix_local.storage.NativeBatch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class NativeBatchTest {
    @Test fun defaultSizeIs250AndAccumulatorStaysBounded() {
        val sizes = mutableListOf<Int>()
        val accumulator = NativeBatch.Accumulator(NativeBatch.DEFAULT_SIZE) { batch, _, _ ->
            sizes += batch.length()
        }
        repeat(1000) { accumulator.add(JSONObject().put("id", it)) }
        accumulator.flush()
        assertTrue(sizes.isNotEmpty())
        assertEquals(250, sizes.maxOrNull())
        assertEquals(4, sizes.size)
    }

    @Test fun ndjsonReaderStopsOnCancellationAndFlushesThePartialBatch() {
        val file = File.createTempFile("native-batch-cancel", ".ndjson")
        try {
            file.writeText((0 until 10).joinToString("\n") {
                JSONObject().put("id", it).toString()
            })
            var cancellationChecks = 0
            val sizes = mutableListOf<Int>()
            val processed = NativeBatch.readNdjsonBatches(
                file,
                batchSize = 4,
                shouldCancel = { cancellationChecks++ >= 5 },
            ) { batch ->
                sizes += batch.length()
            }

            assertEquals(5, processed)
            assertEquals(listOf(4, 1), sizes)
        } finally {
            file.delete()
        }
    }

    @Test fun normalizationRejectsUnboundedBatchRequests() {
        assertEquals(250, NativeBatch.normalizeSize(250))
        assertEquals(1000, NativeBatch.normalizeSize(Int.MAX_VALUE))
        assertEquals(25, NativeBatch.normalizeSize(Int.MIN_VALUE))
    }
}
