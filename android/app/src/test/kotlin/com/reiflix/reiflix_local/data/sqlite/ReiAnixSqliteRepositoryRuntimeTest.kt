package com.reiflix.reiflix_local.data.sqlite

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
 * provides the columns touched by the repository contract while retaining the
 * canonical file path and v29 migration marker.
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
        assertEquals(emptySet<String>(), schemaBefore.missingRequiredTables)
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
        assertEquals(emptySet<String>(), reopened.inspectSchema().missingRequiredTables)
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
        } catch (expected: IllegalStateException) {
            assertTrue(expected is ReiAnixSqliteOpenException)
        }
        assertEquals("not a sqlite database", databaseFile.readText(Charsets.UTF_8))
    }

    private fun createCanonicalFixture() {
        databaseFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE schema_migrations (
                    version INTEGER PRIMARY KEY
                )
                """.trimIndent(),
            )
            db.execSQL("INSERT INTO schema_migrations(version) VALUES (29)")
            db.execSQL(
                """
                CREATE TABLE anime (
                    id INTEGER PRIMARY KEY,
                    lookup_title TEXT NOT NULL,
                    anilist_id INTEGER,
                    title TEXT NOT NULL,
                    romaji TEXT,
                    english TEXT,
                    native TEXT,
                    aliases TEXT,
                    description TEXT,
                    description_original TEXT,
                    cover_url TEXT,
                    cover_cache TEXT,
                    banner_url TEXT,
                    genres TEXT,
                    year INTEGER,
                    season TEXT,
                    status TEXT,
                    episodes_count INTEGER,
                    duration INTEGER,
                    score INTEGER,
                    format TEXT,
                    studio TEXT,
                    metadata_updated_at REAL,
                    metadata_fetched_at REAL,
                    metadata_source TEXT,
                    metadata_confidence TEXT,
                    metadata_status TEXT,
                    metadata_manual_fields TEXT,
                    favorite INTEGER,
                    user_tags TEXT,
                    media_kind TEXT,
                    is_pinned INTEGER,
                    personal_note TEXT,
                    added_at REAL NOT NULL,
                    anilist_match_status TEXT,
                    anilist_match_score REAL,
                    anilist_match_margin REAL,
                    anilist_match_manual INTEGER
                )
                """.trimIndent(),
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
                CREATE TABLE episodes (
                    id INTEGER PRIMARY KEY,
                    anime_id INTEGER NOT NULL,
                    path TEXT NOT NULL,
                    file_name TEXT NOT NULL,
                    season INTEGER NOT NULL DEFAULT 0,
                    number REAL,
                    duration REAL,
                    progress REAL,
                    watched INTEGER,
                    mime_type TEXT,
                    file_size INTEGER,
                    modified_at REAL,
                    source_folder TEXT,
                    absolute_number REAL,
                    relative_path TEXT,
                    volume_id TEXT,
                    volume_uuid TEXT,
                    episode_type TEXT,
                    episode_title TEXT,
                    identification_source TEXT,
                    identification_confidence TEXT,
                    manual_override INTEGER,
                    missing INTEGER,
                    last_played_at REAL,
                    media_identity TEXT,
                    availability_state TEXT
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
                CREATE TABLE folders (
                    path TEXT PRIMARY KEY,
                    name TEXT,
                    kind TEXT,
                    authorization TEXT,
                    account_id TEXT,
                    added_at REAL,
                    last_scan_at REAL,
                    last_error TEXT,
                    saf_authority TEXT,
                    saf_document_id TEXT,
                    saf_volume_id TEXT,
                    saf_identity TEXT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO folders(path, name, added_at)
                VALUES ('/media/show', 'Show Folder', 1000)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE artwork (
                    id INTEGER PRIMARY KEY,
                    entity_type TEXT NOT NULL,
                    entity_id TEXT NOT NULL,
                    artwork_type TEXT NOT NULL,
                    source TEXT NOT NULL,
                    source_ref TEXT,
                    local_path TEXT,
                    external_url TEXT,
                    manual INTEGER,
                    priority INTEGER,
                    status TEXT,
                    discovered_at REAL,
                    updated_at REAL,
                    last_attempt_at REAL,
                    failure_count INTEGER,
                    artwork_key TEXT,
                    variant TEXT,
                    byte_size INTEGER,
                    width INTEGER,
                    height INTEGER,
                    checksum TEXT,
                    content_type TEXT,
                    last_access REAL,
                    next_retry_at REAL,
                    http_status INTEGER
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO artwork(id, entity_type, entity_id, artwork_type, source, priority, updated_at)
                VALUES (3, 'anime', '1', 'cover', 'anilist', 5, 1000)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE preferences (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL,
                    updated_at REAL NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("INSERT INTO preferences(key, value, updated_at) VALUES ('theme', 'light', 900)")
            db.execSQL("CREATE TABLE account (id INTEGER PRIMARY KEY)")
        }
    }
}
