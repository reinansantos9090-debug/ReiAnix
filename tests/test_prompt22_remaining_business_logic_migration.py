from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
DISCOVERY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibraryDiscovery.kt"
REPOSITORY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryRepository.kt"
MODELS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteModels.kt"
DEPENDENCIES = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ReiAnixAppDependencies.kt"
DOC = ROOT / "docs/KOTLIN_REMAINING_BUSINESS_LOGIC_MIGRATION.md"


class Prompt22RemainingBusinessLogicMigrationContract(unittest.TestCase):
    def test_discovery_rules_are_implemented_in_kotlin_without_a_second_store(self):
        source = DISCOVERY.read_text(encoding="utf-8")
        for token in (
            "fun timelineGroups(",
            "fun durationAnomalies(",
            "fun marathonPlan(",
            "fun formatDuration(",
            "0.90",
            "Duração muito acima da média",
            "Duração muito abaixo da média",
            "Ano desconhecido",
        ):
            self.assertIn(token, source)
        self.assertNotIn("ProcessBuilder", source)
        self.assertNotIn("Runtime.getRuntime", source)
        self.assertNotIn("SQLiteOpenHelper(", source)

    def test_discovery_uses_the_existing_repository_and_shared_graph(self):
        repository = REPOSITORY.read_text(encoding="utf-8")
        models = MODELS.read_text(encoding="utf-8")
        dependencies = DEPENDENCIES.read_text(encoding="utf-8")
        for token in (
            "suspend fun timelineItems(",
            "suspend fun durationObservations(",
            "suspend fun marathonEpisodes(",
        ):
            self.assertIn(token, repository)
        self.assertIn("FROM episodes e", repository)
        self.assertIn("JOIN anime a", repository)
        self.assertIn("data class ReiAnixTimelineSourceRecord", models)
        self.assertIn("data class ReiAnixDurationObservationRecord", models)
        self.assertIn("ReiAnixLibraryDiscoveryService(sqliteLibraryRepository)", dependencies)
        view_model = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt").read_text(encoding="utf-8")
        for token in ("queryNativeTimelineGroups(", "queryNativeDurationAnomalies(", "queryNativeMarathonPlan("):
            self.assertIn(token, view_model)

    def test_migration_report_has_traceability_and_describes_remaining_runtime(self):
        document = DOC.read_text(encoding="utf-8")
        for token in (
            "| Módulo/função Python |",
            "core/library_discovery.py",
            "core/organizer_ai.py",
            "core/library_parser.py",
            "core/media_identity.py",
            "core/compose_library_bridge.py",
            "scripts/prepare_flet_template.py",
            "runtime Flet",
            "já migrado e validado",
            "migrado nesta etapa",
            "parcialmente migrado",
            "ainda dependente de Python",
            "ferramenta de desenvolvimento",
            "schema v29",
            "gacha_pick",
            "random_catalog_item",
        ):
            self.assertIn(token, document)


if __name__ == "__main__":
    unittest.main()
