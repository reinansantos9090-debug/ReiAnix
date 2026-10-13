from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
HOME_PATH = "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/home/ReiAnixHome.kt"
HOST_PATH = "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt"
NAV_PATH = "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt"


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def section(source: str, start: str, end: str) -> str:
    start_index = source.index(start)
    end_index = source.index(end, start_index)
    return source[start_index:end_index]


def call_arguments(source: str, call_pattern: str) -> list[str]:
    """Extract balanced Kotlin call argument lists matching a call-site pattern."""
    results = []
    for match in re.finditer(call_pattern, source):
        open_index = source.find("(", match.start())
        depth = 0
        in_string = False
        escaped = False
        for index in range(open_index, len(source)):
            char = source[index]
            if in_string:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
                continue
            if char == '"':
                in_string = True
            elif char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
                if depth == 0:
                    results.append(source[open_index + 1:index])
                    break
        else:
            raise AssertionError(f"Unclosed Kotlin call matching {call_pattern!r}")
    return results


class HomeThumbnailPreferenceTest(unittest.TestCase):
    def test_persisted_preference_flows_from_settings_host_into_home_route(self):
        host = read(HOST_PATH)
        navigation = read(NAV_PATH)
        home = read(HOME_PATH)

        self.assertIn("settingsViewModel.appearanceShowThumbnails.collectAsStateWithLifecycle()", host)
        self.assertIn("appearanceShowThumbnails = appearanceShowThumbnails", host)
        self.assertRegex(
            navigation,
            r"ReiAnixHomeRoute\([\s\S]*?showThumbnails\s*=\s*appearanceShowThumbnails",
        )
        self.assertRegex(
            home,
            r"fun ReiAnixHomeRoute\([\s\S]*?showThumbnails:\s*Boolean",
        )
        self.assertIn("showThumbnails = showThumbnails", home)

    def test_observed_home_forwards_preference_at_each_catalog_render_callsite(self):
        source = read(HOME_PATH)
        observed = section(
            source,
            "private fun ReiAnixHomeObservedScreen(",
            "@Composable\nprivate fun ColumnScope.HomeObservedContent(",
        )
        calls = call_arguments(observed, r"HomeObservedContent\(\s*state\s*=")
        self.assertEqual(len(calls), 4, "Expected content rendering for loading-with-data, error-with-data, unavailable-with-data, and ready states")
        for arguments in calls:
            with self.subTest(arguments=arguments[:100]):
                self.assertRegex(arguments, r"showThumbnails\s*=\s*showThumbnails")

    def test_legacy_home_forwards_preference_at_each_catalog_render_callsite(self):
        source = read(HOME_PATH)
        legacy = section(
            source,
            "fun ReiAnixHomeScreen(",
            "@Composable\nprivate fun HomeHeader(",
        )
        calls = call_arguments(legacy, r"HomeContent\(\s*state\s*=")
        self.assertEqual(len(calls), 4, "Expected content rendering for loading-with-data, error-with-data, unavailable-with-data, and ready states")
        for arguments in calls:
            with self.subTest(arguments=arguments[:100]):
                self.assertRegex(arguments, r"showThumbnails\s*=\s*showThumbnails")

    def test_both_content_adapters_forward_preference_to_shared_home_sections(self):
        source = read(HOME_PATH)
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
        self.assertRegex(observed_content, r"HomeReadyContent\([\s\S]*?showThumbnails\s*=\s*showThumbnails")
        self.assertRegex(legacy_content, r"HomeReadyContent\([\s\S]*?showThumbnails\s*=\s*showThumbnails")

    def test_continue_watching_applies_preference_to_local_and_remote_artwork(self):
        source = read(HOME_PATH)
        continue_section = section(
            source,
            "private fun HomeContinueCard(",
            "private const val HOME_MY_LIST_CONTENT_DESCRIPTION",
        )
        self.assertIn("item.artwork?.localPath.takeIf { showThumbnails }", continue_section)
        self.assertIn("item.artwork?.externalUrl.takeIf { showThumbnails }", continue_section)
        self.assertIn("identity = item.stableKey", continue_section)


if __name__ == "__main__":
    unittest.main()
