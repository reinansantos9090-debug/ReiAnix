import tempfile
import unittest
from pathlib import Path

from core.library_store import LibraryStore
from core.scan_coordinator import ScanCoordinator, ScanOrigin, ScanState, ScanTarget
from core.storage_access import (
    StorageCapabilities,
    configured_library_saf_roots,
    dedupe_saf_roots,
    library_saf_roots,
    saf_source_identity,
)

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "main.py"
SAF = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/SafScanner.kt"


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
    def test_library_target_provider_only_dispatches_saf(self):
        source = MAIN.read_text(encoding="utf-8")
        start = source.index("    def _authorized_scan_targets(source=None, scope_ref=None):")
        end = source.index("    scan_coordinator = ScanCoordinator(", start)
        block = source[start:end]
        self.assertIn("configured_library_saf_roots", block)
        self.assertIn('ScanTarget("saf", uri)', block)
        self.assertNotIn('ScanTarget("mediastore")', block)
        self.assertNotIn('ScanTarget("broad_storage")', block)
        self.assertNotIn("/storage/emulated/0", block)
        self.assertIn("non_library_scanner", block)

    def test_refresh_and_pull_to_refresh_share_the_coordinator(self):
        source = MAIN.read_text(encoding="utf-8")
        a = source.index("    async def refresh_library")
        b = source.index("    async def refresh_home_library", a)
        refresh = source[a:b]
        self.assertIn("scan_coordinator.request(", refresh)
        self.assertIn("ScanOrigin.USER_REFRESH", refresh)
        a = source.index("    async def request_home_refresh")
        b = source.index("    async def refresh_home_library", a)
        self.assertIn("await refresh_library(_home_refresh_context=home_refresh_context)", source[a:b])
    def test_global_scan_results_never_enter_library(self):
        source = MAIN.read_text(encoding="utf-8")
        a = source.index("                        elif event_type == 'broad_storage_scan':")
        b = source.index("                        elif event_type == 'broad_storage_status':", a)
        broad = source[a:b]
        a = source.index("                        elif event_type == 'mediastore_scan':")
        b = source.index("                        elif event_type == 'mediastore_permission':", a)
        media = source[a:b]
        self.assertIn("SCAN_SOURCE_REJECTED", broad)
        self.assertNotIn("library.ingest_documents", broad)
        self.assertNotIn("library.finish_ingest_documents", broad)
        self.assertIn("SCAN_SOURCE_REJECTED", media)
        self.assertNotIn("library.ingest_documents", media)
        self.assertNotIn("library.finish_ingest_documents", media)

    def test_media_and_broad_permissions_do_not_create_library_source_rows(self):
        source = MAIN.read_text(encoding="utf-8")
        a = source.index("                        elif event_type == 'broad_storage_status':")
        b = source.index("                        elif event_type == 'broad_storage_error':", a)
        broad = source[a:b]
        a = source.index("                        elif event_type == 'mediastore_permission':")
        b = source.index("                        elif event_type == 'mediastore_error':", a)
        media = source[a:b]
        self.assertNotIn("store.add_folder('broad-storage'", broad)
        self.assertNotIn("store.restore_source('broad-storage')", broad)
        self.assertNotIn("kind='mediastore'", media)
        self.assertNotIn("store.restore_source(source)", media)

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
