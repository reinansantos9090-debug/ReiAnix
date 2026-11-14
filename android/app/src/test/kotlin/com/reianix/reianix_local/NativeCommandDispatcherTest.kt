package com.reianix.reianix_local
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import com.reianix.reianix_local.bridge.NativeCommandDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class NativeCommandDispatcherTest {
    @Test
    fun parseAcceptsCompleteAtomicCommandEnvelope() {
        val command = NativeCommandDispatcher.parse(
            """{"version":1,"requestId":"abc","action":"play","createdAt":123456789,"url":"reianix://native?action=play&request_id=abc"}"""
        )
        assertEquals("abc", command?.requestId)
        assertEquals("play", command?.action)
        assertEquals(123456789L, command?.createdAt)
        assertTrue(command?.url?.startsWith("reianix://native") == true)
    }

    @Test
    fun parseRejectsIncompleteCommandEnvelope() {
        assertNull(
            NativeCommandDispatcher.parse(
                """{"requestId":"abc","action":"play","createdAt":0,"url":"reianix://native"}"""
            )
        )
    }
}
