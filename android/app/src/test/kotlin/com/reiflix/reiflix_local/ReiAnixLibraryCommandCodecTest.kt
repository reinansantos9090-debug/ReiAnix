package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.data.library.ReiAnixLibraryCommandCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReiAnixLibraryCommandCodecTest {

    @Test
    fun catalogPublicationContainsOnlyStableIdentifiers() {
        val command = ReiAnixLibraryCommandCodec.create(
            requestId = "req-1",
            action = ReiAnixLibraryCommandCodec.Action.PUBLISH_CATALOG,
        )

        assertEquals("compose_library_command", command.getString("type"))
        assertEquals("req-1", command.getString("requestId"))
        val payload = command.getJSONObject("payload")
        assertEquals("publish_catalog", payload.getString("action"))
        assertFalse(payload.has("episodeId"))
        assertFalse(payload.has("watched"))
        assertFalse(payload.has("file"))
        assertFalse(payload.has("episodes"))
    }

    @Test
    fun actionsAreExactlyTheExistingLibraryOperations() {
        assertEquals(
            setOf("refresh", "publish_catalog"),
            ReiAnixLibraryCommandCodec.Action.entries.map { it.value }.toSet(),
        )
    }
    

    @Test
    fun blankRequestIsRejected() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ReiAnixLibraryCommandCodec.create(
                "",
                ReiAnixLibraryCommandCodec.Action.REFRESH,
            )
        }
    }
}
