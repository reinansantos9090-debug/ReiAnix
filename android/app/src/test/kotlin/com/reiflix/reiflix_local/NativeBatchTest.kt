package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.storage.NativeBatch
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test fun normalizationRejectsUnboundedBatchRequests() {
        assertEquals(250, NativeBatch.normalizeSize(250))
        assertEquals(1000, NativeBatch.normalizeSize(Int.MAX_VALUE))
        assertEquals(25, NativeBatch.normalizeSize(Int.MIN_VALUE))
    }
}
