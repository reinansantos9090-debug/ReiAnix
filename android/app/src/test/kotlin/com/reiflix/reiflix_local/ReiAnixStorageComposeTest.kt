package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.data.library.ReiAnixLibrarySnapshotCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixStorageComposeTest {
    @Test
    fun snapshot_decodes_storage_capabilities_and_sources_without_parallel_state() {
        val raw = """
            {
              "schemaVersion":1,
              "revision":7,
              "status":"READY",
              "sourceState":"AVAILABLE",
              "sourceAvailable":true,
              "scanInProgress":false,
              "scanState":"IDLE",
              "storage":{
                "onboardingState":"ready",
                "onboardingMessage":null,
                "onboardingError":null,
                "capabilities":{
                  "mediaReadState":"full",
                  "broadStorageState":"available",
                  "safRoots":["content://com.example/tree/primary%3AAnime"],
                  "safRootIdentities":["saf:com.example:primary:Anime"],
                  "removableVolumes":["sdcard"],
                  "scannerCapabilities":["saf","mediastore"],
                  "reconciliationCapabilities":["saf","mediastore"],
                  "lifecycleState":"revalidated",
                  "api":36
                },
                "configuredSources":[
                  {
                    "reference":"content://com.example/tree/primary%3AAnime",
                    "name":"Anime",
                    "kind":"saf",
                    "authorization":"granted",
                    "status":"granted",
                    "saf_identity":"saf:com.example:primary:Anime",
                    "saf_volume_id":"primary",
                    "saf_document_id":"primary:Anime"
                  }
                ]
              },
              "animes":[]
            }
        """.trimIndent()

        val state = ReiAnixLibrarySnapshotCodec.decode(raw)

        assertEquals("full", state.storage.mediaReadState)
        assertEquals("available", state.storage.broadStorageState)
        assertEquals("ready", state.storage.onboardingState)
        assertEquals(null, state.storage.onboardingMessage)
        assertEquals(null, state.storage.onboardingError)
        assertEquals(listOf("saf:com.example:primary:Anime"), state.storage.safRootIdentities)
        assertEquals(1, state.storage.configuredSources.size)
        assertTrue(state.storage.sourceState(state.storage.configuredSources.first()) == "available")
    }

    @Test
    fun revoked_configured_saf_source_stays_visible() {
        val source = com.reiflix.reiflix_local.ui.model.ReiAnixStorageSourceUiModel(
            reference = "content://com.example/tree/primary%3AAnime",
            name = "Anime",
            kind = "saf",
            authorization = "granted",
            status = "revoked",
            safIdentity = "saf:com.example:primary:Anime",
        )
        val state = com.reiflix.reiflix_local.ui.model.ReiAnixStorageUiState(
            configuredSources = listOf(source),
        )

        assertEquals("revoked", state.sourceState(source))
        assertEquals("Anime", state.configuredSources.first().name)
    }
}
