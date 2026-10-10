from __future__ import annotations

import asyncio
import json
import tempfile
import unittest
from pathlib import Path

from core.compose_library_bridge import ComposeLibraryBridge
from core.storage_access import StorageCapabilities


class FakeLibrary:
    def catalog(self):
        return []

    def continue_watching(self, limit=12):
        return []


class FakeStore:
    def folders(self):
        return [
            {
                "path": "content://com.example/tree/primary%3AAnime",
                "name": "Anime",
                "kind": "saf",
                "authorization": "granted",
                "status": "granted",
                "saf_identity": "saf:com.example:primary:Anime",
                "saf_volume_id": "primary",
                "saf_document_id": "primary:Anime",
            },
            {
                "path": "broad-storage",
                "name": "Armazenamento amplo",
                "kind": "broad-storage",
                "authorization": "granted",
                "status": "available",
            },
        ]


class ComposeStorageBridgeTest(unittest.TestCase):
    def test_recreated_bridge_continues_persisted_snapshot_revision(self):
        with tempfile.TemporaryDirectory() as directory:
            snapshot_dir = Path(directory) / "reianix-compose"
            snapshot_dir.mkdir(parents=True)
            snapshot_path = snapshot_dir / "library.json"
            snapshot_path.write_text(
                json.dumps({
                    "schemaVersion": 1, "revision": 41, "generatedAt": 1000,
                    "reason": "previous_process", "status": "EMPTY",
                    "sourceState": "UNAVAILABLE", "sourceAvailable": False,
                    "scanInProgress": False, "scanState": "IDLE", "lastScanStatus": None,
                    "storage": {}, "animes": [], "continue_watching": [],
                }), encoding="utf-8",
            )
            bridge = ComposeLibraryBridge(directory, FakeLibrary(), FakeStore())

            async def publish():
                bridge.request_publish("restart_revision_regression")
                await bridge.wait_for_idle()

            asyncio.run(publish())
            snapshot = json.loads(snapshot_path.read_text(encoding="utf-8"))
            self.assertGreater(snapshot["revision"], 41)

    def test_storage_projection_uses_canonical_capabilities_and_configured_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            bridge = ComposeLibraryBridge(
                directory,
                FakeLibrary(),
                FakeStore(),
                storage_state_provider=lambda: {
                    "capabilities": StorageCapabilities(
                        media_read_state="full",
                        broad_storage_state="available",
                        saf_roots=("content://com.example/tree/primary%3AAnime",),
                        scanner_capabilities=frozenset({"saf", "mediastore"}),
                        reconciliation_capabilities=frozenset({"saf", "mediastore"}),
                        lifecycle_state="revalidated",
                        api=36,
                    ),
                    "safSelectionPending": True,
                },
            )
            async def publish():
                bridge.request_publish("stage17")
                await bridge.wait_for_idle()

            asyncio.run(publish())

            payload = json.loads(
                (Path(directory) / "reianix-compose" / "library.json").read_text(encoding="utf-8")
            )

            storage = payload["storage"]
            self.assertEqual("full", storage["capabilities"]["mediaReadState"])
            capabilities = storage["capabilities"]
            self.assertEqual("available", capabilities["broadStorageState"])
            self.assertEqual("revalidated", capabilities["lifecycleState"])
            self.assertEqual(36, capabilities["api"])
            self.assertEqual({"saf", "mediastore"}, set(capabilities["scannerCapabilities"]))
            self.assertEqual({"saf", "mediastore"}, set(capabilities["reconciliationCapabilities"]))
            self.assertTrue(storage["safSelectionPending"])
            self.assertEqual(
                ["saf:com.example:primary:Anime"],
                capabilities["safRootIdentities"],
            )

            sources_by_kind = {
                source["kind"]: source
                for source in storage["configuredSources"]
            }
            self.assertEqual(2, len(sources_by_kind))

            saf = sources_by_kind["saf"]
            self.assertEqual("Anime", saf["name"])
            self.assertEqual("content://com.example/tree/primary%3AAnime", saf["reference"])
            self.assertEqual("granted", saf["authorization"])
            self.assertEqual("granted", saf["status"])
            self.assertEqual("saf:com.example:primary:Anime", saf["saf_identity"])
            self.assertEqual("primary", saf["saf_volume_id"])
            self.assertEqual("primary:Anime", saf["saf_document_id"])

            broad = sources_by_kind["broad-storage"]
            self.assertEqual("Armazenamento amplo", broad["name"])
            self.assertEqual("granted", broad["authorization"])
            self.assertEqual("available", broad["status"])


if __name__ == "__main__":
    unittest.main()
