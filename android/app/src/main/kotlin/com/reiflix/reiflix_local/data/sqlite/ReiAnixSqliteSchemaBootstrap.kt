package com.reiflix.reiflix_local.data.sqlite

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Initializes and advances the single canonical LibraryStore-compatible SQLite schema.
 *
 * DDL comes from the build-generated runtime contract, which is extracted from a
 * temporary database initialized by the repository's real LibraryStore. This keeps
 * native bootstrap aligned with schema v29 without maintaining a second handwritten
 * schema or creating a second database. Existing rows are never copied, deleted or
 * rewritten as part of schema repair; only the same idempotent state normalization
 * already performed by LibraryStore is applied after structural changes.
 */
internal class ReiAnixSqliteSchemaBootstrap(
    context: Context,
    private val databaseBoundary: ReiAnixSqliteDatabase,
) {
    private val appContext = context.applicationContext

    internal enum class Outcome {
        ALREADY_CURRENT,
        CREATED,
        MIGRATED,
    }

    /**
     * Call only after startup has ruled out interrupted database promotion/recovery
     * evidence. Damaged files and unsupported future contracts fail closed; they are
     * never silently replaced with an empty catalog.
     */
    fun ensureCurrentSchema(): Outcome = synchronized(PROCESS_LOCK) {
        val canonicalFile = databaseBoundary.canonicalDatabaseFile
        val existedBefore = canonicalFile.exists()
        if (existedBefore) {
            check(canonicalFile.isFile && canonicalFile.length() > 0L) {
                "The canonical SQLite path exists but is not a valid non-empty file."
            }
            val snapshot = databaseBoundary.inspectSchema()
            ensureSafeToMigrate(snapshot)
            if (snapshot.supportsCurrentContract) return Outcome.ALREADY_CURRENT
            if (snapshot.schemaVersion == ReiAnixSqliteDatabase.EXPECTED_SCHEMA_VERSION) {
                throw ReiAnixSqliteSchemaMismatchException(snapshot.incompatibilities)
            }
        }

        val contract = databaseBoundary.schemaContractForBootstrap()
        val expectedVersion = contract.getInt("schema_version")
        check(expectedVersion == ReiAnixSqliteDatabase.EXPECTED_SCHEMA_VERSION) {
            "The generated SQLite contract and native bootstrap version disagree."
        }
        val expectedUserVersion = contract.getInt("sqlite_user_version")
        val bootstrapSql = contract.getJSONObject("bootstrap_sql")
        val tablesSql = bootstrapSql.getJSONArray("tables")
        val indexesSql = bootstrapSql.getJSONArray("indexes")
        val triggersSql = bootstrapSql.getJSONArray("triggers")
        var outcome = if (existedBefore) Outcome.MIGRATED else Outcome.CREATED

        databaseBoundary.withSchemaBootstrap(createIfMissing = !existedBefore) { connection ->
            connection.beginTransactionNonExclusive()
            try {
                val current = databaseBoundary.inspectSchemaForBootstrap(connection)
                ensureSafeToMigrate(current)
                if (current.supportsCurrentContract) {
                    outcome = Outcome.ALREADY_CURRENT
                } else {
                    if (current.schemaVersion == expectedVersion) {
                        throw ReiAnixSqliteSchemaMismatchException(current.incompatibilities)
                    }

                    val existingTables = current.tables
                    if (existingTables.isEmpty() && existedBefore) {
                        // A valid but empty SQLite file has no user rows to migrate.
                        // It is safe to initialize its schema in place, at this path.
                    }

                    for (index in 0 until tablesSql.length()) {
                        connection.execSQL(asIdempotentCreate(tablesSql.getString(index), ObjectKind.TABLE))
                    }

                    addMissingColumns(connection, contract)

                    // Rebuild only explicit indexes, never tables or rows. This mirrors
                    // LibraryStore's idx_artwork_key replacement and ensures old index
                    // definitions cannot survive under a canonical name. Transaction
                    // rollback restores these indexes if any later validation fails.
                    for (index in 0 until indexesSql.length()) {
                        val sql = indexesSql.getString(index)
                        val name = createdObjectName(sql, ObjectKind.INDEX)
                        connection.execSQL("DROP INDEX IF EXISTS ${quoteIdentifier(name)}")
                    }
                    for (index in 0 until triggersSql.length()) {
                        val sql = triggersSql.getString(index)
                        val name = createdObjectName(sql, ObjectKind.TRIGGER)
                        connection.execSQL("DROP TRIGGER IF EXISTS ${quoteIdentifier(name)}")
                    }
                    for (index in 0 until indexesSql.length()) {
                        connection.execSQL(asIdempotentCreate(indexesSql.getString(index), ObjectKind.INDEX))
                    }
                    for (index in 0 until triggersSql.length()) {
                        connection.execSQL(asIdempotentCreate(triggersSql.getString(index), ObjectKind.TRIGGER))
                    }

                    // Keep the metadata-state normalization in parity with the existing
                    // LibraryStore initializer without overwriting personal/user fields.
                    connection.execSQL(
                        """
                        UPDATE anime
                        SET anilist_match_status = CASE
                            WHEN metadata_status = 'manual' AND anilist_id IS NOT NULL THEN 'manual'
                            WHEN anilist_id IS NOT NULL THEN 'matched'
                            ELSE COALESCE(NULLIF(anilist_match_status, ''), 'unmatched')
                        END
                        WHERE anilist_id IS NOT NULL OR anilist_match_status IS NULL
                        """.trimIndent(),
                    )
                    connection.execSQL(
                        "INSERT OR IGNORE INTO schema_migrations(version, applied_at) VALUES (?, ?)",
                        arrayOf(expectedVersion, System.currentTimeMillis() / 1000.0),
                    )

                    val migrated = databaseBoundary.inspectSchemaForBootstrap(connection)
                    if (!migrated.supportsCurrentContract) {
                        throw ReiAnixSqliteSchemaMismatchException(migrated.incompatibilities)
                    }
                    check(migrated.sqliteUserVersion == expectedUserVersion) {
                        "Native schema bootstrap unexpectedly changed PRAGMA user_version."
                    }
                }
                connection.setTransactionSuccessful()
            } finally {
                connection.endTransaction()
            }
        }
        outcome
    }

    private fun ensureSafeToMigrate(snapshot: ReiAnixSqliteSchemaSnapshot) {
        check(snapshot.sqliteUserVersion == 0) {
            "The canonical SQLite PRAGMA user_version is not the supported v29 contract."
        }
        check(snapshot.integrityErrors.isEmpty()) {
            "SQLite integrity check failed; the existing file was preserved for Recovery Mode."
        }
        check(snapshot.foreignKeyViolations.isEmpty()) {
            "SQLite foreign-key validation failed; the existing file was preserved for Recovery Mode."
        }
        val version = snapshot.schemaVersion
        check(version == null || version <= ReiAnixSqliteDatabase.EXPECTED_SCHEMA_VERSION) {
            "The canonical SQLite database is newer than this application and will not be downgraded."
        }
    }

    private fun addMissingColumns(connection: SQLiteDatabase, contract: JSONObject) {
        val expectedTables = contract.getJSONArray("tables")
        val allColumns = contract.getJSONObject("columns")
        for (tableIndex in 0 until expectedTables.length()) {
            val table = expectedTables.getString(tableIndex)
            val actualColumns = connection.rawQuery(
                "PRAGMA table_xinfo(${quoteIdentifier(table)})",
                null,
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(1))
                }
            }
            val expected = allColumns.getJSONArray(table)
            for (columnIndex in 0 until expected.length()) {
                val column = expected.getJSONObject(columnIndex)
                val name = column.getString("name")
                if (name in actualColumns) continue

                val type = column.optString("type", "").trim()
                val primaryKeyPosition = column.optInt("primary_key_position", 0)
                val hidden = column.optInt("hidden", 0)
                val notNull = column.optBoolean("not_null", false)
                val hasDefault = column.has("default") && !column.isNull("default")
                check(type.isNotBlank() && primaryKeyPosition == 0 && hidden == 0) {
                    "SQLite column $table.$name cannot be safely added without rebuilding its table."
                }
                check(!notNull || hasDefault) {
                    "SQLite column $table.$name is NOT NULL without a default and cannot be added safely."
                }

                val declaration = buildString {
                    append(type)
                    if (notNull) append(" NOT NULL")
                    if (hasDefault) append(" DEFAULT ").append(column.get("default").toString())
                }
                connection.execSQL(
                    "ALTER TABLE ${quoteIdentifier(table)} ADD COLUMN ${quoteIdentifier(name)} $declaration",
                )
            }
        }
    }

    private enum class ObjectKind { TABLE, INDEX, TRIGGER }

    private fun asIdempotentCreate(sql: String, kind: ObjectKind): String {
        val pattern: Regex
        val replacement: String
        when (kind) {
            ObjectKind.TABLE -> {
                pattern = Regex("^\\s*CREATE\\s+TABLE\\s+", RegexOption.IGNORE_CASE)
                replacement = "CREATE TABLE IF NOT EXISTS "
            }
            ObjectKind.INDEX -> {
                pattern = Regex(
                    "^\\s*CREATE\\s+(UNIQUE\\s+)?INDEX\\s+",
                    RegexOption.IGNORE_CASE,
                )
                replacement = ""
            }
            ObjectKind.TRIGGER -> {
                pattern = Regex("^\\s*CREATE\\s+TRIGGER\\s+", RegexOption.IGNORE_CASE)
                replacement = "CREATE TRIGGER IF NOT EXISTS "
            }
        }
        if (kind == ObjectKind.INDEX) {
            val match = pattern.find(sql)
                ?: throw IllegalArgumentException("Unexpected index DDL in canonical SQLite contract.")
            val uniquePrefix = match.groupValues[1]
            val prefix = "CREATE ${uniquePrefix}INDEX IF NOT EXISTS "
            return prefix + sql.substring(match.range.last + 1)
        }
        check(pattern.containsMatchIn(sql)) {
            "Unexpected ${kind.name.lowercase()} DDL in canonical SQLite contract."
        }
        return pattern.replaceFirst(sql, replacement)
    }

    private fun createdObjectName(sql: String, kind: ObjectKind): String {
        val regex = when (kind) {
            ObjectKind.TABLE -> Regex(
                "^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([A-Za-z0-9_]+)",
                RegexOption.IGNORE_CASE,
            )
            ObjectKind.INDEX -> Regex(
                "^\\s*CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([A-Za-z0-9_]+)",
                RegexOption.IGNORE_CASE,
            )
            ObjectKind.TRIGGER -> Regex(
                "^\\s*CREATE\\s+TRIGGER\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([A-Za-z0-9_]+)",
                RegexOption.IGNORE_CASE,
            )
        }
        return regex.find(sql)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Unable to read object name from canonical SQLite DDL.")
    }

    private fun quoteIdentifier(value: String): String =
        "\"" + value.replace("\"", "\"\"") + "\""

    private companion object {
        val PROCESS_LOCK = Any()
    }
}
