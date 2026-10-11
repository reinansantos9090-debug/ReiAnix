from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
STORE = ROOT / "core/library_store.py"
DATABASE = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteDatabase.kt"
LIBRARY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryRepository.kt"
SETTINGS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteSettingsRepository.kt"
MODELS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteModels.kt"


class NativeSqlitePersistenceContractTests(unittest.TestCase):
    def test_native_layer_targets_the_existing_schema_and_path(self):
        python_store = STORE.read_text(encoding="utf-8")
        database = DATABASE.read_text(encoding="utf-8")

        self.assertIn("SCHEMA_VERSION = 29", python_store)
        self.assertIn('File(File(appContext.filesDir, "data"), DATABASE_FILE_NAME)', database)
        self.assertIn('DATABASE_FILE_NAME = "library.sqlite3"', database)
        self.assertIn("schema_migrations", database)
        self.assertIn("PRAGMA user_version", database)
        self.assertIn("PRAGMA quick_check", database)

    def test_opening_never_creates_or_rebuilds_the_canonical_database(self):
        database = DATABASE.read_text(encoding="utf-8")

        self.assertIn("SQLiteDatabase.OPEN_READONLY", database)
        self.assertIn("SQLiteDatabase.OPEN_READWRITE", database)
        self.assertNotIn("CREATE_IF_NECESSARY", database)
        self.assertNotIn("SQLiteOpenHelper", database)
        self.assertNotIn("CREATE TABLE", database.upper())
        self.assertNotIn("DROP TABLE", database.upper())
        self.assertNotIn("deleteDatabase(", database)
        self.assertIn("beginTransactionNonExclusive", database)
        self.assertIn("setTransactionSuccessful", database)
        self.assertIn("endTransaction", database)

    def test_native_library_repository_uses_canonical_tables_and_bound_values(self):
        library = LIBRARY.read_text(encoding="utf-8")

        for table in ("anime", "episodes", "folders", "artwork"):
            self.assertIn("FROM " + table, library)
        self.assertIn('"id = ?"', library)
        self.assertIn("arrayOf(id.toString())", library)
        self.assertIn('connection.update(', library)
        self.assertIn('"episodes"', library)
        self.assertIn("database.transaction", library)
        self.assertIn("withContext(Dispatchers.IO)", library)


    def test_metadata_patch_updates_catalog_fields_without_overwriting_user_state(self):
        library = LIBRARY.read_text(encoding="utf-8")
        method_start = library.index("suspend fun updateAnimeMetadata(")
        method_end = library.index("suspend fun getEpisode(", method_start)
        method = library[method_start:method_end]

        self.assertIn('database.transaction', method)
        self.assertIn('"metadata_updated_at"', method)
        self.assertIn('SELECT * FROM anime WHERE id = ?', method)
        self.assertIn('optionalString("metadata_manual_fields")', method)
        self.assertIn('if ("title" !in manualFields)', method)
        self.assertNotIn('values.put("metadata_source"', method)
        self.assertNotIn('values.put("anilist_id"', method)
        self.assertIn('patch.coverCache?.takeIf { it.isNotBlank() }', method)
        self.assertIn('patch.genresJson?.takeIf { it.isNotBlank() && it.trim() != "[]" }', method)
        for user_owned_column in ('"favorite"', '"is_pinned"', '"personal_note"', '"user_tags"'):
            self.assertNotIn(user_owned_column, method)

    def test_settings_are_parameterized_and_transactional(self):
        settings = SETTINGS.read_text(encoding="utf-8")

        self.assertIn("FROM preferences", settings)
        self.assertIn('"key = ?"', settings)
        self.assertIn("connection.update(", settings)
        self.assertIn("connection.insertOrThrow(", settings)
        self.assertIn("connection.delete(", settings)
        self.assertIn("database.transaction", settings)
        self.assertIn("withContext(Dispatchers.IO)", settings)

    def test_models_keep_nullable_sqlite_values_and_existing_identity(self):
        models = MODELS.read_text(encoding="utf-8")
        library = LIBRARY.read_text(encoding="utf-8")

        self.assertIn("val anilistId: Long?", models)
        self.assertIn("val number: Double?", models)
        self.assertIn("val mediaIdentity: String?", models)
        self.assertIn("optionalString", library)
        self.assertIn("optionalDouble", library)
        self.assertIn("ORDER BY season ASC, number ASC, id ASC", library)


if __name__ == "__main__":
    unittest.main()
