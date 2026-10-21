import tempfile
import unittest
from pathlib import Path

from core.library_store import LibraryStore
from core.scan_coordinator import ScanCoordinator, ScanOrigin, ScanState, ScanTarget
from core.storage_access import (
    StorageCapabilities,
    configured_library_saf_roots,
    pending_saf_inventory_selection_candidate,
    dedupe_saf_roots,
    library_saf_roots,
    saf_source_identity,
    saf_folder_identity,
    saf_inventory_by_identity,
)

ROOT = Path(__file__).resolve().parents[1]
MAIN_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
SAF = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/SafScanner.kt"
SCAN_RUNNER = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/NativeScanRunner.kt"
SCAN_CONTROLLER = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/NativeScanController.kt"


class FakeStore:
    def __init__(self, folders=None):
        self._folders = list(folders or [])

    def last_scan(self):
        return {"status": "completed"}

    def folders(self):
        return list(self._folders)


class FakeBridge:
    def __init__(self):
        self.calls = []
        self._next = 0

    async def rescan_tree(self, uri):
        self._next += 1
        request_id = f"saf-{self._next}"
        self.calls.append(("saf", uri, request_id))
        return request_id

    async def scan_media_store(self):
        raise AssertionError("MediaStore must never be a library scan target")

    async def scan_all_storage(self):
        raise AssertionError("BroadStorage must never be a library scan target")


class SourceIdentityTests(unittest.TestCase):
    URI_A = "content://com.android.externalstorage.documents/tree/primary%3AAnime"
    URI_DUPLICATE_ENCODING = "content://com.android.externalstorage.documents/tree/primary%3AAnime/primary%3AAnime"
    URI_OTHER = "content://com.android.externalstorage.documents/tree/primary%3AAnime2"

    def test_saf_identity_is_stable(self):
        self.assertEqual(saf_source_identity(self.URI_A), "saf:com.android.externalstorage.documents:primary:Anime")
        self.assertEqual(saf_source_identity(self.URI_A), saf_source_identity(self.URI_DUPLICATE_ENCODING))

    def test_duplicate_sources_collapse(self):
        roots = dedupe_saf_roots([self.URI_A, self.URI_A, self.URI_DUPLICATE_ENCODING, self.URI_OTHER])
        self.assertEqual((self.URI_A, self.URI_OTHER), roots)

    def test_invalid_paths_are_not_sources(self):
        self.assertIsNone(saf_source_identity("/storage/emulated/0/Anime"))
        self.assertEqual((), dedupe_saf_roots(["/storage/emulated/0/Anime", "file:///storage/emulated/0/Anime"]))

    def test_scope_matching_is_canonical(self):
        self.assertEqual((self.URI_A,), library_saf_roots([self.URI_A, self.URI_OTHER], scope_ref=self.URI_DUPLICATE_ENCODING))

    def test_capabilities_preserve_authoritative_saf_roots(self):
        caps = StorageCapabilities.from_native({"safRoots": [self.URI_A, self.URI_DUPLICATE_ENCODING], "api": 35})
        self.assertEqual((self.URI_A, self.URI_DUPLICATE_ENCODING), caps.saf_roots)
        self.assertEqual((self.URI_A,), dedupe_saf_roots(caps.saf_roots))

    def test_persisted_but_unconfigured_saf_grant_is_not_a_library_source(self):
        other = self.URI_OTHER
        configured = [{"path": self.URI_A, "kind": "saf", "authorization": "granted"}]
        self.assertEqual(
            (self.URI_A,),
            configured_library_saf_roots([self.URI_A, other], configured),
        )

    def test_stale_cached_identity_never_redirects_a_configured_saf_folder(self):
        configured = [{
            "path": self.URI_A,
            "kind": "saf",
            "authorization": "granted",
            "saf_identity": saf_source_identity(self.URI_OTHER),
        }]
        self.assertEqual(
            (self.URI_A,),
            configured_library_saf_roots([self.URI_A, self.URI_OTHER], configured),
        )
        self.assertEqual(
            (),
            configured_library_saf_roots([self.URI_OTHER], configured),
        )
        self.assertEqual(saf_source_identity(self.URI_A), saf_folder_identity(configured[0]))

    def test_saf_inventory_collapses_uri_variants_and_prefers_valid_tree_status(self):
        rows = saf_inventory_by_identity([
            {
                "treeUri": self.URI_A,
                "identity": saf_source_identity(self.URI_A),
                "status": "REVOKED",
            },
            {
                "treeUri": self.URI_DUPLICATE_ENCODING,
                "identity": saf_source_identity(self.URI_DUPLICATE_ENCODING),
                "status": "COMPLETED",
            },
        ])
        self.assertEqual(1, len(rows))
        identity = saf_source_identity(self.URI_A)
        self.assertEqual("COMPLETED", rows[identity]["status"])
        self.assertEqual(self.URI_DUPLICATE_ENCODING, rows[identity]["treeUri"])

    def test_inventory_recovery_only_accepts_one_new_persisted_root_after_selection(self):
        baseline = {saf_source_identity(self.URI_A)}
        trees = [
            {"treeUri": self.URI_A, "identity": saf_source_identity(self.URI_A), "status": "COMPLETED", "persisted": True},
            {"treeUri": self.URI_OTHER, "identity": saf_source_identity(self.URI_OTHER), "status": "COMPLETED", "persisted": True},
        ]
        selected = pending_saf_inventory_selection_candidate(
            trees, baseline, inventory_started_at_ms=2200,
            selection_started_at_ms=2000, inventory_complete=True,
        )
        self.assertIsNotNone(selected)
        self.assertEqual(self.URI_OTHER, selected["treeUri"])

    def test_inventory_recovery_rejects_stale_ambiguous_or_unpersisted_roots(self):
        baseline = {saf_source_identity(self.URI_A)}
        new_tree = {"treeUri": self.URI_OTHER, "identity": saf_source_identity(self.URI_OTHER), "status": "COMPLETED", "persisted": True}
        self.assertIsNone(pending_saf_inventory_selection_candidate(
            [new_tree], baseline, inventory_started_at_ms=1999,
            selection_started_at_ms=2000, inventory_complete=True,
        ))
        second_uri = "content://com.android.externalstorage.documents/tree/primary%3AAnime3"
        second = {"treeUri": second_uri, "identity": saf_source_identity(second_uri), "status": "COMPLETED", "persisted": True}
        self.assertIsNone(pending_saf_inventory_selection_candidate(
            [new_tree, second], baseline, inventory_started_at_ms=2200,
            selection_started_at_ms=2000, inventory_complete=True,
        ))
        self.assertIsNone(pending_saf_inventory_selection_candidate(
            [dict(new_tree, persisted=False)], baseline, inventory_started_at_ms=2200,
            selection_started_at_ms=2000, inventory_complete=True,
        ))

    def test_revoked_configured_saf_grant_is_not_a_library_source(self):
        configured = [{"path": self.URI_A, "kind": "saf", "authorization": "revoked"}]
        self.assertEqual(
            (),
            configured_library_saf_roots([self.URI_A], configured),
        )

    def test_configured_saf_scope_filter_remains_stable(self):
        configured = [
            {"path": self.URI_A, "kind": "saf", "authorization": "granted"},
            {"path": self.URI_OTHER, "kind": "saf", "authorization": "granted"},
        ]
        self.assertEqual(
            (self.URI_A,),
            configured_library_saf_roots(
                [self.URI_A, self.URI_OTHER],
                configured,
                scope_ref=self.URI_DUPLICATE_ENCODING,
            ),
        )



class CoordinatorTests(unittest.IsolatedAsyncioTestCase):
    ROOT = "content://com.android.externalstorage.documents/tree/primary%3AAnime"
    OTHER = "content://com.android.externalstorage.documents/tree/primary%3AAnime2"

    def provider(self, persisted):
        roots = dedupe_saf_roots(persisted)
        def _provider(source, scope_ref):
            if source not in (None, "saf"):
                return []
            return [ScanTarget("saf", uri) for uri in library_saf_roots(roots, scope_ref=scope_ref)]
        return _provider

    async def test_no_source_blocks_without_storage_fallback(self):
        bridge = FakeBridge()
        coordinator = ScanCoordinator(bridge, FakeStore(), self.provider([]))
        transition = await coordinator.request(ScanOrigin.USER_REFRESH)
        self.assertEqual("blocked", transition.kind)
        self.assertEqual(ScanState.BLOCKED, coordinator.snapshot.state)
        self.assertEqual([], bridge.calls)

    async def test_one_source_dispatches_only_that_source(self):
        bridge = FakeBridge()
        coordinator = ScanCoordinator(bridge, FakeStore(), self.provider([self.ROOT]))
        transition = await coordinator.request(ScanOrigin.USER_REFRESH)
        self.assertEqual("started", transition.kind)
        self.assertEqual([("saf", self.ROOT, "saf-1")], bridge.calls)

    async def test_multiple_sources_dispatch_independently(self):
        bridge = FakeBridge()
        coordinator = ScanCoordinator(bridge, FakeStore(), self.provider([self.ROOT, self.OTHER]))
        transition = await coordinator.request(ScanOrigin.USER_REFRESH)
        self.assertEqual("started", transition.kind)
        self.assertEqual({self.ROOT, self.OTHER}, {call[1] for call in bridge.calls})

    async def test_media_and_broad_are_blocked(self):
        bridge = FakeBridge()
        coordinator = ScanCoordinator(bridge, FakeStore(), self.provider([self.ROOT]))
        media = await coordinator.request(ScanOrigin.USER_REFRESH, source="mediastore")
        broad = await coordinator.request(ScanOrigin.USER_REFRESH, source="broad_storage")
        self.assertEqual("blocked", media.kind)
        self.assertEqual("blocked", broad.kind)
        self.assertEqual([], bridge.calls)


class DatabaseSourceTests(unittest.TestCase):
    URI_A = "content://com.android.externalstorage.documents/tree/primary%3AAnime"
    URI_B = "content://com.android.externalstorage.documents/tree/primary%3AAnime/primary%3AAnime"
    ID = "saf:com.android.externalstorage.documents:primary:Anime"

    def test_same_saf_identity_does_not_create_two_rows(self):
        with tempfile.TemporaryDirectory() as data_dir:
            store = LibraryStore(data_dir)
            first = store.add_folder(self.URI_A, name="Anime", kind="saf", authorization="granted",
                                     saf_authority="com.android.externalstorage.documents", saf_document_id="primary:Anime",
                                     saf_volume_id="primary", saf_identity=self.ID)
            second = store.add_folder(self.URI_B, name="Anime", kind="saf", authorization="granted",
                                      saf_authority="com.android.externalstorage.documents", saf_document_id="primary:Anime",
                                      saf_volume_id="primary", saf_identity=self.ID)
            folders = [f for f in store.folders() if f.get("kind") == "saf"]
            self.assertEqual(first, second)
            self.assertEqual(1, len(folders))


class ContractTests(unittest.TestCase):

    def test_library_target_provider_only_dispatches_configured_saf(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        start = source.index("private fun scanConfiguredSafRoots(")
        end = source.index("private fun scanTree(", start)
        block = source[start:end]
        self.assertIn("repository.listFolders()", block)
        self.assertIn('it.kind.equals("saf", ignoreCase = true)', block)
        self.assertIn("SafScanner.hasPersistedReadPermission", block)
        self.assertIn("SafScanner.inspectTree", block)
        self.assertIn("scanTree(reference, requestId)", block)
        self.assertNotIn('kind = "mediastore"', block)
        self.assertNotIn('kind = "broad_storage"', block)

    def test_native_refresh_uses_the_same_scan_dispatch_path(self):
        view_model = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt").read_text(encoding="utf-8")
        activity = MAIN_ACTIVITY.read_text(encoding="utf-8")
        repository = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryRepository.kt").read_text(encoding="utf-8")
        refresh_start = view_model.index("fun refresh()")
        refresh_end = view_model.index("fun refreshDetails(", refresh_start)
        refresh = view_model[refresh_start:refresh_end]
        self.assertIn("repository.refresh()", refresh)
        self.assertIn("publishScanRequest(", activity)
        self.assertIn("scan_library", activity)
        self.assertIn('"scan_library"', activity)
        self.assertIn("publishScanRequest(", activity)
        self.assertIn("NativeScanController", activity)

    def test_scan_catalog_writes_are_owned_by_the_native_index_pipeline(self):
        runner = SCAN_RUNNER.read_text(encoding="utf-8")
        activity = MAIN_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("nativeLibraryIndexOwner", runner)
        self.assertIn("indexDiscoveredMedia(", runner)
        self.assertIn("finishNativeScanRun(", runner)
        self.assertIn("scanMediaStore(requestId)", activity)
        self.assertIn("scanAllStorage(requestId)", activity)
        self.assertIn("repository.listFolders()", activity)
        self.assertIn('it.kind.equals("saf", ignoreCase = true)', activity)

    def test_media_and_broad_permissions_do_not_create_library_source_rows(self):
        activity = MAIN_ACTIVITY.read_text(encoding="utf-8")
        source_start = activity.index("private fun scanConfiguredSafRoots(")
        source_end = activity.index("private fun scanTree(", source_start)
        configured_sources = activity[source_start:source_end]
        self.assertIn('it.kind.equals("saf", ignoreCase = true)', configured_sources)
        self.assertNotIn('it.kind.equals("mediastore"', configured_sources)
        self.assertNotIn('it.kind.equals("broad_storage"', configured_sources)
        broad_start = activity.index("private fun handleBroadSettingsReturn(")
        broad_end = activity.index("private fun launchExternalSettings(", broad_start)
        broad = activity[broad_start:broad_end]
        media_start = activity.index("private fun requestMediaAccess(")
        media_end = activity.index("fun dismissNativeStorageOnboarding(", media_start)
        media = activity[media_start:media_end]
        self.assertIn("publishScanRequest(", broad)
        self.assertIn("scanMediaStore(requestId)", media)
        self.assertNotIn("upsertSafFolder(", broad)
        self.assertNotIn("upsertSafFolder(", media)
    def test_saf_scanner_recurses_from_selected_tree_and_not_storage_root(self):
        source = SAF.read_text(encoding="utf-8")
        self.assertIn("pending.addLast(identity.documentId to \"\")", source)
        self.assertIn("DocumentsContract.buildChildDocumentsUriUsingTree", source)
        self.assertIn("MIME_TYPE_DIR", source)
        self.assertNotIn("Environment.getExternalStorageDirectory", source)

    def test_saf_permission_and_scope_logs_exist(self):
        source = SAF.read_text(encoding="utf-8")
        for event in ("LIBRARY_SOURCE_LOADED", "LIBRARY_SOURCE_INVALID", "LIBRARY_SOURCE_PERMISSION_LOST", "SCAN_SOURCE_STARTED", "SCAN_SOURCE_COMPLETED"):
            self.assertIn(event, source)


if __name__ == "__main__":
    unittest.main()
