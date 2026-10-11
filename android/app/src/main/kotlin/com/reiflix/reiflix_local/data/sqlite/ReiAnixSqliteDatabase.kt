package com.reiflix.reiflix_local.data.sqlite

import android.content.Context
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

        val columnsByTable = mutableMapOf<String, Set<String>>()
        for (table in REQUIRED_TABLES.intersect(tables)) {
            columnsByTable[table] = database.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(1))
                }
            }
        }
        val missingRequiredColumns = REQUIRED_COLUMNS.mapNotNull { (table, requiredColumns) ->
            val missing = requiredColumns - (columnsByTable[table] ?: emptySet())
            if (missing.isEmpty()) null else table to missing
        }.toMap()

        val indexes = database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = ? AND name NOT LIKE ? ORDER BY name",
            arrayOf("index", "sqlite_%"),
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

        val userVersion = database.rawQuery("PRAGMA user_version", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

        ReiAnixSqliteSchemaSnapshot(
            schemaVersion = schemaVersion,
            sqliteUserVersion = userVersion,
            tables = tables,
            missingRequiredTables = REQUIRED_TABLES - tables,
            missingRequiredColumns = missingRequiredColumns,
            indexes = indexes,
            missingRequiredIndexes = REQUIRED_INDEXES - indexes,
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
                database.rawQuery("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS, null).use { cursor ->
                    while (cursor.moveToNext()) Unit
                }
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

        // Mirrors the full canonical table set created/migrated by LibraryStore._init().
        private val REQUIRED_COLUMNS = mapOf(
            "folders" to setOf(
                "path", "name", "kind", "authorization", "account_id", "added_at",
                "last_scan_at", "last_error", "saf_authority", "saf_document_id",
                "saf_volume_id", "saf_identity",
            ),
            "anime" to setOf(
                "id", "lookup_title", "anilist_id", "title", "romaji", "english", "native",
                "aliases", "description", "description_original", "cover_url", "cover_cache",
                "banner_url", "genres", "year", "season", "status", "episodes_count",
                "duration", "score", "format", "studio", "metadata_updated_at",
                "metadata_fetched_at", "metadata_source", "metadata_confidence", "metadata_status",
                "metadata_manual_fields", "favorite", "user_tags", "media_kind", "is_pinned",
                "personal_note", "added_at", "anilist_match_status", "anilist_match_score",
                "anilist_match_margin", "anilist_match_manual",
            ),
            "episodes" to setOf(
                "id", "anime_id", "path", "file_name", "season", "number", "duration",
                "progress", "watched", "mime_type", "file_size", "modified_at", "source_folder",
                "absolute_number", "relative_path", "volume_id", "volume_uuid", "episode_type",
                "episode_title", "identification_source", "identification_confidence",
                "manual_override", "missing", "last_played_at", "media_identity", "availability_state",
            ),
            "episode_observations" to setOf(
                "id", "episode_id", "source_kind", "scope_kind", "scope_ref", "uri", "volume_id",
                "native_generation", "fingerprint", "first_seen", "last_seen", "last_checked_at",
                "state", "error",
            ),
            "artwork" to setOf(
                "id", "entity_type", "entity_id", "artwork_type", "source", "source_ref", "local_path",
                "external_url", "manual", "priority", "status", "discovered_at", "updated_at",
                "last_attempt_at", "failure_count", "artwork_key", "variant", "byte_size", "width",
                "height", "checksum", "content_type", "last_access", "next_retry_at", "http_status",
            ),
            "associations" to setOf("lookup_title", "anilist_id"),
            "pending_matches" to setOf("lookup_title", "display_title", "candidates"),
            "account" to setOf("key", "value"),
            "preferences" to setOf("key", "value", "updated_at"),
            "genres" to setOf(
                "id", "canonical_name", "normalized_name", "source", "is_system", "is_custom",
                "created_at", "updated_at",
            ),
            "genre_aliases" to setOf(
                "id", "genre_id", "alias", "normalized_alias", "source", "created_at", "updated_at",
            ),
            "anime_genres" to setOf("anime_id", "genre_id", "source", "created_at", "updated_at"),
            "schema_migrations" to setOf("version", "applied_at"),
            "scan_runs" to setOf(
                "id", "started_at", "finished_at", "status", "folders", "files", "videos", "animes",
                "episodes", "new_files", "updated_files", "unchanged_files", "ignored_files",
                "duplicate_files", "unknown_files", "reconciled_files", "scan_id", "request_id",
                "source", "volume_id", "scope", "source_kind", "scope_kind", "scope_ref",
                "native_generation", "generation_id", "generation_status", "cancelled", "batch_id",
                "batch_number", "batch_size", "discovered", "processed", "inserted_files", "removed_files",
                "elapsed_ms", "errors",
            ),
        )
        private val REQUIRED_TABLES = REQUIRED_COLUMNS.keys

        // These indexes are created by the canonical schema initialization, including query-plan indexes.
        private val REQUIRED_INDEXES = setOf(
            "idx_episode_observation_unique",
            "idx_episode_observation_scope",
            "idx_episode_observation_volume",
            "idx_artwork_entity",
            "idx_artwork_status",
            "idx_genre_aliases_genre",
            "idx_anime_genres_genre",
            "idx_anime_genres_anime",
            "idx_episode_observation_scope_runtime",
            "idx_episodes_anime_playback",
            "idx_episodes_source_folder",
            "idx_episodes_volume_availability",
            "idx_episodes_media_identity",
            "idx_folders_account",
            "idx_folders_saf_identity",
            "idx_scan_runs_scan_id",
            "idx_scan_runs_status",
            "idx_anime_anilist_match",
            "idx_artwork_key",
            "idx_artwork_last_access",
            "idx_artwork_retry",
            "idx_anime_pinned",
            "idx_anime_media_kind",
            "idx_anime_added_title",
            "idx_anime_favorite_added",
            "idx_episodes_resume",
            "idx_episodes_season_number",
            "idx_episodes_hierarchy",
            "idx_episodes_anime_season_number_abs",
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
    val missingRequiredColumns: Map<String, Set<String>>,
    val indexes: Set<String>,
    val missingRequiredIndexes: Set<String>,
) {
    val supportsCurrentContract: Boolean
        get() = schemaVersion == ReiAnixSqliteDatabase.EXPECTED_SCHEMA_VERSION &&
            missingRequiredTables.isEmpty() &&
            missingRequiredColumns.isEmpty() &&
            missingRequiredIndexes.isEmpty()
}
