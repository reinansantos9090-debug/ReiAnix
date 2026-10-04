package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.data.library.ReiAnixLibraryRepository
import com.reiflix.reiflix_local.data.library.ReiAnixLibrarySnapshotCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class ReiAnixLibraryRepositoryContractTest {
    @Test
    fun snapshotReplacementPreservesOnlyCommandTransportState() {
        val previous = ReiAnixLibrarySnapshotCodec.decode(
            """{"schemaVersion":1,"revision":1,"status":"READY","sourceState":"AVAILABLE","sourceAvailable":true,
               "animes":[],"continue_watching":[]}""".trimIndent(),
        ).copy(
            lastCommandId = "request-1",
            lastCommandAction = "refresh",
            lastCommandStatus = "QUEUED",
            lastCommandError = null,
        )

        val decoded = ReiAnixLibrarySnapshotCodec.decode(
            """{"schemaVersion":1,"revision":2,"status":"READY","sourceState":"AVAILABLE","sourceAvailable":true,
               "animes":[{"id":7,"main_title":"Local","media_kind":"series","favorite":false,
               "year":null,"genres":[],"genre_ids":[],"meta":{},"seasons":[],"specials":[],"media_files":[]}],
               "continue_watching":[]}""".trimIndent(),
        )

        val merged = ReiAnixLibraryRepository.mergeSnapshotState(decoded, previous)

        assertEquals(2L, merged.revision)
        assertEquals(7L, merged.animes.single().id)
        assertEquals("request-1", merged.lastCommandId)
        assertEquals("refresh", merged.lastCommandAction)
        assertEquals("QUEUED", merged.lastCommandStatus)
        assertEquals(null, merged.lastCommandError)
    }
}
