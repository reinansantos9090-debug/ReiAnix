package com.reiflix.reiflix_local.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReiAnixSettingsSnapshotCodecTest {

    @Test
    fun decodesSettingsProjectionWithExplicitTransportStringification() {
        val snapshot = ReiAnixSettingsSnapshotCodec.decode(
            """
            {
              "schemaVersion": 1,
              "revision": 4,
              "status": "READY",
              "account": {
                "integrationAvailable": true,
                "connected": false,
                "name": "Example",
                "email": "",
                "state": "disconnected"
              },
              "categories": {"Geral": true, "Player": false},
              "storage": {
                "mediaReadState": "granted",
                "broadStorageState": "available",
                "safRootCount": 2,
                "removableVolumeCount": 1,
                "lifecycleState": "ready",
                "api": 36,
                "safSelectionPending": false
              },
              "settings": {
                "appearance.theme": "dark",
                "player.autoplay_next": true,
                "player.auto_hide_seconds": 5
              },
              "error": null
            }
            """.trimIndent(),
        )

        assertEquals(4L, snapshot.revision)
        assertEquals("READY", snapshot.status)
        assertEquals("Example", snapshot.account.name)
        assertEquals(false, snapshot.account.connected)
        assertEquals(mapOf("Geral" to true, "Player" to false), snapshot.categories)
        assertEquals("dark", snapshot.settings["appearance.theme"])
        assertEquals("true", snapshot.settings["player.autoplay_next"])
        assertEquals("5", snapshot.settings["player.auto_hide_seconds"])
        assertEquals(2, snapshot.storage.safRootCount)
        assertEquals(36, snapshot.storage.api)
        assertNull(snapshot.error)
    }

    @Test
    fun settingsSnapshotRejectsMalformedWireTypesAndNumericRanges() {
        val base = """
            {"schemaVersion":1,"revision":1,"status":"READY",
             "account":{"integrationAvailable":true,"connected":false,"name":"","email":"","state":"disconnected"},
             "categories":{},"storage":{},"settings":{}}
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixSettingsSnapshotCodec.decode(base.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixSettingsSnapshotCodec.decode(base.replace("\"revision\":1", "\"revision\":1.5"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixSettingsSnapshotCodec.decode(base.replace("\"connected\":false", "\"connected\":\"false\""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixSettingsSnapshotCodec.decode(base.replace("\"categories\":{}", "\"categories\":{\"Geral\":\"true\"}"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixSettingsSnapshotCodec.decode(base.replace("\"storage\":{}", "\"storage\":{\"safRootCount\":2147483648}"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixSettingsSnapshotCodec.decode(base.replace("\"settings\":{}", "\"settings\":{\"future.key\":null}"))
        }
    }

    @Test
    fun settingsSnapshotPreservesEmptyStringAndTreatsAbsentSettingsAsAbsent() {
        val snapshot = ReiAnixSettingsSnapshotCodec.decode(
            """{"schemaVersion":1,"revision":2,"status":"READY","account":{},"categories":{},"storage":{},"settings":{"known.empty":""}}"""
        )
        assertEquals("", snapshot.settings["known.empty"])
        assertEquals(false, snapshot.settings.containsKey("missing.key"))
    }
}
