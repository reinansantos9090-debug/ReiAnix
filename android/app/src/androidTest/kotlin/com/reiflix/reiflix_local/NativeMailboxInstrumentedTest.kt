package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.bridge.NativeMailbox
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeMailboxInstrumentedTest {
    private lateinit var queue: File

    @Before
    fun cleanMailboxQueue() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        queue = File(File(context.filesDir, "data"), "reiflix-native-events")
        queue.deleteRecursively()
    }

    @Test
    fun mailbox_write_is_atomic_envelope_and_leaves_no_temp_file() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NativeMailbox.write(context, JSONObject().put("type", "mailbox_test"))

        val events = queue.listFiles { _, name -> name.startsWith("event-") && name.endsWith(".json") }
            ?: emptyArray()
        assertEquals(1, events.size)

        val payload = JSONObject(events.single().readText())
        assertEquals("mailbox_test", payload.getString("type"))
        assertFalse(payload.getString("eventId").isBlank())
        assertEquals("mailbox_test", payload.getString("eventType"))
        assertTrue(payload.getLong("createdAt") > 0L)
        assertTrue(payload.getLong("timestamp") >= payload.getLong("createdAt"))
        assertTrue(queue.listFiles { _, name -> name.endsWith(".tmp") }?.isEmpty() ?: true)
    }
    @Test
    fun mailbox_best_effort_writes_atomic_envelope() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(NativeMailbox.writeBestEffort(context, JSONObject().put("type", "player_progress")))

        val deadline = android.os.SystemClock.uptimeMillis() + 2_000L
        var events = emptyArray<File>()
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            events = queue.listFiles { _, name -> name.startsWith("event-") && name.endsWith(".json") }
                ?: emptyArray()
            if (events.size == 1) break
            android.os.SystemClock.sleep(20L)
        }
        assertEquals(1, events.size)

        val payload = JSONObject(events.single().readText())
        assertEquals("player_progress", payload.getString("eventType"))
        assertFalse(payload.getString("eventId").isBlank())
        assertTrue(payload.getLong("createdAt") > 0L)
        assertTrue(queue.listFiles { _, name -> name.endsWith(".tmp") }?.isEmpty() ?: true)
    }

    @Test
    fun mailbox_promotes_request_id_and_operation_state_without_partial_writes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NativeMailbox.write(
            context,
            JSONObject()
                .put("type", "saf_permission")
                .put("payload", JSONObject()
                    .put("requestId", "request-A")
                    .put("granted", true)),
        )

        val events = queue.listFiles { _, name -> name.startsWith("event-") && name.endsWith(".json") }
            ?: emptyArray()
        assertEquals(1, events.size)
        val payload = JSONObject(events.single().readText())

        assertEquals("request-A", payload.getString("requestId"))
        assertEquals("COMPLETED", payload.getString("operationState"))
        assertTrue(queue.listFiles { _, name -> name.endsWith(".tmp") }?.isEmpty() ?: true)
    }

}
