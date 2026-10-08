package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.data.library.ReiAnixLibraryCommandCodec
import com.reiflix.reiflix_local.data.library.ReiAnixLibraryRepository
import com.reiflix.reiflix_local.data.library.ReiAnixLibrarySnapshotCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class ReiAnixLibraryRepositoryContractTest {
    @Test
    fun libraryPageCommandEncodesAndDecodesBoundedPayload() {
        val command = ReiAnixLibraryCommandCodec.create(
            requestId = "page-1",
            action = ReiAnixLibraryCommandCodec.Action.LOAD_LIBRARY_PAGE,
            page = 2,
            pageSize = 36,
            query = "Naruto",
            genre = "action",
            sort = "Nome A-Z",
            favoritesOnly = true,
            watchingOnly = false,
            completedOnly = true,
            generation = 9L,
        )
        val payload = command.getJSONObject("payload")
        assertEquals("load_library_page", payload.getString("action"))
        assertEquals(2, payload.getInt("page"))
        assertEquals(36, payload.getInt("pageSize"))
        assertEquals("Naruto", payload.getString("query"))
        assertEquals("action", payload.getString("genre"))
        assertEquals("Nome A-Z", payload.getString("sort"))
        assertEquals(true, payload.getBoolean("favoritesOnly"))
        assertEquals(false, payload.getBoolean("watchingOnly"))
        assertEquals(true, payload.getBoolean("completedOnly"))
        assertEquals(9L, payload.getLong("generation"))

        val result = ReiAnixLibrarySnapshotCodec.decodeCommandResult(
            """{"schemaVersion":1,"requestId":"page-1","action":"load_library_page",
               "status":"COMPLETED","payload":{"kind":"library_page","generation":9,
               "page":2,"page_size":36,"total":72,"has_more":true,"items":[]}}""".trimIndent(),
        )
        assertEquals("page-1", result.requestId)
        assertEquals("load_library_page", result.action)
        assertEquals(9L, result.libraryPage?.generation)
        assertEquals(2, result.libraryPage?.page)
        assertEquals(72, result.libraryPage?.total)
        assertEquals(true, result.libraryPage?.hasMore)
        assertEquals(emptyList<Any>(), result.libraryPage?.items)
    }

    @Test
    fun scanSnapshotPreservesKnownCatalogWhileScanIsInProgress() {
        val previous = ReiAnixLibrarySnapshotCodec.decode(
            """{"schemaVersion":1,"revision":1,"status":"READY","sourceState":"AVAILABLE","sourceAvailable":true,
               "animes":[{"id":7,"main_title":"Local","media_kind":"series",
               "favorite":true,"year":2026,"genres":[],"genre_ids":[],"meta":{},
               "seasons":[],"specials":[],"media_files":[]}],"continue_watching":[]}""".trimIndent(),
        )

        val decoded = ReiAnixLibrarySnapshotCodec.decode(
            """{"schemaVersion":1,"revision":2,"status":"EMPTY","sourceState":"ERROR","sourceAvailable":false,
               "scanInProgress":true,"scanState":"SCANNING","animes":[],"continue_watching":[]}""".trimIndent(),
        )

        val merged = ReiAnixLibraryRepository.mergeSnapshotState(decoded, previous)

        assertEquals("READY", merged.status.name)
        assertEquals(listOf(7L), merged.animes.map { it.id })
        assertEquals(true, merged.scanInProgress)
        assertEquals("SCANNING", merged.scanState)
    }

    @Test
    fun scanFailurePreservesKnownCatalogWhileSurfacingError() {
        val previous = ReiAnixLibrarySnapshotCodec.decode(
            """{"schemaVersion":1,"revision":1,"status":"READY","sourceState":"AVAILABLE","sourceAvailable":true,
               "animes":[{"id":7,"main_title":"Local","media_kind":"series",
               "favorite":false,"year":2026,"genres":[],"genre_ids":[],"meta":{},
               "seasons":[],"specials":[],"media_files":[]}],"continue_watching":[]}""".trimIndent(),
        )

        val decoded = ReiAnixLibrarySnapshotCodec.decode(
            """{"schemaVersion":1,"revision":2,"status":"ERROR","sourceState":"UNKNOWN","sourceAvailable":false,
               "scanInProgress":false,"scanState":"FAILED","error":"scan failed",
               "animes":[],"continue_watching":[]}""".trimIndent(),
        )

        val merged = ReiAnixLibraryRepository.mergeSnapshotState(decoded, previous)

        assertEquals("ERROR", merged.status.name)
        assertEquals("scan failed", merged.error)
        assertEquals(listOf(7L), merged.animes.map { it.id })
        assertEquals(false, merged.animes.isEmpty())
    }

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
