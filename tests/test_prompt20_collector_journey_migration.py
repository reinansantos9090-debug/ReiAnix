import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local"
SQL_REPOSITORY = ANDROID / "data/sqlite/ReiAnixSqliteLibraryRepository.kt"
SQL_MODELS = ANDROID / "data/sqlite/ReiAnixSqliteModels.kt"
JOURNEY_SERVICE = ANDROID / "data/collector/ReiAnixCollectorJourneyService.kt"
DEPENDENCIES = ANDROID / "ReiAnixAppDependencies.kt"
VIEW_MODEL = ANDROID / "viewmodel/ReiAnixCollectorJourneyViewModel.kt"
COMPOSE_SCREEN = ANDROID / "ui/collector/ReiAnixCollectorJourneyScreen.kt"
NAVIGATION = ANDROID / "ui/navigation/ReiAnixNavigation.kt"
COMPOSE_HOST = ANDROID / "ui/host/ReiAnixComposeLibraryHost.kt"
MAIN = ROOT / "main.py"
DOCUMENTATION = ROOT / "docs/KOTLIN_COLLECTOR_JOURNEY_MIGRATION.md"
KOTLIN_JVM_TEST = ROOT / "android/app/src/test/java/com/reiflix/reiflix_local/data/collector/ReiAnixCollectorJourneyCalculatorTest.kt"


class CollectorJourneyKotlinMigrationContractTests(unittest.TestCase):
    def test_journey_calculates_from_canonical_episodes_and_anime_metadata(self):
        repository = SQL_REPOSITORY.read_text(encoding="utf-8")
        models = SQL_MODELS.read_text(encoding="utf-8")
        service = JOURNEY_SERVICE.read_text(encoding="utf-8")
        self.assertIn("suspend fun collectorSnapshot()", repository)
        self.assertIn("FROM episodes e", repository)
        self.assertIn("JOIN anime a ON a.id = e.anime_id", repository)
        snapshot = repository.split("suspend fun collectorSnapshot()", 1)[1].split(
            "suspend fun listRecentlyPlayed(", 1
        )[0]
        self.assertNotIn("WHERE e.missing = 0", snapshot)
        self.assertIn("val libraryTitleCount = rows.map { it.animeId }.toSet().size", service)
        self.assertIn("data class ReiAnixCollectorEpisodeRow", models)
        self.assertIn("ReiAnixCollectorJourneyCalculator", service)
        self.assertIn("LEVEL_EPISODES = 10", service)
        self.assertIn(">= 0.90", service)
        self.assertIn(">= 0.05", service)

    def test_only_explicit_active_title_is_persisted_in_canonical_preferences(self):
        service = JOURNEY_SERVICE.read_text(encoding="utf-8")
        dependencies = DEPENDENCIES.read_text(encoding="utf-8")
        self.assertIn('const val ACTIVE_TITLE_PREFERENCE = "collector.active_title"', service)
        self.assertIn("settings.setPreference(ACTIVE_TITLE_PREFERENCE", service)
        self.assertIn("settings.removePreference(ACTIVE_TITLE_PREFERENCE)", service)
        self.assertNotIn("CREATE TABLE", service)
        self.assertNotIn("gamification.db", service)
        self.assertNotIn("achievements.db", service)
        self.assertIn("sqliteLibraryRepository", dependencies)
        self.assertIn("sqliteSettingsRepository", dependencies)
        self.assertIn("collectorJourneyService", dependencies)

    def test_compose_route_runs_native_actions_and_does_not_delegate_to_python(self):
        navigation = NAVIGATION.read_text(encoding="utf-8")
        host = COMPOSE_HOST.read_text(encoding="utf-8")
        view_model = VIEW_MODEL.read_text(encoding="utf-8")
        screen = COMPOSE_SCREEN.read_text(encoding="utf-8")
        main = MAIN.read_text(encoding="utf-8")
        self.assertIn('const val COLLECTOR = "collector"', navigation)
        self.assertIn("composable(ReiAnixRoutes.COLLECTOR) { collector() }", navigation)
        self.assertIn("navController.navigateToCollector()", host)
        self.assertIn("service.setActiveTitle(titleId)", view_model)
        self.assertIn("collectAsStateWithLifecycle()", screen)
        self.assertIn("'collector'}", main)
        self.assertIn("if compose_primary_ui:", main)
        self.assertIn("control = ft.Container(expand=True)", main)

    def test_manual_kotlin_jvm_regressions_cover_domain_thresholds(self):
        source = KOTLIN_JVM_TEST.read_text(encoding="utf-8")
        self.assertIn("completionThresholdIsNinetyPercent", source)
        self.assertIn("activityThresholdIsFivePercent", source)
        self.assertIn("onlyFullyCompletedAnimeUnlocksAnimeTitle", source)
        self.assertIn("genreAchievementUsesCompletedTitles", source)

    def test_documentation_records_legacy_parity_and_runtime_boundary(self):
        document = DOCUMENTATION.read_text(encoding="utf-8")
        self.assertIn("90%", document)
        self.assertIn("5%", document)
        self.assertIn("collector.active_title", document)
        self.assertIn("schema SQLite v29", document)
        self.assertIn("fallback", document.lower())


if __name__ == "__main__":
    unittest.main()
