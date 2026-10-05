package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.data.library.ReiAnixLibraryCommandCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixLibraryCommandCodecTest {

    @Test
    fun commandContainsOnlySmallStableIdentifiers() {
        val command = ReiAnixLibraryCommandCodec.create(
            requestId = "req-1",
            action = ReiAnixLibraryCommandCodec.Action.SET_WATCHED,
            episodeId = 71L,
            watched = true,
        )

        assertEquals("compose_library_command", command.getString("type"))
        assertEquals("req-1", command.getString("requestId"))
        val payload = command.getJSONObject("payload")
        assertEquals("set_watched", payload.getString("action"))
        assertEquals(71L, payload.getLong("episodeId"))
        assertTrue(payload.getBoolean("watched"))
        assertFalse(payload.has("file"))
        assertFalse(payload.has("episodes"))
    }

    @Test
    fun actionsAreExactlyTheExistingLibraryOperations() {
        assertEquals(
            setOf("toggle_favorite", "set_watched", "refresh", "open_media", "select_saf", "remove_saf"),
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
