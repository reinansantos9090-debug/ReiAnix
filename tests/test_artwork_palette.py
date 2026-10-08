import json
import tempfile
import time
import unittest
from pathlib import Path

from PIL import Image

from core.artwork import ArtworkEngine
from core.artwork_palette import contrast_ratio, extract_palette
from core.library_store import LibraryStore


class ArtworkPaletteTests(unittest.TestCase):
    def _image(self, path, color, *, alpha=255):
        image = Image.new("RGBA", (96, 96), (*color, alpha))
        image.save(path, format="PNG")

    def test_palette_extraction_handles_light_dark_and_saturated_artwork(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = {
                "light": root / "light.png",
                "dark": root / "dark.png",
                "red": root / "red.png",
            }
            self._image(paths["light"], (235, 238, 245))
            self._image(paths["dark"], (28, 32, 40))
            self._image(paths["red"], (245, 18, 24))

            dark_palette = extract_palette(paths["light"], "dark")
            light_palette = extract_palette(paths["dark"], "light")
            red_palette = extract_palette(paths["red"], "dark")

            for palette in (dark_palette, light_palette, red_palette):
                self.assertIsNotNone(palette)
                self.assertTrue(palette["accent"].startswith("#"))
                self.assertTrue(palette["on_accent"].startswith("#"))

    def test_palette_guarantees_accessible_text_choice(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "green.png"
            self._image(path, (24, 200, 80))
            palette = extract_palette(path, "dark")
            self.assertIsNotNone(palette)
            def parse_hex(value):
                value = value.lstrip("#")
                return tuple(int(value[index:index + 2], 16) for index in (0, 2, 4))
            accent = parse_hex(palette["accent"])
            on_accent = parse_hex(palette["on_accent"])
            self.assertGreaterEqual(contrast_ratio(accent, on_accent), 4.5)

    def test_monochrome_artwork_keeps_a_neutral_accent(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "mono.png"
            self._image(path, (120, 120, 120))
            palette = extract_palette(path, "dark")
            self.assertIsNotNone(palette)
            accent = palette["accent"].lstrip("#")
            rgb = tuple(int(accent[index:index + 2], 16) for index in (0, 2, 4))
            self.assertLessEqual(max(rgb) - min(rgb), 8)

    def test_transparent_and_corrupt_artwork_fall_back_safely(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            transparent = root / "transparent.png"
            corrupt = root / "corrupt.png"
            self._image(transparent, (20, 40, 180), alpha=120)
            corrupt.write_bytes(b"not-an-image")
            self.assertIsNotNone(extract_palette(transparent, "dark"))
            self.assertIsNone(extract_palette(corrupt, "dark"))

    def test_artwork_engine_caches_palette_and_invalidates_when_artwork_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            engine = ArtworkEngine(store, max_workers=1)
            try:
                anime_id = store.upsert_anime("palette-show", {"title": "Palette Show", "genres": "[]"}, source="local")
                cover = Path(directory) / "cover.png"
                self._image(cover, (210, 30, 40))
                self.assertTrue(engine.add_local("anime", anime_id, "poster", cover))

                first = engine.resolve_palette("anime", anime_id, "poster", mode="dark")
                self.assertIsNotNone(first)
                cache_file = Path(store.cache_dir) / "artwork" / f".reiflix-palette-{first['artwork_id']}.json"
                self.assertTrue(cache_file.is_file())
                cached_disk = json.loads(cache_file.read_text(encoding="utf-8"))
                self.assertEqual(cached_disk["fingerprint"], first["fingerprint"])

                time.sleep(0.01)
                self._image(cover, (30, 70, 220))
                second = engine.resolve_palette("anime", anime_id, "poster", mode="dark")
                self.assertIsNotNone(second)
                self.assertNotEqual(first["fingerprint"], second["fingerprint"])
                self.assertEqual(json.loads(cache_file.read_text(encoding="utf-8"))["fingerprint"], second["fingerprint"])
            finally:
                engine.shutdown()

    def test_missing_artwork_returns_none_without_network(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            engine = ArtworkEngine(store, max_workers=1)
            try:
                anime_id = store.upsert_anime("missing-cover", {"title": "Missing Cover", "genres": "[]"}, source="local")
                self.assertIsNone(engine.resolve_palette("anime", anime_id, "poster", mode="dark"))
            finally:
                engine.shutdown()


if __name__ == "__main__":
    unittest.main()
