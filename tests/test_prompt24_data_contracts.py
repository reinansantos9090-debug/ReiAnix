from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
CONTRACT_VALUES = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/contract/ReiAnixContractValues.kt"
LIBRARY_MAPPERS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/mapper/LibraryUiMappers.kt"
SQLITE_MODELS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteModels.kt"
SNAPSHOT_CODEC = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibrarySnapshotCodec.kt"
SETTINGS_CODEC = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsSnapshotCodec.kt"
JSON_CONTRACT = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/contract/ReiAnixJsonContract.kt"
ANILIST_CLIENT = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/anilist/ReiAnixAniListClient.kt"
NATIVE_MAILBOX = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeMailbox.kt"
BACKUP_SERVICE = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/backup/ReiAnixBackupService.kt"
SQLITE_DATABASE = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteDatabase.kt"
PYTHON_STORE = ROOT / "core/library_store.py"
LIBRARY_BRIDGE = ROOT / "core/compose_library_bridge.py"
KOTLIN_JVM_WORKFLOW = ROOT / ".github/workflows/kotlin_jvm_tests.yml"


class Prompt24DataContractTests(unittest.TestCase):
    def test_shared_scalar_contract_rejects_lossy_numeric_and_boolean_conversions(self):
        source = CONTRACT_VALUES.read_text(encoding="utf-8")
        self.assertIn("decimal.longValueExact()", source)
        self.assertIn("Int.MIN_VALUE", source)
        self.assertIn("Int.MAX_VALUE", source)
        self.assertIn("!result.isFinite()", source)
        self.assertIn("0L -> false", source)
        self.assertIn("1L -> true", source)
        self.assertIn("Invalid contract value", source)

    def test_library_projection_uses_shared_strict_conversions(self):
        source = LIBRARY_MAPPERS.read_text(encoding="utf-8")
        self.assertIn("ReiAnixContractValues.longOrNull(this[key], key)", source)
        self.assertIn("ReiAnixContractValues.intOrNull(this[key], key)", source)
        self.assertIn("ReiAnixContractValues.doubleOrNull(this[key], key)", source)
        self.assertIn("ReiAnixContractValues.booleanOrNull(this[key], key)", source)
        self.assertIn("IDs must be positive", source)
        self.assertIn("expected an object", source)
        self.assertIn("expected an array", source)
        self.assertNotIn("is Number -> value.toLong()", source)
        self.assertNotIn("is Number -> value.toInt()", source)

    def test_snapshot_codec_rejects_malformed_typed_fields(self):
        source = SNAPSHOT_CODEC.read_text(encoding="utf-8")
        self.assertIn('root.intRequired("schemaVersion")', source)
        self.assertIn('root.longRequired("revision")', source)
        self.assertIn("booleanOrDefault", source)
        self.assertIn("arrayOrDefault", source)
        self.assertIn("objectOrNull", source)
        self.assertIn("page_size", source)
        self.assertIn("pagination values must be non-negative", source)
        self.assertIn("Malformed anime", source)
        self.assertIn("Malformed continue-watching item", source)

    def test_sqlite_scalar_adapters_preserve_nulls_and_reject_invalid_persisted_values(self):
        source = SQLITE_MODELS.read_text(encoding="utf-8")
        self.assertIn("ReiAnixContractValues.intOrNull(optionalLong(column), column)", source)
        self.assertIn("ReiAnixContractValues.booleanOrNull(optionalLong(column), column)", source)
        self.assertIn("value.isFinite()", source)
        self.assertIn("if (index < 0 || isNull(index)) return null", source)

        database = SQLITE_DATABASE.read_text(encoding="utf-8")
        python_store = PYTHON_STORE.read_text(encoding="utf-8")
        self.assertIn('DATABASE_FILE_NAME = "library.sqlite3"', database)
        self.assertIn("native-sqlite-contract-v29.json", database)
        self.assertIn("SCHEMA_VERSION = 29", python_store)
        self.assertNotIn("RoomDatabase", source)

    def test_anilist_client_validates_external_contract_types_and_ranges(self):
        source = ANILIST_CLIENT.read_text(encoding="utf-8")
        self.assertIn('media.intOrNull("id")', source)
        self.assertIn('media.nonNegativeIntOrNull("episodes")', source)
        self.assertIn('it in 0..100', source)
        self.assertIn("Malformed AniList field", source)
        self.assertIn("Malformed GraphQL errors array", source)
        self.assertIn("AniListError.InvalidResponse", source)

    def test_native_mailbox_rejects_lossy_event_envelope_coercions(self):
        source = NATIVE_MAILBOX.read_text(encoding="utf-8")
        self.assertIn('event.longOrDefault("createdAt", 0L)', source)
        self.assertIn('payload?.booleanOrDefault("granted", false)', source)
        self.assertIn('event.stringOrDefault("type", "unknown")', source)
        self.assertIn('payload.stringOrNull("requestId")', source)
        self.assertNotIn('event.optLong("createdAt", 0L)', source)
        self.assertNotIn('payload?.optBoolean("granted", false)', source)

    def test_backup_integrity_validation_rejects_wrong_field_types(self):
        source = BACKUP_SERVICE.read_text(encoding="utf-8")
        self.assertIn('manifest.intRequired("format_version")', source)
        self.assertIn('manifest.intRequired("schema_version")', source)
        self.assertIn('integrity.arrayOrDefault("optional_entries")', source)
        self.assertIn('expected.longRequired("size")', source)
        self.assertIn('expected.stringOrNull("sha256")', source)
        self.assertNotIn('manifest.optInt("schema_version", -1)', source)

    def test_transport_boundary_keeps_its_real_schema_and_timestamp_units_documented_in_code(self):
        bridge = LIBRARY_BRIDGE.read_text(encoding="utf-8")
        self.assertIn("SCHEMA_VERSION = 1", bridge)
        self.assertIn('int(time.time() * 1000)', bridge)
        codec = SNAPSHOT_CODEC.read_text(encoding="utf-8")
        self.assertIn('"schemaVersion"', codec)
        self.assertIn('"revision"', codec)

    def test_kotlin_jvm_test_workflow_remains_manual(self):
        workflow = KOTLIN_JVM_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", workflow)
        self.assertNotIn("\npull_request:", workflow)
        self.assertNotIn("\npush:", workflow)


    def test_settings_snapshot_uses_shared_typed_json_contract(self):
        source = SETTINGS_CODEC.read_text(encoding="utf-8")
        shared = JSON_CONTRACT.read_text(encoding="utf-8")
        self.assertIn('root.intRequired("schemaVersion")', source)
        self.assertIn('root.longRequired("revision")', source)
        self.assertIn("booleanOrDefault", source)
        self.assertIn("intOrDefault", source)
        self.assertIn("cannot be null; omit an absent setting instead", source)
        self.assertIn("JSONObject.stringOrNull", shared)
        self.assertIn("expected a JSON Boolean", shared)
        self.assertIn("null is not valid for this array field", shared)


if __name__ == "__main__":
    unittest.main()
