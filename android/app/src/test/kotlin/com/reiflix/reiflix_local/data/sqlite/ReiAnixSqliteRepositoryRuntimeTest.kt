package com.reiflix.reiflix_local.data.sqlite

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Runtime tests against the Android SQLite engine through Robolectric.
 *
 * These are local JVM tests, not instrumented/device/emulator tests. The fixture
 * DDL is generated from an isolated temporary database initialized by the real
 * core.library_store.LibraryStore; the runtime test seeds only synthetic rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReiAnixSqliteRepositoryRuntimeTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val databaseFile: File
        get() = File(File(context.filesDir, "data"), ReiAnixSqliteDatabase.DATABASE_FILE_NAME)

    @Before
    fun resetFixture() {
        cleanupFixture()
    }

    @After
    fun cleanupFixture() {
        databaseFile.delete()
        File(databaseFile.path + "-wal").delete()
        File(databaseFile.path + "-shm").delete()
        databaseFile.parentFile?.delete()
    }

    @Test
    fun missingCanonicalDatabaseIsNotCreatedOrReportedAsAnEmptyLibrary() = runBlocking {
        assertFalse(databaseFile.exists())
        try {
            ReiAnixSqliteLibraryRepository(context).inspectSchema()
            fail("A missing database must fail instead of becoming an empty catalog.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("existing canonical SQLite database"))
        }
        assertFalse("Opening a missing DB must not create the canonical file.", databaseFile.exists())
    }

    @Test
    fun readsPreexistingCatalogAndReportsVersionWithoutChangingSchema() = runBlocking {
        createCanonicalFixture()
        val repository = ReiAnixSqliteLibraryRepository(context)

        val schemaBefore = repository.inspectSchema()
        assertEquals(29, schemaBefore.schemaVersion)
        assertTrue(schemaBefore.missingRequiredTables.isEmpty())
        assertTrue(schemaBefore.missingRequiredColumns.isEmpty())
        assertTrue(schemaBefore.missingRequiredIndexes.isEmpty())
        assertEquals(14, schemaBefore.tables.size)
        assertTrue(schemaBefore.tables.containsAll(setOf(
            "episode_observations", "associations", "pending_matches", "scan_runs",
            "genres", "genre_aliases", "anime_genres",
        )))
        assertTrue(schemaBefore.supportsCurrentContract)
        assertEquals(listOf("ok"), repository.quickCheck())

        val anime = repository.getAnime(1L)
        assertNotNull(anime)
        assertEquals("Original Title", anime!!.title)
        assertEquals("catalog-key", anime.lookupTitle)
        assertEquals(1, repository.listAnime().size)

        val episode = repository.getEpisode(7L)
        assertNotNull(episode)
        assertEquals("/media/show/episode-01.mkv", episode!!.path)
        assertEquals(11.0, episode.progress!!, 0.0)
        assertEquals(1, repository.listEpisodes(1L).size)
        assertEquals(1, repository.listFolders().size)
        assertEquals(1, repository.artworkForAnime(1L).size)

        // Reopen through a separate repository/SQLite connection and verify the
        // schema marker and persisted data remain intact.
        val reopened = ReiAnixSqliteLibraryRepository(context)
        assertEquals("Original Title", reopened.getAnime(1L)!!.title)
        assertEquals(29, reopened.inspectSchema().schemaVersion)
        assertTrue(reopened.inspectSchema().missingRequiredTables.isEmpty())
        assertTrue(reopened.inspectSchema().missingRequiredColumns.isEmpty())
        assertTrue(reopened.inspectSchema().missingRequiredIndexes.isEmpty())
    }

    @Test
    fun playbackUpdatePersistsAcrossConnectionsAndRollbackRestoresPreviousProgress() = runBlocking {
        createCanonicalFixture()
        val repository = ReiAnixSqliteLibraryRepository(context)

        val updated = repository.updatePlaybackProgress(
            ReiAnixPlaybackProgressUpdate(
                episodeId = 7L,
                progress = 321.5,
                duration = 1500.0,
                watched = true,
                playedAtEpochSeconds = 1_700_000_000.0,
            ),
        )
        assertEquals(321.5, updated.progress!!, 0.0)
        assertEquals(1500.0, updated.duration!!, 0.0)
        assertEquals(true, updated.watched)

        val reopened = ReiAnixSqliteLibraryRepository(context)
        assertEquals(321.5, reopened.getEpisode(7L)!!.progress!!, 0.0)

        val database = ReiAnixSqliteDatabase(context)
        try {
            database.transaction { connection ->
                connection.execSQL(
                    "UPDATE episodes SET progress = ? WHERE id = ?",
                    arrayOf(999.0, 7L),
                )
                throw IllegalStateException("force rollback")
            }
            fail("The transaction exception must propagate.")
        } catch (expected: IllegalStateException) {
            assertEquals("force rollback", expected.message)
        }
        assertEquals(
            "The failed transaction must roll back the SQLite write.",
            321.5,
            repository.getEpisode(7L)!!.progress!!,
            0.0,
        )
    }

    @Test
    fun settingsWritesAreVisibleAfterReopenAndDeleteOnlyTheNamedKey() = runBlocking {
        createCanonicalFixture()
        val settings = ReiAnixSqliteSettingsRepository(context)
        settings.setPreference(" player.volume ", "0.65", 1_700_000_001.0)
        settings.setPreference("theme", "dark", 1_700_000_002.0)

        val reopened = ReiAnixSqliteSettingsRepository(context)
        assertEquals("0.65", reopened.getPreference("player.volume")!!.value)
        assertEquals(1_700_000_001.0, reopened.getPreference("player.volume")!!.updatedAt, 0.0)
        assertEquals("dark", reopened.getPreference("theme")!!.value)
        assertTrue(reopened.removePreference("player.volume"))
        assertNull(reopened.getPreference("player.volume"))
        assertEquals("dark", reopened.getPreference("theme")!!.value)
    }

    @Test
    fun metadataUpdatesRespectManualOwnershipAndPreserveUserOwnedFields() = runBlocking {
        createCanonicalFixture()
        val repository = ReiAnixSqliteLibraryRepository(context)

        val updated = repository.updateAnimeMetadata(
            ReiAnixAnimeMetadataPatch(
                animeId = 1L,
                title = "Remote Title",
                coverUrl = "https://example.invalid/new-cover.jpg",
                english = "New English Title",
                genresJson = "[]",
            ),
        )

        assertEquals("Original Title", updated.title)
        assertEquals("https://example.invalid/old-cover.jpg", updated.coverUrl)
        assertEquals("New English Title", updated.english)
        // Empty JSON is treated as "not supplied", not as a destructive clear.
        assertEquals(null, updated.genresJson)
        assertEquals(true, updated.favorite)
        assertEquals("[\"local-tag\"]", updated.userTagsJson)
        assertEquals(true, updated.isPinned)
        assertEquals("keep this note", updated.personalNote)
        assertEquals("catalog-key", updated.lookupTitle)
        assertEquals("anilist", updated.metadataSource)
    }

    @Test
    fun corruptDatabaseDoesNotGetReplacedWithAnEmptyCatalog() = runBlocking {
        databaseFile.parentFile?.mkdirs()
        databaseFile.writeText("not a sqlite database", Charsets.UTF_8)
        try {
            ReiAnixSqliteLibraryRepository(context).inspectSchema()
            fail("A corrupt existing database must fail rather than be replaced.")
        } catch (expected: Exception) {
            // SQLite may detect corruption while opening the file or on the
            // first catalog query; both the explicit wrapper and SQLite's
            // native exception are valid, provided the file remains untouched.
            assertTrue(
                "Expected SQLite corruption/open failure, got ${expected::class.java.name}",
                expected is ReiAnixSqliteOpenException || expected is SQLiteException,
            )
        }
        assertEquals("not a sqlite database", databaseFile.readText(Charsets.UTF_8))
    }

    @Test
    fun futureSchemaVersionIsNotAssumedCompatible() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            db.execSQL("UPDATE schema_migrations SET version = 30 WHERE version = 29")
        }

        val schema = ReiAnixSqliteLibraryRepository(context).inspectSchema()
        assertEquals(30, schema.schemaVersion)
        assertFalse(schema.supportsCurrentContract)
    }

    @Test
    fun missingCanonicalTableIsReportedAsIncompatible() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { db -> db.execSQL("DROP TABLE scan_runs") }

        val schema = ReiAnixSqliteLibraryRepository(context).inspectSchema()
        assertTrue(schema.missingRequiredTables.contains("scan_runs"))
        assertTrue(schema.missingRequiredIndexes.contains("idx_scan_runs_scan_id"))
        assertFalse(schema.supportsCurrentContract)
    }

    @Test
    fun blankRemoteTitleDoesNotEraseAnExistingCatalogTitle() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            db.execSQL("UPDATE anime SET metadata_manual_fields = '[]' WHERE id = 1")
        }

        val updated = ReiAnixSqliteLibraryRepository(context).updateAnimeMetadata(
            ReiAnixAnimeMetadataPatch(animeId = 1L, title = "   "),
        )
        assertEquals("Original Title", updated.title)
        assertEquals(1000.0, updated.addedAt, 0.0)
    }

    private fun loadCanonicalSchemaFixture(): JSONObject {
        val stream = javaClass.classLoader?.getResourceAsStream("native-sqlite-schema-v29.json")
            ?: throw IllegalStateException(
                "Canonical SQLite schema fixture is missing. Run " +
                    "scripts/generate_native_sqlite_test_fixture.py before JVM tests.",
            )
        return stream.bufferedReader(Charsets.UTF_8).use { reader -> JSONObject(reader.readText()) }
    }

    private fun createCanonicalFixture() {
        databaseFile.parentFile?.mkdirs()
        val schema = loadCanonicalSchemaFixture()
        assertEquals(ReiAnixSqliteDatabase.EXPECTED_SCHEMA_VERSION, schema.getInt("schema_version"))

        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            val statements = schema.getJSONArray("statements")
            for (index in 0 until statements.length()) {
                db.execSQL(statements.getString(index))
            }
            db.execSQL(
                "INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)",
                arrayOf(schema.getInt("schema_version"), 1_000.0),
            )
            db.execSQL(
                """
                INSERT INTO anime (
                    id, lookup_title, title, added_at, metadata_manual_fields,
                    favorite, user_tags, is_pinned, personal_note, cover_url,
                    english, metadata_source
                ) VALUES (
                    1, 'catalog-key', 'Original Title', 1000,
                    '["title","cover_url"]', 1, '["local-tag"]', 1,
                    'keep this note', 'https://example.invalid/old-cover.jpg',
                    'Old English Title', 'anilist'
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO episodes (
                    id, anime_id, path, file_name, season, number, duration,
                    progress, watched, last_played_at
                ) VALUES (
                    7, 1, '/media/show/episode-01.mkv', 'episode-01.mkv',
                    1, 1, 1200, 11, 0, NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO folders(path, name, added_at)
                VALUES ('/media/show', 'Show Folder', 1000)
                """.trimIndent(),
            )
            // discovered_at is NOT NULL in the canonical LibraryStore schema.
            db.execSQL(
                """
                INSERT INTO artwork(
                    id, entity_type, entity_id, artwork_type, source, priority,
                    discovered_at, updated_at
                ) VALUES (3, 'anime', '1', 'cover', 'anilist', 5, 1000, 1000)
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO preferences(key, value, updated_at) VALUES ('theme', 'light', 900)",
            )
        }
    }
}
