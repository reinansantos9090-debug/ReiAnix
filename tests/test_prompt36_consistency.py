"""Prompt 36 consistency hardening contracts without emulator/instrumented tests."""
from pathlib import Path

from core.compose_library_bridge import ComposeLibraryBridge


ROOT = Path(__file__).resolve().parents[1]


def test_revoked_source_never_uses_stale_granted_authorization():
    assert ComposeLibraryBridge._source_state([
        {"status": "revoked", "authorization": "granted"},
    ]) == "UNAVAILABLE"


def test_mixed_terminal_and_unknown_sources_remain_unknown():
    assert ComposeLibraryBridge._source_state([
        {"status": "revoked", "authorization": "granted"},
        {"status": "checking", "authorization": ""},
    ]) == "UNKNOWN"


def test_repository_keeps_known_catalog_while_transient_scan_projection_is_empty():
    source = (
        ROOT
        / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibraryRepository.kt"
    ).read_text(encoding="utf-8")
    assert "val preserveCatalogDuringScan = decoded.scanInProgress" in source
    assert 'decoded.status == com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.EMPTY' in source
    assert 'decoded.sourceState !in setOf("UNAVAILABLE", "ERROR")' in source
    assert "status = if (preserveCatalogDuringScan) previous.status else decoded.status" in source


def test_continue_watching_is_bound_to_the_same_snapshot_catalog():
    source = (
        ROOT
        / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibrarySnapshotCodec.kt"
    ).read_text(encoding="utf-8")
    assert "val validAnimeIds = animes.asSequence()" in source
    assert "val validEpisodeIds = animes.asSequence()" in source
    assert "if (model.animeId in validAnimeIds && model.episodeId in validEpisodeIds)" in source
