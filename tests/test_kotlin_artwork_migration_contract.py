import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local"
ARTWORK = KOTLIN / "data/artwork/ReiAnixArtworkService.kt"
DEPENDENCIES = KOTLIN / "ReiAnixAppDependencies.kt"
EXTRACTOR = KOTLIN / "storage/VideoThumbnailExtractor.kt"
ACTIVITY = KOTLIN / "MainActivity.kt"
DISPATCHER = KOTLIN / "bridge/NativeCommandDispatcher.kt"
ANILIST = KOTLIN / "data/anilist/ReiAnixAniListIntegrationService.kt"


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
        self.assertIn("tmp.renameTo(target)", source)
        self.assertIn("tmp.copyTo(target, overwrite = false)", source)
        self.assertIn("validImage(target, ext)", source)
        self.assertIn("Could not safely promote the downloaded artwork", source)
        self.assertIn("conn.disconnect()", source)
        self.assertNotIn("HostnameVerifier", source)
        self.assertNotIn("TrustManager", source)

    def test_native_anilist_flow_persists_then_requests_native_artwork(self):
        integration = ANILIST.read_text(encoding="utf-8")
        dependencies = DEPENDENCIES.read_text(encoding="utf-8")
        artwork = ARTWORK.read_text(encoding="utf-8")
        self.assertIn('put("type", "anilist_metadata_committed")', integration)
        self.assertIn("requestAnimeArtwork(animeId, force = true)", integration + dependencies)
        self.assertIn("ARTWORK_PUBLISHED", artwork)
        self.assertIn("persistDownloaded(", artwork)
        self.assertNotIn("library.artwork.sync_anime_metadata(", integration)
        self.assertNotIn("library.artwork.request(", integration)

    def test_thumbnail_extraction_registers_with_canonical_artwork_writer(self):
        extractor = EXTRACTOR.read_text(encoding="utf-8")
        activity = ACTIVITY.read_text(encoding="utf-8")
        dispatcher = DISPATCHER.read_text(encoding="utf-8")
        artwork = ARTWORK.read_text(encoding="utf-8")
        self.assertIn("registerGeneratedThumbnail(", artwork)
        self.assertIn("registerGeneratedArtwork", extractor)
        self.assertIn("canonicalArtworkRegistered", extractor)
        self.assertIn('put("canonicalArtworkRegistered", result.canonicalArtworkRegistered)', activity)
        self.assertIn('put("canonicalArtworkRegistered", result.canonicalArtworkRegistered)', dispatcher)
        self.assertIn("VideoThumbnailExtractor", activity + dispatcher)
        self.assertIn("ARTWORK_PUBLISHED", artwork)

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
