import os
import tempfile
import unittest

from core.library_service import LibraryService
from core.library_store import LibraryStore


class ReconciliationTests(unittest.TestCase):
    SAF_ROOT = "content://com.android.externalstorage.documents/tree/primary%3AAnime"

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = LibraryStore(self.tmp.name)
        self.service = LibraryService(self.store)
        self.store.add_folder(
            self.SAF_ROOT,
            name="Anime",
            kind="saf",
            authorization="granted",
            saf_authority="com.android.externalstorage.documents",
            saf_document_id="primary:Anime",
            saf_volume_id="primary",
            saf_identity="saf:com.android.externalstorage.documents:primary:Anime",
        )

    def tearDown(self):
        self.tmp.cleanup()

    def anime(self, title):
        return self.store.upsert_anime(title.casefold().replace(" ", "_"), {
            "title": title,
            "media_kind": "series",
        })

    def episode(self, anime_id, path, relative_path, *, identity=None, source_folder=None, progress=0):
        episode_id = self.store.upsert_episode(
            anime_id,
            path,
            os.path.basename(relative_path),
            1,
            1,
            mime_type="video/mp4",
            file_size=100,
            modified_at=1000,
            source_folder=source_folder,
            media_identity=identity,
        )
        if progress:
            self.store.save_progress(path, progress, 100)
        return episode_id

    def test_mixed_valid_and_invalid(self):
        valid_anime = self.anime("Anime A")
        invalid_anime = self.anime("Anime B")
        valid_path = "content://com.android.externalstorage.documents/document/primary%3AAnime%2FA%2Fep01.mp4"
        invalid_path = "/storage/emulated/0/WhatsApp/ep.mp4"
        self.episode(
            valid_anime,
            valid_path,
            "A/ep01.mp4",
            identity="shared:primary:Anime/A/ep01.mp4",
            source_folder=self.SAF_ROOT,
            progress=40,
        )
        self.episode(
            invalid_anime,
            invalid_path,
            "ep.mp4",
            identity="shared:primary:WhatsApp/ep.mp4",
            source_folder="broad-storage",
            progress=35,
        )

        report = self.service.reconcile_existing_library()
        self.assertEqual(2, report["total"])
        self.assertEqual(1, report["preserved"])
        self.assertEqual(1, report["removed"])
        self.assertIsNotNone(self.store.physical_row(valid_path))
        self.assertIsNone(self.store.physical_row(invalid_path))
        self.assertEqual(40, self.store.physical_row(valid_path)["progress"])

    def test_no_physical_file_is_deleted(self):
        with tempfile.TemporaryDirectory() as phone_like:
            library_root = os.path.join(phone_like, "Anime")
            external_root = os.path.join(phone_like, "WhatsApp")
            os.makedirs(library_root)
            os.makedirs(external_root)
            valid_path = os.path.join(library_root, "ep.mp4")
            invalid_path = os.path.join(external_root, "ep.mp4")
            for path in (valid_path, invalid_path):
                with open(path, "wb") as handle:
                    handle.write(b"video")

            self.store.remove_folder(self.SAF_ROOT)
            self.store.add_folder(
                library_root,
                name="Local Anime",
                kind="filesystem",
                authorization="granted",
            )
            valid_anime = self.anime("Local Anime")
            invalid_anime = self.anime("External")
            self.episode(valid_anime, valid_path, "ep.mp4", source_folder=library_root)
            self.episode(
                invalid_anime,
                invalid_path,
                "ep.mp4",
                identity="file:" + invalid_path,
                source_folder="broad-storage",
            )

            self.service.reconcile_existing_library()
            self.assertTrue(os.path.isfile(valid_path))
            self.assertTrue(os.path.isfile(invalid_path))
            self.assertIsNone(self.store.physical_row(invalid_path))

    def test_no_source_is_non_destructive(self):
        store = LibraryStore(self.tmp.name + "_empty")
        service = LibraryService(store)
        anime_id = store.upsert_anime("legacy", {"title": "Legacy", "media_kind": "series"})
        path = "/storage/emulated/0/Download/legacy.mp4"
        store.upsert_episode(anime_id, path, "legacy.mp4", 1, 1, source_folder="broad-storage")

        report = service.reconcile_existing_library()
        self.assertEqual("skipped_no_reliable_sources", report["status"])
        self.assertIsNotNone(store.physical_row(path))

    def test_revoked_source_is_preserved(self):
        store = LibraryStore(self.tmp.name + "_revoked")
        service = LibraryService(store)
        store.add_folder(
            self.SAF_ROOT,
            name="Anime",
            kind="saf",
            authorization="revoked",
            saf_authority="com.android.externalstorage.documents",
            saf_document_id="primary:Anime",
            saf_volume_id="primary",
            saf_identity="saf:com.android.externalstorage.documents:primary:Anime",
        )
        anime_id = store.upsert_anime("revoked", {"title": "Revoked", "media_kind": "series"})
        path = "content://com.android.externalstorage.documents/document/primary%3AAnime%2Fep.mp4"
        store.upsert_episode(anime_id, path, "ep.mp4", 1, 1, source_folder=self.SAF_ROOT, media_identity="shared:primary:Anime/ep.mp4")

        report = service.reconcile_existing_library()
        self.assertEqual("skipped_no_reliable_sources", report["status"])
        self.assertIsNotNone(store.physical_row(path))

    def test_duplicate_merge_preserves_best_progress_and_canonical_row(self):
        anime_id = self.anime("Canonical")
        target_path = "content://com.android.externalstorage.documents/document/primary%3AAnime%2Fep.mp4"
        source_path = "content://com.android.externalstorage.documents/document/primary%3AAnime%2Fep-alt.mp4"
        target_id = self.episode(anime_id, target_path, "ep.mp4", source_folder=self.SAF_ROOT, progress=20)
        source_id = self.episode(anime_id, source_path, "ep-alt.mp4", source_folder=self.SAF_ROOT, progress=35)

        result = self.store.apply_library_reconciliation(
            [source_id],
            [{"source_id": source_id, "target_id": target_id}],
        )
        row = self.store.physical_row(target_path)
        self.assertEqual(1, result["duplicates_merged"])
        self.assertEqual(35, row["progress"])
        self.assertIsNone(self.store.physical_row(source_path))

    def test_favorite_and_pinned_anime_survive_invalid_episode_removal(self):
        valid_anime = self.anime("Pinned")
        valid_path = "content://com.android.externalstorage.documents/document/primary%3AAnime%2Fep.mp4"
        self.episode(valid_anime, valid_path, "ep.mp4", identity="shared:primary:Anime/ep.mp4", source_folder=self.SAF_ROOT)
        self.store.toggle_favorite(valid_anime)
        self.store.toggle_pinned(valid_anime)

        invalid_anime = self.anime("Invalid")
        invalid_path = "/storage/emulated/0/Instagram/ep.mp4"
        self.episode(invalid_anime, invalid_path, "ep.mp4", identity="shared:primary:Instagram/ep.mp4", source_folder="broad-storage")

        self.service.reconcile_existing_library()
        self.assertTrue(self.store.is_favorite(valid_anime))
        catalog = self.store.catalog()
        self.assertEqual(1, len(catalog))
        self.assertTrue(catalog[0]["is_pinned"])

    def test_dry_run_does_not_modify(self):
        anime_id = self.anime("Dry Run")
        path = "/storage/emulated/0/DCIM/video.mp4"
        self.episode(anime_id, path, "video.mp4", identity="shared:primary:DCIM/video.mp4", source_folder="broad-storage")

        report = self.service.reconcile_existing_library(dry_run=True)
        self.assertEqual(1, report["removed"])
        self.assertIsNotNone(self.store.physical_row(path))

    def test_missing_files_are_not_deleted(self):
        with tempfile.TemporaryDirectory() as root:
            self.store.remove_folder(self.SAF_ROOT)
            self.store.add_folder(root, name="Local", kind="filesystem", authorization="granted")
            anime_id = self.anime("Missing")
            path = os.path.join(root, "missing.mp4")
            self.episode(anime_id, path, "missing.mp4", source_folder=root)

            report = self.service.reconcile_existing_library()
            self.assertEqual(1, report["inaccessible"])
            self.assertIsNotNone(self.store.physical_row(path))

    def test_multiple_sources_keep_both_scopes(self):
        with tempfile.TemporaryDirectory() as root_a, tempfile.TemporaryDirectory() as root_b:
            self.store.remove_folder(self.SAF_ROOT)
            self.store.add_folder(root_a, name="Anime A", kind="filesystem", authorization="granted")
            self.store.add_folder(root_b, name="Anime B", kind="filesystem", authorization="granted")
            anime_a = self.anime("A")
            anime_b = self.anime("B")
            path_a = os.path.join(root_a, "ep.mp4")
            path_b = os.path.join(root_b, "ep.mp4")
            for path in (path_a, path_b):
                with open(path, "wb") as handle:
                    handle.write(b"video")
            self.episode(anime_a, path_a, "ep.mp4", source_folder=root_a)
            self.episode(anime_b, path_b, "ep.mp4", source_folder=root_b)

            report = self.service.reconcile_existing_library()
            self.assertEqual(2, report["preserved"])
            self.assertEqual(0, report["removed"])
            self.assertIsNotNone(self.store.physical_row(path_a))
            self.assertIsNotNone(self.store.physical_row(path_b))

    def test_saf_authority_must_match_configured_tree(self):
        anime_id = self.anime("Wrong Authority")
        path = "content://com.example.other/document/primary%3AAnime%2Fep.mp4"
        self.episode(
            anime_id,
            path,
            "ep.mp4",
            identity="uri:com.example.other:document/primary%3AAnime%2Fep.mp4",
            source_folder=self.SAF_ROOT,
        )
        report = self.service.reconcile_existing_library()
        self.assertEqual(1, report["removed"])
        self.assertIsNone(self.store.physical_row(path))

    def test_recursive_saf_scope_is_preserved(self):
        anime_id = self.anime("Nested")
        path = "content://com.android.externalstorage.documents/document/primary%3AAnime%2FNaruto%2FSeason%2001%2Fep01.mp4"
        self.episode(
            anime_id,
            path,
            "Naruto/Season 01/ep01.mp4",
            identity="shared:primary:Anime/Naruto/Season 01/ep01.mp4",
            source_folder=self.SAF_ROOT,
        )
        report = self.service.reconcile_existing_library()
        self.assertEqual(1, report["preserved"])
        self.assertIsNotNone(self.store.physical_row(path))

    def test_automatic_reconciliation_is_hooked_to_complete_native_ingest(self):
        invalid_anime = self.anime("Legacy")
        invalid_path = "/storage/emulated/0/Movies/legacy.mp4"
        self.episode(invalid_anime, invalid_path, "legacy.mp4", identity="shared:primary:Movies/legacy.mp4", source_folder="broad-storage")
        valid_path = "content://com.android.externalstorage.documents/document/primary%3AAnime%2Fnew.mp4"

        catalog = self.service.ingest_documents(
            self.SAF_ROOT,
            [{
                "uri": valid_path,
                "treeUri": self.SAF_ROOT,
                "documentId": "primary:Anime/new.mp4",
                "scope": "saf:com.android.externalstorage.documents:primary:Anime",
                "name": "new.mp4",
                "mimeType": "video/mp4",
                "size": 100,
                "modifiedAt": 1000,
            }],
            source_kind="saf",
            scan_id="fixture_35-complete",
            scope_kind="root",
            scope_ref=self.SAF_ROOT,
            scan_stats={"status": "completed"},
            enforce_library_source=True,
        )
        self.assertTrue(catalog)
        self.assertIsNone(self.store.physical_row(invalid_path))
        self.assertIsNotNone(self.store.physical_row(valid_path))
        self.assertIsNotNone(self.store.physical_row(valid_path))
        self.assertEqual(1, len(catalog))
        self.assertEqual("new", catalog[0]["main_title"])


if __name__ == "__main__":
    unittest.main()
