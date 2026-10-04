import io
import json
import os
import tempfile
import unittest
import zipfile
from pathlib import Path

from core.artwork import ArtworkEngine
from core.library_parser import parse_video_path
from core.library_service import LibraryService
from core.library_store import LibraryStore
from PIL import Image


def _valid_jpeg():
    buffer = io.BytesIO()
    Image.new("RGB", (2, 2), (255, 255, 255)).save(buffer, format="JPEG")
    return buffer.getvalue()


JPEG = _valid_jpeg()


class FinalRegressionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = LibraryStore(self.tmp.name)
        self.service = LibraryService(self.store)

    def tearDown(self):
        self.tmp.cleanup()

    def test_s00_is_special_and_not_regular(self):
        parsed = parse_video_path("Show/Season 00/Show S00E01.mkv")
        self.assertEqual("special", parsed.episode_type)
        self.assertEqual(1, parsed.episode)
        self.assertEqual(2, parse_video_path("Show/Season 00/Show E02.mkv").episode)
        self.assertEqual("special", parse_video_path("Show/Season 00/Show E02.mkv").episode_type)

    def test_movie_word_does_not_override_explicit_episode(self):
        parsed = parse_video_path("Show/Show Movie Night S01E05.mkv")
        self.assertEqual("regular", parsed.episode_type)
        self.assertEqual(1, parsed.season)
        self.assertEqual(5, parsed.episode)

        movie = parse_video_path("Films/My Movie.mkv")
        self.assertEqual("movie", movie.episode_type)

    def test_regular_episode_zero_becomes_unknown(self):
        parsed = parse_video_path("Show/Show S01E00.mkv")
        self.assertEqual("unknown", parsed.episode_type)
        self.assertIsNone(parsed.episode)
        self.assertIn("invalid_regular_episode_number", parsed.unresolved_parts)

    def test_special_only_catalog_does_not_create_current_or_next_regular_episode(self):
        anime = self.store.upsert_anime("show", {"title": "Show", "media_kind": "series"}, source="local")
        self.store.upsert_episode(
            anime, "content://special", "Show S00E01.mkv", 0, 1,
            file_size=10, modified_at=10, source_folder="tree",
            media_identity="tree:special", episode_type="special",
        )
        catalog = self.store.catalog()[0]
        self.assertEqual(0, catalog["regular_count"])
        self.assertIsNone(catalog["current_episode"])
        self.assertIsNone(catalog["next_episode"])
        self.assertEqual(1, catalog["special_count"])

    def test_movie_is_first_class_without_next_episode(self):
        movie = self.store.upsert_anime(
            "movie",
            {"title": "Film", "media_kind": "movie"},
            source="local",
        )
        self.store.upsert_episode(
            movie, "content://movie", "Film Movie.mkv", 0, None,
            file_size=20, modified_at=20, source_folder="tree",
            media_identity="tree:movie", episode_type="movie",
        )
        self.store.save_progress("content://movie", 12, 100)
        catalog = self.store.catalog()[0]
        self.assertEqual("movie", catalog["media_kind"])
        self.assertEqual(1, catalog["movie_file_count"])
        self.assertIsNone(catalog["next_episode"])
        self.assertEqual("content://movie", catalog["current_episode"]["path"])

    def test_local_backup_restores_user_and_media_state(self):
        os.makedirs(os.path.join(self.tmp.name, "covers"), exist_ok=True)
        cover = os.path.join(self.tmp.name, "covers", "poster.jpg")
        Path(cover).write_bytes(JPEG)
        anime = self.store.upsert_anime(
            "attack",
            {
                "title": "Attack",
                "romaji": "Attack",
                "english": "Attack",
                "aliases": ["AOT"],
                "studio": "Studio",
                "media_kind": "series",
            },
            source="local",
        )
        episode = self.store.upsert_episode(
            anime, "content://e1", "Attack S01E01.mkv", 1, 1,
            file_size=42, modified_at=123, source_folder="tree",
            media_identity="tree:e1",
        )
        self.store.set_manual_metadata("attack", {"title": "Título manual"})
        self.store.toggle_favorite(anime)
        self.store.toggle_pinned(anime)
        self.store.set_user_tags(anime, ["favorito", "teste"])
        self.store.set_personal_note(anime, "nota persistente")
        self.store.set_episode_identification(
            "content://e1", season=1, number=1, episode_type="regular", title="Episódio manual"
        )
        self.store.save_progress("content://e1", 55, 100)
        ArtworkEngine(self.store).set_manual("anime", anime, "poster", path=cover)

        backup = self.store.create_backup()
        self.assertTrue(os.path.isfile(backup))

        self.store.set_manual_metadata("attack", {"title": "Alterado depois"})
        self.store.set_user_tags(anime, ["apagado"])
        self.store.save_progress("content://e1", 5, 100)
        self.store.remove_folder("tree")
        restored = self.store.restore_backup(backup)

        self.assertIsInstance(restored, dict)
        self.assertTrue(restored.get("report"))
        reopened = LibraryStore(self.tmp.name)
        item = reopened.catalog()[0]
        restored_episode = reopened.physical_row("content://e1")
        self.assertEqual("Título manual", item["meta"]["title"])
        self.assertTrue(item["favorite"])
        self.assertTrue(item["is_pinned"])
        self.assertEqual(["favorito", "teste"], item["user_tags"])
        self.assertEqual("nota persistente", item["personal_note"])
        self.assertEqual(55, restored_episode["progress"])
        self.assertTrue(restored_episode["manual_override"])
        self.assertEqual("Episódio manual", restored_episode["episode_title"])
        self.assertEqual("tree:e1", restored_episode["media_identity"])
        self.assertTrue(os.path.isfile(cover))

    def test_backup_rejects_incompatible_schema_before_replacement(self):
        bad = os.path.join(self.tmp.name, "bad.zip")
        with zipfile.ZipFile(bad, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("manifest.json", json.dumps({"app": "ReiAnix", "schema": 20}))
            archive.writestr("library.sqlite3", b"not-a-sqlite-db")
        from core.backup import BackupValidationError
        with self.assertRaises(BackupValidationError):
            self.store.restore_backup(bad)

    def test_release_identity_matches_product_source(self):
        project = Path("pyproject.toml").read_text(encoding="utf-8")
        android = Path("android/app/build.gradle.kts").read_text(encoding="utf-8")
        workflow = Path(".github/workflows/build_apk.yml").read_text(encoding="utf-8")
        self.assertIn('version = "0.2.1"', project)
        self.assertIn('applicationId = "com.reiflix.reiflix_local"', android)
        self.assertIn('versionCode = 2', android)
        self.assertIn('versionName = "0.2.1"', android)
        self.assertIn('name=\'com.reiflix.reiflix_local\'', workflow)
        self.assertIn("versionCode='$GITHUB_RUN_NUMBER'", workflow)
        self.assertIn("versionName='0.2.1'", workflow)

    def test_settings_and_android_compile_regressions_are_closed_in_source(self):
        settings = Path("views/settings_view.py").read_text(encoding="utf-8")
        broad = Path("android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/BroadStorageScanner.kt").read_text(encoding="utf-8")
        main = Path("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        player = Path("android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt").read_text(encoding="utf-8")
        self.assertNotIn("media_is_partial", settings)
        self.assertIn("Permissão de vídeos:", settings)
        self.assertIn("Acesso amplo:", settings)
        self.assertIn("Verificar permissão de vídeos", settings)
        self.assertIn("fun roots(context: Context): List<StorageRoot>", broad)
        self.assertNotIn("return found.values.toList()", broad[:broad.index("    fun roots(context: Context):")])
        self.assertNotIn("nomediaDirectories++", broad)
        self.assertIn("private var mediaPermissionRequestPending = false", main)
        self.assertIn("import android.os.Build", main)
        self.assertIn("AspectRatioFrameLayout.RESIZE_MODE_FIT", player)
        self.assertIn("applyGlobalTrackPreferences()", player)
        self.assertIn("updateTrackButtons()", player)


if __name__ == "__main__":
    unittest.main()
