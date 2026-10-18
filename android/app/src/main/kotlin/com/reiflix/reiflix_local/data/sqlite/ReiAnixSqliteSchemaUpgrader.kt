package com.reiflix.reiflix_local.data.sqlite

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Performs the schema-v29 additive upgrades previously applied by LibraryStore._init().
 *
 * The canonical database is never replaced. Existing data is upgraded in one SQLite
 * transaction; an unknown, corrupt, or non-additive schema is refused so startup can
 * enter Recovery Mode without silently resetting user data.
 */
internal class ReiAnixSqliteSchemaUpgrader(
    context: Context,
    private val database: ReiAnixSqliteDatabase,
) {
    private val appContext = context.applicationContext

    /**
     * Returns true only when an existing schema needed an additive upgrade.
     * A fresh installation is handled by ReiAnixSqliteBootstrapper instead.
     */
    fun upgradeIfNeeded(): Boolean = database.withExclusiveFileMaintenance { canonicalFile ->
        check(canonicalFile.isFile && canonicalFile.length() > 0L) {
            "Native schema upgrade requires an existing, non-empty canonical database."
        }

        val before = runCatching { database.inspectSchema() }.getOrNull()
        if (before?.supportsCurrentContract == true) return@withExclusiveFileMaintenance false

        upgradeExistingDatabase(canonicalFile)
        true
    }

    private fun upgradeExistingDatabase(canonicalFile: File) {
        val payload = readBootstrapPayload()
        val schemaVersion = payload.getInt("schema_version")
        val expectedUserVersion = payload.getInt("sqlite_user_version")
        check(schemaVersion == ReiAnixSqliteDatabase.EXPECTED_SCHEMA_VERSION) {
            "Native schema upgrade contract version is not supported."
        }

        val expectedTables = payload.getJSONArray("tables").toStringSet()
        check(REQUIRED_EXISTING_TABLES.all { it in expectedTables }) {
            "Canonical upgrade asset omits a required schema table."
        }

        val schemaStatements = payload.getJSONArray("statements").toStringList()
        val tableDefinitions = schemaStatements
            .filter { it.trimStart().startsWith("CREATE TABLE", ignoreCase = true) }
            .map(::parseTableDefinition)
        val tableStatements = tableDefinitions.associate { it.name to it.sql }
        check(tableStatements.keys == expectedTables) {
            "Canonical upgrade table DDL does not match its table inventory."
        }
        val indexStatements = schemaStatements.filter {
            it.trimStart().startsWith("CREATE INDEX", ignoreCase = true) ||
                it.trimStart().startsWith("CREATE UNIQUE INDEX", ignoreCase = true)
        }
        check(indexStatements.isNotEmpty()) { "Canonical upgrade asset has no index DDL." }
        check(tableDefinitions.isNotEmpty()) { "Canonical upgrade asset has no table DDL." }
        val triggerStatements = payload.getJSONArray("triggers").toStringList()

        val connection = SQLiteDatabase.openDatabase(
            canonicalFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            connection.setForeignKeyConstraintsEnabled(true)
            connection.execSQL("PRAGMA busy_timeout=10000")

            val integrityBefore = queryStrings(connection, "PRAGMA quick_check")
            check(integrityBefore == listOf("ok")) {
                "Existing canonical SQLite database failed quick_check before upgrade."
            }

            val existingTables = queryStrings(
                connection,
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
            ).toSet()
            check(REQUIRED_EXISTING_TABLES.all { it in existingTables }) {
                "Canonical SQLite database lacks core tables needed for a safe upgrade."
            }

            val currentVersion = connection.rawQuery(
                "SELECT MAX(version) FROM schema_migrations",
                null,
            ).use { cursor ->
                check(cursor.moveToFirst() && !cursor.isNull(0)) {
                    "Canonical SQLite migration ledger is missing or empty."
                }
                cursor.getInt(0)
            }
            check(currentVersion in 1..schemaVersion) {
                "Canonical SQLite schema version is unknown or newer than this app."
            }

            val currentUserVersion = connection.rawQuery("PRAGMA user_version", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
            check(currentUserVersion == expectedUserVersion) {
                "Canonical SQLite user_version does not match the known legacy contract."
            }

            connection.beginTransactionNonExclusive()
            try {
                // Add any absent non-core tables from the checked-in schema source.
                // Core-table absence is rejected above rather than mistaken for a fresh install.
                tableDefinitions.forEach { definition ->
                    connection.execSQL(createTableIfAbsent(definition.sql))
                }

                // Add only columns whose definitions are SQLite ALTER TABLE-safe.
                // Ambiguous constraints cause rollback and Recovery Mode, never data replacement.
                tableDefinitions.forEach { definition ->
                    val actualColumns = queryStrings(
                        connection,
                        "PRAGMA table_info(" + quoteIdentifier(definition.name) + ")",
                    ).toSet()
                    // PRAGMA table_info returns rows rather than scalar strings; query the names below.
                    val presentColumns = connection.rawQuery(
                        "PRAGMA table_info(" + quoteIdentifier(definition.name) + ")",
                        null,
                    ).use { cursor ->
                        buildSet {
                            while (cursor.moveToNext()) add(cursor.getString(1))
                        }
                    }
                    for (column in definition.columns) {
                        if (column.name in presentColumns) continue
                        check(isSafeAdditiveColumn(column.definition)) {
                            "Schema upgrade refused a non-additive column definition."
                        }
                        connection.execSQL(
                            "ALTER TABLE " + quoteIdentifier(definition.name) +
                                " ADD COLUMN " + quoteIdentifier(column.name) + " " + column.definition,
                        )
                    }
                }

                // LibraryStore intentionally refreshes this derived index when migrating older
                // schema-v29 databases. Recreating an index does not discard catalog rows.
                if (indexStatements.any { indexName(it) == ARTWORK_KEY_INDEX }) {
                    connection.execSQL("DROP INDEX IF EXISTS " + quoteIdentifier(ARTWORK_KEY_INDEX))
                }
                indexStatements.forEach { statement ->
                    connection.execSQL(createIndexIfAbsent(statement))
                }
                triggerStatements.forEach { statement ->
                    check(statement.trimStart().startsWith("CREATE TRIGGER", ignoreCase = true)) {
                        "Canonical schema asset contains an unsupported trigger statement."
                    }
                    connection.execSQL(createTriggerIfAbsent(statement))
                }

                // Match the idempotent metadata normalization performed by LibraryStore._init().
                connection.execSQL(
                    "UPDATE anime SET anilist_match_status = CASE " +
                        "WHEN metadata_status = 'manual' AND anilist_id IS NOT NULL THEN 'manual' " +
                        "WHEN anilist_id IS NOT NULL THEN 'matched' " +
                        "ELSE COALESCE(NULLIF(anilist_match_status, ''), 'unmatched') END " +
                        "WHERE anilist_id IS NOT NULL OR anilist_match_status IS NULL",
                )

                // Preserve the legacy startup contract: close scan runs left unfinished by a
                // prior process, without changing media rows or the scan's recorded discoveries.
                connection.execSQL(
                    "UPDATE scan_runs SET status='interrupted', generation_status='FAILED', " +
                        "cancelled=1, finished_at=? WHERE status='running' AND finished_at IS NULL",
                    arrayOf(System.currentTimeMillis().toDouble() / 1000.0),
                )

                connection.execSQL(
                    "INSERT OR IGNORE INTO schema_migrations(version, applied_at) VALUES (?, ?)",
                    arrayOf(schemaVersion, System.currentTimeMillis().toDouble() / 1000.0),
                )

                val finalSchema = database.inspectSchema(connection)
                check(finalSchema.supportsCurrentContract) {
                    "Additive SQLite upgrade did not satisfy the schema-v29 contract: " +
                        finalSchema.incompatibilities.joinToString("; ")
                }
                val finalIntegrity = queryStrings(connection, "PRAGMA quick_check")
                check(finalIntegrity == listOf("ok")) {
                    "Canonical SQLite quick_check failed after additive upgrade."
                }
                val foreignKeyViolations = queryRows(connection, "PRAGMA foreign_key_check")
                check(foreignKeyViolations.isEmpty()) {
                    "Canonical SQLite foreign-key validation failed after additive upgrade."
                }
                connection.setTransactionSuccessful()
            } finally {
                connection.endTransaction()
            }
        } finally {
            connection.close()
        }

        val verified = database.inspectSchema()
        check(verified.supportsCurrentContract) {
            "Canonical SQLite schema is still incompatible after additive upgrade."
        }
        database.quickCheck()
        Log.i(TAG, "NATIVE_SQLITE_SCHEMA_UPGRADE result=COMPLETED schemaVersion=" + schemaVersion)
    }

    private fun readBootstrapPayload(): JSONObject =
        appContext.assets.open(BOOTSTRAP_ASSET_NAME).bufferedReader(Charsets.UTF_8).use {
            JSONObject(it.readText())
        }

    private fun isSafeAdditiveColumn(definition: String): Boolean {
        val normalized = definition.trim().uppercase(Locale.ROOT)
        if (UNSAFE_ADDITION.containsMatchIn(normalized)) return false
        if (normalized.contains("NOT NULL") && !normalized.contains("DEFAULT")) return false
        if (UNSAFE_DEFAULT.containsMatchIn(normalized)) return false
        return normalized.isNotBlank()
    }

    private fun parseTableDefinition(sql: String): TableDefinition {
        val trimmed = sql.trim()
        val match = TABLE_START.find(trimmed)
            ?: throw IllegalStateException("Canonical SQLite asset has an unrecognized table DDL statement.")
        val name = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }
            ?: throw IllegalStateException("Canonical SQLite asset has an empty table name.")
        val open = match.range.last
        val close = findClosingParenthesis(trimmed, open)
        check(close > open) { "Canonical SQLite table DDL has unbalanced parentheses." }
        val columns = splitTopLevel(trimmed.substring(open + 1, close))
            .mapNotNull(::parseColumnDefinition)
        return TableDefinition(name, trimmed, columns)
    }

    private fun parseColumnDefinition(segment: String): ColumnDefinition? {
        val trimmed = segment.trim()
        if (trimmed.isEmpty()) return null
        val upper = trimmed.uppercase(Locale.ROOT)
        if (TABLE_CONSTRAINT_START.containsMatchIn(upper)) return null

        val match = COLUMN_START.matchEntire(trimmed)
            ?: throw IllegalStateException("Canonical SQLite asset has an unsupported column definition.")
        val name = match.groupValues.drop(1).take(4).firstOrNull { it.isNotBlank() }
            ?: throw IllegalStateException("Canonical SQLite asset has an empty column name.")
        val definition = match.groupValues[5].trim()
        check(definition.isNotBlank()) { "Canonical SQLite column definition has no type or constraint." }
        return ColumnDefinition(name, definition)
    }

    private fun createTableIfAbsent(sql: String): String =
        CREATE_TABLE_PREFIX.replaceFirst(sql.trim(), "CREATE TABLE IF NOT EXISTS ")

    private fun createIndexIfAbsent(sql: String): String =
        CREATE_INDEX_PREFIX.replaceFirst(sql.trim()) { match ->
            val prefix = match.value
            if (prefix.contains("IF NOT EXISTS", ignoreCase = true)) prefix
            else prefix.replace(Regex("\\s+$"), "") + " IF NOT EXISTS "
        }

    private fun createTriggerIfAbsent(sql: String): String =
        CREATE_TRIGGER_PREFIX.replaceFirst(sql.trim()) { match ->
            val prefix = match.value
            if (prefix.contains("IF NOT EXISTS", ignoreCase = true)) prefix
            else prefix.replace(Regex("\\s+$"), "") + " IF NOT EXISTS "
        }

    private fun indexName(sql: String): String? {
        val match = INDEX_NAME.find(sql.trim()) ?: return null
        return match.groupValues.drop(1).firstOrNull { it.isNotBlank() }
    }

    private fun findClosingParenthesis(sql: String, openIndex: Int): Int {
        var depth = 0
        var index = openIndex
        while (index < sql.length) {
            if (isQuotedOrCommentStart(sql, index)) {
                index = skipQuotedOrComment(sql, index)
                continue
            }
            when (sql[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                    if (depth < 0) throw IllegalStateException("Unexpected closing parenthesis in SQLite DDL.")
                }
            }
            index++
        }
        return -1
    }

    private fun splitTopLevel(sql: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        var index = 0
        while (index < sql.length) {
            if (isQuotedOrCommentStart(sql, index)) {
                index = skipQuotedOrComment(sql, index)
                continue
            }
            when (sql[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    check(depth >= 0) { "Unbalanced parentheses in canonical SQLite DDL." }
                }
                ',' -> if (depth == 0) {
                    parts += sql.substring(start, index)
                    start = index + 1
                }
            }
            index++
        }
        check(depth == 0) { "Unbalanced parentheses in canonical SQLite DDL." }
        parts += sql.substring(start)
        return parts.filter { it.isNotBlank() }
    }

    private fun isQuotedOrCommentStart(sql: String, index: Int): Boolean {
        val char = sql[index]
        return char == '\'' || char == '"' || char == '[' ||
            sql.startsWith("--", index) || sql.startsWith("/*", index)
    }

    private fun skipQuotedOrComment(sql: String, start: Int): Int {
        if (sql.startsWith("--", start)) {
            val newline = sql.indexOf('\n', start + 2)
            return if (newline < 0) sql.length else newline + 1
        }
        if (sql.startsWith("/*", start)) {
            val close = sql.indexOf("*/", start + 2)
            return if (close < 0) sql.length else close + 2
        }
        val opening = sql[start]
        val closing = if (opening == '[') ']' else opening
        var index = start + 1
        while (index < sql.length) {
            if (sql[index] == closing) {
                if (opening != '[' && index + 1 < sql.length && sql[index + 1] == closing) {
                    index += 2
                    continue
                }
                return index + 1
            }
            index++
        }
        return index
    }

    private fun queryStrings(connection: SQLiteDatabase, sql: String): List<String> =
        connection.rawQuery(sql, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private fun queryRows(connection: SQLiteDatabase, sql: String): List<String> =
        connection.rawQuery(sql, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(buildString {
                        for (column in 0 until cursor.columnCount) {
                            if (column > 0) append(':')
                            append(if (cursor.isNull(column)) "" else cursor.getString(column))
                        }
                    })
                }
            }
        }

    private fun quoteIdentifier(identifier: String): String =
        "\"" + identifier.replace("\"", "\"\"") + "\""

    private data class TableDefinition(
        val name: String,
        val sql: String,
        val columns: List<ColumnDefinition>,
    )

    private data class ColumnDefinition(val name: String, val definition: String)

    private companion object {
        const val TAG = "ReiAnixSqliteSchemaUpgrader"
        const val BOOTSTRAP_ASSET_NAME = "native-sqlite-bootstrap-v29.json"
        const val ARTWORK_KEY_INDEX = "idx_artwork_key"
        val REQUIRED_EXISTING_TABLES = setOf("folders", "anime", "episodes", "schema_migrations")
        val TABLE_START = Regex(
            "^CREATE\\\\s+TABLE\\\\s+(?:IF\\\\s+NOT\\\\s+EXISTS\\\\s+)?(?:\\\"([^\\\"]+)\\\"|\\\\[([^]]+)\\\\]|([A-Za-z_][A-Za-z0-9_]*))\\\\s*\\\\(",
            RegexOption.IGNORE_CASE,
        )
        val COLUMN_START = Regex(
            "^(?:\\\"((?:\\\"\\\"|[^\\\"])*)\\\"|\\\\[([^]]+)\\\\]|([A-Za-z_][A-Za-z0-9_]*))\\\\s+(.+)$",
            RegexOption.IGNORE_CASE,
        )
        val TABLE_CONSTRAINT_START = Regex(
            "^(?:CONSTRAINT\\\\b|PRIMARY\\\\s+KEY\\\\b|UNIQUE\\\\b|CHECK\\\\b|FOREIGN\\\\s+KEY\\\\b)",
            RegexOption.IGNORE_CASE,
        )
        val UNSAFE_ADDITION = Regex(
            "\\\\b(?:PRIMARY\\\\s+KEY|UNIQUE|REFERENCES|CHECK|GENERATED\\\\s+ALWAYS)\\\\b",
            RegexOption.IGNORE_CASE,
        )
        val UNSAFE_DEFAULT = Regex(
            "\\\\bDEFAULT\\\\s*(?:\\\\(|CURRENT_TIME(?:STAMP)?\\\\b|CURRENT_DATE\\\\b)",
            RegexOption.IGNORE_CASE,
        )
        val CREATE_TABLE_PREFIX = Regex(
            "^CREATE\\\\s+TABLE\\\\s+(?:IF\\\\s+NOT\\\\s+EXISTS\\\\s+)?",
            RegexOption.IGNORE_CASE,
        )
        val CREATE_INDEX_PREFIX = Regex(
            "^CREATE\\\\s+(?:UNIQUE\\\\s+)?INDEX\\\\s+(?:IF\\\\s+NOT\\\\s+EXISTS\\\\s+)?",
            RegexOption.IGNORE_CASE,
        )
        val CREATE_TRIGGER_PREFIX = Regex(
            "^CREATE\\\\s+TRIGGER\\\\s+(?:IF\\\\s+NOT\\\\s+EXISTS\\\\s+)?",
            RegexOption.IGNORE_CASE,
        )
        val INDEX_NAME = Regex(
            "^CREATE\\\\s+(?:UNIQUE\\\\s+)?INDEX\\\\s+(?:IF\\\\s+NOT\\\\s+EXISTS\\\\s+)?(?:\\\"([^\\\"]+)\\\"|([A-Za-z_][A-Za-z0-9_]*))",
            RegexOption.IGNORE_CASE,
        )

        private fun org.json.JSONArray.toStringList(): List<String> =
            buildList { for (index in 0 until length()) add(getString(index)) }

        private fun org.json.JSONArray.toStringSet(): Set<String> = toStringList().toSet()
    }
}
