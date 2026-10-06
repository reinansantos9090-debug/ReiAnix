from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class Phase7DetailsContractTests(unittest.TestCase):
    def read(self, relative):
        return (ROOT / relative).read_text(encoding="utf-8")

    def test_details_shows_explicit_anilist_sources_without_replacing_local_truth(self):
        details = self.read("views/details_view.py")
        self.assertIn("Score AniList", details)
        self.assertIn("Biblioteca:", details)
        self.assertIn("AniList: ", details)
        self.assertIn("Studio(s)", details)
        self.assertIn("metadata.get(\"format\")", details)
        self.assertIn("metadata.get(\"episodes_count\")", details)

    def test_details_loads_contextual_palette_off_thread(self):
        details = self.read("views/details_view.py")
        main = self.read("main.py")
        self.assertIn("resolve_artwork_palette=None", details)
        self.assertIn("asyncio.to_thread(", details)
        self.assertIn("resolve_artwork_palette", details)
        self.assertIn("run_task(load_contextual_palette)", details)
        self.assertIn("resolve_artwork_palette=library.resolve_artwork_palette", main)

    def test_contextual_palette_does_not_replace_global_theme(self):
        details = self.read("views/details_view.py")
        self.assertIn("activate_theme_for_page(page)", details)
        self.assertIn("run_task(load_contextual_palette)", details)
        self.assertNotIn("page.theme =", details)
        self.assertNotIn("page.theme_mode =", details)
        self.assertNotIn("apply_page_theme(", details)

    def test_anilist_client_has_no_local_consumption_sync_contract(self):
        anilist = self.read("core/anilist.py").casefold()
        self.assertNotIn("save_progress", anilist)
        self.assertNotIn("set_watched", anilist)
        self.assertNotIn("last_played_at", anilist)
        self.assertNotIn("history", anilist)
        self.assertNotIn("file_path", anilist)

    def test_compose_details_has_no_dead_characters_or_related_sections(self):
        details = self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt")
        self.assertNotIn('text = "Personagens"', details)
        self.assertNotIn('text = "Relacionados"', details)
        self.assertNotIn("Personagens indisponíveis", details)
        self.assertNotIn("Relacionados indisponíveis", details)

    def test_compose_details_is_projection_only(self):
        details = self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt").casefold()
        self.assertNotIn("anilistclient", details)
        self.assertNotIn("urlconnection", details)
        self.assertNotIn("http", details)

    def test_palette_cache_belongs_to_artwork_engine_not_sqlite_schema(self):
        artwork = self.read("core/artwork.py")
        self.assertIn(".reiflix-palette-", artwork)
        self.assertIn("resolve_palette", artwork)
        self.assertNotIn("ALTER TABLE artwork ADD COLUMN palette", artwork)


if __name__ == "__main__":
    unittest.main()
