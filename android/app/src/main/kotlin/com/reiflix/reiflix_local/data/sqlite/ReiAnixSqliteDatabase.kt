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
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }

        val schemaVersion = if (SCHEMA_MIGRATIONS_TABLE in tables) {
            database.rawQuery("SELECT MAX(version) FROM schema_migrations", null).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else null
            }
        } else null

        val userVersion = database.rawQuery("PRAGMA user_version", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

        val columnSnapshots = mutableMapOf<String, Map<String, ReiAnixSqliteColumnSnapshot>>()
        for (table in REQUIRED_TABLES.intersect(tables)) {
            columnSnapshots[table] = database.rawQuery("PRAGMA table_info(\"$table\")", null).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        val column = ReiAnixSqliteColumnSnapshot(
                            name = cursor.getString(1),
                            declaredType = cursor.getString(2).orEmpty().trim().uppercase(),
                            notNull = cursor.getInt(3) != 0,
                            defaultValue = (if (cursor.isNull(4)) null else cursor.getString(4))?.let(::normalizeSqlExpression),
                            primaryKeyPosition = cursor.getInt(5),
                        )
                        put(column.name, column)
                    }
                }
            }
        }

        val missingRequiredColumns = REQUIRED_COLUMNS.mapNotNull { (table, requiredColumns) ->
            val missing = requiredColumns - (columnSnapshots[table]?.keys ?: emptySet())
            if (missing.isEmpty()) null else table to missing
        }.toMap()

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
                val indexListEntry = database.rawQuery("PRAGMA index_list(\"$table\")", null).use { listCursor ->
                    var found: ReiAnixSqliteIndexListEntry? = null
                    while (listCursor.moveToNext()) {
                        if (listCursor.getString(1) == name) {
                            found = ReiAnixSqliteIndexListEntry(
                                unique = listCursor.getInt(2) != 0,
                                partial = listCursor.columnCount > 4 && listCursor.getInt(4) != 0,
                            )
                            break
                        }
                    }
                    found
                }
                val columns = database.rawQuery("PRAGMA index_xinfo(\"$name\")", null).use { indexCursor ->
                    buildList {
                        while (indexCursor.moveToNext()) {
                            if (indexCursor.columnCount > 5 && indexCursor.getInt(5) != 0) {
                                add(
                                    ReiAnixSqliteIndexColumn(
                                        name = if (indexCursor.isNull(2)) null else indexCursor.getString(2),
                                        descending = indexCursor.getInt(3) != 0,
                                        collation = (if (indexCursor.isNull(4)) null else indexCursor.getString(4))?.uppercase(),
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
                    partial = indexListEntry?.partial ?: false,
                    columns = columns,
                    predicate = sql?.let(::extractWherePredicate),
                )
            }
        }

        val foreignKeys = mutableMapOf<String, Set<ReiAnixSqliteForeignKeySnapshot>>()
        for (table in REQUIRED_TABLES.intersect(tables)) {
            foreignKeys[table] = database.rawQuery("PRAGMA foreign_key_list(\"$table\")", null).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        add(
                            ReiAnixSqliteForeignKeySnapshot(
                                table = cursor.getString(2),
                                from = cursor.getString(3),
                                to = cursor.getString(4),
                                onUpdate = cursor.getString(5).uppercase(),
                                onDelete = cursor.getString(6).uppercase(),
                                match = cursor.getString(7).uppercase(),
                            ),
                        )
                    }
                }
            }
        }

        val incompatibilities = linkedSetOf<String>()
        if (schemaVersion != EXPECTED_SCHEMA_VERSION) {
            incompatibilities += "schema_migrations version expected $EXPECTED_SCHEMA_VERSION but was $schemaVersion"
        }
        // LibraryStore records its schema in schema_migrations and deliberately leaves PRAGMA user_version at 0.
        if (userVersion != CANONICAL_SQLITE_USER_VERSION) {
            incompatibilities += "PRAGMA user_version expected $CANONICAL_SQLITE_USER_VERSION but was $userVersion"
        }

        val missingTables = REQUIRED_TABLES - tables
        if (missingTables.isNotEmpty()) incompatibilities += "missing tables: ${missingTables.sorted().joinToString()}"
        if (missingRequiredColumns.isNotEmpty()) {
            incompatibilities += missingRequiredColumns.entries.joinToString("; ") { (table, columns) ->
                "missing columns in $table: ${columns.sorted().joinToString()}"
            }
        }

        for ((table, expectedColumns) in REQUIRED_COLUMN_CONTRACT) {
            val actual = columnSnapshots[table].orEmpty()
            for ((columnName, expected) in expectedColumns) {
                val found = actual[columnName] ?: continue
                if (found.declaredType != expected.declaredType) {
                    incompatibilities += "$table.$columnName declared type expected ${expected.declaredType} but was ${found.declaredType}"
                }
                if (found.notNull != expected.notNull) {
                    incompatibilities += "$table.$columnName NOT NULL expected ${expected.notNull} but was ${found.notNull}"
                }
                if (found.defaultValue != expected.defaultValue) {
                    incompatibilities += "$table.$columnName default expected ${expected.defaultValue} but was ${found.defaultValue}"
                }
                if (found.primaryKeyPosition != expected.primaryKeyPosition) {
                    incompatibilities += "$table.$columnName primary-key position expected ${expected.primaryKeyPosition} but was ${found.primaryKeyPosition}"
                }
            }
        }

        for ((table, expectedKeys) in REQUIRED_FOREIGN_KEYS) {
            val actualKeys = foreignKeys[table].orEmpty()
            val missing = expectedKeys - actualKeys
            val unexpected = actualKeys - expectedKeys
            if (missing.isNotEmpty() || unexpected.isNotEmpty()) {
                incompatibilities += "$table foreign keys differ; missing=$missing unexpected=$unexpected"
            }
        }

        for (expected in REQUIRED_UNIQUE_CONSTRAINTS) {
            val present = indexSnapshots.values.any { index ->
                index.table == expected.table &&
                    index.unique &&
                    index.columns.map { it.name.orEmpty() } == expected.columns
            }
            if (!present) {
                incompatibilities += "missing unique constraint on ${expected.table}${expected.columns}"
            }
        }

        for ((name, expected) in REQUIRED_INDEX_CONTRACT) {
            val actual = indexSnapshots[name]
            if (actual == null) {
                incompatibilities += "missing index: $name"
                continue
            }
            if (actual.table != expected.table) incompatibilities += "index $name belongs to ${actual.table}, expected ${expected.table}"
            if (actual.unique != expected.unique) incompatibilities += "index $name unique expected ${expected.unique} but was ${actual.unique}"
            if (actual.partial != (expected.predicate != null)) incompatibilities += "index $name partial expected ${expected.predicate != null} but was ${actual.partial}"
            val actualColumns = actual.columns.map { it.name.orEmpty() }
            if (actualColumns != expected.columns) incompatibilities += "index $name columns expected ${expected.columns} but were $actualColumns"
            val actualDescending = actual.columns.mapIndexedNotNull { index, column ->
                if (column.descending) actualColumns.getOrNull(index) else null
            }.toSet()
            if (actualDescending != expected.descendingColumns) {
                incompatibilities += "index $name descending columns expected ${expected.descendingColumns} but were $actualDescending"
            }
            for ((column, collation) in expected.collations) {
                val actualCollation = actual.columns.firstOrNull { it.name == column }?.collation
                if (actualCollation != collation) incompatibilities += "index $name collation for $column expected $collation but was $actualCollation"
            }
            if (expected.predicate != null && normalizeSqlExpression(actual.predicate.orEmpty()) != normalizeSqlExpression(expected.predicate)) {
                incompatibilities += "index $name predicate expected ${expected.predicate} but was ${actual.predicate}"
            }
        }

        ReiAnixSqliteSchemaSnapshot(
            schemaVersion = schemaVersion,
            sqliteUserVersion = userVersion,
            tables = tables,
            missingRequiredTables = missingTables,
            missingRequiredColumns = missingRequiredColumns,
            indexes = indexes,
            missingRequiredIndexes = REQUIRED_INDEXES - indexes,
            columnSnapshots = columnSnapshots,
            indexSnapshots = indexSnapshots,
            foreignKeys = foreignKeys,
            incompatibilities = incompatibilities,
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
            check(results == listOf("ok")) {
                "SQLite quick_check failed: ${results.joinToString("; ").ifBlank { "no result" }}"
            }
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
        const val CANONICAL_SQLITE_USER_VERSION = 0
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

        private val REQUIRED_INDEX_CONTRACT = mapOf(
            "idx_episode_observation_unique" to ReiAnixSqliteExpectedIndex("episode_observations", listOf("episode_id", "source_kind", "scope_kind", "scope_ref", "uri"), unique = true),
            "idx_episode_observation_scope" to ReiAnixSqliteExpectedIndex("episode_observations", listOf("source_kind", "scope_kind", "scope_ref", "state")),
            "idx_episode_observation_volume" to ReiAnixSqliteExpectedIndex("episode_observations", listOf("volume_id", "state")),
            "idx_artwork_entity" to ReiAnixSqliteExpectedIndex("artwork", listOf("entity_type", "entity_id", "artwork_type", "priority"), descendingColumns = setOf("priority")),
            "idx_artwork_status" to ReiAnixSqliteExpectedIndex("artwork", listOf("status", "last_attempt_at")),
            "idx_genre_aliases_genre" to ReiAnixSqliteExpectedIndex("genre_aliases", listOf("genre_id")),
            "idx_anime_genres_genre" to ReiAnixSqliteExpectedIndex("anime_genres", listOf("genre_id", "anime_id")),
            "idx_anime_genres_anime" to ReiAnixSqliteExpectedIndex("anime_genres", listOf("anime_id", "genre_id")),
            "idx_episode_observation_scope_runtime" to ReiAnixSqliteExpectedIndex("episode_observations", listOf("source_kind", "scope_kind", "scope_ref", "uri")),
            "idx_episodes_anime_playback" to ReiAnixSqliteExpectedIndex("episodes", listOf("anime_id", "missing", "watched", "last_played_at")),
            "idx_episodes_source_folder" to ReiAnixSqliteExpectedIndex("episodes", listOf("source_folder")),
            "idx_episodes_volume_availability" to ReiAnixSqliteExpectedIndex("episodes", listOf("volume_id", "availability_state", "missing")),
            "idx_episodes_media_identity" to ReiAnixSqliteExpectedIndex("episodes", listOf("media_identity"), unique = true, predicate = "media_identity IS NOT NULL"),
            "idx_folders_account" to ReiAnixSqliteExpectedIndex("folders", listOf("account_id")),
            "idx_folders_saf_identity" to ReiAnixSqliteExpectedIndex("folders", listOf("saf_identity"), unique = true, predicate = "saf_identity IS NOT NULL"),
            "idx_scan_runs_scan_id" to ReiAnixSqliteExpectedIndex("scan_runs", listOf("scan_id"), unique = true, predicate = "scan_id IS NOT NULL"),
            "idx_scan_runs_status" to ReiAnixSqliteExpectedIndex("scan_runs", listOf("status", "started_at")),
            "idx_anime_anilist_match" to ReiAnixSqliteExpectedIndex("anime", listOf("anilist_id", "anilist_match_status", "anilist_match_manual")),
            "idx_artwork_key" to ReiAnixSqliteExpectedIndex("artwork", listOf("entity_type", "entity_id", "artwork_key"), unique = true, predicate = "artwork_key IS NOT NULL"),
            "idx_artwork_last_access" to ReiAnixSqliteExpectedIndex("artwork", listOf("last_access")),
            "idx_artwork_retry" to ReiAnixSqliteExpectedIndex("artwork", listOf("status", "next_retry_at")),
            "idx_anime_pinned" to ReiAnixSqliteExpectedIndex("anime", listOf("is_pinned", "added_at")),
            "idx_anime_media_kind" to ReiAnixSqliteExpectedIndex("anime", listOf("media_kind", "added_at")),
            "idx_anime_added_title" to ReiAnixSqliteExpectedIndex("anime", listOf("added_at", "title", "id"), descendingColumns = setOf("added_at", "id"), collations = mapOf("title" to "NOCASE")),
            "idx_anime_favorite_added" to ReiAnixSqliteExpectedIndex("anime", listOf("favorite", "added_at", "id"), descendingColumns = setOf("added_at", "id")),
            "idx_episodes_resume" to ReiAnixSqliteExpectedIndex("episodes", listOf("missing", "last_played_at", "anime_id", "episode_type"), descendingColumns = setOf("last_played_at")),
            "idx_episodes_season_number" to ReiAnixSqliteExpectedIndex("episodes", listOf("season", "number", "anime_id")),
            "idx_episodes_hierarchy" to ReiAnixSqliteExpectedIndex("episodes", listOf("anime_id", "episode_type", "season", "number", "absolute_number")),
            "idx_episodes_anime_season_number_abs" to ReiAnixSqliteExpectedIndex("episodes", listOf("anime_id", "season", "number", "absolute_number", "id")),
        )
        private val REQUIRED_INDEXES = REQUIRED_INDEX_CONTRACT.keys

        private val REQUIRED_COLUMN_CONTRACT = mapOf(
            "folders" to mapOf(
                "path" to ReiAnixSqliteExpectedColumn("TEXT", notNull = false, primaryKeyPosition = 1),
                "kind" to ReiAnixSqliteExpectedColumn("TEXT", notNull = true, defaultValue = "'path'"),
                "authorization" to ReiAnixSqliteExpectedColumn("TEXT", notNull = true, defaultValue = "'unknown'"),
                "added_at" to ReiAnixSqliteExpectedColumn("REAL", notNull = true),
            ),
            "anime" to mapOf(
                "id" to ReiAnixSqliteExpectedColumn("INTEGER", notNull = false, primaryKeyPosition = 1),
                "lookup_title" to ReiAnixSqliteExpectedColumn("TEXT", notNull = true),
                "title" to ReiAnixSqliteExpectedColumn("TEXT", notNull = true),
                "added_at" to ReiAnixSqliteExpectedColumn("REAL", notNull = true),
                "metadata_source" to ReiAnixSqliteExpectedColumn("TEXT", notNull = true, defaultValue = "'unknown'"),
                "favorite" to ReiAnixSqliteExpectedColumn("INTEGER", notNull = true, defaultValue = "0"),
            ),
            "episodes" to mapOf(
                "id" to ReiAnixSqliteExpectedColumn("INTEGER", notNull = false, primaryKeyPosition = 1),
                "anime_id" to ReiAnixSqliteExpectedColumn("INTEGER", notNull = true),
                "path" to ReiAnixSqliteExpectedColumn("TEXT", notNull = true),
                "number" to ReiAnixSqliteExpectedColumn("REAL", notNull = false),
                "file_size" to ReiAnixSqliteExpectedColumn("INTEGER", notNull = false),
                "episode_type" to ReiAnixSqliteExpectedColumn("TEXT", notNull = true, defaultValue = "'regular'"),
            ),
            "episode_observations" to mapOf(
                "id" to ReiAnixSqliteExpectedColumn("INTEGER", notNull = false, primaryKeyPosition = 1),
                "episode_id" to ReiAnixSqliteExpectedColumn("INTEGER", notNull = true),
                "source_kind" to ReiAnixSqliteExpectedColumn("TEXT", notNull = true),
                "scope_ref" to ReiAnixSqliteExpectedColumn("TEXT", notNull = true, defaultValue = "''"),
            ),
            "artwork" to mapOf(
                "id" to ReiAnixSqliteExpectedColumn("INTEGER", notNull = false, primaryKeyPosition = 1),
                "discovered_at" to ReiAnixSqliteExpectedColumn("REAL", notNull = true),
                "updated_at" to ReiAnixSqliteExpectedColumn("REAL", notNull = true),
                "manual" to ReiAnixSqliteExpectedColumn("INTEGER", notNull = true, defaultValue = "0"),
            ),
            "preferences" to mapOf(
                "key" to ReiAnixSqliteExpectedColumn("TEXT", notNull = false, primaryKeyPosition = 1),
                "value" to ReiAnixSqliteExpectedColumn("TEXT", notNull = true),
                "updated_at" to ReiAnixSqliteExpectedColumn("REAL", notNull = true),
            ),
            "schema_migrations" to mapOf(
                "version" to ReiAnixSqliteExpectedColumn("INTEGER", notNull = false, primaryKeyPosition = 1),
                "applied_at" to ReiAnixSqliteExpectedColumn("REAL", notNull = true),
            ),
            "anime_genres" to mapOf(
                "anime_id" to ReiAnixSqliteExpectedColumn("INTEGER", notNull = true, primaryKeyPosition = 1),
                "genre_id" to ReiAnixSqliteExpectedColumn("TEXT", notNull = true, primaryKeyPosition = 2),
                "source" to ReiAnixSqliteExpectedColumn("TEXT", notNull = true, defaultValue = "'local'", primaryKeyPosition = 3),
            ),
        )

        private val REQUIRED_FOREIGN_KEYS = mapOf(
            "folders" to emptySet<ReiAnixSqliteForeignKeySnapshot>(),
            "anime" to emptySet(),
            "episodes" to setOf(ReiAnixSqliteForeignKeySnapshot("anime", "anime_id", "id", "NO ACTION", "CASCADE", "NONE")),
            "episode_observations" to setOf(ReiAnixSqliteForeignKeySnapshot("episodes", "episode_id", "id", "NO ACTION", "CASCADE", "NONE")),
            "artwork" to emptySet(),
            "associations" to emptySet(),
            "pending_matches" to emptySet(),
            "account" to emptySet(),
            "preferences" to emptySet(),
            "genres" to emptySet(),
            "genre_aliases" to setOf(ReiAnixSqliteForeignKeySnapshot("genres", "genre_id", "id", "NO ACTION", "CASCADE", "NONE")),
            "anime_genres" to setOf(
                ReiAnixSqliteForeignKeySnapshot("anime", "anime_id", "id", "NO ACTION", "CASCADE", "NONE"),
                ReiAnixSqliteForeignKeySnapshot("genres", "genre_id", "id", "NO ACTION", "CASCADE", "NONE"),
            ),
            "schema_migrations" to emptySet(),
            "scan_runs" to emptySet(),
        )

        private val REQUIRED_UNIQUE_CONSTRAINTS = listOf(
            ReiAnixSqliteExpectedUniqueConstraint("folders", listOf("path")),
            ReiAnixSqliteExpectedUniqueConstraint("anime", listOf("lookup_title")),
            ReiAnixSqliteExpectedUniqueConstraint("episodes", listOf("path")),
            ReiAnixSqliteExpectedUniqueConstraint("artwork", listOf("entity_type", "entity_id", "artwork_type", "source_ref")),
            ReiAnixSqliteExpectedUniqueConstraint("genres", listOf("normalized_name")),
            ReiAnixSqliteExpectedUniqueConstraint("genre_aliases", listOf("normalized_alias")),
            ReiAnixSqliteExpectedUniqueConstraint("associations", listOf("lookup_title")),
            ReiAnixSqliteExpectedUniqueConstraint("pending_matches", listOf("lookup_title")),
            ReiAnixSqliteExpectedUniqueConstraint("account", listOf("key")),
            ReiAnixSqliteExpectedUniqueConstraint("preferences", listOf("key")),
            ReiAnixSqliteExpectedUniqueConstraint("anime_genres", listOf("anime_id", "genre_id", "source")),
        )
    }
}

class ReiAnixSqliteOpenException(message: String, cause: Throwable) :
    IllegalStateException(message, cause)

private data class ReiAnixSqliteIndexListEntry(val unique: Boolean, val partial: Boolean)

internal data class ReiAnixSqliteColumnSnapshot(
    val name: String,
    val declaredType: String,
    val notNull: Boolean,
    val defaultValue: String?,
    val primaryKeyPosition: Int,
)

internal data class ReiAnixSqliteIndexColumn(
    val name: String?,
    val descending: Boolean,
    val collation: String?,
)

internal data class ReiAnixSqliteIndexSnapshot(
    val name: String,
    val table: String,
    val unique: Boolean,
    val partial: Boolean,
    val columns: List<ReiAnixSqliteIndexColumn>,
    val predicate: String?,
)

internal data class ReiAnixSqliteForeignKeySnapshot(
    val table: String,
    val from: String,
    val to: String,
    val onUpdate: String,
    val onDelete: String,
    val match: String,
)

private data class ReiAnixSqliteExpectedColumn(
    val declaredType: String,
    val notNull: Boolean,
    val defaultValue: String? = null,
    val primaryKeyPosition: Int = 0,
)

private data class ReiAnixSqliteExpectedIndex(
    val table: String,
    val columns: List<String>,
    val unique: Boolean = false,
    val predicate: String? = null,
    val descendingColumns: Set<String> = emptySet(),
    val collations: Map<String, String> = emptyMap(),
)

private data class ReiAnixSqliteExpectedUniqueConstraint(
    val table: String,
    val columns: List<String>,
)

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
        } else if (char == '\'' || char == '"') {
            if (pendingSpace && output.isNotEmpty()) output.append(' ')
            pendingSpace = false
            quote = char
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
    val foreignKeys: Map<String, Set<ReiAnixSqliteForeignKeySnapshot>>,
    val incompatibilities: Set<String>,
) {
    val supportsCurrentContract: Boolean
        get() = incompatibilities.isEmpty() &&
            schemaVersion == ReiAnixSqliteDatabase.EXPECTED_SCHEMA_VERSION &&
            sqliteUserVersion == ReiAnixSqliteDatabase.CANONICAL_SQLITE_USER_VERSION &&
            missingRequiredTables.isEmpty() &&
            missingRequiredColumns.isEmpty() &&
            missingRequiredIndexes.isEmpty()
}