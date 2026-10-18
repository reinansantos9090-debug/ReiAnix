package com.reiflix.reiflix_local.data.sqlite

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Builds the canonical schema-v29 database for a genuinely fresh installation.
 *
 * DDL is exported from the real LibraryStore schema. The candidate is built beside the
 * canonical file, checked against the production schema contract, and linked into place
 * with an exclusive filesystem operation that cannot overwrite an existing database.
 */
internal class ReiAnixSqliteBootstrapper(
    context: Context,
    private val database: ReiAnixSqliteDatabase,
) {
    private val appContext = context.applicationContext
    private val dataDirectory = File(appContext.filesDir, "data")
    private val canonicalFile = File(dataDirectory, ReiAnixSqliteDatabase.DATABASE_FILE_NAME)

    fun bootstrapIfMissing(): Boolean = synchronized(bootstrapLock) {
        if (canonicalFile.exists()) {
            check(canonicalFile.isFile && canonicalFile.length() > 0L) {
                "Canonical SQLite path exists but is not a non-empty database file."
            }
            return@synchronized false
        }

        check(dataDirectory.isDirectory || dataDirectory.mkdirs()) {
            "Could not create the application data directory for a fresh SQLite database."
        }

        val payload = readBootstrapPayload()
        val schemaVersion = payload.getInt("schema_version")
        val initialMigrationVersion = payload.getInt("initial_migration_version")
        val userVersion = payload.getInt("sqlite_user_version")
        check(schemaVersion == ReiAnixSqliteDatabase.EXPECTED_SCHEMA_VERSION) {
            "Native SQLite bootstrap schema version $schemaVersion is not supported."
        }
        check(initialMigrationVersion == schemaVersion) {
            "Native SQLite bootstrap migration seed does not match schema version $schemaVersion."
        }
        check(userVersion >= 0) {
            "Native SQLite bootstrap declares a negative PRAGMA user_version."
        }

        val expectedTables = buildSet {
            val values = payload.getJSONArray("tables")
            for (index in 0 until values.length()) add(values.getString(index))
        }
        check("schema_migrations" in expectedTables) {
            "Native SQLite bootstrap does not declare schema_migrations."
        }

        val statements = buildList {
            val values = payload.getJSONArray("statements")
            for (index in 0 until values.length()) {
                val statement = values.getString(index).trim()
                check(isAllowedSchemaStatement(statement)) {
                    "Native SQLite bootstrap contains a non-schema statement."
                }
                add(statement)
            }
        }
        check(statements.isNotEmpty()) {
            "Native SQLite bootstrap contains no schema statements."
        }

        val triggerStatements = buildList {
            val values = payload.getJSONArray("triggers")
            for (index in 0 until values.length()) {
                val statement = values.getString(index).trim()
                check(statement.startsWith("CREATE TRIGGER ", ignoreCase = true)) {
                    "Native SQLite bootstrap contains a non-trigger statement."
                }
                add(statement)
            }
        }

        val candidateFile = File(dataDirectory, ".library.sqlite3.candidate-${UUID.randomUUID()}")
        try {
            val candidate = SQLiteDatabase.openDatabase(
                candidateFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
            )
            try {
                candidate.setForeignKeyConstraintsEnabled(true)
                candidate.beginTransaction()
                try {
                    statements.forEach(candidate::execSQL)
                    triggerStatements.forEach(candidate::execSQL)
                    candidate.execSQL(
                        "INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)",
                        arrayOf(
                            initialMigrationVersion,
                            System.currentTimeMillis().toDouble() / 1000.0,
                        ),
                    )
                    candidate.execSQL("PRAGMA user_version = $userVersion")
                    candidate.setTransactionSuccessful()
                } finally {
                    candidate.endTransaction()
                }
            } finally {
                candidate.close()
            }

            val snapshot = database.inspectSchemaFile(candidateFile)
            check(snapshot.supportsCurrentContract) {
                "Fresh SQLite candidate does not satisfy schema v$schemaVersion: " +
                    snapshot.incompatibilities.joinToString("; ")
            }
            check(snapshot.tables == expectedTables) {
                "Fresh SQLite candidate tables do not exactly match the canonical schema v$schemaVersion."
            }
            check(snapshot.schemaVersion == schemaVersion) {
                "Fresh SQLite candidate migration ledger does not contain schema v$schemaVersion."
            }
            check(snapshot.sqliteUserVersion == userVersion) {
                "Fresh SQLite candidate PRAGMA user_version does not match the generated contract."
            }
            val quickCheck = database.quickCheckFile(candidateFile)
            check(quickCheck == listOf("ok")) {
                "Fresh SQLite candidate failed quick_check: ${quickCheck.joinToString("; ").ifBlank { "no result" }}"
            }

            // Link creation is atomic and fails with EEXIST rather than replacing a racer.
            try {
                Os.link(candidateFile.absolutePath, canonicalFile.absolutePath)
            } catch (error: ErrnoException) {
                if (canonicalFile.exists()) {
                    candidateFile.delete()
                    return@synchronized false
                }
                throw error
            }
            // Both names temporarily refer to the same verified inode. Remove the candidate
            // name without copying, resetting, or rebuilding the canonical database.
            if (candidateFile.exists() && !candidateFile.delete()) {
                Log.w(TAG, "The validated SQLite candidate hard-link could not be removed after promotion.")
            }

            val promoted = database.inspectSchema()
            check(promoted.supportsCurrentContract) {
                "Promoted canonical SQLite database did not satisfy schema v$schemaVersion."
            }
            database.quickCheck()
            true
        } catch (error: Exception) {
            if (candidateFile.exists()) candidateFile.delete()
            throw IllegalStateException(
                "Native fresh-install SQLite bootstrap failed without intentionally replacing canonical data.",
                error,
            )
        }
    }

    private fun readBootstrapPayload(): JSONObject =
        appContext.assets.open(BOOTSTRAP_ASSET_NAME).bufferedReader(Charsets.UTF_8).use {
            JSONObject(it.readText())
        }

    private fun isAllowedSchemaStatement(statement: String): Boolean {
        val sql = statement.trimStart().uppercase(Locale.ROOT)
        return sql.startsWith("CREATE TABLE ") ||
            sql.startsWith("CREATE INDEX ") ||
            sql.startsWith("CREATE UNIQUE INDEX ")
    }

    private companion object {
        const val TAG = "ReiAnixSqliteBootstrapper"
        const val BOOTSTRAP_ASSET_NAME = "native-sqlite-bootstrap-v29.json"
        val bootstrapLock = Any()
    }
}
