package com.reiflix.reiflix_local.data.sqlite

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File

/**
 * Connection boundary for the canonical Python-created SQLite database.
 *
 * This class deliberately does not create files, tables, indexes, or migrations.
 * A missing or unreadable database is an error, never an empty-catalog result.
 */
class ReiAnixSqliteDatabase(context: Context) {
    private val appContext = context.applicationContext
    private val databaseFile = File(File(appContext.filesDir, "data"), DATABASE_FILE_NAME)
    private val connectionLock = Any()

    internal fun <T> read(block: (SQLiteDatabase) -> T): T =
        synchronized(connectionLock) {
            val database = openExisting(readOnly = true)
            try {
                block(database)
            } finally {
                database.close()
            }
        }

    internal fun <T> transaction(block: (SQLiteDatabase) -> T): T =
        synchronized(connectionLock) {
            val database = openExisting(readOnly = false)
            try {
                database.beginTransactionNonExclusive()
                try {
                    val result = block(database)
                    database.setTransactionSuccessful()
                    result
                } finally {
                    database.endTransaction()
                }
            } finally {
                database.close()
            }
        }

    internal fun inspectSchema(): ReiAnixSqliteSchemaSnapshot = read { database ->
        val tables = linkedSetOf<String>()
        database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = ? AND name NOT LIKE ? ORDER BY name",
            arrayOf("table", "sqlite_%"),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                tables += cursor.getString(0)
            }
        }

        val schemaVersion = if (SCHEMA_MIGRATIONS_TABLE in tables) {
            database.rawQuery(
                "SELECT MAX(version) FROM schema_migrations",
                null,
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else null
            }
        } else {
            null
        }

        val userVersion = database.rawQuery("PRAGMA user_version", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

        ReiAnixSqliteSchemaSnapshot(
            schemaVersion = schemaVersion,
            sqliteUserVersion = userVersion,
            tables = tables,
            missingRequiredTables = REQUIRED_TABLES - tables,
        )
    }

    internal fun quickCheck(): List<String> = read { database ->
        database.rawQuery("PRAGMA quick_check", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }.also { results ->
            check(results.isNotEmpty()) { "SQLite quick_check returned no result." }
        }
    }

    private fun openExisting(readOnly: Boolean): SQLiteDatabase {
        check(databaseFile.isFile && databaseFile.length() > 0L) {
            "The existing canonical SQLite database is unavailable. No replacement database was created."
        }

        return try {
            val flags = if (readOnly) {
                SQLiteDatabase.OPEN_READONLY
            } else {
                SQLiteDatabase.OPEN_READWRITE
            }
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, flags).also { database ->
                database.setForeignKeyConstraintsEnabled(true)
                database.execSQL("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS)
            }
        } catch (error: SQLiteException) {
            throw ReiAnixSqliteOpenException(
                "Unable to open the existing canonical SQLite database.",
                error,
            )
        }
    }

    companion object {
        const val DATABASE_FILE_NAME = "library.sqlite3"
        const val EXPECTED_SCHEMA_VERSION = 29
        const val BUSY_TIMEOUT_MS = 10_000
        private const val SCHEMA_MIGRATIONS_TABLE = "schema_migrations"

        private val REQUIRED_TABLES = setOf(
            "account",
            "anime",
            "artwork",
            "episodes",
            "folders",
            "preferences",
            "schema_migrations",
        )
    }
}

class ReiAnixSqliteOpenException(message: String, cause: Throwable) :
    IllegalStateException(message, cause)

data class ReiAnixSqliteSchemaSnapshot(
    val schemaVersion: Int?,
    val sqliteUserVersion: Int,
    val tables: Set<String>,
    val missingRequiredTables: Set<String>,
) {
    val supportsCurrentContract: Boolean
        get() = schemaVersion != null &&
            schemaVersion >= ReiAnixSqliteDatabase.EXPECTED_SCHEMA_VERSION &&
            missingRequiredTables.isEmpty()
}
