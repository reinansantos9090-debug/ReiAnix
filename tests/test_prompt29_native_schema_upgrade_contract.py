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

    def test_upgrade_refuses_non_additive_column_contracts(self):
        upgrader = UPGRADER.read_text(encoding="utf-8")
        self.assertIn("isSafeAdditiveColumn", upgrader)
        self.assertIn("UNSAFE_ADDITION", upgrader)
        self.assertIn("UNSAFE_DEFAULT", upgrader)
        self.assertIn("Canonical SQLite database lacks core tables", upgrader)
        self.assertIn("Canonical SQLite schema version is unknown or newer than this app.", upgrader)


if __name__ == "__main__":
    unittest.main()
