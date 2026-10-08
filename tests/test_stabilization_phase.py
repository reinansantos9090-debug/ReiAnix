import unittest
from pathlib import Path

from core.android_bridge import AndroidBridge
from core.search_engine import LibrarySearchEngine
from core.ui import count_label


ROOT = Path(__file__).resolve().parents[1]


class StabilizationPhaseTests(unittest.TestCase):
    def test_count_label_pluralization(self):
        self.assertEqual(count_label(0, "episódio"), "0 episódios")
        self.assertEqual(count_label(1, "episódio"), "1 episódio")
        self.assertEqual(count_label(2, "episódio"), "2 episódios")

    def test_android_bridge_rejects_non_local_media(self):
        self.assertIsNotNone(
            AndroidBridge.normalize_local_media_reference(
                "content://media/external/video/1"
            )
        )
        self.assertIsNotNone(
            AndroidBridge.normalize_local_media_reference(
                "/storage/emulated/0/Anime/E01.mkv"
            )
        )
        self.assertIsNone(
            AndroidBridge.normalize_local_media_reference(
                "https://example.invalid/video.mkv"
            )
        )

    def test_search_engine_is_deterministic_for_title(self):
        library = [{
            "id": "1",
            "main_title": "One Piece",
            "meta": {
                "title": "One Piece",
                "romaji": "One Piece",
                "english": "One Piece",
                "native": "ワンピース",
                "aliases": [],
            },
            "genres": ["Action"],
            "user_tags": [],
            "personal_note": None,
            "seasons": [],
            "specials": [],
            "media_files": [],
            "media_kind": "series",
        }]
        result = LibrarySearchEngine.search(library, query="one piece")
        self.assertEqual([item["main_title"] for item in result], ["One Piece"])

    def test_settings_has_hierarchical_entry_point(self):
        source = (ROOT / "views" / "settings_view.py").read_text(encoding="utf-8")
        self.assertNotIn("active_category = [None]", source)
        self.assertIn("def open_category", source)
        self.assertIn("def back_to_categories", source)
        self.assertIn("settings_path_provider", source)
        self.assertIn("on_open_settings_category", source)
        self.assertIn("Solicitar permissão de vídeos", source)
        self.assertIn("Verificar permissão de vídeos", source)
        self.assertIn("Backup e Restauração", source)
        self.assertIn('section("Diagnóstico"', source)

    def test_player_handoff_diagnostics_exist(self):
        source = (
            ROOT
            / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("PLAY_INTENT_RESOLVED", source)
        self.assertIn("PLAYER_HANDOFF_START", source)
        self.assertIn("activity_not_resolvable", source)


if __name__ == "__main__":
    unittest.main()
