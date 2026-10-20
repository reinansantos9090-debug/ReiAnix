from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryRepository.kt"
FILTERS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixCatalogFilters.kt"
SERVICE = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixGachaService.kt"
DEPENDENCIES = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ReiAnixAppDependencies.kt"
VIEW_MODEL = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt"
DOC = ROOT / "docs/KOTLIN_REMAINING_DOMAIN_FEATURES_MIGRATION.md"


class Prompt10RemainingDomainFeaturesMigrationContract(unittest.TestCase):
    def test_filter_contract_and_canonical_sqlite_query_are_present(self):
        source = REPOSITORY.read_text(encoding="utf-8")
        filters = FILTERS.read_text(encoding="utf-8")
        for field in ("query", "state", "genre", "sort", "tag", "mediaType", "season",
                      "episodeType", "sourceKind", "availability", "metadata", "artwork"):
            self.assertIn(f"val {field}:", filters)
        for token in ("suspend fun randomCatalogAnime(", "FROM anime a WHERE",
                      "EXISTS (SELECT 1 FROM episodes e0 WHERE e0.anime_id=a.id)",
                      "EXISTS (SELECT 1 FROM anime_genres ag JOIN genres g",
                      "COALESCE(TRIM(a.user_tags),'[]') IN ('[]','')", "a.metadata_source",
                      "ar.artwork_type='poster'", "__episode_search", "tokens.all",
                      "random.nextLong(countWithoutExcluded)", "JOIN folders f ON f.path=e.source_folder",
                      '"Nome A-Z"', '"Nome Z-A"'):
            self.assertIn(token, source)

    def test_gacha_is_read_only_and_uses_existing_episode_query(self):
        source = REPOSITORY.read_text(encoding="utf-8")
        method = source.split("suspend fun randomCatalogAnime(", 1)[1].split(
            "/** Same local anime projection", 1
        )[0]
        for forbidden in ("INSERT INTO", "UPDATE anime", "DELETE FROM", "db.execSQL"):
            self.assertNotIn(forbidden, method)
        service = SERVICE.read_text(encoding="utf-8")
        for token in ("repository.randomCatalogAnime(", "repository.marathonEpisodes(anime.id)",
                      "ReiAnixLibraryDiscovery.formatDuration", "episodeDrawRequested"):
            self.assertIn(token, service)
        self.assertIn("ReiAnixGachaService(sqliteLibraryRepository)", DEPENDENCIES.read_text(encoding="utf-8"))
        self.assertIn("drawNativeGacha(", VIEW_MODEL.read_text(encoding="utf-8"))

    def test_report_explains_remaining_ui_cutover(self):
        document = DOC.read_text(encoding="utf-8")
        for token in ("core/library_service.py:gacha_pick", "core/library_store.py:random_catalog_item",
                      "query", "state", "genre", "sort", "tag", "media_type", "season",
                      "episode_type", "availability", "metadata", "artwork", "schema v29",
                      "Kotlin/JVM", "PARCIAL", "interface Compose", "Python/Flet"):
            self.assertIn(token, document)


if __name__ == "__main__":
    unittest.main()
