import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local"
ARTWORK = KOTLIN / "data/artwork/ReiAnixArtworkService.kt"
DEPENDENCIES = KOTLIN / "ReiAnixAppDependencies.kt"
EXTRACTOR = KOTLIN / "storage/VideoThumbnailExtractor.kt"
MAIN_ACTIVITY = KOTLIN / "MainActivity.kt"
DISPATCHER = KOTLIN / "bridge/NativeCommandDispatcher.kt"
MAIN = ROOT / "main.py"


class KotlinArtworkMigrationContractTests(unittest.TestCase):
    def test_artwork_service_reuses_canonical_database_and_schema(self):
        source = ARTWORK.read_text(encoding="utf-8")
        dependencies = DEPENDENCIES.read_text(encoding="utf-8")
        self.assertIn("ReiAnixSqliteDatabase", source)
        self.assertIn("ReiAnixArtworkService(appContext, sqliteDatabase)", dependencies)
        self.assertIn('File(appContext.filesDir, "data/covers")', source)
        self.assertNotIn("SQLiteOpenHelper", source)
        self.assertNotIn("CREATE TABLE", source)

    def test_download_streams_validates_and_promotes_complete_image(self):
        source = ARTWORK.read_text(encoding="utf-8")
        self.assertIn("BufferedInputStream(conn.inputStream)", source)
        self.assertIn("MAX_DOWNLOAD_BYTES", source)
        self.assertIn("output.fd.sync()", source)
        self.assertIn("ATOMIC_MOVE", source)
        self.assertIn("AtomicMoveNotSupportedException", source)
        self.assertIn("validImage(tmp, ext)", source)
        self.assertIn("conn.disconnect()", source)
        self.assertNotIn("HostnameVerifier", source)
        self.assertNotIn("TrustManager", source)

    def test_native_anilist_flow_does_not_delegate_artwork_to_python(self):
        dependencies = DEPENDENCIES.read_text(encoding="utf-8")
        artwork = ARTWORK.read_text(encoding="utf-8")
        main = MAIN.read_text(encoding="utf-8")
        start = main.index("if event_type == 'anilist_metadata_committed':")
        end = main.index("if event_type == 'compose_settings_set':", start)
        handler = main[start:end]
        self.assertIn("artworkService.requestAnimeArtwork(animeId, force = true)", dependencies)
        self.assertIn('put("requestArtwork", false)', dependencies)
        self.assertIn("ARTWORK_PUBLISHED", artwork)
        self.assertNotIn("library.artwork.sync_anime_metadata(", handler)
        self.assertNotIn("library.artwork.request(", handler)
        self.assertIn("if event_type == 'artwork_published':", handler)
        self.assertIn("artwork_ready:", handler)

    def test_thumbnail_extraction_registers_with_canonical_artwork_writer(self):
        extractor = EXTRACTOR.read_text(encoding="utf-8")
        activity = MAIN_ACTIVITY.read_text(encoding="utf-8")
        dispatcher = DISPATCHER.read_text(encoding="utf-8")
        main = MAIN.read_text(encoding="utf-8")
        self.assertIn("registerGeneratedThumbnail(", ARTWORK.read_text(encoding="utf-8"))
        self.assertIn("registerGeneratedArtwork", extractor)
        self.assertIn("canonicalArtworkRegistered", extractor)
        self.assertIn('put("canonicalArtworkRegistered", result.canonicalArtworkRegistered)', activity)
        self.assertIn('put("canonicalArtworkRegistered", result.canonicalArtworkRegistered)', dispatcher)
        start = main.index("elif event_type == 'thumbnail_ready':")
        end = main.index("elif event_type == 'thumbnail_error':", start)
        handler = main[start:end]
        self.assertIn("payload.get('canonicalArtworkRegistered') is True", handler)
        self.assertIn("not compose_library_bridge.enabled", handler)
        self.assertNotIn("library.register_generated_thumbnail", handler.split("if not registered", 1)[0])

    def test_event_is_emitted_only_after_native_persistence_path(self):
        source = ARTWORK.read_text(encoding="utf-8")
        self.assertIn('.put("type", eventType)', source)
        self.assertIn('.put("event", "ARTWORK_PUBLISHED")', source)
        self.assertIn("persistDownloaded(rowId", source)
        self.assertIn("if (saved.current) publish", source)
        self.assertIn("registerGeneratedThumbnail", source)
        self.assertIn("private fun trimCache()", source)


if __name__ == "__main__":
    unittest.main()
