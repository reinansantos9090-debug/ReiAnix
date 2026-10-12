import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local"
DETAILS = KOTLIN / "ui/details/ReiAnixDetails.kt"
LIBRARY_VM = KOTLIN / "viewmodel/ReiAnixLibraryViewModel.kt"
ANILIST_VM = KOTLIN / "viewmodel/ReiAnixAniListViewModel.kt"
SERVICE = KOTLIN / "data/anilist/ReiAnixAniListIntegrationService.kt"
LIBRARY_REPOSITORY = KOTLIN / "data/sqlite/ReiAnixSqliteLibraryRepository.kt"
ASSOCIATION = KOTLIN / "data/sqlite/ReiAnixSqliteAniListAssociation.kt"


class NativeAniListIntegrationContractTests(unittest.TestCase):
    def test_details_uses_shared_buttons_for_association_actions(self):
        source = DETAILS.read_text(encoding="utf-8")
        self.assertNotIn("TextButton(", source)
        self.assertIn('text = "Pesquisar"', source)
        self.assertIn('text = "Fechar"', source)
        self.assertIn("ReiAnixCompactButton(", source)
        self.assertIn("ReiAnixSecondaryButton(", source)

    def test_local_search_viewmodel_isolated_from_remote_anilist(self):
        source = LIBRARY_VM.read_text(encoding="utf-8")
        self.assertNotIn("anilist", source.lower())
        integration = ANILIST_VM.read_text(encoding="utf-8")
        self.assertIn("ReiAnixAniListIntegrationService", integration)
        self.assertIn("CancellationException", integration)
        self.assertIn("associationRevision", integration)

    def test_generic_metadata_patch_does_not_own_association_bookkeeping(self):
        source = LIBRARY_REPOSITORY.read_text(encoding="utf-8")
        start = source.index("suspend fun updateAnimeMetadata(")
        end = source.index("suspend fun getEpisode(", start)
        method = source[start:end]
        self.assertIn("database.transaction", method)
        self.assertNotIn('values.put("metadata_source"', method)
        self.assertNotIn('values.put("anilist_id"', method)
        self.assertIn('patch.coverCache?.takeIf { it.isNotBlank() }', method)
        self.assertIn('patch.genresJson?.takeIf { it.isNotBlank() && it.trim() != "[]" }', method)

    def test_anilist_association_is_atomic_and_preserves_manual_ownership(self):
        source = ASSOCIATION.read_text(encoding="utf-8")
        self.assertIn("database.transaction", source)
        self.assertIn('if (!manual && current.anilistMatchManual == true)', source)
        self.assertIn("current.anilistId != anilistId", source)
        self.assertIn('optionalString("metadata_manual_fields")', source)
        self.assertIn('values.put("metadata_source", "anilist")', source)
        self.assertIn('"cover_cache" !in protectedFields', source)
        for local_column in ('"local_path"', '"favorite"', '"personal_note"', '"user_tags"'):
            self.assertNotIn(f'values.put({local_column}', source)

    def test_anilist_preferences_and_artwork_gate_are_reused(self):
        source = SERVICE.read_text(encoding="utf-8")
        self.assertIn('"metadata.anilist_enabled"', source)
        self.assertIn('"metadata.auto_match"', source)
        self.assertIn('"artwork.enabled"', source)
        self.assertIn("coverUrl = if (artworkEnabled)", source)
        self.assertIn("bannerUrl = remote.bannerUrl.takeIf { artworkEnabled }", source)
        self.assertIn("private val autoMatchAttemptTimestamps", source)
        self.assertIn("MediaTitleNormalizer.normalizeTitle(value)", source)
        self.assertIn("best.second <= 0.0", source)
        self.assertIn("best.second < AUTO_MATCH_MIN_SCORE || margin < AUTO_MATCH_MIN_MARGIN", source)


if __name__ == "__main__":
    unittest.main()
