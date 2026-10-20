from pathlib import Path

from core.library_parser import parse_video_path


ROOT = Path(__file__).resolve().parents[1]


def test_python_parser_baseline_preserves_ordinary_episode_suffix_words():
    parsed = parse_video_path("Show Episode 03 - Complete Batch 1080p WEB-DL.mkv")

    assert parsed.display_title == "Complete Batch"


def test_native_parser_uses_the_narrow_legacy_episode_suffix_cleanup():
    parser = (
        ROOT
        / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/media/MediaFilenameParser.kt"
    ).read_text(encoding="utf-8")
    normalizer = (
        ROOT
        / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/media/MediaIdentity.kt"
    ).read_text(encoding="utf-8")

    assert "MediaTitleNormalizer.episodeTitleAfter(stem, marker)" in parser
    start = normalizer.index("internal fun episodeTitleAfter(")
    end = normalizer.index("\n    }", start) + len("\n    }")
    helper = normalizer[start:end]

    assert "technicalPatterns.forEach" in helper
    assert "cleanMediaTitle(" not in helper
    assert 'Regex("(?i)v?\\\\d+")' in helper
