from pathlib import Path
import re


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
                elif char == "\\\\":
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


def test_persisted_preference_flows_from_settings_host_into_home_route():
    host = read(HOST_PATH)
    navigation = read(NAV_PATH)
    home = read(HOME_PATH)

    assert "settingsViewModel.appearanceShowThumbnails.collectAsStateWithLifecycle()" in host
    assert "appearanceShowThumbnails = appearanceShowThumbnails" in host
    assert re.search(
        r"ReiAnixHomeRoute\([\s\S]*?showThumbnails\s*=\s*appearanceShowThumbnails",
        navigation,
    )
    assert re.search(r"fun ReiAnixHomeRoute\([\s\S]*?showThumbnails:\s*Boolean", home)
    assert "showThumbnails = showThumbnails" in home


def test_observed_home_forwards_preference_at_each_catalog_render_callsite():
    source = read(HOME_PATH)
    observed = section(
        source,
        "private fun ReiAnixHomeObservedScreen(",
        "@Composable\nprivate fun ColumnScope.HomeObservedContent(",
    )
    calls = call_arguments(observed, r"HomeObservedContent\(\s*state\s*=")
    assert len(calls) == 4, (
        "Expected loading-with-data, error-with-data, unavailable-with-data, and ready render paths"
    )
    for arguments in calls:
        assert re.search(r"showThumbnails\s*=\s*showThumbnails", arguments)


def test_legacy_home_forwards_preference_at_each_catalog_render_callsite():
    source = read(HOME_PATH)
    legacy = section(
        source,
        "fun ReiAnixHomeScreen(",
        "@Composable\nprivate fun HomeHeader(",
    )
    calls = call_arguments(legacy, r"HomeContent\(\s*state\s*=")
    assert len(calls) == 4, (
        "Expected loading-with-data, error-with-data, unavailable-with-data, and ready render paths"
    )
    for arguments in calls:
        assert re.search(r"showThumbnails\s*=\s*showThumbnails", arguments)


def test_both_content_adapters_forward_preference_to_shared_home_sections():
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
    assert re.search(
        r"HomeReadyContent\([\s\S]*?showThumbnails\s*=\s*showThumbnails",
        observed_content,
    )
    assert re.search(
        r"HomeReadyContent\([\s\S]*?showThumbnails\s*=\s*showThumbnails",
        legacy_content,
    )


def test_continue_watching_applies_preference_to_local_and_remote_artwork():
    source = read(HOME_PATH)
    continue_section = section(
        source,
        "private fun HomeContinueCard(",
        "private const val HOME_MY_LIST_CONTENT_DESCRIPTION",
    )
    assert "item.artwork?.localPath.takeIf { showThumbnails }" in continue_section
    assert "item.artwork?.externalUrl.takeIf { showThumbnails }" in continue_section
    assert "identity = item.stableKey" in continue_section
