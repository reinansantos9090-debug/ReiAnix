package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.storage.NativeIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeIndexInstrumentedTest {
    @Before fun clean() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(File(context.filesDir, "data"), "reiflix-native-index.json").delete()
    }
    @Test fun partialScanKeepsCommittedSnapshot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = "instrumented"
        NativeIndex.prepare(context, NativeIndex.SOURCE_BROAD, scope, JSONArray().put(JSONObject().put("uri","file:///one").put("name","one.mkv").put("relativePath","one.mkv")), true)
        NativeIndex.prepare(context, NativeIndex.SOURCE_BROAD, scope, JSONArray(), false)
        var total = 0
        NativeIndex.forEachCachedBatch(context, scope, 250) { batch, _ -> total += batch.length() }
        assertEquals(1, total)
    }
}
