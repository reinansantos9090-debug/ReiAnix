from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
HOME = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/home/ReiAnixHome.kt"


def section(source: str, start: str, end: str) -> str:
    start_index = source.index(start)
    end_index = source.index(end, start_index)
    return source[start_index:end_index]


class HomeThumbnailPreferenceTest(unittest.TestCase):
    def test_observed_home_forwards_thumbnail_preference_for_every_library_state(self):
        source = HOME.read_text(encoding="utf-8")
        observed = section(
            source,
            "private fun ReiAnixHomeObservedScreen(",
            "@Composable\nprivate fun ColumnScope.HomeObservedContent(",
        )
        self.assertEqual(observed.count("HomeObservedContent("), 4)
        self.assertEqual(observed.count("showThumbnails = showThumbnails"), 4)

    def test_legacy_home_forwards_thumbnail_preference_for_every_library_state(self):
        source = HOME.read_text(encoding="utf-8")
        legacy = section(
            source,
            "fun ReiAnixHomeScreen(",
            "@Composable\nprivate fun HomeHeader(",
        )
        self.assertEqual(legacy.count("HomeContent("), 4)
        self.assertEqual(legacy.count("showThumbnails = showThumbnails"), 4)

    def test_both_home_content_paths_forward_preference_to_shared_sections(self):
        source = HOME.read_text(encoding="utf-8")
        observed_content = section(
            source,
            "private fun ColumnScope.HomeObservedContent(",
            "fun ReiAnixHomeScreen(",
        )
        legacy_content = section(
            source,
            "private fun ColumnScope.HomeContent(",
            "private fun ColumnScope.HomeReadyContent(",
        )
        self.assertIn("showThumbnails = showThumbnails", observed_content)
        self.assertIn("showThumbnails = showThumbnails", legacy_content)

    def test_continue_watching_uses_the_forwarded_preference_for_both_artwork_sources(self):
        source = HOME.read_text(encoding="utf-8")
        continue_section = section(
            source,
            "private fun HomeContinueCard(",
            "private const val HOME_MY_LIST_CONTENT_DESCRIPTION",
        )
        self.assertIn("item.artwork?.localPath.takeIf { showThumbnails }", continue_section)
        self.assertIn("item.artwork?.externalUrl.takeIf { showThumbnails }", continue_section)


if __name__ == "__main__":
    unittest.main()
