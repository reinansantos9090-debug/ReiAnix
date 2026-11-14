package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.storage.NativeBatch
import com.reiflix.reiflix_local.storage.NativeIndex
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceFlowInstrumentedTest {
    private lateinit var context: android.content.Context

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        File(File(context.filesDir, "data"), "reiflix-native-index.json").delete()
    }

    @Test fun runtimeApiAndNativeBatchContract() {
        assertTrue(Build.VERSION.SDK_INT >= 30)
        assertTrue(Build.VERSION.SDK_INT <= 36)
        val sizes = mutableListOf<Int>()
        val batch = NativeBatch.Accumulator(250) { value, _, _ -> sizes += value.length() }
        repeat(1001) { batch.add(JSONObject().put("uri", "content://deviceflow/$it")) }
        batch.flush()
        assertEquals(5, sizes.size)
        assertTrue(sizes.all { it <= 250 })
    }

    private fun cachedCount(context: android.content.Context, scope: String): Int {
        var count = 0
        NativeIndex.forEachCachedBatch(context, scope, 250) { batch, _ -> count += batch.length() }
        return count
    }

    private fun cachedFirstUri(context: android.content.Context, scope: String): String {
        var value = ""
        NativeIndex.forEachCachedBatch(context, scope, 250) { batch, _ ->
            if (value.isEmpty() && batch.length() > 0) value = batch.getJSONObject(0).getString("uri")
        }
        return value
    }

    @Test fun cancelledGenerationPreservesCommittedSnapshot() {
        val scope = "deviceflow:instrumented"
        val first = JSONArray().put(JSONObject().put("uri", "content://deviceflow/committed").put("name", "committed.mp4"))
        val generation1 = NativeIndex.startGeneration(context, NativeIndex.SOURCE_SAF, scope, JSONObject().put("scanId", "g1"))
        NativeIndex.prepareBatch(
            context, NativeIndex.SOURCE_SAF, scope,
            first, generation1, "b1", 1,
            JSONObject().put("scanId", "g1"),
        )
        NativeIndex.finishGeneration(
            context, NativeIndex.SOURCE_SAF, scope, generation1,
            NativeIndex.STATUS_COMPLETED, JSONObject().put("scanId", "g1"),
        )
        assertEquals(1, cachedCount(context, scope))
        assertEquals("content://deviceflow/committed", cachedFirstUri(context, scope))
        val generation2 = NativeIndex.startGeneration(context, NativeIndex.SOURCE_SAF, scope, JSONObject().put("scanId", "g2"))
        NativeIndex.prepareBatch(
            context, NativeIndex.SOURCE_SAF, scope,
            JSONArray().put(JSONObject().put("uri", "content://deviceflow/new").put("name", "new.mp4")),
            generation2, "b1", 1,
            JSONObject().put("scanId", "g2"),
        )
        NativeIndex.finishGeneration(
            context, NativeIndex.SOURCE_SAF, scope, generation2,
            NativeIndex.STATUS_CANCELLED, JSONObject().put("scanId", "g2"),
        )
        assertEquals(1, cachedCount(context, scope))
        assertEquals("content://deviceflow/committed", cachedFirstUri(context, scope))
    }
}
