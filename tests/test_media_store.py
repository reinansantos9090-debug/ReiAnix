import tempfile
import unittest
from pathlib import Path

from core.library_service import LibraryService
from core.library_store import LibraryStore


ROOT = Path(__file__).resolve().parents[1]


class TestMediaStoreAndroidHost(unittest.TestCase):
    def test_media_store_scanner_uses_content_uris_and_media_store_video(self):
        source = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "scanner" / "MediaStoreScanner.kt").read_text(encoding="utf-8")
        self.assertIn("MediaStore.Video.Media.getContentUri(volumeName)", source)
        self.assertIn("MediaStore.Video.Media.EXTERNAL_CONTENT_URI", source)
        self.assertIn("ContentUris.withAppendedId", source)
        self.assertIn("content://", source) if "content://" in source else self.assertIn("uri.toString()", source)
        self.assertNotIn("MediaStore.Video.Media.DATA", source)
        self.assertNotIn("Environment.getExternalStorageDirectory", source)

    def test_media_store_permissions_are_version_aware(self):
        manifest = (ROOT / "android" / "app" / "src" / "main" / "AndroidManifest.xml").read_text(encoding="utf-8")
        scanner = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "scanner" / "MediaStoreScanner.kt").read_text(encoding="utf-8")
        self.assertIn('android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32"', manifest)
        self.assertIn('android.permission.READ_MEDIA_VIDEO', manifest)
        self.assertIn('android.permission.READ_MEDIA_VISUAL_USER_SELECTED', manifest)
        self.assertIn("Build.VERSION.SDK_INT >= 34", scanner)
        self.assertIn("READ_MEDIA_VISUAL_USER_SELECTED", scanner)
        self.assertIn('fun accessLevel(context: Context): String', scanner)
        self.assertIn('"partial"', scanner)
        self.assertIn('"full"', scanner)
        self.assertIn("READ_MEDIA_VIDEO", scanner)

    def test_media_store_access_level_is_scoped_for_scan_result(self):
        scanner = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "scanner" / "MediaStoreScanner.kt").read_text(encoding="utf-8")
        self.assertIn("val access=accessLevel(context)", scanner)
        self.assertNotIn('val access=accessLevel(context);val scopeKey', scanner)
        self.assertIn('.put("access",access)', scanner)

    def test_partial_media_store_access_never_marks_volume_complete(self):
        scanner = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "scanner" / "MediaStoreScanner.kt").read_text(encoding="utf-8")
        self.assertIn("val complete=StorageAuthorization.canReconcileMediaStore(accessState)", scanner)
        self.assertIn("accessState", scanner)
        self.assertIn('access!="full"', scanner)
        self.assertIn("videos++;volumeVideos++", scanner)

    def test_storage_authorization_explicitly_separates_scan_from_reconciliation(self):
        source = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "storage" / "StorageAuthorization.kt").read_text(encoding="utf-8")
        self.assertIn("fun canScanMediaStore(access: MediaAccessLevel): Boolean", source)
        self.assertIn("fun canReconcileMediaStore(access: MediaAccessLevel): Boolean", source)
        self.assertIn("access == MediaAccessLevel.FULL || access == MediaAccessLevel.PARTIAL", source)
        self.assertIn("access == MediaAccessLevel.FULL", source)

    def test_partial_media_store_results_are_rejected_from_library_ingest(self):
        main = (ROOT / "main.py").read_text(encoding="utf-8")
        start = main.index("                        elif event_type == 'mediastore_scan':")
        end = main.index("                        elif event_type == 'mediastore_permission':", start)
        block = main[start:end]
        self.assertIn("SCAN_SOURCE_REJECTED", block)
        self.assertIn("mediastore_not_library_source", block)
        self.assertNotIn("library.finish_ingest_documents", block)
        self.assertNotIn("library.ingest_documents", block)

    def test_player_accepts_saf_or_media_store_without_path_conversion(self):
        main = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "MainActivity.kt").read_text(encoding="utf-8")
        player = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "NativePlayerActivity.kt").read_text(encoding="utf-8")
        handoff = main[main.index("    private fun openPlayer"):main.index("    private fun clearPendingPlay", main.index("    private fun openPlayer"))]
        self.assertIn('localUri.scheme?.lowercase() !in setOf("content", "file")', handoff)
        self.assertNotIn("SafScanner.isAuthorizedDocument(this, localUri)", handoff)
        self.assertNotIn("MediaStoreScanner.isAuthorizedDocument(this, localUri)", handoff)
        self.assertNotIn("BroadStorageScanner.isAuthorizedFile(this, localUri)", handoff)
        self.assertNotIn("validatePlayerSource(localUri)", handoff)
        self.assertIn("SafScanner.isAuthorizedDocument(this, localUri)", player)
        self.assertIn("MediaStoreScanner.isAuthorizedDocument(this, localUri)", player)
        self.assertIn("BroadStorageScanner.isAuthorizedFile(this, localUri)", player)
        self.assertIn(".setUri(mediaUri)", player)
        self.assertIn("validateLocalSource", player)
        self.assertIn('openFileDescriptor(localUri, "r")', player)
        self.assertIn("Uri.fromFile(File(reference).canonicalFile)", player)
        self.assertNotIn("/storage/emulated/0", main + player)

    def test_flet_template_copies_media_store_scanner_and_permissions(self):
        template = (ROOT / "scripts" / "prepare_flet_template.py").read_text(encoding="utf-8")
        self.assertIn("MediaStoreScanner.kt", template)
        self.assertIn("READ_MEDIA_VIDEO", template)
        self.assertIn("READ_MEDIA_VISUAL_USER_SELECTED", template)
        self.assertIn("READ_EXTERNAL_STORAGE", template)
        self.assertIn("MANAGE_EXTERNAL_STORAGE", template)


class TestMediaStorePersistence(unittest.TestCase):
    def test_media_store_source_isolated_from_saf_missing_updates(self):
        with tempfile.TemporaryDirectory() as data_dir:
            store = LibraryStore(data_dir)
            saf = "content://com.android.externalstorage.documents/tree/primary%3AMovies"
            media = "mediastore:external:video"
            store.add_folder(saf, name="SAF", kind="saf", authorization="granted")
            store.add_folder(media, name="Vídeos do dispositivo", kind="mediastore", authorization="granted")
            anime_id = store.upsert_anime("anime", {"title": "Anime"})
            store.upsert_episode(anime_id, "content://docs/1", "E01.mkv", 1, 1, source_folder=saf)
            store.upsert_episode(anime_id, "content://media/1", "E01.mkv", 1, 1, source_folder=media)
            store.mark_missing(media, [])
            rows = {row["path"]: row["missing"] for row in store.catalog()[0]["seasons"][0]["episodes"]}
            self.assertEqual(rows["content://media/1"], True)
            self.assertEqual(rows["content://docs/1"], False)

    def test_media_store_ingestion_persists_as_its_own_source_kind(self):
        with tempfile.TemporaryDirectory() as data_dir:
            store = LibraryStore(data_dir)
            service = LibraryService(store)
            service._identify = lambda lookup, display, on_status: {"title": display}
            source = "mediastore:external:video"
            service.ingest_documents(
                source,
                [{
                    "uri": "content://media/external/video/media/7",
                    "name": "Example S01E02.mkv",
                    "relativePath": "Movies/Example/Example S01E02.mkv",
                    "mimeType": "video/x-matroska",
                    "size": 123,
                    "modifiedAt": 456,
                }],
                source_kind="mediastore",
                folder_name="Vídeos do dispositivo",
            )
            folders = store.folders()
            self.assertEqual(folders[0]["kind"], "mediastore")
            episode = store.catalog()[0]["seasons"][0]["episodes"][0]
            self.assertEqual(episode["path"], "content://media/external/video/media/7")
            self.assertEqual(episode["source_folder"], source)
            self.assertFalse(episode["missing"])

    def test_native_bridge_exposes_media_store_scan_without_making_it_a_library_source(self):
        bridge = (ROOT / "core" / "android_bridge.py").read_text(encoding="utf-8")
        main = (ROOT / "main.py").read_text(encoding="utf-8")
        self.assertIn("async def scan_media_store(self)", bridge)
        self.assertIn('scan_coordinator.request(', main)
        self.assertIn("ScanOrigin.USER_REFRESH", main)
        self.assertNotIn('await bridge.scan_media_store()', main)
        self.assertNotIn("pending_native_scans", main)
        start = main.index("                        elif event_type == 'mediastore_scan':")
        end = main.index("                        elif event_type == 'mediastore_permission':", start)
        block = main[start:end]
        self.assertIn("mediastore_not_library_source", block)
        self.assertNotIn("source_kind='mediastore'", block)

    def test_android_35_36_states(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/storage/StorageAuthorization.kt").read_text(encoding="utf-8")
        self.assertIn("enum class StorageLifecycleState", source)
        self.assertIn("MediaAccessLevel.PARTIAL", source)
        self.assertIn("fun capabilities(", source)

if __name__ == "__main__":
    unittest.main()


class TestFinalStorageHardening(unittest.TestCase):
    def test_media_store_has_stability_observer_and_waiting_state(self):
        source = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "scanner" / "MediaStoreScanner.kt").read_text(encoding="utf-8")
        index = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "storage" / "NativeIndex.kt").read_text(encoding="utf-8")
        self.assertIn("ContentObserver", source)
        self.assertIn("WAITING_FOR_MEDIASTORE", source)
        self.assertIn("postVersion!=version", source)
        self.assertIn("postGeneration!=generation", source)
        self.assertIn("stableVersion!=postVersion", source)
        self.assertIn("stableGeneration!=postGeneration", source)
        self.assertIn("probeHasMedia", source)
        self.assertIn("getGeneration", source)
        self.assertIn("STATUS_WAITING_FOR_MEDIASTORE", index)

    def test_native_request_state_persists_across_process_death(self):
        source = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "bridge" / "NativeRequestState.kt").read_text(encoding="utf-8")
        self.assertIn("getSharedPreferences", source)
        self.assertIn("seen_request_ids", source)
        self.assertIn("persist()", source)

    def test_broad_batch_reads_generation_after_start(self):
        source = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "scanner" / "BroadStorageScanner.kt").read_text(encoding="utf-8")
        self.assertIn("currentGeneration()", source)
        self.assertNotIn('val generation = generationByVolume[root.volumeId] ?: 0L', source)

    def test_storage_capabilities_expose_reconciliation_layer(self):
        source = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "MainActivity.kt").read_text(encoding="utf-8")
        runner = (ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "scanner" / "NativeScanRunner.kt").read_text(encoding="utf-8")
        self.assertIn("reconciliationCapabilities", source)
        self.assertIn("WAITING_FOR_MEDIASTORE", runner)


class TestNovaFormatCompatibility(unittest.TestCase):
    def test_media_store_and_saf_use_extended_video_extension_fallback(self):
        media = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/MediaStoreScanner.kt").read_text(encoding="utf-8")
        saf = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/SafScanner.kt").read_text(encoding="utf-8")
        for source in (media, saf):
            for extension in ("3g2","3gp","asf","divx","f4v","mpeg","mpg","ogm","ogv","ogx","vob","wtv","webm"):
                self.assertIn('"' + extension + '"', source)
