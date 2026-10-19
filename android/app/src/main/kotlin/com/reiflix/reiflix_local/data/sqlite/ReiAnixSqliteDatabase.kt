package com.reiflix.reiflix_local.data.sqlite

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Connection boundary for the existing LibraryStore-created SQLite database.
 *
 * The canonical runtime contract is generated from LibraryStore during the
 * Android build. No open/read path creates, migrates, deletes, or replaces the
 * database. Ordinary repository operations reject a schema that fails the
 * contract; inspectSchema() remains diagnostic and returns the incompatibilities.
 */
enum class ReiAnixSqliteTransactionOutcome {
    COMMITTED,
    ROLLED_BACK,
    UNKNOWN,
}

class ReiAnixSqliteDatabase(context: Context) {
    private val appContext = context.applicationContext
    /**
     * Canonical durable path shared with Flet 0.86.5's FLET_APP_STORAGE_DATA
     * on Android (the package's private files/data directory). Bootstrap and
     * recovery must consult this resolved file instead of reconstructing a path.
     */
    internal val canonicalDatabaseFile: File = File(File(appContext.filesDir, "data"), DATABASE_FILE_NAME)
    private val databaseFile: File
        get() = canonicalDatabaseFile
    private val connectionLock = Any()
    @Volatile private var contractValidated = false
    private val canonicalContract: JSONObject by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        loadCanonicalContract()
    }

    internal fun <T> read(block: (SQLiteDatabase) -> T): T =
        synchronized(connectionLock) {
            val database = openExisting(readOnly = true)
            try {
                block(database)
            } finally {
                database.close()
            }
        }

    /**
     * Reads several related tables from one SQLite snapshot where the platform
     * supports read-only transactions. Older Android releases keep the existing
     * read-only behavior; Android 15+ pins all projection queries to one snapshot.
     */
    internal fun <T> readConsistentSnapshot(block: (SQLiteDatabase) -> T): T =
        synchronized(connectionLock) {
            val database = openExisting(readOnly = true)
            try {
                if (android.os.Build.VERSION.SDK_INT < 35) {
                    block(database)
                } else {
                    database.beginTransactionReadOnly()
                    try {
                        val result = block(database)
                        database.setTransactionSuccessful()
                        result
                    } finally {
                        database.endTransaction()
                    }
                }
            } finally {
                database.close()
            }
        }

    /**
     * Notifies the caller only after endTransaction() gives a definitive result.
     * A failure in endTransaction() is UNKNOWN: callers must not start a second writer.
     * Notification is best-effort and must never turn a committed transaction into an
     * apparent rollback.
     */
    internal fun <T> transaction(
        onOutcome: ((ReiAnixSqliteTransactionOutcome) -> Unit)? = null,
        block: (SQLiteDatabase) -> T,
    ): T = synchronized(connectionLock) {
        val database = openExisting(readOnly = false)
        try {
            database.beginTransactionNonExclusive()
            var successRequested = false
            try {
                val result = block(database)
                database.setTransactionSuccessful()
                successRequested = true
                result
            } finally {
                try {
                    database.endTransaction()
                    notifyTransactionOutcome(
                        onOutcome,
                        if (successRequested) {
                            ReiAnixSqliteTransactionOutcome.COMMITTED
                        } else {
                            ReiAnixSqliteTransactionOutcome.ROLLED_BACK
                        },
                    )
                } catch (endError: Throwable) {
                    notifyTransactionOutcome(onOutcome, ReiAnixSqliteTransactionOutcome.UNKNOWN)
                    throw endError
                }
            }
        } finally {
            database.close()
        }
    }

    /**
     * Serializes file-level backup/recovery maintenance with all Kotlin readers and writers
     * that share this canonical database instance. The block receives the existing database
     * path but must not assume a connection is open; recovery may run precisely because the
     * database cannot be opened. Revalidate the runtime schema contract after maintenance.
     */
    internal fun <T> withExclusiveFileMaintenance(block: (File) -> T): T =
        synchronized(connectionLock) {
            try {
                block(databaseFile)
            } finally {
                contractValidated = false
            }
        }

    private fun notifyTransactionOutcome(
        onOutcome: ((ReiAnixSqliteTransactionOutcome) -> Unit)?,
        outcome: ReiAnixSqliteTransactionOutcome,
    ) {
        runCatching { onOutcome?.invoke(outcome) }
    }

    internal fun inspectSchema(): ReiAnixSqliteSchemaSnapshot =
        synchronized(connectionLock) {
            val database = openExisting(readOnly = true, validateContract = false)
            try {
                inspectSchema(database)
            } finally {
                database.close()
            }
        }

    /**
     * Returns the build-generated canonical contract to the native schema bootstrap.
     * The JSON is derived from LibraryStore's real schema in an isolated build fixture;
     * callers must not mutate it or use it as a second persistence source.
     */
    internal fun schemaContractForBootstrap(): JSONObject = canonicalContract

    /**
     * Runs schema creation/migration against the same canonical file and under the same
     * process lock as repository operations. CREATE_IF_NECESSARY is permitted only to
     * the bootstrap caller after startup has excluded interrupted-recovery evidence.
     * Existing files are never truncated, deleted, copied or replaced.
     */
    internal fun <T> withSchemaBootstrap(
        createIfMissing: Boolean,
        block: (SQLiteDatabase) -> T,
    ): T = synchronized(connectionLock) {
        if (!createIfMissing) {
            check(databaseFile.isFile && databaseFile.length() > 0L) {
                "The existing canonical SQLite database is unavailable for schema migration."
            }
        } else {
            check(!databaseFile.exists() || (databaseFile.isFile && databaseFile.length() > 0L)) {
                "The canonical SQLite path exists but is not a valid non-empty file."
            }
        }
        if (createIfMissing && !databaseFile.exists()) {
            val parent = requireNotNull(databaseFile.parentFile) {
                "The canonical SQLite database must have a parent directory."
            }
            check(parent.isDirectory || parent.mkdirs()) {
                "Could not create the canonical SQLite data directory."
            }
        }
        val flags = SQLiteDatabase.OPEN_READWRITE or
            (if (createIfMissing) SQLiteDatabase.CREATE_IF_NECESSARY else 0)
        val database = try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, flags)
        } catch (error: SQLiteException) {
            throw ReiAnixSqliteOpenException(
                "Unable to open the canonical SQLite database for native schema bootstrap.",
                error,
            )
        }
        try {
            database.setForeignKeyConstraintsEnabled(true)
            database.rawQuery("PRAGMA busy_timeout = $BUSY_TIMEOUT_MS", null).use { cursor ->
                while (cursor.moveToNext()) Unit
            }
            block(database)
        } finally {
            database.close()
        }
    }

    /** Full contract/integrity inspection inside the schema-bootstrap transaction. */
    internal fun inspectSchemaForBootstrap(database: SQLiteDatabase): ReiAnixSqliteSchemaSnapshot =
        inspectSchema(database)

    internal fun quickCheck(): List<String> =
        synchronized(connectionLock) {
            val database = openExisting(readOnly = true)
            try {
                runQuickCheck(database).also { results ->
                    check(results == listOf("ok")) {
                        "SQLite quick_check failed: ${results.joinToString("; ").ifBlank { "no result" }}"
                    }
                }
            } finally {
                database.close()
            }
        }

    private fun inspectSchema(database: SQLiteDatabase): ReiAnixSqliteSchemaSnapshot {
        val contract = canonicalContract
        val expectedVersion = contract.getInt("schema_version")
        val expectedUserVersion = contract.getInt("sqlite_user_version")
        val expectedTables = contract.getJSONArray("tables").toStringSet()
        val tables = database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = ? AND name NOT LIKE ? ORDER BY name",
            arrayOf("table", "sqlite_%"),
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        val missingTables = expectedTables - tables
        val schemaVersion = if (SCHEMA_MIGRATIONS_TABLE in tables) {
            database.rawQuery("SELECT MAX(version) FROM schema_migrations", null).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else null
            }
        } else null
        val userVersion = database.rawQuery("PRAGMA user_version", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

        val columnSnapshots = mutableMapOf<String, Map<String, ReiAnixSqliteColumnSnapshot>>()
        val missingColumns = mutableMapOf<String, Set<String>>()
        val columnContract = contract.getJSONObject("columns")
        for (table in expectedTables.intersect(tables)) {
            val actualColumns = database.rawQuery("PRAGMA table_xinfo(${quoteIdentifier(table)})", null).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        val column = ReiAnixSqliteColumnSnapshot(
                            name = cursor.getString(1),
                            declaredType = cursor.getString(2).orEmpty().trim().uppercase(Locale.ROOT),
                            notNull = cursor.getInt(3) != 0,
                            defaultValue = (if (cursor.isNull(4)) null else cursor.getString(4))
                                ?.let(::normalizeSqlExpression),
                            primaryKeyPosition = cursor.getInt(5),
                            hidden = if (cursor.columnCount > 6) cursor.getInt(6) else 0,
                        )
                        put(column.name, column)
                    }
                }
            }
            columnSnapshots[table] = actualColumns
            val expectedColumns = columnContract.getJSONArray(table).toColumnMap()
            val missing = expectedColumns.keys - actualColumns.keys
            if (missing.isNotEmpty()) missingColumns[table] = missing
        }

        val indexes = linkedSetOf<String>()
        val indexSnapshots = mutableMapOf<String, ReiAnixSqliteIndexSnapshot>()
        database.rawQuery(
            "SELECT name, tbl_name, sql FROM sqlite_master WHERE type = ? ORDER BY name",
            arrayOf("index"),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                val table = cursor.getString(1)
                val sql = if (cursor.isNull(2)) null else cursor.getString(2)
                indexes += name
                val indexListEntry = database.rawQuery(
                    "PRAGMA index_list(${quoteIdentifier(table)})",
                    null,
                ).use { listCursor ->
                    var found: ReiAnixSqliteIndexListEntry? = null
                    while (listCursor.moveToNext()) {
                        if (listCursor.getString(1) == name) {
                            found = ReiAnixSqliteIndexListEntry(
                                unique = listCursor.getInt(2) != 0,
                                origin = if (listCursor.columnCount > 3) listCursor.getString(3) else "c",
                                partial = listCursor.columnCount > 4 && listCursor.getInt(4) != 0,
                            )
                            break
                        }
                    }
                    found
                }
                val columns = database.rawQuery(
                    "PRAGMA index_xinfo(${quoteIdentifier(name)})",
                    null,
                ).use { indexCursor ->
                    buildList {
                        while (indexCursor.moveToNext()) {
                            if (indexCursor.columnCount > 5 && indexCursor.getInt(5) != 0) {
                                val columnId = indexCursor.getInt(1)
                                add(
                                    ReiAnixSqliteIndexColumn(
                                        name = if (indexCursor.isNull(2)) null else indexCursor.getString(2),
                                        columnId = columnId,
                                        expression = columnId == -2,
                                        descending = indexCursor.getInt(3) != 0,
                                        collation = (if (indexCursor.isNull(4)) null else indexCursor.getString(4))
                                            ?.uppercase(Locale.ROOT),
                                    ),
                                )
                            }
                        }
                    }
                }
                indexSnapshots[name] = ReiAnixSqliteIndexSnapshot(
                    name = name,
                    table = table,
                    unique = indexListEntry?.unique ?: false,
                    origin = indexListEntry?.origin ?: "unknown",
                    partial = indexListEntry?.partial ?: false,
                    columns = columns,
                    predicate = sql?.let(::extractWherePredicate),
                )
            }
        }

        val foreignKeys = mutableMapOf<String, List<ReiAnixSqliteForeignKeySnapshot>>()
        for (table in expectedTables.intersect(tables)) {
            foreignKeys[table] = database.rawQuery(
                "PRAGMA foreign_key_list(${quoteIdentifier(table)})",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            ReiAnixSqliteForeignKeySnapshot(
                                id = cursor.getInt(0),
                                sequence = cursor.getInt(1),
                                table = cursor.getString(2),
                                from = cursor.getString(3),
                                to = cursor.getString(4),
                                onUpdate = cursor.getString(5).uppercase(Locale.ROOT),
                                onDelete = cursor.getString(6).uppercase(Locale.ROOT),
                                match = cursor.getString(7).uppercase(Locale.ROOT),
                            ),
                        )
                    }
                }.sortedWith(compareBy<ReiAnixSqliteForeignKeySnapshot> { it.id }.thenBy { it.sequence })
            }
        }

        val tableContract = contract.getJSONObject("table_contract")
        val actualTableContract = mutableMapOf<String, ReiAnixSqliteTableSnapshot>()
        for (table in expectedTables.intersect(tables)) {
            val ddl = database.rawQuery(
                "SELECT sql FROM sqlite_master WHERE type = ? AND name = ?",
                arrayOf("table", table),
            ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else "" }
            val actual = ReiAnixSqliteTableSnapshot(
                strict = Regex("\\bSTRICT\\s*;?\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(ddl),
                withoutRowId = Regex("\\bWITHOUT\\s+ROWID\\b", RegexOption.IGNORE_CASE).containsMatchIn(ddl),
                checks = extractParenthesizedSql(ddl, "CHECK"),
            )
            actualTableContract[table] = actual
        }

        val actualTriggers = database.rawQuery(
            "SELECT name, tbl_name, sql FROM sqlite_master WHERE type = ? AND name NOT LIKE ? ORDER BY name",
            arrayOf("trigger", "sqlite_%"),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(
                        cursor.getString(0),
                        ReiAnixSqliteTriggerSnapshot(
                            name = cursor.getString(0),
                            table = cursor.getString(1),
                            sql = (if (cursor.isNull(2)) "" else cursor.getString(2)).let(::normalizeSqlExpression),
                        ),
                    )
                }
            }
        }

        val incompatibilities = linkedSetOf<String>()
        if (schemaVersion != expectedVersion) {
            incompatibilities += "schema_migrations version expected $expectedVersion but was $schemaVersion"
        }
        if (userVersion != expectedUserVersion) {
            incompatibilities += "PRAGMA user_version expected $expectedUserVersion but was $userVersion"
        }
        if (missingTables.isNotEmpty()) {
            incompatibilities += "missing tables: ${missingTables.sorted().joinToString()}"
        }

        for (table in expectedTables.intersect(tables)) {
            val expectedColumns = columnContract.getJSONArray(table).toColumnMap()
            val actualColumns = columnSnapshots[table].orEmpty()
            val unexpected = actualColumns.keys - expectedColumns.keys
            if (unexpected.isNotEmpty()) {
                incompatibilities += "unexpected columns in $table: ${unexpected.sorted().joinToString()}"
            }
            for ((columnName, expected) in expectedColumns) {
                val actual = actualColumns[columnName] ?: continue
                if (actual.declaredType != expected.declaredType) {
                    incompatibilities += "$table.$columnName declared type expected ${expected.declaredType} but was ${actual.declaredType}"
                }
                if (actual.notNull != expected.notNull) {
                    incompatibilities += "$table.$columnName NOT NULL expected ${expected.notNull} but was ${actual.notNull}"
                }
                if (actual.defaultValue != expected.defaultValue) {
                    incompatibilities += "$table.$columnName default expected ${expected.defaultValue} but was ${actual.defaultValue}"
                }
                if (actual.primaryKeyPosition != expected.primaryKeyPosition) {
                    incompatibilities += "$table.$columnName primary-key position expected ${expected.primaryKeyPosition} but was ${actual.primaryKeyPosition}"
                }
                if (actual.hidden != expected.hidden) {
                    incompatibilities += "$table.$columnName hidden/generated kind expected ${expected.hidden} but was ${actual.hidden}"
                }
            }

            val expectedTableOptions = tableContract.getJSONObject(table)
            val actualOptions = actualTableContract[table] ?: continue
            val expectedStrict = expectedTableOptions.getBoolean("strict")
            val expectedWithoutRowId = expectedTableOptions.getBoolean("without_rowid")
            val expectedChecks = expectedTableOptions.getJSONArray("checks").toStringList()
            if (actualOptions.strict != expectedStrict) {
                incompatibilities += "$table STRICT expected $expectedStrict but was ${actualOptions.strict}"
            }
            if (actualOptions.withoutRowId != expectedWithoutRowId) {
                incompatibilities += "$table WITHOUT ROWID expected $expectedWithoutRowId but was ${actualOptions.withoutRowId}"
            }
            if (actualOptions.checks != expectedChecks) {
                incompatibilities += "$table CHECK constraints expected $expectedChecks but were ${actualOptions.checks}"
            }

            val expectedForeignKeys = contract.getJSONObject("foreign_keys").getJSONArray(table)
                .toForeignKeyList()
            val actualForeignKeys = foreignKeys[table].orEmpty()
            if (expectedForeignKeys != actualForeignKeys) {
                incompatibilities += "$table foreign keys differ; expected=$expectedForeignKeys actual=$actualForeignKeys"
            }
        }

        val expectedIndexes = contract.getJSONArray("indexes").toIndexList()
        val expectedExplicitIndexes = expectedIndexes.filter { it.origin == "c" }
        for (expected in expectedExplicitIndexes) {
            val actual = indexSnapshots[expected.name]
            if (actual == null) {
                incompatibilities += "missing index: ${expected.name}"
                continue
            }
            val issues = compareIndex(expected, actual)
            incompatibilities.addAll(issues)
        }

        val expectedExplicitIndexNames = expectedExplicitIndexes.map { it.name }.toSet()
        val actualExplicitIndexNames = indexSnapshots.values
            .filter { it.origin == "c" && it.table in expectedTables }
            .map { it.name }
            .toSet()
        val unexpectedExplicitIndexes = actualExplicitIndexNames - expectedExplicitIndexNames
        if (unexpectedExplicitIndexes.isNotEmpty()) {
            incompatibilities += "unexpected indexes on canonical tables: ${unexpectedExplicitIndexes.sorted().joinToString()}"
        }

        // SQLite-generated UNIQUE/PRIMARY KEY indexes are compared by semantic
        // signature, not sqlite_autoindex_* names (whose suffix is implementation detail).
        val expectedConstraintIndexes = expectedIndexes.filter { it.origin != "c" }
        val actualConstraintIndexes = indexSnapshots.values
            .filter { it.origin != "c" && it.table in expectedTables }
        val expectedConstraintCounts = expectedConstraintIndexes.groupingBy { it.semanticSignature() }.eachCount()
        val actualConstraintCounts = actualConstraintIndexes.groupingBy { it.semanticSignature() }.eachCount()
        for ((signature, expectedCount) in expectedConstraintCounts) {
            val actualCount = actualConstraintCounts[signature] ?: 0
            if (actualCount < expectedCount) {
                val expected = expectedConstraintIndexes.first { it.semanticSignature() == signature }
                val columns = expected.columns.joinToString(",") { it.name.orEmpty() }
                val constraintKind = if (expected.origin == "u" || expected.unique) "unique constraint" else "primary-key constraint"
                incompatibilities += "missing $constraintKind on ${expected.table}[$columns]"
            }
        }
        for ((signature, actualCount) in actualConstraintCounts) {
            val expectedCount = expectedConstraintCounts[signature] ?: 0
            if (actualCount > expectedCount) {
                val actual = actualConstraintIndexes.first { it.semanticSignature() == signature }
                val columns = actual.columns.joinToString(",") { it.name.orEmpty() }
                val constraintKind = if (actual.origin == "u" || actual.unique) "unique constraint" else "primary-key constraint"
                incompatibilities += "unexpected $constraintKind on ${actual.table}[$columns]"
            }
        }
        if (expectedConstraintCounts != actualConstraintCounts) {
            incompatibilities += "SQLite UNIQUE/PRIMARY KEY index constraints differ; expected=$expectedConstraintCounts actual=$actualConstraintCounts"
        }

        val expectedTriggers = contract.getJSONArray("triggers").toTriggerMap()
        if (expectedTriggers.keys != actualTriggers.keys) {
            incompatibilities += "trigger names differ; expected=${expectedTriggers.keys} actual=${actualTriggers.keys}"
        }
        for ((name, expected) in expectedTriggers) {
            val actual = actualTriggers[name] ?: continue
            if (expected.table != actual.table || expected.sql != actual.sql) {
                incompatibilities += "trigger $name definition differs from the canonical contract"
            }
        }

        val quickCheckResults = runQuickCheck(database)
        val integrityErrors = if (quickCheckResults == listOf("ok")) {
            emptyList()
        } else {
            quickCheckResults.ifEmpty { listOf("PRAGMA quick_check returned no result") }
        }
        if (integrityErrors.isNotEmpty()) {
            incompatibilities += "PRAGMA quick_check failed: ${integrityErrors.joinToString("; ")}"
        }

        val foreignKeyViolations = database.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        "table=${cursor.getString(0)}, rowid=${if (cursor.isNull(1)) "NULL" else cursor.getString(1)}, " +
                            "parent=${cursor.getString(2)}, fk_id=${cursor.getString(3)}",
                    )
                }
            }
        }
        if (foreignKeyViolations.isNotEmpty()) {
            incompatibilities += "PRAGMA foreign_key_check reported ${foreignKeyViolations.size} violation(s): ${foreignKeyViolations.take(5).joinToString("; ")}"
        }

        return ReiAnixSqliteSchemaSnapshot(
            schemaVersion = schemaVersion,
            sqliteUserVersion = userVersion,
            tables = tables,
            missingRequiredTables = missingTables,
            missingRequiredColumns = missingColumns,
            indexes = indexes,
            missingRequiredIndexes = expectedExplicitIndexes.map { it.name }.toSet() - indexes,
            columnSnapshots = columnSnapshots,
            indexSnapshots = indexSnapshots,
            foreignKeys = foreignKeys,
            tableContract = actualTableContract,
            triggers = actualTriggers,
            incompatibilities = incompatibilities,
            integrityErrors = integrityErrors,
            foreignKeyViolations = foreignKeyViolations,
        )
    }

    private fun loadCanonicalContract(): JSONObject = try {
        appContext.assets.open(CONTRACT_ASSET_NAME).bufferedReader(Charsets.UTF_8).use { reader ->
            JSONObject(reader.readText())
        }
    } catch (error: IOException) {
        throw IllegalStateException(
            "Canonical SQLite runtime contract asset is missing: $CONTRACT_ASSET_NAME. " +
                "Generate it from core.library_store.LibraryStore before building the Android host.",
            error,
        )
    }

    private fun openExisting(
        readOnly: Boolean,
        validateContract: Boolean = true,
    ): SQLiteDatabase {
        check(databaseFile.isFile && databaseFile.length() > 0L) {
            "The existing canonical SQLite database is unavailable. No replacement database was created."
        }

        val flags = if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE
        val database = try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, flags)
        } catch (error: SQLiteException) {
            throw ReiAnixSqliteOpenException("Unable to open the existing canonical SQLite database.", error)
        }

        try {
            database.setForeignKeyConstraintsEnabled(true)
            database.rawQuery("PRAGMA busy_timeout = $BUSY_TIMEOUT_MS", null).use { cursor ->
                while (cursor.moveToNext()) Unit
            }
            if (validateContract && !contractValidated) {
                val snapshot = inspectSchema(database)
                if (!snapshot.supportsCurrentContract) {
                    throw ReiAnixSqliteSchemaMismatchException(snapshot.incompatibilities)
                }
                contractValidated = true
            }
            return database
        } catch (error: SQLiteException) {
            database.close()
            throw ReiAnixSqliteOpenException("Unable to inspect the existing canonical SQLite database.", error)
        } catch (error: Throwable) {
            database.close()
            throw error
        }
    }

    private fun runQuickCheck(database: SQLiteDatabase): List<String> =
        database.rawQuery("PRAGMA quick_check", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private fun compareIndex(
        expected: ReiAnixSqliteIndexSnapshot,
        actual: ReiAnixSqliteIndexSnapshot,
    ): Set<String> {
        val issues = linkedSetOf<String>()
        if (expected.table != actual.table) issues += "index ${expected.name} table expected ${expected.table} but was ${actual.table}"
        if (expected.unique != actual.unique) issues += "index ${expected.name} unique expected ${expected.unique} but was ${actual.unique}"
        if (expected.origin != actual.origin) issues += "index ${expected.name} origin expected ${expected.origin} but was ${actual.origin}"
        if (expected.partial != actual.partial) issues += "index ${expected.name} partial expected ${expected.partial} but was ${actual.partial}"
        if (expected.columns != actual.columns) {
            issues += "index ${expected.name} columns expected ${expected.columns} (including order and options) but were ${actual.columns}"
        }
        if (normalizeSqlExpression(expected.predicate.orEmpty()) != normalizeSqlExpression(actual.predicate.orEmpty())) {
            issues += "index ${expected.name} predicate expected ${expected.predicate} but was ${actual.predicate}"
        }
        return issues
    }

    companion object {
        const val DATABASE_FILE_NAME = "library.sqlite3"
        const val EXPECTED_SCHEMA_VERSION = 29
        const val BUSY_TIMEOUT_MS = 10_000
        const val CONTRACT_ASSET_NAME = "native-sqlite-contract-v29.json"
        private const val SCHEMA_MIGRATIONS_TABLE = "schema_migrations"

        private fun quoteIdentifier(identifier: String) = "\"" + identifier.replace("\"", "\"\"") + "\""
    }
}

class ReiAnixSqliteOpenException(message: String, cause: Throwable) :
    IllegalStateException(message, cause)

class ReiAnixSqliteSchemaMismatchException(
    val incompatibilities: Set<String>,
) : IllegalStateException(
    "The existing ReiAnix SQLite database is incompatible with the canonical schema: " +
        incompatibilities.joinToString("; "),
)

data class ReiAnixSqliteIndexListEntry(val unique: Boolean, val origin: String, val partial: Boolean)

data class ReiAnixSqliteColumnSnapshot(
    val name: String,
    val declaredType: String,
    val notNull: Boolean,
    val defaultValue: String?,
    val primaryKeyPosition: Int,
    val hidden: Int,
)

data class ReiAnixSqliteIndexColumn(
    val name: String?,
    val columnId: Int,
    val expression: Boolean,
    val descending: Boolean,
    val collation: String?,
)

data class ReiAnixSqliteIndexSnapshot(
    val name: String,
    val table: String,
    val unique: Boolean,
    val origin: String,
    val partial: Boolean,
    val columns: List<ReiAnixSqliteIndexColumn>,
    val predicate: String?,
) {
    fun semanticSignature(): String =
        "$table|$origin|$unique|$partial|${columns.joinToString()}|${normalizeSqlExpression(predicate.orEmpty())}"
}

data class ReiAnixSqliteForeignKeySnapshot(
    val id: Int,
    val sequence: Int,
    val table: String,
    val from: String,
    val to: String,
    val onUpdate: String,
    val onDelete: String,
    val match: String,
)

data class ReiAnixSqliteTableSnapshot(
    val strict: Boolean,
    val withoutRowId: Boolean,
    val checks: List<String>,
)

data class ReiAnixSqliteTriggerSnapshot(
    val name: String,
    val table: String,
    val sql: String,
)

data class ReiAnixSqliteSchemaSnapshot(
    val schemaVersion: Int?,
    val sqliteUserVersion: Int,
    val tables: Set<String>,
    val missingRequiredTables: Set<String>,
    val missingRequiredColumns: Map<String, Set<String>>,
    val indexes: Set<String>,
    val missingRequiredIndexes: Set<String>,
    val columnSnapshots: Map<String, Map<String, ReiAnixSqliteColumnSnapshot>>,
    val indexSnapshots: Map<String, ReiAnixSqliteIndexSnapshot>,
    val foreignKeys: Map<String, List<ReiAnixSqliteForeignKeySnapshot>>,
    val tableContract: Map<String, ReiAnixSqliteTableSnapshot>,
    val triggers: Map<String, ReiAnixSqliteTriggerSnapshot>,
    val incompatibilities: Set<String>,
    val integrityErrors: List<String>,
    val foreignKeyViolations: List<String>,
) {
    val supportsCurrentContract: Boolean
        get() = incompatibilities.isEmpty() &&
            schemaVersion == ReiAnixSqliteDatabase.EXPECTED_SCHEMA_VERSION &&
            missingRequiredTables.isEmpty() &&
            missingRequiredColumns.isEmpty() &&
            missingRequiredIndexes.isEmpty() &&
            integrityErrors.isEmpty() &&
            foreignKeyViolations.isEmpty()
}

private fun JSONArray.toStringSet(): Set<String> =
    buildSet { for (index in 0 until length()) add(getString(index)) }

private fun JSONArray.toStringList(): List<String> =
    buildList { for (index in 0 until length()) add(getString(index)) }

private fun JSONArray.toColumnMap(): Map<String, ReiAnixSqliteColumnSnapshot> =
    buildMap {
        for (index in 0 until length()) {
            val column = getJSONObject(index)
            val rawDefault = column.opt("default")
            val defaultValue = if (rawDefault == null || rawDefault == JSONObject.NULL) {
                null
            } else {
                normalizeSqlExpression(rawDefault.toString())
            }
            val snapshot = ReiAnixSqliteColumnSnapshot(
                name = column.getString("name"),
                declaredType = column.getString("type").trim().uppercase(Locale.ROOT),
                notNull = column.getBoolean("not_null"),
                defaultValue = defaultValue,
                primaryKeyPosition = column.getInt("primary_key_position"),
                hidden = column.getInt("hidden"),
            )
            put(snapshot.name, snapshot)
        }
    }

private fun JSONArray.toIndexList(): List<ReiAnixSqliteIndexSnapshot> =
    buildList {
        for (index in 0 until length()) {
            val item = getJSONObject(index)
            val predicate = item.opt("predicate").let {
                if (it == null || it == JSONObject.NULL) null else normalizeSqlExpression(it.toString())
            }
            val columns = item.getJSONArray("columns").let { array ->
                buildList {
                    for (columnIndex in 0 until array.length()) {
                        val column = array.getJSONObject(columnIndex)
                        val rawName = column.opt("name")
                        add(
                            ReiAnixSqliteIndexColumn(
                                name = if (rawName == null || rawName == JSONObject.NULL) null else rawName.toString(),
                                columnId = column.getInt("column_id"),
                                expression = column.getBoolean("expression"),
                                descending = column.getBoolean("descending"),
                                collation = column.optString("collation").uppercase(Locale.ROOT),
                            ),
                        )
                    }
                }
            }
            add(
                ReiAnixSqliteIndexSnapshot(
                    name = item.getString("name"),
                    table = item.getString("table"),
                    unique = item.getBoolean("unique"),
                    origin = item.getString("origin"),
                    partial = item.getBoolean("partial"),
                    columns = columns,
                    predicate = predicate,
                ),
            )
        }
    }

private fun JSONArray.toForeignKeyList(): List<ReiAnixSqliteForeignKeySnapshot> =
    buildList {
        for (index in 0 until length()) {
            val item = getJSONObject(index)
            add(
                ReiAnixSqliteForeignKeySnapshot(
                    id = item.getInt("id"),
                    sequence = item.getInt("sequence"),
                    table = item.getString("table"),
                    from = item.getString("from"),
                    to = item.getString("to"),
                    onUpdate = item.getString("on_update").uppercase(Locale.ROOT),
                    onDelete = item.getString("on_delete").uppercase(Locale.ROOT),
                    match = item.getString("match").uppercase(Locale.ROOT),
                ),
            )
        }
    }.sortedWith(compareBy<ReiAnixSqliteForeignKeySnapshot> { it.id }.thenBy { it.sequence })

private fun JSONArray.toTriggerMap(): Map<String, ReiAnixSqliteTriggerSnapshot> =
    buildMap {
        for (index in 0 until length()) {
            val item = getJSONObject(index)
            val snapshot = ReiAnixSqliteTriggerSnapshot(
                name = item.getString("name"),
                table = item.getString("table"),
                sql = normalizeSqlExpression(item.getString("sql")),
            )
            put(snapshot.name, snapshot)
        }
    }

private fun normalizeSqlExpression(sql: String): String {
    val output = StringBuilder()
    var quote: Char? = null
    var pendingSpace = false
    var index = 0
    while (index < sql.length) {
        val char = sql[index]
        if (quote != null) {
            output.append(char)
            if (char == quote) {
                if (index + 1 < sql.length && sql[index + 1] == quote) {
                    output.append(sql[index + 1])
                    index++
                } else {
                    quote = null
                }
            }
        } else if (char.code == 39 || char == '"' || char == '`') {
            if (pendingSpace && output.isNotEmpty()) output.append(' ')
            pendingSpace = false
            quote = char
            output.append(char)
        } else if (char == '[') {
            if (pendingSpace && output.isNotEmpty()) output.append(' ')
            pendingSpace = false
            quote = ']'
            output.append(char)
        } else if (char.isWhitespace()) {
            pendingSpace = true
        } else {
            if (pendingSpace && output.isNotEmpty()) output.append(' ')
            pendingSpace = false
            output.append(char.uppercaseChar())
        }
        index++
    }
    return output.toString().trim().trimEnd(';')
}

private fun extractWherePredicate(sql: String): String? {
    val match = Regex("(?is)\\bWHERE\\b(.*)$").find(sql) ?: return null
    return normalizeSqlExpression(match.groupValues[1])
}

private fun extractParenthesizedSql(sql: String, keyword: String): List<String> {
    val values = mutableListOf<String>()
    val target = keyword.uppercase(Locale.ROOT)
    var index = 0
    while (index < sql.length) {
        val char = sql[index]
        if (char.code == 39 || char == '"' || char.code == 96 || char == '[' ||
            sql.startsWith("--", index) || sql.startsWith("/*", index)
        ) {
            index = skipQuotedOrComment(sql, index)
            continue
        }
        val beforeIsWord = index > 0 && (sql[index - 1].isLetterOrDigit() || sql[index - 1] == '_')
        val endKeyword = index + target.length
        val afterIsWord = endKeyword < sql.length &&
            (sql[endKeyword].isLetterOrDigit() || sql[endKeyword] == '_')
        if (!beforeIsWord && !afterIsWord &&
            sql.regionMatches(index, target, 0, target.length, ignoreCase = true)
        ) {
            var openIndex = endKeyword
            while (openIndex < sql.length && sql[openIndex].isWhitespace()) openIndex++
            if (openIndex < sql.length && sql[openIndex] == '(') {
                var depth = 1
                var cursor = openIndex + 1
                while (cursor < sql.length && depth > 0) {
                    val token = sql[cursor]
                    if (token.code == 39 || token == '"' || token.code == 96 || token == '[' ||
                        sql.startsWith("--", cursor) || sql.startsWith("/*", cursor)
                    ) {
                        cursor = skipQuotedOrComment(sql, cursor)
                        continue
                    }
                    when (token) {
                        '(' -> depth++
                        ')' -> depth--
                    }
                    cursor++
                }
                check(depth == 0) { "Unbalanced $keyword clause in SQLite DDL." }
                values += normalizeSqlExpression(sql.substring(openIndex + 1, cursor - 1))
                index = cursor
                continue
            }
        }
        index++
    }
    return values
}

private fun skipQuotedOrComment(sql: String, start: Int): Int {
    if (sql.startsWith("--", start)) {
        val newline = sql.indexOf(10.toChar(), start + 2)
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
