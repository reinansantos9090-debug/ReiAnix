package com.reianix.reianix_local.data.diagnostics

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ReiAnixDiagnosticTimelineOffMainTest {
    @Test
    fun artworkEventsAreDeliveredFromWorkerWithOriginalContext() = runBlocking {
        ReiAnixDiagnosticTimeline.clear()
        val marker = "ARTWORK_TEST_ASYNC"
        ReiAnixDiagnosticTimeline.recordOffMain(
            name = marker,
            source = "ReiAnixLocalArtwork",
            context = mapOf("source_kind" to "local_file", "width_px" to 200),
        )
        val event = withTimeout(5_000L) {
            ReiAnixDiagnosticTimeline.events.first { events ->
                events.any { it.name == marker }
            }.first { it.name == marker }
        }
        assertEquals("local_file", event.context["source_kind"])
        assertEquals("200", event.context["width_px"])
        assertTrue(event.sequence > 0L)
        ReiAnixDiagnosticTimeline.clear()
    }
}
