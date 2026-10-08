package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.bridge.NativeCommandDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCommandDispatcherTest {
    @Test
    fun parseAcceptsCompleteAtomicCommandEnvelope() {
        val command = NativeCommandDispatcher.parse(
            """{"version":1,"requestId":"abc","action":"play","createdAt":123456789,"url":"reiflix://native?action=play&request_id=abc"}"""
        )
        assertEquals("abc", command?.requestId)
        assertEquals("play", command?.action)
        assertEquals(123456789L, command?.createdAt)
        assertTrue(command?.url?.startsWith("reiflix://native") == true)
    }

    @Test
    fun parseRejectsIncompleteCommandEnvelope() {
        assertNull(
            NativeCommandDispatcher.parse(
                """{"requestId":"abc","action":"play","createdAt":0,"url":"reiflix://native"}"""
            )
        )
    }
}
