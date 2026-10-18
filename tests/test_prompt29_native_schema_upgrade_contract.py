import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite"
UPGRADER = KOTLIN / "ReiAnixSqliteSchemaUpgrader.kt"
DATABASE = KOTLIN / "ReiAnixSqliteDatabase.kt"
BOOTSTRAP = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ReiAnixAppBootstrap.kt"


class Prompt29NativeSchemaUpgradeContractTests(unittest.TestCase):
    def test_existing_database_uses_native_upgrade_before_acceptance_decision(self):
        bootstrap = BOOTSTRAP.read_text(encoding="utf-8")
        upgrade_at = bootstrap.index(
            "ReiAnixSqliteSchemaUpgrader(appContext, dependencies.sqliteDatabase).upgradeIfNeeded()"
        )
        schema_check_at = bootstrap.index(
            "val schemaCompatible = dependencies.sqliteDatabase.inspectSchema().supportsCurrentContract",
            upgrade_at,
        )
        self.assertLess(upgrade_at, schema_check_at)
        self.assertIn("ReiAnixSqliteSchemaUpgrader", bootstrap)
        self.assertIn("Recovery Mode remains required", bootstrap)

    def test_upgrader_is_additive_transactional_and_fails_closed(self):
        upgrader = UPGRADER.read_text(encoding="utf-8")
        database = DATABASE.read_text(encoding="utf-8")
        for token in (
            "SQLiteDatabase.OPEN_READWRITE",
            "PRAGMA quick_check",
            "schema_migrations",
            "REQUIRED_EXISTING_TABLES",
            "ALTER TABLE",
            "beginTransactionNonExclusive",
            "setTransactionSuccessful",
            "endTransaction",
            "inspectSchema(connection)",
            "foreign_key_check",
            "idx_artwork_key",
            "PRAGMA busy_timeout=10000",
        ):
            self.assertIn(token, upgrader)
        self.assertNotIn("CREATE_IF_NECESSARY", upgrader)
        self.assertNotIn("DROP TABLE", upgrader.upper())
        self.assertNotIn("DELETE FROM", upgrader.upper())
        self.assertIn("internal fun inspectSchema(database: SQLiteDatabase)", database)

    def test_schema_version_and_core_source_of_truth_remain_unchanged(self):
        upgrader = UPGRADER.read_text(encoding="utf-8")
        database = DATABASE.read_text(encoding="utf-8")
        store = (ROOT / "core/library_store.py").read_text(encoding="utf-8")
        self.assertIn("schemaVersion == ReiAnixSqliteDatabase.EXPECTED_SCHEMA_VERSION", upgrader)
        self.assertIn("EXPECTED_SCHEMA_VERSION = 29", database)
        self.assertIn("SCHEMA_VERSION = 29", store)
        self.assertIn('DATABASE_FILE_NAME = "library.sqlite3"', database)

    def test_compose_domain_and_storage_commands_are_emitted_by_native_owners(self):
        repository = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibraryRepository.kt").read_text(encoding="utf-8")
        codec = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibraryCommandCodec.kt").read_text(encoding="utf-8")
        view_model = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt").read_text(encoding="utf-8")
        main = (ROOT / "main.py").read_text(encoding="utf-8")

        self.assertIn("consumptionService.setWatched(episodeId, watched)", view_model)
        self.assertIn("personalListService.toggle(animeId)", view_model)
        self.assertIn("fun selectSafTree()", view_model)
        self.assertIn('createNativeStorageActionIntent(appContext, "release_tree", normalized)', view_model)

        for action in ("set_watched", "select_saf", "dismiss_storage_onboarding", "remove_saf"):
            self.assertNotIn('"' + action + '"', codec)
        for method in ("fun setEpisodeWatched(", "fun selectSafTree(", "fun dismissStorageOnboarding(", "fun removeSafTree("):
            self.assertNotIn(method, repository)
        self.assertNotIn("fun dismissStorageOnboarding()", view_model)

        # Current Compose page membership is queried directly from canonical SQLite;
        # no second IPC-backed page producer/state should remain in the compatibility adapter.
        self.assertNotIn("fun loadLibraryPage(", repository)
        self.assertNotIn("pagedLibraryState", repository)
        self.assertNotIn("Action.LOAD_LIBRARY_PAGE", repository)
        self.assertNotIn("LOAD_LIBRARY_PAGE", codec)
        self.assertIn("nativeLibraryQueries.searchAnime(", view_model)

        # Keep old receivers until messages durably queued by previous versions can be drained.
        for token in (
            'if action == "set_watched":',
            'elif action == "select_saf":',
            'elif action == "dismiss_storage_onboarding":',
            'elif action == "remove_saf":',
            'elif action == "load_library_page":',
        ):
            self.assertIn(token, main)
        bridge = (ROOT / "core/compose_library_bridge.py").read_text(encoding="utf-8")
        self.assertIn("project_library_page", bridge)


    def test_upgrade_refuses_non_additive_column_contracts(self):
        upgrader = UPGRADER.read_text(encoding="utf-8")
        self.assertIn("isSafeAdditiveColumn", upgrader)
        self.assertIn("UNSAFE_ADDITION", upgrader)
        self.assertIn("UNSAFE_DEFAULT", upgrader)
        self.assertIn("Canonical SQLite database lacks core tables", upgrader)
        self.assertIn("Canonical SQLite schema version is unknown or newer than this app.", upgrader)


if __name__ == "__main__":
    unittest.main()
