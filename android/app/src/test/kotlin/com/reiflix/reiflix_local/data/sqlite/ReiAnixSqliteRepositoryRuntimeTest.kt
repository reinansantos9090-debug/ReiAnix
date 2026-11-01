package com.reiflix.reiflix_local.data.sqlite

import android.content.Context
import android.content.ContentValues
import android.graphics.Bitmap
import android.app.Application
import android.os.Build
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
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
import com.reiflix.reiflix_local.data.sqlite.ReiAnixLibraryQueryService
import com.reiflix.reiflix_local.data.sqlite.ReiAnixLibraryQueryResult
import com.reiflix.reiflix_local.data.anilist.readPersistedArtworkDiscoveryAttemptTimes
import com.reiflix.reiflix_local.data.anilist.writePersistedArtworkDiscoveryAttemptAt
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogAnime
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogIdentity
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogIdentifierMapping
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogIdResolver
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogIdentityMatcher
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogMediaKind
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogSource
import com.reiflix.reiflix_local.data.catalog.ReiAnixExternalCatalogIds
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogError
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogResult
import com.reiflix.reiflix_local.data.catalog.ReiAnixAnimeCatalogProvider
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogProviderRegistry
import com.reiflix.reiflix_local.data.catalog.ReiAnixExternalCatalogIntegrationService
import com.reiflix.reiflix_local.data.catalog.ReiAnixExternalRequestCoordinator
import com.reiflix.reiflix_local.data.artwork.ReiAnixArtworkService
import com.reiflix.reiflix_local.data.diagnostics.ReiAnixDiagnosticTimeline
import com.reiflix.reiflix_local.data.library.ReiAnixNativeLibraryCatalogProjector
import com.reiflix.reiflix_local.data.sqlite.toExternalCatalogIdentity
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.withTimeout

/**
 * Runtime tests against the Android SQLite engine through Robolectric.
 *
 * These are local JVM tests, not instrumented/device/emulator tests. A plain
 * Android Application is used intentionally: ReiAnixApplication launches the real
 * process bootstrap, which would race the synthetic database fixture in the same
 * app-private data directory. The fixture DDL comes from the canonical v29 contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
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
    fun transactionOutcomeCallbackDistinguishesCommittedAndRolledBackWrites() = runBlocking {
        createCanonicalFixture()
        val database = ReiAnixSqliteDatabase(context)
        val outcomes = mutableListOf<ReiAnixSqliteTransactionOutcome>()

        database.transaction(onOutcome = { outcomes += it }) { connection ->
            connection.execSQL(
                "INSERT INTO preferences(key, value, updated_at) VALUES(?, ?, ?)",
                arrayOf("native.test.transaction_outcome", "committed", 1.0),
            )
        }
        assertEquals(listOf(ReiAnixSqliteTransactionOutcome.COMMITTED), outcomes)

        outcomes.clear()
        try {
            database.transaction(onOutcome = { outcomes += it }) { connection ->
                connection.execSQL(
                    "UPDATE preferences SET value = ? WHERE key = ?",
                    arrayOf("rolled-back", "native.test.transaction_outcome"),
                )
                throw IllegalStateException("force rollback for outcome callback test")
            }
            fail("The transaction block must propagate its original failure.")
        } catch (expected: IllegalStateException) {
            assertEquals("force rollback for outcome callback test", expected.message)
        }

        assertEquals(listOf(ReiAnixSqliteTransactionOutcome.ROLLED_BACK), outcomes)
        assertEquals(
            "committed",
            ReiAnixSqliteSettingsRepository(context)
                .getPreference("native.test.transaction_outcome")?.value,
        )
    }

    @Test
    fun artworkDiscoveryAttemptTimestampsPersistInCanonicalPreferencesAcrossDatabaseInstances() = runBlocking {
        createCanonicalFixture()
        val firstDatabase = ReiAnixSqliteDatabase(context)
        writePersistedArtworkDiscoveryAttemptAt(firstDatabase, animeId = 1L, attemptAtSeconds = 200_000.25)
        writePersistedArtworkDiscoveryAttemptAt(firstDatabase, animeId = 812L, attemptAtSeconds = 300_000.75)

        // A distinct database wrapper models a later service/process instance.
        val reopenedDatabase = ReiAnixSqliteDatabase(context)
        val attempts = readPersistedArtworkDiscoveryAttemptTimes(reopenedDatabase)
        assertEquals(200_000.25, attempts[1L]!!, 0.0)
        assertEquals(300_000.75, attempts[812L]!!, 0.0)
        assertEquals(2, attempts.size)

        val schema = ReiAnixSqliteLibraryRepository(context).inspectSchema()
        assertEquals(29, schema.schemaVersion)
        assertTrue(schema.missingRequiredTables.isEmpty())
        assertTrue(schema.missingRequiredColumns.isEmpty())
    }


    @Test
    fun verifiedExternalCatalogIdsPersistInV29PreferencesWithoutChangingLibraryRows() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE anime SET year=2016, format='TV', media_kind='series', season='spring' WHERE id=1")
        }
        val localIdentity = ReiAnixCatalogIdentity(
            titles = setOf("catalog-key", "Original Title", "Old English Title"),
            releaseYear = 2016,
            format = "TV",
            mediaKind = ReiAnixCatalogMediaKind.SERIES,
            season = "spring",
        )
        val remote = ReiAnixCatalogAnime(
            source = ReiAnixCatalogSource.KITSU,
            ids = ReiAnixExternalCatalogIds(
                aniListId = 800L,
                myAnimeListId = 42L,
                kitsuId = "kitsu-42",
                aniDbId = 91L,
                additionalIds = mapOf("anipub" to "pub-91"),
            ),
            title = "Original Title",
            alternativeTitles = setOf("Old English Title"),
            releaseYear = 2016,
            format = "TV",
            mediaKind = ReiAnixCatalogMediaKind.SERIES,
            season = "spring",
        )
        val match = ReiAnixCatalogIdentityMatcher.verify(1L, localIdentity, remote)
        assertNotNull("Exact title/year/format/kind/season evidence should match.", match)
        val verifiedMatch = requireNotNull(match)
        val repository = ReiAnixSqliteLibraryRepository(context)
        val saved = repository.saveExternalCatalogIds(verifiedMatch)
        assertEquals(800L, saved.aniListId)
        assertEquals(42L, saved.myAnimeListId)
        assertEquals("kitsu-42", saved.kitsuId)
        assertEquals(91L, saved.aniDbId)
        assertEquals("pub-91", saved.additionalIds["anipub"])
        assertEquals(saved, ReiAnixSqliteLibraryRepository(context).getExternalCatalogIds(1L))

        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM anime", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("External ID discovery must not insert library anime.", 1, cursor.getInt(0))
            }
            db.rawQuery("SELECT COUNT(*) FROM episodes", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("External ID discovery must not change episodes.", 1, cursor.getInt(0))
            }
            db.rawQuery("SELECT anilist_id FROM anime WHERE id=1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue("Cross-catalog IDs must not rewrite the canonical AniList association.", cursor.isNull(0))
            }
        }

        val conflictingMatch = ReiAnixCatalogIdentityMatcher.verify(
            1L, localIdentity, remote.copy(ids = remote.ids.copy(kitsuId = "different-kitsu-id")),
        )
        assertNotNull(conflictingMatch)
        try {
            repository.saveExternalCatalogIds(requireNotNull(conflictingMatch))
            fail("A conflicting verified Kitsu ID must be rejected.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Conflicting verified Kitsu"))
        }

        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE anime SET year=2017 WHERE id=1")
        }
        try {
            repository.saveExternalCatalogIds(verifiedMatch)
            fail("A mapping verified against stale local identity must be rejected.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("identity changed"))
        }
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
    fun initializationCreatesFreshCanonicalDatabaseOnlyAfterExplicitAuthorization() = runBlocking {
        val database = ReiAnixSqliteDatabase(context)
        assertFalse("The fixture starts without a canonical database.", databaseFile.exists())

        try {
            database.initializeOrMigrate(allowCreate = false)
            fail("A missing database must not be created without explicit fresh-install authorization.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("creation was not authorized"))
        }
        assertFalse("Refused initialization must leave the database absent.", databaseFile.exists())

        val created = database.initializeOrMigrate(allowCreate = true)
        assertTrue(databaseFile.isFile)
        assertEquals(29, created.schemaVersion)
        assertEquals(0, created.sqliteUserVersion)
        assertTrue("Fresh schema must satisfy the runtime contract.", created.supportsCompatibleRuntimeContract)
        assertTrue(created.missingRequiredTables.isEmpty())
        assertTrue(created.missingRequiredColumns.isEmpty())
        assertTrue(created.missingRequiredIndexes.isEmpty())
        val canonicalTables = loadCanonicalSchemaFixture().getJSONArray("tables")
        val requiredTables = (0 until canonicalTables.length())
            .map { canonicalTables.getString(it) }
            .toSet()
        assertEquals("The canonical fixture must define the 14 v29 tables.", 14, requiredTables.size)
        assertTrue(
            "Fresh initialization is missing canonical tables: ${requiredTables - created.tables}",
            created.tables.containsAll(requiredTables),
        )

        val repeated = database.initializeOrMigrate(allowCreate = false)
        assertTrue("Reinitialization must remain compatible.", repeated.supportsCompatibleRuntimeContract)
        assertEquals(29, repeated.schemaVersion)
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM schema_migrations WHERE version = 29", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Repeated initialization must not duplicate the v29 marker.", 1, cursor.getInt(0))
            }
        }
    }

    @Test
    fun initializationIsIdempotentAndPreservesExistingCanonicalCatalogData() = runBlocking {
        createCanonicalFixture()
        val originalCanonicalPath = databaseFile.canonicalPath
        val database = ReiAnixSqliteDatabase(context)

        val first = database.initializeOrMigrate(allowCreate = false)
        assertTrue("Existing canonical data must remain compatible: ${first.incompatibilities}", first.supportsCompatibleRuntimeContract)
        val second = database.initializeOrMigrate(allowCreate = false)
        assertTrue("Repeated migration must remain compatible: ${second.incompatibilities}", second.supportsCompatibleRuntimeContract)
        assertEquals(originalCanonicalPath, databaseFile.canonicalPath)

        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT title, personal_note, favorite FROM anime WHERE id = 1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Original Title", cursor.getString(0))
                assertEquals("keep this note", cursor.getString(1))
                assertEquals(1, cursor.getInt(2))
            }
            db.rawQuery("SELECT path, progress FROM episodes WHERE id = 7", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("/media/show/episode-01.mkv", cursor.getString(0))
                assertEquals(11.0, cursor.getDouble(1), 0.0)
            }
            db.rawQuery("SELECT name FROM folders WHERE path = '/media/show'", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Show Folder", cursor.getString(0))
            }
            db.rawQuery("SELECT value FROM preferences WHERE key = 'theme'", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("light", cursor.getString(0))
            }
            db.rawQuery("SELECT COUNT(*) FROM schema_migrations WHERE version = 29", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    @Test
    fun initializationRejectsMissingCoreTablesWithoutRecreatingThemOrReplacingTheCatalog() = runBlocking {
        createCanonicalFixture()
        val originalCanonicalPath = databaseFile.canonicalPath
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE folders")
        }

        try {
            ReiAnixSqliteDatabase(context).initializeOrMigrate(allowCreate = true)
            fail("An existing database missing a core table must require recovery, not auto-recreate it.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("missing existing core tables"))
            assertTrue(expected.message.orEmpty().contains("folders"))
        }

        assertTrue(databaseFile.isFile)
        assertEquals(originalCanonicalPath, databaseFile.canonicalPath)
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'folders'", null).use { cursor ->
                assertFalse("The missing core table must stay missing until explicit recovery.", cursor.moveToFirst())
            }
            db.rawQuery("SELECT title FROM anime WHERE id = 1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Original Title", cursor.getString(0))
            }
            db.rawQuery("SELECT progress FROM episodes WHERE id = 7", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(11.0, cursor.getDouble(0), 0.0)
            }
        }
    }

    @Test
    fun initializationRejectsCorruptExistingDatabaseWithoutReplacingItsBytes() = runBlocking {
        databaseFile.parentFile?.mkdirs()
        databaseFile.writeText("not a sqlite database", Charsets.UTF_8)
        val originalBytes = databaseFile.readBytes()

        try {
            ReiAnixSqliteDatabase(context).initializeOrMigrate(allowCreate = true)
            fail("Initialization must reject a corrupt existing database rather than replace it.")
        } catch (expected: Exception) {
            assertTrue(
                "Expected an SQLite open/corruption failure, got ${expected::class.java.name}",
                expected is ReiAnixSqliteOpenException || expected is SQLiteException,
            )
        }

        assertTrue(databaseFile.exists())
        assertEquals(originalBytes.toList(), databaseFile.readBytes().toList())
    }

    @Test
    fun initializationRejectsEmptyExistingDatabaseWithoutReplacingIt() = runBlocking {
        databaseFile.parentFile?.mkdirs()
        databaseFile.writeBytes(byteArrayOf())

        try {
            ReiAnixSqliteDatabase(context).initializeOrMigrate(allowCreate = true)
            fail("An empty existing file must require recovery instead of fresh creation.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("empty or is not a regular file"))
        }

        assertTrue(databaseFile.exists())
        assertEquals(0L, databaseFile.length())
    }

    @Test
    fun initializationDoesNotOverwriteAnUnexpectedSqliteUserVersion() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("PRAGMA user_version = 29")
        }

        try {
            ReiAnixSqliteDatabase(context).initializeOrMigrate(allowCreate = false)
            fail("Initialization must reject an unexpected PRAGMA user_version without silently changing it.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("SQLite user_version is 29"))
            assertTrue(expected.message.orEmpty().contains("expected 0"))
        }

        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA user_version", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(29, cursor.getInt(0))
            }
            db.rawQuery("SELECT title FROM anime WHERE id = 1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Original Title", cursor.getString(0))
            }
        }
    }


    @Test
    fun bootstrapKeepsNativeServicesGatedWhenCanonicalCoreTablesAreMissing() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE folders")
        }

        val bootstrap = com.reiflix.reiflix_local.ReiAnixAppDependencies(context).bootstrap
        bootstrap.awaitStarted()
        val state = bootstrap.state.value

        assertTrue("A canonical database missing a core table must require recovery.", state.recoveryRequired)
        assertEquals(
            com.reiflix.reiflix_local.ReiAnixBootstrapPersistenceStatus.RECOVERY_REQUIRED,
            state.persistenceStatus,
        )
        assertEquals(
            com.reiflix.reiflix_local.ReiAnixBootstrapStage.RECOVERY_REQUIRED,
            state.stage,
        )
        assertFalse(
            "Native services must not be published as ready while core tables are missing.",
            state.completedStages.contains(com.reiflix.reiflix_local.ReiAnixBootstrapStage.NATIVE_SERVICES_READY),
        )
        assertFalse(
            state.completedStages.contains(com.reiflix.reiflix_local.ReiAnixBootstrapStage.STARTING_NATIVE_COMMAND_DISPATCHER),
        )
    }

    @Test
    fun readsPreexistingCatalogAndReportsVersionWithoutChangingSchema() = runBlocking {
        createCanonicalFixture()
        val repository = ReiAnixSqliteLibraryRepository(context)

        val schemaBefore = repository.inspectSchema()
        println("Prompt 4 SQLite fixture incompatibilities: ${schemaBefore.incompatibilities}")
        assertEquals(29, schemaBefore.schemaVersion)
        assertTrue(schemaBefore.missingRequiredTables.isEmpty())
        assertTrue(schemaBefore.missingRequiredColumns.isEmpty())
        assertTrue(schemaBefore.missingRequiredIndexes.isEmpty())
        // Android SQLite may expose platform metadata tables, and compatible
        // extension tables must survive inspection. Validate the complete
        // canonical table set rather than assuming the physical DB has no extras.
        val fixtureTables = loadCanonicalSchemaFixture().getJSONArray("tables")
        val canonicalTables = (0 until fixtureTables.length())
            .map { fixtureTables.getString(it) }
            .toSet()
        assertEquals("The canonical LibraryStore schema has 14 tables", 14, canonicalTables.size)
        assertTrue(
            "Runtime SQLite is missing canonical tables: ${canonicalTables - schemaBefore.tables}",
            schemaBefore.tables.containsAll(canonicalTables),
        )
        assertTrue("SQLite contract incompatibilities: ${schemaBefore.incompatibilities}", schemaBefore.supportsCurrentContract)
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
    fun runtimeSchemaMetadataMatchesEveryColumnAndIndexInCanonicalFixture() = runBlocking {
        createCanonicalFixture()
        val fixture = loadCanonicalSchemaFixture()
        val snapshot = ReiAnixSqliteLibraryRepository(context).inspectSchema()
        assertEquals(fixture.getInt("sqlite_user_version"), snapshot.sqliteUserVersion)

        val canonicalTables = fixture.getJSONArray("tables")
        for (tableIndex in 0 until canonicalTables.length()) {
            val table = canonicalTables.getString(tableIndex)
            val canonicalColumns = fixture.getJSONObject("columns").getJSONArray(table)
            val actualColumns = snapshot.columnSnapshots[table].orEmpty()
            assertEquals("Column count differs for $table", canonicalColumns.length(), actualColumns.size)
            for (columnIndex in 0 until canonicalColumns.length()) {
                val expected = canonicalColumns.getJSONObject(columnIndex)
                val columnName = expected.getString("name")
                val actual = actualColumns[columnName]
                    ?: throw AssertionError("$table.$columnName missing from PRAGMA table_info")
                assertEquals(table + "." + columnName + " type", expected.getString("type").uppercase(), actual.declaredType)
                assertEquals(table + "." + columnName + " nullability", expected.getBoolean("not_null"), actual.notNull)
                assertEquals(table + "." + columnName + " primary key", expected.getInt("primary_key_position"), actual.primaryKeyPosition)
                val expectedDefault = if (expected.isNull("default")) null else expected.getString("default").replace(Regex("\\s+"), " ").trim()
                assertEquals(table + "." + columnName + " default", expectedDefault, actual.defaultValue)
            }
        }

        val canonicalIndexes = fixture.getJSONArray("indexes")
        for (index in 0 until canonicalIndexes.length()) {
            val expected = canonicalIndexes.getJSONObject(index)
            val indexName = expected.getString("name")
            val actual = snapshot.indexSnapshots[indexName]
                ?: throw AssertionError("Index $indexName missing from SQLite metadata")
            assertEquals(expected.getString("table"), actual.table)
            assertEquals(expected.getBoolean("unique"), actual.unique)
            assertEquals(expected.getBoolean("partial"), actual.partial)
            val expectedColumns = expected.getJSONArray("columns")
            val expectedNames = (0 until expectedColumns.length()).map { expectedColumns.getJSONObject(it).getString("name") }
            assertEquals(expectedNames, actual.columns.map { it.name.orEmpty() })
            val expectedColumnIds = (0 until expectedColumns.length()).map { expectedColumns.getJSONObject(it).getInt("column_id") }
            assertEquals(expectedColumnIds, actual.columns.map { it.columnId })
            val expectedExpressions = (0 until expectedColumns.length()).map { expectedColumns.getJSONObject(it).getBoolean("expression") }
            assertEquals(expectedExpressions, actual.columns.map { it.expression })
            val expectedDescending = (0 until expectedColumns.length())
                .filter { expectedColumns.getJSONObject(it).getBoolean("descending") }
                .map { expectedColumns.getJSONObject(it).getString("name") }
                .toSet()
            assertEquals(expectedDescending, actual.columns.mapIndexedNotNull { columnIndex, column ->
                if (column.descending) expectedNames.getOrNull(columnIndex) else null
            }.toSet())
            for (columnIndex in 0 until expectedColumns.length()) {
                val expectedColumn = expectedColumns.getJSONObject(columnIndex)
                assertEquals(
                    "Index collation ${expected.getString("name")}.${expectedColumn.getString("name")}",
                    expectedColumn.optString("collation").uppercase(),
                    actual.columns[columnIndex].collation,
                )
            }
            val expectedPredicate = if (expected.isNull("predicate")) null else expected.getString("predicate")
            if (expectedPredicate != null) {
                assertEquals(expectedPredicate.replace(Regex("\\s+"), " ").trim().uppercase(), actual.predicate)
            }
        }

        val tableContract = fixture.getJSONObject("table_contract")
        for (tableIndex in 0 until canonicalTables.length()) {
            val tableName = canonicalTables.getString(tableIndex)
            val expected = tableContract.getJSONObject(tableName)
            val actual = snapshot.tableContract[tableName]
                ?: throw AssertionError("Table contract metadata missing for $tableName")
            assertEquals(expected.getBoolean("strict"), actual.strict)
            assertEquals(expected.getBoolean("without_rowid"), actual.withoutRowId)
            val checks = expected.getJSONArray("checks")
            assertEquals(
                (0 until checks.length()).map { checks.getString(it) },
                actual.checks,
            )
        }
        val expectedTriggers = fixture.getJSONArray("triggers")
        assertEquals(expectedTriggers.length(), snapshot.triggers.size)
        for (triggerIndex in 0 until expectedTriggers.length()) {
            val expected = expectedTriggers.getJSONObject(triggerIndex)
            val actual = snapshot.triggers[expected.getString("name")]
                ?: throw AssertionError("Trigger ${expected.getString("name")} missing")
            assertEquals(expected.getString("table"), actual.table)
            assertEquals(expected.getString("sql"), actual.sql)
        }

        val canonicalForeignKeys = fixture.getJSONObject("foreign_keys")
        for (tableIndex in 0 until canonicalTables.length()) {
            val tableName = canonicalTables.getString(tableIndex)
            val expectedKeys = canonicalForeignKeys.getJSONArray(tableName)
            val expectedKeysInOrder = buildList {
                for (keyIndex in 0 until expectedKeys.length()) {
                    val key = expectedKeys.getJSONObject(keyIndex)
                    add(
                        ReiAnixSqliteForeignKeySnapshot(
                            id = key.getInt("id"),
                            sequence = key.getInt("sequence"),
                            table = key.getString("table"),
                            from = key.getString("from"),
                            to = key.getString("to"),
                            onUpdate = key.getString("on_update").uppercase(),
                            onDelete = key.getString("on_delete").uppercase(),
                            match = key.getString("match").uppercase(),
                        ),
                    )
                }
            }.sortedWith(compareBy<ReiAnixSqliteForeignKeySnapshot> { it.id }.thenBy { it.sequence })
            assertEquals("Foreign keys differ for $tableName", expectedKeysInOrder, snapshot.foreignKeys[tableName].orEmpty())
        }
        assertTrue(snapshot.supportsCurrentContract)
    }

    @Test
    fun compatibleLegacyColumnsAndIndexesRemainReadableWithoutRebuildingUserTables() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ALTER TABLE episodes ADD COLUMN legacy_annotation TEXT")
            db.execSQL("CREATE INDEX idx_legacy_episode_annotation ON episodes(legacy_annotation)")
            db.execSQL("UPDATE episodes SET legacy_annotation='preserve-me' WHERE id=7")
        }

        val repository = ReiAnixSqliteLibraryRepository(context)
        val schema = repository.inspectSchema()
        println(
            "LEGACY_COMPATIBILITY_DIAGNOSTIC " +
                "supported=${schema.supportsCompatibleRuntimeContract} " +
                "strict=${schema.supportsCurrentContract} " +
                "issues=${schema.incompatibilities} " +
                "missingTables=${schema.missingRequiredTables} " +
                "missingColumns=${schema.missingRequiredColumns} " +
                "missingIndexes=${schema.missingRequiredIndexes} " +
                "integrityErrors=${schema.integrityErrors} " +
                "foreignKeyViolations=${schema.foreignKeyViolations}",
        )
        assertFalse("Strict diagnostics must continue reporting legacy schema drift.", schema.supportsCurrentContract)
        assertTrue(
            "Ordinary legacy columns/indexes must not route an otherwise healthy catalog to Recovery Mode: ${schema.incompatibilities}",
            schema.supportsCompatibleRuntimeContract,
        )
        assertEquals("Original Title", repository.getAnime(1L)?.title)
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT legacy_annotation FROM episodes WHERE id=7", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("preserve-me", cursor.getString(0))
            }
        }
    }

    @Test
    fun migrationRepairsStaleCanonicalIndexDefinitionWithoutRebuildingCatalogTables() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP INDEX IF EXISTS idx_episodes_anime_playback")
            db.execSQL("CREATE INDEX idx_episodes_anime_playback ON episodes(anime_id)")
        }

        val database = ReiAnixSqliteDatabase(context)
        val migrated = database.initializeOrMigrate(allowCreate = false)
        assertTrue("Migration should restore the canonical index: ${migrated.incompatibilities}", migrated.supportsCurrentContract)
        assertEquals(
            listOf("anime_id", "missing", "watched", "last_played_at"),
            migrated.indexSnapshots["idx_episodes_anime_playback"]?.columns?.mapNotNull { it.name },
        )
        assertEquals("Original Title", ReiAnixSqliteLibraryRepository(database).getAnime(1L)?.title)
    }

    @Test
    fun applicationRepositoryRejectsAnIncompatibleSchemaBeforeServingRows() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ALTER TABLE episodes RENAME COLUMN file_name TO old_file_name")
        }
        try {
            ReiAnixSqliteLibraryRepository(context).getAnime(1L)
            fail("A repository read must reject an incompatible schema, not only expose a diagnostic snapshot.")
        } catch (expected: ReiAnixSqliteSchemaMismatchException) {
            assertTrue(expected.incompatibilities.any {
                it.contains("episodes.file_name") || it.contains("unexpected columns in episodes")
            })
        }
    }

    @Test
    fun additiveTablesOutsideTheCanonicalNamespaceDoNotInvalidateTheLibraryContract() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TABLE extension_cache (key TEXT PRIMARY KEY, value TEXT)")
        }
        val schema = ReiAnixSqliteLibraryRepository(context).inspectSchema()
        assertTrue(schema.tables.contains("extension_cache"))
        assertTrue(schema.supportsCurrentContract)
    }

    @Test
    fun foreignKeyDataViolationsAreReportedWithoutRepairingRows() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.setForeignKeyConstraintsEnabled(false)
            db.execSQL(
                "INSERT INTO episodes(id, anime_id, path, file_name, season) " +
                    "VALUES (99, 999, '/orphan/episode.mkv', 'episode.mkv', 1)",
            )
        }
        val schema = ReiAnixSqliteLibraryRepository(context).inspectSchema()
        assertFalse(schema.supportsCurrentContract)
        assertTrue(schema.foreignKeyViolations.isNotEmpty())
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM episodes WHERE id = 99", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    @Test
    fun unexpectedTriggerIsRejectedBecauseItCanChangeCatalogWrites() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                "CREATE TRIGGER injected_contract_trigger AFTER INSERT ON anime " +
                    "BEGIN UPDATE anime SET favorite = 1 WHERE id = NEW.id; END",
            )
        }
        val schema = ReiAnixSqliteLibraryRepository(context).inspectSchema()
        assertFalse(schema.supportsCurrentContract)
        assertTrue(schema.incompatibilities.any { it.contains("trigger names differ") })
    }

    @Test
    fun checkConstraintNotPresentInCanonicalContractIsDetected() = runBlocking {
        createCanonicalFixture { schema ->
            replaceCreateTableStatement(schema, "folders") { sql ->
                sql.replace("saf_identity TEXT", "saf_identity TEXT CHECK (length(saf_identity) >= 0)")
            }
        }
        val schema = ReiAnixSqliteLibraryRepository(context).inspectSchema()
        assertFalse(schema.supportsCurrentContract)
        assertTrue(schema.incompatibilities.any { it.contains("folders CHECK constraints") })
    }

    @Test
    fun generatedColumnIsDetectedByTableXinfoAndRejectedAtTheRuntimeBoundary() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                "ALTER TABLE episodes ADD COLUMN generated_label TEXT " +
                    "GENERATED ALWAYS AS (path || '.generated') VIRTUAL",
            )
        }
        val schema = ReiAnixSqliteLibraryRepository(context).inspectSchema()
        assertFalse(schema.supportsCurrentContract)
        assertTrue(schema.incompatibilities.any { it.contains("unexpected columns in episodes") })
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
    fun settingsBatchWritesAreAtomicAndUnchangedValuesKeepTheirTimestamp() = runBlocking {
        createCanonicalFixture()
        val settings = ReiAnixSqliteSettingsRepository(context)
        settings.setPreference("player.auto_hide_seconds", "5", 1_700_000_001.0)

        settings.setPreference("player.auto_hide_seconds", "05", 1_700_000_002.0)
        assertEquals("5", settings.getPreference("player.auto_hide_seconds")!!.value)
        assertEquals(
            1_700_000_001.0,
            settings.getPreference("player.auto_hide_seconds")!!.updatedAt,
            0.0,
        )

        val written = settings.setPreferencesAtomically(
            mapOf("appearance.theme" to "light", "player.resume" to "false"),
            1_700_000_003.0,
        )
        assertEquals("light", written["appearance.theme"]!!.value)
        assertEquals("false", written["player.resume"]!!.value)
        val reopened = ReiAnixSqliteSettingsRepository(context)
        assertEquals("light", reopened.getPreference("appearance.theme")!!.value)
        assertEquals("false", reopened.getPreference("player.resume")!!.value)
    }

    @Test
    fun invalidBatchDoesNotWriteAnyPreference() = runBlocking {
        createCanonicalFixture()
        val settings = ReiAnixSqliteSettingsRepository(context)
        try {
            settings.setPreferencesAtomically(
                mapOf("appearance.theme" to "light", "player.auto_hide_seconds" to "7"),
                1_700_000_004.0,
            )
            fail("Invalid preference batch must be rejected.")
        } catch (expected: IllegalArgumentException) {
            // All values are validated before the SQLite transaction starts.
        }
        assertNull(settings.getPreference("appearance.theme"))
        assertNull(settings.getPreference("player.auto_hide_seconds"))
    }

    @Test
    fun sqliteFailureRollsBackEveryPreferenceInTheBatch() = runBlocking {
        createCanonicalFixture()
        val database = ReiAnixSqliteDatabase(context)
        database.transaction { connection ->
            connection.execSQL(
                """
                CREATE TRIGGER fail_player_resume_insert
                BEFORE INSERT ON preferences
                WHEN NEW.key = 'player.resume'
                BEGIN
                    SELECT RAISE(ABORT, 'forced settings test failure');
                END
                """.trimIndent(),
            )
        }

        val settings = ReiAnixSqliteSettingsRepository(context)
        try {
            settings.setPreferencesAtomically(
                mapOf("appearance.theme" to "light", "player.resume" to "false"),
                1_700_000_005.0,
            )
            fail("Injected SQLite failure must abort the batch.")
        } catch (expected: android.database.sqlite.SQLiteException) {
            // The transaction must roll back the earlier insert as well.
        }
        assertNull(settings.getPreference("appearance.theme"))
        assertNull(settings.getPreference("player.resume"))
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
    fun aniListAssociationPersistsOriginalAndLocalizedSynopsisSeparately() = runBlocking {
        createCanonicalFixture()
        val repository = ReiAnixSqliteLibraryRepository(context)
        val updated = repository.updateAniListMetadataAndAssociation(
            patch = ReiAnixAnimeMetadataPatch(
                animeId = 1L,
                description = "A sinopse em português.",
                descriptionOriginal = "The original synopsis.",
            ),
            anilistId = 42L,
            manual = true,
            score = 1.0,
            margin = 1.0,
        )

        assertEquals("A sinopse em português.", updated.description)
        assertEquals("The original synopsis.", updated.descriptionOriginal)
        assertEquals(42L, updated.anilistId)
    }

    @Test
    fun genericMetadataPatchPersistsOriginalSynopsisWithoutClearingLocalFields() = runBlocking {
        createCanonicalFixture()
        val repository = ReiAnixSqliteLibraryRepository(context)
        val before = repository.getAnime(1L)!!
        val updated = repository.updateAnimeMetadata(
            ReiAnixAnimeMetadataPatch(
                animeId = 1L,
                descriptionOriginal = "The original synopsis.",
            ),
        )

        assertEquals("The original synopsis.", updated.descriptionOriginal)
        assertEquals(before.favorite, updated.favorite)
        assertEquals(before.lookupTitle, updated.lookupTitle)
    }

    @Test
    fun automaticAniListOutcomeIsPersistedWithoutOverwritingManualAssociation() = runBlocking {
        createCanonicalFixture()
        val repository = ReiAnixSqliteLibraryRepository(context)

        val attempted = repository.recordAniListAutoMatchOutcome(1L, "not_found")
        assertEquals("not_found", attempted.anilistMatchStatus)
        assertNull(attempted.anilistId)

        repository.updateAniListMetadataAndAssociation(
            patch = ReiAnixAnimeMetadataPatch(
                animeId = 1L,
                title = "Manually selected title",
            ),
            anilistId = 42L,
            manual = true,
            score = 1.0,
            margin = 1.0,
        )
        val afterManualSelection = repository.recordAniListAutoMatchOutcome(1L, "ambiguous")
        assertEquals(42L, afterManualSelection.anilistId)
        assertEquals("manual", afterManualSelection.anilistMatchStatus)
        assertEquals("Manually selected title", afterManualSelection.title)
    }

    @Test
    fun manualAniListAssociationPreservesProtectedAndManualMetadataBookkeeping() = runBlocking {
        createCanonicalFixture()
        val database = ReiAnixSqliteDatabase(context)
        database.transaction { connection ->
            connection.execSQL(
                "UPDATE anime SET metadata_source = 'manual', metadata_status = 'manual', metadata_confidence = 'high', metadata_updated_at = 1234 WHERE id = 1",
            )
        }

        val repository = ReiAnixSqliteLibraryRepository(context)
        val updated = repository.updateAniListMetadataAndAssociation(
            patch = ReiAnixAnimeMetadataPatch(
                animeId = 1L,
                title = "Remote Title",
                coverUrl = "https://example.invalid/new-cover.jpg",
                english = "New English Title",
                metadataFetchedAt = 2000.0,
            ),
            anilistId = 42L,
            manual = true,
            score = 1.0,
            margin = 1.0,
        )

        assertEquals(42L, updated.anilistId)
        assertEquals(true, updated.anilistMatchManual)
        assertEquals("manual", updated.anilistMatchStatus)
        assertEquals(1.0, updated.anilistMatchScore!!, 0.0)
        assertEquals(1.0, updated.anilistMatchMargin!!, 0.0)
        assertEquals("Original Title", updated.title)
        assertEquals("https://example.invalid/old-cover.jpg", updated.coverUrl)
        assertEquals("New English Title", updated.english)
        assertEquals("manual", updated.metadataSource)
        assertEquals("manual", updated.metadataStatus)
        assertEquals("high", updated.metadataConfidence)
        assertEquals(1234.0, updated.metadataUpdatedAt!!, 0.0)
    }

    @Test
    fun automaticAniListAssociationDoesNotReplaceAnExistingDifferentAssociation() = runBlocking {
        createCanonicalFixture()
        val database = ReiAnixSqliteDatabase(context)
        database.transaction { connection ->
            connection.execSQL(
                "UPDATE anime SET anilist_id = 99, anilist_match_manual = 0, anilist_match_status = 'matched' WHERE id = 1",
            )
        }

        val repository = ReiAnixSqliteLibraryRepository(context)
        val updated = repository.updateAniListMetadataAndAssociation(
            patch = ReiAnixAnimeMetadataPatch(animeId = 1L, title = "Must Not Replace"),
            anilistId = 42L,
            manual = false,
            score = 0.96,
            margin = 0.20,
        )

        assertEquals(99L, updated.anilistId)
        assertEquals("Original Title", updated.title)
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

    @Test
    fun emptyDatabaseFileIsRejectedWithoutReplacement() = runBlocking {
        databaseFile.parentFile?.mkdirs()
        databaseFile.writeBytes(byteArrayOf())
        try {
            ReiAnixSqliteLibraryRepository(context).inspectSchema()
            fail("An empty existing file must not be treated as an empty library.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("existing canonical SQLite database"))
        }
        assertTrue(databaseFile.exists())
        assertEquals(0L, databaseFile.length())
    }

    @Test
    fun schemaMigrationVersionAndSqliteUserVersionMustAgreeWithCanonicalSemantics() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("PRAGMA user_version = 29")
        }
        val schema = ReiAnixSqliteLibraryRepository(context).inspectSchema()
        assertEquals(29, schema.schemaVersion)
        assertEquals(29, schema.sqliteUserVersion)
        assertFalse(schema.supportsCurrentContract)
        assertTrue(schema.incompatibilities.any { it.contains("PRAGMA user_version expected 0") })
    }

    @Test
    fun declaredColumnTypeMismatchIsRejected() = runBlocking {
        createCanonicalFixture { schema ->
            replaceCreateTableStatement(schema, "episodes") { sql ->
                sql.replace("number REAL", "number TEXT")
            }
        }
        val schema = ReiAnixSqliteLibraryRepository(context).inspectSchema()
        assertFalse(schema.supportsCurrentContract)
        assertTrue(schema.incompatibilities.any { it.contains("episodes.number declared type") })
    }

    @Test
    fun missingRequiredColumnIsRejected() = runBlocking {
        createCanonicalFixture { schema ->
            replaceCreateTableStatement(schema, "episodes") { sql ->
                sql.replace("relative_path TEXT, ", "")
            }
        }
        val schema = ReiAnixSqliteLibraryRepository(context).inspectSchema()
        assertTrue(schema.missingRequiredColumns["episodes"].orEmpty().contains("relative_path"))
        assertFalse(schema.supportsCurrentContract)
    }

    @Test
    fun nullabilityMismatchIsRejected() = runBlocking {
        createCanonicalFixture { schema ->
            replaceCreateTableStatement(schema, "artwork") { sql ->
                sql.replace("discovered_at REAL NOT NULL", "discovered_at REAL")
            }
        }
        val schema = ReiAnixSqliteLibraryRepository(context).inspectSchema()
        assertFalse(schema.supportsCurrentContract)
        assertTrue(schema.incompatibilities.any { it.contains("artwork.discovered_at NOT NULL expected true") })
    }

    @Test
    fun defaultValueMismatchIsRejected() = runBlocking {
        createCanonicalFixture { schema ->
            replaceCreateTableStatement(schema, "folders") { sql ->
                sql.replace("DEFAULT 'path'", "DEFAULT 'other'")
            }
        }
        val schema = ReiAnixSqliteLibraryRepository(context).inspectSchema()
        assertFalse(schema.supportsCurrentContract)
        assertTrue(schema.incompatibilities.any { it.contains("folders.kind default expected") })
    }

    @Test
    fun compositePrimaryKeyOrderMismatchIsRejected() = runBlocking {
        createCanonicalFixture { schema ->
            replaceCreateTableStatement(schema, "anime_genres") { sql ->
                sql.replace(
                    "PRIMARY KEY(anime_id, genre_id, source)",
                    "PRIMARY KEY(anime_id, source, genre_id)",
                )
            }
        }
        val schema = ReiAnixSqliteLibraryRepository(context).inspectSchema()
        assertFalse(schema.supportsCurrentContract)
        assertTrue(schema.incompatibilities.any { it.contains("anime_genres.genre_id primary-key position expected 2") })
        assertTrue(schema.incompatibilities.any { it.contains("anime_genres.source primary-key position expected 3") })
    }

    @Test
    fun missingUniqueConstraintIsRejectedEvenWhenColumnsStillExist() = runBlocking {
        createCanonicalFixture { schema ->
            replaceCreateTableStatement(schema, "anime") { sql ->
                sql.replace("lookup_title TEXT UNIQUE NOT NULL", "lookup_title TEXT NOT NULL")
            }
        }
        val schema = ReiAnixSqliteLibraryRepository(context).inspectSchema()
        assertFalse(schema.supportsCurrentContract)
        assertTrue(schema.incompatibilities.any {
            it.contains("missing unique constraint on anime[lookup_title]")
        })
    }

    @Test
    fun foreignKeyOnDeleteContractIsValidated() = runBlocking {
        createCanonicalFixture { schema ->
            replaceCreateTableStatement(schema, "episodes") { sql ->
                sql.replace("REFERENCES anime(id) ON DELETE CASCADE", "REFERENCES anime(id)")
            }
        }
        val schema = ReiAnixSqliteLibraryRepository(context).inspectSchema()
        assertFalse(schema.supportsCurrentContract)
        assertTrue(schema.incompatibilities.any { it.contains("episodes foreign keys differ") })
    }

    @Test
    fun missingRequiredIndexIsRejectedIndependentlyOfItsTable() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP INDEX idx_artwork_entity")
        }
        val schema = ReiAnixSqliteLibraryRepository(context).inspectSchema()
        assertTrue(schema.missingRequiredIndexes.contains("idx_artwork_entity"))
        assertTrue(schema.incompatibilities.any { it.contains("missing index: idx_artwork_entity") })
        assertFalse(schema.supportsCurrentContract)
    }

    @Test
    fun unexpectedExplicitIndexOnCanonicalTableIsRejected() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE INDEX injected_contract_index ON anime(title)")
        }

        val schema = ReiAnixSqliteLibraryRepository(context).inspectSchema()
        assertFalse(schema.supportsCurrentContract)
        assertTrue(schema.incompatibilities.any {
            it.contains("unexpected indexes on canonical tables: injected_contract_index")
        })
    }

    @Test
    fun sameIndexNameWithDifferentUniquenessIsRejected() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP INDEX idx_episode_observation_unique")
            db.execSQL(
                "CREATE INDEX idx_episode_observation_unique " +
                    "ON episode_observations(episode_id, source_kind, scope_kind, scope_ref, uri)",
            )
        }
        val schema = ReiAnixSqliteLibraryRepository(context).inspectSchema()
        assertFalse(schema.supportsCurrentContract)
        assertTrue(schema.incompatibilities.any { it.contains("index idx_episode_observation_unique unique expected true") })
    }

    @Test
    fun sameIndexNameWithWrongCompoundColumnOrderIsRejected() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP INDEX idx_episodes_anime_season_number_abs")
            db.execSQL(
                "CREATE INDEX idx_episodes_anime_season_number_abs " +
                    "ON episodes(season, anime_id, number, absolute_number, id)",
            )
        }
        val schema = ReiAnixSqliteLibraryRepository(context).inspectSchema()
        assertFalse(schema.supportsCurrentContract)
        assertTrue(schema.incompatibilities.any { it.contains("index idx_episodes_anime_season_number_abs columns expected") })
    }

    @Test
    fun partialIndexPredicateIsValidatedNotOnlyItsName() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP INDEX idx_episodes_media_identity")
            db.execSQL("CREATE UNIQUE INDEX idx_episodes_media_identity ON episodes(media_identity)")
        }
        val schema = ReiAnixSqliteLibraryRepository(context).inspectSchema()
        assertFalse(schema.supportsCurrentContract)
        assertTrue(schema.incompatibilities.any { it.contains("index idx_episodes_media_identity partial expected true") })
    }

    private fun replaceCreateTableStatement(
        schema: JSONObject,
        table: String,
        transform: (String) -> String,
    ) {
        val statements = schema.getJSONArray("statements")
        var replaced = false
        for (index in 0 until statements.length()) {
            val sql = statements.getString(index)
            if (sql.startsWith("CREATE TABLE $table ") || sql.startsWith("CREATE TABLE $table(")) {
                statements.put(index, transform(sql))
                replaced = true
                break
            }
        }
        assertTrue("Expected canonical CREATE TABLE statement for $table", replaced)
    }

    private fun loadCanonicalSchemaFixture(): JSONObject {
        val stream = javaClass.classLoader?.getResourceAsStream("native-sqlite-schema-v29.json")
            ?: throw IllegalStateException(
                "Canonical SQLite schema fixture is missing. Run " +
                    "scripts/generate_native_sqlite_test_fixture.py before JVM tests.",
            )
        return stream.bufferedReader(Charsets.UTF_8).use { reader -> JSONObject(reader.readText()) }
    }

    @Test
    fun indexingInsertsOnceAndReexecutionPreservesExistingEpisodeIdentity() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE episodes SET source_folder = ? WHERE id = ?", arrayOf("/media/show", 7L))
        }
        val repository = ReiAnixSqliteLibraryRepository(context)
        val snapshot = NativeLibraryScanSnapshot(
            operationId = "index-insert-idempotent",
            source = "saf",
            scopeRoot = "/media/show",
            expectedLocations = setOf("/media/show"),
            processedLocations = setOf("/media/show"),
            status = "COMPLETED",
            complete = true,
            authorizationValid = true,
            items = listOf(
                NativeDiscoveredMedia(
                    location = "/media/show/episode-02.mkv",
                    fileName = "episode-02.mkv",
                    scopeLocation = "/media/show/episode-02.mkv",
                    animeTitle = "Original Title",
                    season = 1,
                    episodeNumber = 2.0,
                    identificationSource = "sxxexx",
                    identificationConfidence = "high",
                    sourceFolder = "/media/show",
                    mediaIdentity = "shared:primary:show/episode-02.mkv",
                ),
            ),
        )

        val first = repository.indexDiscoveredMedia(snapshot)
        assertEquals(1, first.inserted)
        val inserted = repository.listEpisodes(1L).single { it.path.endsWith("episode-02.mkv") }
        assertTrue(inserted.id > 7L)

        val second = repository.indexDiscoveredMedia(snapshot.copy(operationId = "index-retry"))
        assertEquals(0, second.inserted)
        assertEquals(1, second.unchanged)
        assertEquals(inserted.id, repository.listEpisodes(1L).single { it.path.endsWith("episode-02.mkv") }.id)
        assertEquals(11.0, repository.getEpisode(7L)!!.progress!!, 0.0)
    }

    @Test
    fun partialOrCancelledScanNeverMarksOldRowsMissing() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE episodes SET source_folder = ? WHERE id = ?", arrayOf("/media/show", 7L))
        }
        val report = ReiAnixSqliteLibraryRepository(context).indexDiscoveredMedia(
            NativeLibraryScanSnapshot(
                operationId = "partial-scan",
                source = "saf",
                scopeRoot = "/media/show",
                expectedLocations = setOf("/media/show"),
                processedLocations = emptySet(),
                failedLocations = setOf("/media/show/season-2"),
                status = "PARTIAL",
                complete = false,
                cancelled = true,
                authorizationValid = true,
                items = emptyList(),
            ),
        )
        assertFalse(report.reconciliationAuthorized)
        assertEquals(0, report.reconciledMissing)
        assertEquals(false, ReiAnixSqliteLibraryRepository(context).getEpisode(7L)!!.missing)
    }

    @Test
    fun emptyCompleteScanMarksOnlyRowsInsideTheVerifiedScopeMissing() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE episodes SET source_folder = ? WHERE id = ?", arrayOf("/media/show", 7L))
            db.execSQL(
                "INSERT INTO episodes(id, anime_id, path, file_name, season, number, source_folder) VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf(8L, 1L, "/media/other/episode-02.mkv", "episode-02.mkv", 1, 2.0, "/media/other"),
            )
        }
        val repository = ReiAnixSqliteLibraryRepository(context)
        val report = repository.indexDiscoveredMedia(
            NativeLibraryScanSnapshot(
                operationId = "empty-complete",
                source = "saf",
                scopeRoot = "/media/show",
                expectedLocations = setOf("/media/show"),
                processedLocations = setOf("/media/show"),
                status = "EMPTY_COMPLETE",
                complete = true,
                authorizationValid = true,
                items = emptyList(),
            ),
        )
        assertTrue(report.reconciliationAuthorized)
        assertEquals(1, report.reconciledMissing)
        assertEquals(true, repository.getEpisode(7L)!!.missing)
        assertEquals(false, repository.getEpisode(8L)!!.missing)
        assertEquals(11.0, repository.getEpisode(7L)!!.progress!!, 0.0)
    }

    @Test
    fun ambiguousSecondReleaseBlocksReconciliationAndPreservesOriginalReference() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE episodes SET source_folder = ?, media_identity = ? WHERE id = ?",
                arrayOf("/media/show", "shared:primary:show/episode-01.mkv", 7L))
        }
        val report = ReiAnixSqliteLibraryRepository(context).indexDiscoveredMedia(
            NativeLibraryScanSnapshot(
                operationId = "ambiguous-release",
                source = "saf",
                scopeRoot = "/media/show",
                expectedLocations = setOf("/media/show"),
                processedLocations = setOf("/media/show"),
                status = "COMPLETED",
                complete = true,
                authorizationValid = true,
                items = listOf(
                    NativeDiscoveredMedia(
                        location = "/media/show/renamed-release.mkv",
                        fileName = "renamed-release.mkv",
                        scopeLocation = "/media/show/renamed-release.mkv",
                        animeTitle = "Original Title",
                        season = 1,
                        episodeNumber = 1.0,
                        identificationConfidence = "high",
                        sourceFolder = "/media/show",
                        mediaIdentity = "shared:primary:show/renamed-release.mkv",
                    ),
                ),
            ),
        )
        assertFalse(report.reconciliationAuthorized)
        assertEquals(1, report.ambiguous)
        assertEquals("/media/show/episode-01.mkv", ReiAnixSqliteLibraryRepository(context).getEpisode(7L)!!.path)
        assertEquals(false, ReiAnixSqliteLibraryRepository(context).getEpisode(7L)!!.missing)
        assertEquals(11.0, ReiAnixSqliteLibraryRepository(context).getEpisode(7L)!!.progress!!, 0.0)
    }

    @Test
    fun confidentlyParsedNewTitleCreatesLocalAnimeWithoutRemoteAssociation() = runBlocking {
        createCanonicalFixture()
        val repository = ReiAnixSqliteLibraryRepository(context)
        val report = repository.indexDiscoveredMedia(
            NativeLibraryScanSnapshot(
                operationId = "new-local-anime",
                source = "saf",
                scopeRoot = "/media/new-show",
                expectedLocations = setOf("/media/new-show"),
                processedLocations = setOf("/media/new-show"),
                status = "COMPLETED",
                complete = true,
                authorizationValid = true,
                items = listOf(
                    NativeDiscoveredMedia(
                        location = "/media/new-show/Brand.New.Show.S01E01.mkv",
                        fileName = "Brand.New.Show.S01E01.mkv",
                        scopeLocation = "/media/new-show/Brand.New.Show.S01E01.mkv",
                        animeTitle = "Brand New Show",
                        season = 1,
                        episodeNumber = 1.0,
                        identificationSource = "sxxexx",
                        identificationConfidence = "high",
                        sourceFolder = "/media/new-show",
                        mediaIdentity = "shared:primary:new-show/Brand.New.Show.S01E01.mkv",
                    ),
                ),
            ),
        )
        assertEquals(1, report.inserted)
        val localAnime = repository.listAnime(limit = 10).single { it.title == "Brand New Show" }
        assertNull(localAnime.anilistId)
        assertEquals("local_index", localAnime.metadataSource)
        val episode = repository.listEpisodes(localAnime.id).single()
        assertEquals(1.0, episode.number!!, 0.0)
        assertEquals("/media/new-show/Brand.New.Show.S01E01.mkv", episode.path)
    }

    @Test
    fun movedMediaKeepsObservationHistoryAndPlaybackProgress() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                "UPDATE episodes SET source_folder = ?, media_identity = ? WHERE id = ?",
                arrayOf("/media/show", "shared:primary:show/episode-01.mkv", 7L),
            )
            db.execSQL(
                """
                INSERT INTO episode_observations(
                    episode_id, source_kind, scope_kind, scope_ref, uri, first_seen, last_seen, state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(7L, "saf", "root", "saf://tree", "/media/show/episode-01.mkv", 1000.0, 1000.0, "available"),
            )
        }
        val repository = ReiAnixSqliteLibraryRepository(context)
        val report = repository.indexDiscoveredMedia(
            NativeLibraryScanSnapshot(
                operationId = "moved-media",
                source = "saf",
                scopeRoot = "/media/show",
                expectedLocations = setOf("/media/show"),
                processedLocations = setOf("/media/show"),
                status = "COMPLETED",
                complete = true,
                authorizationValid = true,
                scopeReference = "saf://tree",
                scanGeneration = 4L,
                items = listOf(
                    NativeDiscoveredMedia(
                        location = "/media/show/episode-01-renamed.mkv",
                        fileName = "episode-01-renamed.mkv",
                        scopeLocation = "/media/show/episode-01-renamed.mkv",
                        animeTitle = "Original Title",
                        season = 1,
                        episodeNumber = 1.0,
                        identificationConfidence = "high",
                        sourceFolder = "/media/show",
                        mediaIdentity = "shared:primary:show/episode-01.mkv",
                    ),
                ),
            ),
        )
        assertTrue(report.reconciliationAuthorized)
        assertEquals("/media/show/episode-01-renamed.mkv", repository.getEpisode(7L)!!.path)
        assertEquals(11.0, repository.getEpisode(7L)!!.progress!!, 0.0)

        val observations = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery(
                "SELECT uri, state FROM episode_observations WHERE episode_id = ? AND scope_ref = ? ORDER BY uri",
                arrayOf("7", "saf://tree"),
            ).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
                }
            }
        }
        assertEquals("superseded", observations["/media/show/episode-01.mkv"])
        assertEquals("available", observations["/media/show/episode-01-renamed.mkv"])
    }

    @Test
    fun nativeQueryServiceDistinguishesDataEmptyAndMissingRecords() = runBlocking {
        createCanonicalFixture()
        val service = ReiAnixLibraryQueryService(context)

        val page = service.animePage(limit = 1, offset = 0)
        assertTrue(page is ReiAnixLibraryQueryResult.Success)
        val pageValue = (page as ReiAnixLibraryQueryResult.Success).value
        assertEquals(listOf(1L), pageValue.items.map { it.id })
        assertFalse(pageValue.hasMore)

        val beyondEnd = service.animePage(limit = 1, offset = 5)
        assertTrue(beyondEnd is ReiAnixLibraryQueryResult.Empty)

        val missingAnime = service.animeById(999L)
        assertTrue(missingAnime is ReiAnixLibraryQueryResult.NotFound)

        val episodes = service.episodesForAnime(1L)
        assertTrue(episodes is ReiAnixLibraryQueryResult.Success)
        assertEquals(listOf(7L), (episodes as ReiAnixLibraryQueryResult.Success).value.map { it.id })

        val missingParentEpisodes = service.episodesForAnime(999L)
        assertTrue(missingParentEpisodes is ReiAnixLibraryQueryResult.NotFound)

        val episode = service.episodeById(7L)
        assertTrue(episode is ReiAnixLibraryQueryResult.Success)
        assertEquals(1L, (episode as ReiAnixLibraryQueryResult.Success).value.animeId)

        val folders = service.folders()
        assertTrue(folders is ReiAnixLibraryQueryResult.Success)
        assertEquals("/media/show", (folders as ReiAnixLibraryQueryResult.Success).value.single().path)

        val artwork = service.artworkForAnime(1L)
        assertTrue(artwork is ReiAnixLibraryQueryResult.Success)
        assertEquals("1", (artwork as ReiAnixLibraryQueryResult.Success).value.single().entityId)
    }

    @Test
    fun nativeQueryServiceRejectsInvalidPageBoundsAsFailure() = runBlocking {
        createCanonicalFixture()
        val service = ReiAnixLibraryQueryService(context)

        assertTrue(service.animePage(limit = 0) is ReiAnixLibraryQueryResult.Failure)
        assertTrue(service.animePage(limit = ReiAnixLibraryQueryService.MAX_PAGE_SIZE + 1) is ReiAnixLibraryQueryResult.Failure)
        assertTrue(service.animePage(limit = 1, offset = -1) is ReiAnixLibraryQueryResult.Failure)
    }

    @Test
    fun nativeQueryServiceNeverTurnsMissingDatabaseIntoEmptyLibrary() = runBlocking {
        assertFalse(databaseFile.exists())
        val result = ReiAnixLibraryQueryService(context).animePage()
        assertTrue(result is ReiAnixLibraryQueryResult.Failure)
        assertFalse(databaseFile.exists())
    }

    @Test
    fun consumptionServicePersistsProgressRejectsOlderCheckpointAndSurvivesReopen() = runBlocking {
        createCanonicalFixture()
        val service = ReiAnixConsumptionService(ReiAnixSqliteLibraryRepository(context))

        val saved = service.saveProgress(
            episodeId = 7L,
            positionMs = 45_000L,
            durationMs = 120_000L,
            playedAtEpochSeconds = 2_000.0,
        )
        assertEquals(45_000L, saved.positionMs)
        assertEquals(120_000L, saved.durationMs)
        assertFalse(saved.watched)

        val reopenedService = ReiAnixConsumptionService(ReiAnixSqliteLibraryRepository(context))
        val stale = reopenedService.saveProgress(
            episodeId = 7L,
            positionMs = 10_000L,
            durationMs = 120_000L,
            playedAtEpochSeconds = 1_500.0,
        )
        assertEquals(45_000L, stale.positionMs)
        assertEquals(45_000L, reopenedService.getProgress(7L)!!.positionMs)

        val completed = reopenedService.saveProgress(
            episodeId = 7L,
            positionMs = 108_000L,
            durationMs = 120_000L,
            playedAtEpochSeconds = 3_000.0,
        )
        assertTrue(completed.watched)
        assertEquals(108_000L, completed.positionMs)

        val explicitlyCompleted = reopenedService.saveProgress(
            episodeId = 7L,
            positionMs = 110_000L,
            durationMs = 120_000L,
            playedAtEpochSeconds = 4_000.0,
            completed = true,
        )
        assertTrue(explicitlyCompleted.watched)
        assertEquals(120_000L, explicitlyCompleted.positionMs)

        val reopenedAgain = ReiAnixConsumptionService(ReiAnixSqliteLibraryRepository(context))
        assertTrue(reopenedAgain.getProgress(7L)!!.watched)
        assertFalse(reopenedAgain.markWatched(7L, false).watched)
    }

    @Test
    fun consumptionServicePreservesUnknownDurationAndBoundsKnownDuration() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            db.execSQL("UPDATE episodes SET duration = NULL WHERE id = ?", arrayOf(7L))
        }
        val service = ReiAnixConsumptionService(ReiAnixSqliteLibraryRepository(context))
        val unknownDuration = service.saveProgress(
            episodeId = 7L,
            positionMs = 30_000L,
            durationMs = null,
            playedAtEpochSeconds = 2_000.0,
        )
        assertEquals(30_000L, unknownDuration.positionMs)
        assertNull(unknownDuration.durationMs)
        assertFalse(unknownDuration.watched)

        val bounded = service.saveProgress(
            episodeId = 7L,
            positionMs = 90_000L,
            durationMs = 60_000L,
            playedAtEpochSeconds = 3_000.0,
        )
        assertEquals(60_000L, bounded.positionMs)
        assertEquals(60_000L, bounded.durationMs)
        assertTrue(bounded.watched)
    }

    @Test
    fun nativeSearchReportsDatabaseFailureSeparatelyFromNoMatches() = runBlocking {
        assertFalse(databaseFile.exists())
        val result = ReiAnixLibraryQueryService(context).searchAnime("missing title")
        assertTrue(result is ReiAnixLibraryQueryResult.Failure)
        assertFalse("A failed read must not create a new catalog database.", databaseFile.exists())
    }

    @Test
    fun nativeSqliteSearchHandlesNullOptionalMetadataText() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                """
                UPDATE anime
                SET romaji=NULL, english=NULL, native=NULL, aliases=NULL,
                    description=NULL, genres=NULL, season=NULL, status=NULL,
                    format=NULL, studio=NULL, user_tags=NULL, personal_note=NULL
                WHERE id=1
                """.trimIndent(),
            )
        }

        val repository = ReiAnixSqliteLibraryRepository(context)
        // The initial library page has an empty query and must tolerate absent
        // AniList metadata just as the search path does.
        assertEquals(
            listOf(1L),
            repository.searchAnimeIds(
                query = "",
                genreKey = null,
                favoritesOnly = false,
                watchingOnly = false,
                completedOnly = false,
                sortLabel = null,
                limit = 36,
                offset = 0,
                libraryOnly = true,
            ).animeIds,
        )
        assertEquals(
            listOf(1L),
            repository.searchAnimeIds(
                query = "original title",
                genreKey = null,
                favoritesOnly = false,
                watchingOnly = false,
                completedOnly = false,
                sortLabel = null,
                limit = 36,
                offset = 0,
                libraryOnly = true,
            ).animeIds,
        )
    }

    @Test
    fun nativeSqliteSearchMatchesAliasesUnicodeEpisodesFiltersAndStablePages() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                """UPDATE anime SET english=?, romaji=?, native=?, aliases=?, genres=?, year=?
                   WHERE id=?""".trimIndent(),
                arrayOf("São Paulo", "Shingeki no Kyojin", "進撃の巨人",
                    """["Attack on Titan","進撃の巨人"]""", """["Action"]""", 2026, 1),
            )
            db.execSQL(
                """INSERT INTO genres(id, canonical_name, normalized_name, source, is_system, is_custom, created_at, updated_at)
                   VALUES ('action-id', 'Action', 'action', 'local', 0, 1, 1000, 1000)""".trimIndent(),
            )
            db.execSQL(
                """INSERT INTO anime_genres(anime_id, genre_id, source, created_at, updated_at)
                   VALUES (1, 'action-id', 'local', 1000, 1000)""".trimIndent(),
            )
            db.execSQL(
                """INSERT INTO anime(id, lookup_title, title, added_at, favorite)
                   VALUES (2, 'another-title', 'Another Title', 2000, 0)""".trimIndent(),
            )
        }

        val repository = ReiAnixSqliteLibraryRepository(context)
        assertEquals(listOf(1L), repository.searchAnimeIds("sao paulo", null, false, false, false, null, 36, 0).animeIds)
        assertEquals(listOf(1L), repository.searchAnimeIds("attack on titan", null, false, false, false, null, 36, 0).animeIds)
        assertEquals(listOf(1L), repository.searchAnimeIds("進撃の巨人", null, false, false, false, null, 36, 0).animeIds)
        assertEquals(listOf(1L), repository.searchAnimeIds("episode 1", null, false, false, false, null, 36, 0).animeIds)
        assertEquals(listOf(1L), repository.searchAnimeIds("2026", null, false, false, false, null, 36, 0).animeIds)
        assertEquals(listOf(1L), repository.searchAnimeIds("", "action-id", false, false, false, null, 36, 0).animeIds)
        assertEquals(listOf(1L), repository.searchAnimeIds("", "action-id", true, true, false, null, 36, 0).animeIds)
        assertEquals(listOf(1L), repository.searchAnimeIds("", null, true, false, false, null, 36, 0).animeIds)
        assertEquals(listOf(1L), repository.searchAnimeIds("", null, false, true, false, null, 36, 0).animeIds)
        assertTrue(repository.searchAnimeIds("", null, false, false, true, null, 36, 0).animeIds.isEmpty())

        val firstPage = repository.searchAnimeIds("", null, false, false, false, null, 1, 0)
        val secondPage = repository.searchAnimeIds("", null, false, false, false, null, 1, 1)
        assertEquals(listOf(2L), firstPage.animeIds)
        assertTrue(firstPage.hasMore)
        assertEquals(listOf(1L), secondPage.animeIds)
        assertFalse(secondPage.hasMore)
        assertTrue(firstPage.animeIds.intersect(secondPage.animeIds.toSet()).isEmpty())

        val titlePageOne = repository.searchAnimeIds("title", null, false, false, false, null, 1, 0)
        val titlePageTwo = repository.searchAnimeIds("title", null, false, false, false, null, 1, 1)
        assertEquals(listOf(1L), titlePageOne.animeIds)
        assertEquals(listOf(2L), titlePageTwo.animeIds)
        assertFalse(titlePageOne.animeIds.intersect(titlePageTwo.animeIds.toSet()).isNotEmpty())
        assertTrue(repository.searchAnimeIds("not a real title", null, false, false, false, null, 36, 0).animeIds.isEmpty())

        assertEquals(
            listOf(2L, 1L),
            repository.searchAnimeIds("", null, false, false, false, "Nome A-Z", 36, 0).animeIds,
        )
        assertEquals(
            listOf(1L, 2L),
            repository.searchAnimeIds("", null, false, false, false, "Nome Z-A", 36, 0).animeIds,
        )
        assertEquals(
            listOf(2L, 1L),
            repository.searchAnimeIds("", null, false, false, false, "Assistidos recentemente", 36, 0).animeIds,
        )
        assertEquals(
            listOf(1L, 2L),
            repository.searchAnimeIds("", null, false, false, false, "Episódio", 36, 0).animeIds,
        )
    }

    @Test
    fun personalListMembershipIsIdempotentAndIndependentFromPlaybackProgress() = runBlocking {
        createCanonicalFixture()
        val service = ReiAnixPersonalListService(context)
        val repository = ReiAnixSqliteLibraryRepository(context)

        val initial = service.list()
        assertTrue(initial is ReiAnixPersonalListService.Result.Success)
        val initialItems =
            (initial as ReiAnixPersonalListService.Result.Success<List<ReiAnixPersonalListService.Item>>).value
        assertEquals(listOf(1L), initialItems.map { it.animeId })
        assertNull("The canonical schema has no favorite-added timestamp.", initialItems.single().favoritedAtEpochSeconds)

        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                "UPDATE episodes SET progress = ?, watched = ?, last_played_at = ? WHERE id = ?",
                arrayOf(11.0, 1, 1_700_000_000.0, 7L),
            )
        }

        assertTrue(service.remove(1L) is ReiAnixPersonalListService.Result.Success)
        assertTrue(service.remove(1L) is ReiAnixPersonalListService.Result.Success)
        assertTrue((service.list() as ReiAnixPersonalListService.Result.Success<List<ReiAnixPersonalListService.Item>>).value.isEmpty())

        val episodeAfterRemoval = repository.getEpisode(7L)
        assertNotNull(episodeAfterRemoval)
        assertEquals(11.0, episodeAfterRemoval?.progress ?: -1.0, 0.0)
        assertEquals(true, episodeAfterRemoval?.watched)
        assertEquals(1_700_000_000.0, episodeAfterRemoval?.lastPlayedAt ?: -1.0, 0.0)

        assertTrue(service.add(1L) is ReiAnixPersonalListService.Result.Success)
        assertTrue(service.add(1L) is ReiAnixPersonalListService.Result.Success)
        assertEquals(
            listOf(1L),
            (service.list() as ReiAnixPersonalListService.Result.Success<List<ReiAnixPersonalListService.Item>>).value.map { it.animeId },
        )
        assertEquals(true, (service.contains(1L) as ReiAnixPersonalListService.Result.Success<Boolean>).value)

        val firstToggle = service.toggle(1L)
        assertTrue(firstToggle is ReiAnixPersonalListService.Result.Success)
        assertEquals(false, (firstToggle as ReiAnixPersonalListService.Result.Success<Boolean>).value)
        assertEquals(false, (service.contains(1L) as ReiAnixPersonalListService.Result.Success<Boolean>).value)

        val secondToggle = service.toggle(1L)
        assertTrue(secondToggle is ReiAnixPersonalListService.Result.Success)
        assertEquals(true, (secondToggle as ReiAnixPersonalListService.Result.Success<Boolean>).value)
        assertEquals(true, (service.contains(1L) as ReiAnixPersonalListService.Result.Success<Boolean>).value)

        val episodeAfterAllMembershipChanges = repository.getEpisode(7L)
        assertEquals(11.0, episodeAfterAllMembershipChanges?.progress ?: -1.0, 0.0)
        assertEquals(true, episodeAfterAllMembershipChanges?.watched)
        assertEquals(1_700_000_000.0, episodeAfterAllMembershipChanges?.lastPlayedAt ?: -1.0, 0.0)
    }

    @Test
    fun nativeLibraryMutationsPreserveCanonicalStateAndPathIdentity() = runBlocking {
        createCanonicalFixture()
        val repository = ReiAnixSqliteLibraryRepository(context)

        val byPath = repository.findEpisodeByPathOrFileUri("/media/show/episode-01.mkv")
        val byFileUri = repository.findEpisodeByPathOrFileUri(
            Uri.fromFile(File("/media/show/episode-01.mkv")).toString(),
        )
        assertEquals(7L, byPath?.id)
        assertEquals(byPath?.id, byFileUri?.id)
        assertEquals("catalog-key", repository.getAnimeByLookupTitle("catalog-key")?.lookupTitle)

        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                "UPDATE episodes SET media_identity = ?, source_folder = ?, relative_path = ?, volume_id = ? WHERE id = ?",
                arrayOf("persisted-media-identity", "/media/show", "Season 1/Episode 01.mkv", "volume-1", 7L),
            )
        }
        assertEquals(
            1L,
            repository.resolveLocalAnimeOwner(mediaIdentity = "persisted-media-identity"),
        )
        assertEquals(
            1L,
            repository.resolveLocalAnimeOwner(
                sourceFolder = "/media/show",
                relativePath = "Season 1/Episode 01.mkv",
                volumeId = "volume-1",
            ),
        )
        assertEquals(1L, repository.resolveLocalAnimeOwner(lookupTitle = "catalog-key"))

        assertEquals(false, repository.toggleAnimePinned(1L))
        assertEquals(true, repository.toggleAnimePinned(1L))
        assertEquals("updated personal note", repository.setAnimePersonalNote(1L, "  updated personal note  "))
        assertEquals(
            listOf("Action hero", "drama"),
            repository.setAnimeUserTags(1L, listOf("  Action   hero ", "action hero", " drama ")),
        )

        val manual = repository.setManualMetadataByLookupTitle(
            lookupTitle = "catalog-key",
            patch = ReiAnixAnimeMetadataPatch(
                animeId = 1L,
                title = "Manual title",
                english = "Manual English title",
                year = 2026,
            ),
        )
        assertEquals("Manual title", manual.title)
        assertEquals("Manual English title", manual.english)
        assertTrue(manual.metadataManualFieldsJson.orEmpty().contains("english"))
        assertEquals("updated personal note", manual.personalNote)
        assertEquals(true, manual.favorite)
        val persistedTags = org.json.JSONArray(manual.userTagsJson)
        assertEquals(
            listOf("Action hero", "drama"),
            (0 until persistedTags.length()).map(persistedTags::getString),
        )

        val remote = repository.updateAnimeMetadata(
            ReiAnixAnimeMetadataPatch(
                animeId = 1L,
                title = "Remote title must not replace manual title",
                coverUrl = "",
            ),
        )
        assertEquals("Manual title", remote.title)
        assertEquals("https://example.invalid/old-cover.jpg", remote.coverUrl)
        assertTrue(repository.clearManualMetadataByLookupTitle("catalog-key", setOf("english")))
        val afterManualFieldClear = repository.getAnime(1L)
        assertEquals("Manual title", afterManualFieldClear?.title)
        assertTrue(afterManualFieldClear?.metadataManualFieldsJson.orEmpty().contains("title"))
        assertTrue(afterManualFieldClear?.metadataManualFieldsJson.orEmpty().contains("cover_url"))
        assertFalse(afterManualFieldClear?.metadataManualFieldsJson.orEmpty().contains("english"))

        val watched = repository.setEpisodeWatchedState(
            episodeId = 7L,
            watched = true,
            playedAtEpochSeconds = 1_800_000_000.0,
        )
        assertEquals(true, watched.watched)
        assertEquals(1200.0, watched.progress ?: -1.0, 0.0)
        val unwatched = repository.setEpisodeWatchedState(
            episodeId = 7L,
            watched = false,
            playedAtEpochSeconds = 1_800_000_001.0,
        )
        assertEquals(false, unwatched.watched)
        assertEquals(0.0, unwatched.progress ?: -1.0, 0.0)
        assertEquals(1_800_000_001.0, unwatched.lastPlayedAt ?: -1.0, 0.0)

        val stale = repository.setEpisodeWatchedState(
            episodeId = 7L,
            watched = true,
            playedAtEpochSeconds = 1_800_000_000.0,
        )
        assertEquals(false, stale.watched)
        assertEquals(0.0, stale.progress ?: -1.0, 0.0)
        assertEquals(1_800_000_001.0, stale.lastPlayedAt ?: -1.0, 0.0)

        val reopened = ReiAnixSqliteLibraryRepository(context)
        assertEquals("Manual title", reopened.getAnime(1L)?.title)
        assertEquals("updated personal note", reopened.getAnime(1L)?.personalNote)
        assertEquals(0.0, reopened.getEpisode(7L)?.progress ?: -1.0, 0.0)
    }

    @Test
    fun nativeAniListAssociationsAndPendingMatchesStayInTheCanonicalStore() = runBlocking {
        createCanonicalFixture()
        val repository = ReiAnixSqliteLibraryRepository(context)

        assertTrue(
            repository.setAniListMatchByLookupTitle(
                lookupTitle = "catalog-key",
                anilistId = 1234L,
                status = "matched",
                score = 0.96,
                margin = 0.18,
                manual = true,
            ),
        )
        val match = repository.getAniListMatchByLookupTitle("catalog-key")
        assertEquals(1L, match?.animeId)
        assertEquals(1234L, match?.anilistId)
        assertEquals(true, match?.manual)
        assertEquals(0.96, match?.score ?: -1.0, 0.0)

        repository.setPendingAniListMatch(
            lookupTitle = "catalog-key",
            displayTitle = "Catalog Title",
            candidatesJson = """[{"id":5678,"title":"Candidate"}]""",
        )
        val pending = repository.listPendingAniListMatches()
        assertEquals(1, pending.size)
        assertEquals("catalog-key", pending.single().lookupTitle)
        assertEquals("Catalog Title", pending.single().displayTitle)
        assertEquals(5678, org.json.JSONArray(pending.single().candidatesJson).getJSONObject(0).getInt("id"))

        repository.resolvePendingAniListMatch("catalog-key", 5678L)

        // The public query reads anime, so it must immediately expose the resolved ID,
        // not merely observe an entry in associations.
        val resolved = repository.getAniListMatchByLookupTitle("catalog-key")
        assertEquals(5678L, resolved?.anilistId)
        assertEquals("manual", resolved?.status)
        assertEquals(1.0, resolved?.score ?: -1.0, 0.0)
        assertEquals(1.0, resolved?.margin ?: -1.0, 0.0)
        assertEquals(true, resolved?.manual)

        val resolvedAnime = repository.getAnime(1L)
        assertEquals(5678L, resolvedAnime?.anilistId)
        assertEquals("Original Title", resolvedAnime?.title)
        assertEquals("keep this note", resolvedAnime?.personalNote)
        assertEquals(true, resolvedAnime?.favorite)
        assertTrue(resolvedAnime?.metadataManualFieldsJson.orEmpty().contains("title"))
        assertTrue(resolvedAnime?.metadataManualFieldsJson.orEmpty().contains("cover_url"))

        assertTrue(repository.listPendingAniListMatches().isEmpty())
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT anilist_id FROM associations WHERE lookup_title = ?",
                arrayOf("catalog-key"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(5678L, cursor.getLong(0))
            }
        }

        assertTrue(repository.clearAniListMatchByLookupTitle("catalog-key"))
        val cleared = repository.getAniListMatchByLookupTitle("catalog-key")
        assertNull(cleared?.anilistId)
        assertEquals("unmatched", cleared?.status)
        assertTrue(repository.listPendingAniListMatches().isEmpty())
        val animeAfterClear = repository.getAnime(1L)
        assertEquals("Original Title", animeAfterClear?.title)
        assertEquals("keep this note", animeAfterClear?.personalNote)
        assertEquals(true, animeAfterClear?.favorite)
        assertTrue(repository.setMetadataStatusByLookupTitle("catalog-key", "available", "high"))
        assertEquals(1, repository.clearAniListMetadataCache())
        assertEquals(null, repository.getAnime(1L)?.metadataUpdatedAt)
    }

    @Test
    fun pendingResolutionWithoutPendingOrExistingAssociationIsRejectedWithoutWrites() = runBlocking {
        createCanonicalFixture()
        val repository = ReiAnixSqliteLibraryRepository(context)
        val originalMatch = repository.getAniListMatchByLookupTitle("catalog-key")
        val originalAnime = repository.getAnime(1L)
        assertNotNull(originalMatch)
        assertNull(originalMatch?.anilistId)
        assertNotNull(originalAnime)
        assertTrue(repository.listPendingAniListMatches().isEmpty())

        var rejectedAsStale = false
        try {
            repository.resolvePendingAniListMatch("catalog-key", 5678L)
        } catch (expected: IllegalStateException) {
            rejectedAsStale = expected.message.orEmpty().contains("Este candidato não está mais pendente.")
        }
        assertTrue("A stale resolution without a previous mapping must be rejected.", rejectedAsStale)
        assertEquals(originalMatch, repository.getAniListMatchByLookupTitle("catalog-key"))
        assertEquals(originalAnime, repository.getAnime(1L))
        assertTrue(repository.listPendingAniListMatches().isEmpty())

        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM anime", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.rawQuery("SELECT COUNT(*) FROM associations", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            db.rawQuery("SELECT COUNT(*) FROM pending_matches", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun pendingResolutionWithoutPendingButWithConsistentPreviousAssociationCanReplaceIt() = runBlocking {
        createCanonicalFixture()
        val repository = ReiAnixSqliteLibraryRepository(context)
        assertTrue(
            repository.setAniListMatchByLookupTitle(
                lookupTitle = "catalog-key",
                anilistId = 1234L,
                status = "manual",
                score = 0.96,
                margin = 0.18,
                manual = true,
            ),
        )
        assertTrue(repository.listPendingAniListMatches().isEmpty())

        repository.resolvePendingAniListMatch("catalog-key", 5678L)

        val resolved = repository.getAniListMatchByLookupTitle("catalog-key")
        assertEquals(5678L, resolved?.anilistId)
        assertEquals("manual", resolved?.status)
        assertEquals(1.0, resolved?.score ?: -1.0, 0.0)
        assertEquals(1.0, resolved?.margin ?: -1.0, 0.0)
        assertEquals(true, resolved?.manual)
        assertTrue(repository.listPendingAniListMatches().isEmpty())
        val anime = repository.getAnime(1L)
        assertEquals(5678L, anime?.anilistId)
        assertEquals("Original Title", anime?.title)
        assertEquals("keep this note", anime?.personalNote)
        assertEquals(true, anime?.favorite)
        assertTrue(anime?.metadataManualFieldsJson.orEmpty().contains("title"))

        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT anilist_id FROM associations WHERE lookup_title = ?",
                arrayOf("catalog-key"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(5678L, cursor.getLong(0))
                assertFalse(cursor.moveToNext())
            }
            db.rawQuery(
                "SELECT COUNT(*) FROM pending_matches WHERE lookup_title = ?",
                arrayOf("catalog-key"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }
    @Test
    fun pendingResolutionWithMissingLocalAnimePreservesPendingRowAndCreatesNoAssociation() = runBlocking {
        createCanonicalFixture()
        val repository = ReiAnixSqliteLibraryRepository(context)
        repository.setPendingAniListMatch(
            lookupTitle = "missing-local-key",
            displayTitle = "Missing Local Anime",
            candidatesJson = """[{"id":9876,"title":"Candidate"}]""",
        )

        try {
            repository.resolvePendingAniListMatch("missing-local-key", 9876L)
            fail("A pending match must not resolve when its local anime row does not exist.")
        } catch (expected: NoSuchElementException) {
            assertTrue(expected.message.orEmpty().contains("Obra local não encontrada"))
        }

        assertNull(repository.getAnimeByLookupTitle("missing-local-key"))
        assertNull(repository.getAniListMatchByLookupTitle("missing-local-key"))
        assertEquals(
            listOf("missing-local-key"),
            repository.listPendingAniListMatches().map { it.lookupTitle },
        )
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT COUNT(*) FROM associations WHERE lookup_title = ?",
                arrayOf("missing-local-key"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            db.rawQuery(
                "SELECT COUNT(*) FROM anime WHERE lookup_title = ?",
                arrayOf("missing-local-key"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun pendingResolutionRollsBackAnimeAssociationAndPendingDeletionWhenAssociationWriteFails() = runBlocking {
        createCanonicalFixture()
        val repository = ReiAnixSqliteLibraryRepository(context)
        repository.setAniListMatchByLookupTitle(
            lookupTitle = "catalog-key",
            anilistId = 1234L,
            status = "manual",
            score = 0.96,
            margin = 0.18,
            manual = true,
        )
        repository.setPendingAniListMatch(
            lookupTitle = "catalog-key",
            displayTitle = "Catalog Title",
            candidatesJson = """[{"id":5678,"title":"Candidate"}]""",
        )

        // The repository's canonical contract has already been validated. This
        // controlled database constraint failure occurs after anime is updated and
        // forces the surrounding transaction to roll back every mutation.
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                "CREATE TRIGGER reject_pending_resolution_association " +
                    "BEFORE INSERT ON associations " +
                    "BEGIN SELECT RAISE(ABORT, 'forced association write failure'); END",
            )
        }

        var failedAsExpected = false
        try {
            repository.resolvePendingAniListMatch("catalog-key", 5678L)
        } catch (expected: SQLiteException) {
            failedAsExpected = expected.message.orEmpty().contains("forced association write failure")
        }
        assertTrue("The injected association write must fail the transaction.", failedAsExpected)

        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                """SELECT anilist_id, anilist_match_status, anilist_match_score,
                          anilist_match_margin, anilist_match_manual
                   FROM anime WHERE lookup_title = ?""".trimIndent(),
                arrayOf("catalog-key"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1234L, cursor.getLong(0))
                assertEquals("manual", cursor.getString(1))
                assertEquals(0.96, cursor.getDouble(2), 0.0)
                assertEquals(0.18, cursor.getDouble(3), 0.0)
                assertEquals(1, cursor.getInt(4))
            }
            db.rawQuery(
                "SELECT anilist_id FROM associations WHERE lookup_title = ?",
                arrayOf("catalog-key"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1234L, cursor.getLong(0))
            }
            db.rawQuery(
                "SELECT COUNT(*) FROM pending_matches WHERE lookup_title = ?",
                arrayOf("catalog-key"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.rawQuery(
                "SELECT title, personal_note, favorite, metadata_manual_fields FROM anime WHERE lookup_title = ?",
                arrayOf("catalog-key"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Original Title", cursor.getString(0))
                assertEquals("keep this note", cursor.getString(1))
                assertEquals(1, cursor.getInt(2))
                assertTrue(cursor.getString(3).contains("title"))
                assertTrue(cursor.getString(3).contains("cover_url"))
            }
        }
    }

    @Test
    fun personalListRejectsMissingAndInvalidAnimeIdsWithoutChangingCatalog() = runBlocking {
        createCanonicalFixture()
        val service = ReiAnixPersonalListService(context)

        assertTrue(service.add(0L) is ReiAnixPersonalListService.Result.Failure)
        assertTrue(service.add(999L) is ReiAnixPersonalListService.Result.NotFound)
        assertEquals(
            listOf(1L),
            (service.list() as ReiAnixPersonalListService.Result.Success<List<ReiAnixPersonalListService.Item>>).value.map { it.animeId },
        )
    }

    @Test
    fun personalListReportsUnavailableDatabaseInsteadOfTreatingItAsEmpty() = runBlocking {
        val result = ReiAnixPersonalListService(context).list()
        assertTrue(result is ReiAnixPersonalListService.Result.Failure)
        assertFalse(databaseFile.exists())
    }


    @Test
    fun supplementalKitsuMetadataFillsOnlyGapsAndPersistsItsIdInV29Preferences() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE anime SET year=2016, format='TV', media_kind='series' WHERE id=1")
        }
        val repository = ReiAnixSqliteLibraryRepository(context)
        val local = requireNotNull(repository.getAnime(1L))
        val remote = ReiAnixCatalogAnime(
            source = ReiAnixCatalogSource.KITSU,
            ids = ReiAnixExternalCatalogIds(kitsuId = "kitsu-verified-42"),
            title = "Original Title",
            alternativeTitles = setOf("Old English Title", "Kitsu Alternate Title"),
            synopsis = "A valid supplemental synopsis that fills the missing description.",
            coverUrl = "https://media.kitsu.io/poster.jpg",
            backdropUrl = "https://media.kitsu.io/backdrop.jpg",
            releaseYear = 2016,
            format = "TV",
            mediaKind = ReiAnixCatalogMediaKind.SERIES,
            episodes = 12,
            durationMinutes = 24,
            status = "finished",
            averageScore = 86,
        )
        val match = requireNotNull(
            ReiAnixCatalogIdentityMatcher.verify(1L, local.toExternalCatalogIdentity(), remote),
        )

        repository.saveExternalCatalogIds(match)
        val updated = repository.mergeExternalCatalogMetadata(match, remote)

        assertEquals("Original Title", updated.title)
        assertEquals("https://example.invalid/old-cover.jpg", updated.coverUrl)
        assertEquals("A valid supplemental synopsis that fills the missing description.", updated.description)
        assertEquals(2016, updated.year)
        assertEquals("TV", updated.format)
        assertEquals(12, updated.episodesCount)
        assertEquals(24, updated.duration)
        assertEquals("finished", updated.status)
        assertEquals(86, updated.score)
        assertEquals("anilist", updated.metadataSource)
        assertTrue(updated.aliasesJson.orEmpty().contains("Kitsu Alternate Title"))
        assertEquals("kitsu-verified-42", repository.getExternalCatalogIds(1L)?.kitsuId)
        assertEquals(29, repository.inspectSchema().schemaVersion)
    }

    @Test
    fun failedKitsuProviderDoesNotBlockVerifiedJikanFallback() = runBlocking {
        createCanonicalFixture()
        val png = File(context.cacheDir, "external-catalog-valid-poster.png")
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL(
                    "UPDATE anime SET year=2016,format='TV',media_kind='series',cover_cache=? WHERE id=1",
                    arrayOf(png.absolutePath),
                )
            }
            val kitsuCalls = java.util.concurrent.atomic.AtomicInteger()
            val jikanCalls = java.util.concurrent.atomic.AtomicInteger()
            val kitsu = object : ReiAnixAnimeCatalogProvider {
                override val source = ReiAnixCatalogSource.KITSU
                override suspend fun searchAnime(query: String, limit: Int): ReiAnixCatalogResult<List<ReiAnixCatalogAnime>> {
                    kitsuCalls.incrementAndGet()
                    return ReiAnixCatalogResult.Failure(ReiAnixCatalogError.Network("Kitsu unavailable"))
                }
                override suspend fun getAnime(externalId: String) =
                    ReiAnixCatalogResult.Success<ReiAnixCatalogAnime?>(null)
            }
            val remote = ReiAnixCatalogAnime(
                source = ReiAnixCatalogSource.JIKAN,
                ids = ReiAnixExternalCatalogIds(myAnimeListId = 9001L),
                title = "Original Title",
                alternativeTitles = setOf("Old English Title"),
                synopsis = "A complete Jikan synopsis that complements the existing AniList catalog record.",
                releaseYear = 2016,
                format = "TV",
                mediaKind = ReiAnixCatalogMediaKind.SERIES,
                episodes = 12,
                durationMinutes = 24,
                status = "Finished Airing",
                averageScore = 82,
            )
            val jikan = object : ReiAnixAnimeCatalogProvider {
                override val source = ReiAnixCatalogSource.JIKAN
                override suspend fun searchAnime(query: String, limit: Int): ReiAnixCatalogResult<List<ReiAnixCatalogAnime>> {
                    jikanCalls.incrementAndGet()
                    return ReiAnixCatalogResult.Success(listOf(remote))
                }
                override suspend fun getAnime(externalId: String) =
                    ReiAnixCatalogResult.Success<ReiAnixCatalogAnime?>(null)
            }
            val database = ReiAnixSqliteDatabase(context)
            val artwork = ReiAnixArtworkService(context, database)
            val service = ReiAnixExternalCatalogIntegrationService(
                library = ReiAnixSqliteLibraryRepository(database),
                providers = ReiAnixCatalogProviderRegistry(
                    listOf(kitsu, jikan),
                    ReiAnixExternalRequestCoordinator(maxConcurrentRequests = 1),
                ),
                artwork = artwork,
            )

            assertEquals(1, service.enrichLibraryGaps())
            val updated = requireNotNull(ReiAnixSqliteLibraryRepository(database).getAnime(1L))
            assertTrue(kitsuCalls.get() > 0)
            assertTrue(jikanCalls.get() > 0)
            assertEquals(9001L, ReiAnixSqliteLibraryRepository(database).getExternalCatalogIds(1L)?.myAnimeListId)
            assertEquals("A complete Jikan synopsis that complements the existing AniList catalog record.", updated.description)
            assertNull(updated.anilistId)
            assertEquals("Original Title", updated.title)
            assertTrue(artwork.hasValidAnimeArtwork(1L, "poster"))
        } finally {
            png.delete()
        }
    }

    @Test
    fun ambiguousJikanCandidatesAreRejectedWithoutPersistingAnyExternalId() = runBlocking {
        createCanonicalFixture()
        val png = File(context.cacheDir, "external-catalog-ambiguous-poster.png")
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL(
                    "UPDATE anime SET year=2016,format='TV',media_kind='series',cover_cache=? WHERE id=1",
                    arrayOf(png.absolutePath),
                )
            }
            val kitsu = object : ReiAnixAnimeCatalogProvider {
                override val source = ReiAnixCatalogSource.KITSU
                override suspend fun searchAnime(query: String, limit: Int) =
                    ReiAnixCatalogResult.Failure(ReiAnixCatalogError.Network("offline"))
                override suspend fun getAnime(externalId: String) =
                    ReiAnixCatalogResult.Success<ReiAnixCatalogAnime?>(null)
            }
            fun candidate(id: Long) = ReiAnixCatalogAnime(
                source = ReiAnixCatalogSource.JIKAN,
                ids = ReiAnixExternalCatalogIds(myAnimeListId = id),
                title = "Original Title",
                alternativeTitles = setOf("Old English Title"),
                releaseYear = 2016,
                format = "TV",
                mediaKind = ReiAnixCatalogMediaKind.SERIES,
                episodes = 12,
            )
            val jikan = object : ReiAnixAnimeCatalogProvider {
                override val source = ReiAnixCatalogSource.JIKAN
                override suspend fun searchAnime(query: String, limit: Int) =
                    ReiAnixCatalogResult.Success(listOf(candidate(9002L), candidate(9003L)))
                override suspend fun getAnime(externalId: String) =
                    ReiAnixCatalogResult.Success<ReiAnixCatalogAnime?>(null)
            }
            val database = ReiAnixSqliteDatabase(context)
            val service = ReiAnixExternalCatalogIntegrationService(
                library = ReiAnixSqliteLibraryRepository(database),
                providers = ReiAnixCatalogProviderRegistry(
                    listOf(kitsu, jikan),
                    ReiAnixExternalRequestCoordinator(maxConcurrentRequests = 1),
                ),
                artwork = ReiAnixArtworkService(context, database),
            )

            assertEquals(0, service.enrichLibraryGaps())
            assertNull(ReiAnixSqliteLibraryRepository(database).getExternalCatalogIds(1L)?.myAnimeListId)
            assertNull(ReiAnixSqliteLibraryRepository(database).getAnime(1L)?.description)
        } finally {
            png.delete()
        }
    }

    @Test
    fun externalArtworkCandidateCannotReplaceManualOrValidLocalPoster() = runBlocking {
        createCanonicalFixture()
        val database = ReiAnixSqliteDatabase(context)
        val artwork = ReiAnixArtworkService(context, database)

        // The canonical fixture explicitly gives cover_url to manual metadata ownership.
        assertFalse(
            artwork.replaceExternalArtworkCandidate(
                1L, "poster", "https://media.kitsu.io/replacement.jpg",
            ),
        )
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE anime SET metadata_manual_fields='[]' WHERE id=1")
        }

        val png = File(context.cacheDir, "external-catalog-preserved-poster.png")
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("UPDATE anime SET cover_cache=? WHERE id=1", arrayOf(png.absolutePath))
            }
            assertTrue(artwork.hasValidAnimeArtwork(1L, "poster"))
            assertFalse(
                artwork.replaceExternalArtworkCandidate(
                    1L, "poster", "https://cdn.myanimelist.net/replacement.jpg",
                ),
            )
            assertEquals(
                "https://example.invalid/old-cover.jpg",
                ReiAnixSqliteLibraryRepository(database).getAnime(1L)?.coverUrl,
            )
        } finally {
            png.delete()
        }
    }


    @Test
    fun completeMetadataValidPosterAndMissingBackdropStillTriggerSupplementalLookup() = runBlocking {
        createCanonicalFixture()
        val poster = createValidPng("backdrop-only-poster.png")
        val downloadedBackdrop = createValidPng("jikan-backdrop-http-response.png")
        try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL(
                    """
                    UPDATE anime SET year=2016,format='TV',media_kind='series',
                        description='Complete local synopsis for backdrop-only recovery.',
                        episodes_count=12,duration=24,status='Finished Airing',score=86,
                        cover_cache=? WHERE id=1
                    """.trimIndent(),
                    arrayOf(poster.absolutePath),
                )
            }

            val kitsuCalls = AtomicInteger()
            val jikanCalls = AtomicInteger()
            val kitsu = object : ReiAnixAnimeCatalogProvider {
                override val source = ReiAnixCatalogSource.KITSU
                override suspend fun searchAnime(
                    query: String,
                    limit: Int,
                ): ReiAnixCatalogResult<List<ReiAnixCatalogAnime>> {
                    kitsuCalls.incrementAndGet()
                    return ReiAnixCatalogResult.Failure(ReiAnixCatalogError.Network("Kitsu unavailable"))
                }
                override suspend fun getAnime(externalId: String) =
                    ReiAnixCatalogResult.Success<ReiAnixCatalogAnime?>(null)
            }
            val remote = ReiAnixCatalogAnime(
                source = ReiAnixCatalogSource.JIKAN,
                ids = ReiAnixExternalCatalogIds(myAnimeListId = 90210L),
                title = "Original Title",
                alternativeTitles = setOf("Old English Title"),
                releaseYear = 2016,
                format = "TV",
                mediaKind = ReiAnixCatalogMediaKind.SERIES,
                episodes = 12,
                backdropUrl = "https://cdn.myanimelist.net/backdrop-90210.png",
            )
            val jikan = object : ReiAnixAnimeCatalogProvider {
                override val source = ReiAnixCatalogSource.JIKAN
                override suspend fun searchAnime(
                    query: String,
                    limit: Int,
                ): ReiAnixCatalogResult<List<ReiAnixCatalogAnime>> {
                    jikanCalls.incrementAndGet()
                    return ReiAnixCatalogResult.Success(listOf(remote))
                }
                override suspend fun getAnime(externalId: String) =
                    ReiAnixCatalogResult.Success<ReiAnixCatalogAnime?>(null)
            }
            val database = ReiAnixSqliteDatabase(context)
            val repository = ReiAnixSqliteLibraryRepository(database)
            val artwork = ReiAnixArtworkService(context, database)
            val downloadedBackdropBytes = downloadedBackdrop.readBytes()
            val httpCalls = AtomicInteger()
            var requestedUrl: String? = null
            artwork.httpConnectionFactory = { url ->
                httpCalls.incrementAndGet()
                requestedUrl = url.toString()
                FakeArtworkHttpURLConnection(url, 200, downloadedBackdropBytes)
            }
            assertTrue(artwork.hasValidAnimeArtwork(1L, "poster"))
            assertFalse(artwork.hasValidAnimeArtwork(1L, "backdrop"))

            val service = ReiAnixExternalCatalogIntegrationService(
                library = repository,
                providers = ReiAnixCatalogProviderRegistry(
                    listOf(kitsu, jikan),
                    ReiAnixExternalRequestCoordinator(maxConcurrentRequests = 1),
                ),
                artwork = artwork,
            )

            assertEquals(1, service.enrichLibraryGaps())
            assertTrue("A failed Kitsu lookup must not suppress Jikan.", kitsuCalls.get() > 0)
            assertTrue(
                "A complete record with a valid poster but a missing backdrop must remain eligible.",
                jikanCalls.get() > 0,
            )
            assertEquals(90210L, repository.getExternalCatalogIds(1L)?.myAnimeListId)
            val updated = requireNotNull(repository.getAnime(1L))
            assertEquals(poster.absolutePath, updated.coverCache)
            assertEquals("https://example.invalid/old-cover.jpg", updated.coverUrl)
            assertEquals("https://cdn.myanimelist.net/backdrop-90210.png", updated.bannerUrl)
            assertTrue(artwork.hasValidAnimeArtwork(1L, "poster"))
            assertTrue("Backdrop was not materialized: ${artworkDiagnosticSummary()}", artwork.hasValidAnimeArtwork(1L, "backdrop"))
            assertEquals(1, httpCalls.get())
            assertEquals("https://cdn.myanimelist.net/backdrop-90210.png", requestedUrl)

            val posterRow = repository.artworkForAnime(1L).single { it.artworkType == "poster" }
            assertEquals("anime", posterRow.entityType)
            assertEquals("1", posterRow.entityId)
            assertEquals(poster.absolutePath, posterRow.localPath)
            assertEquals("ready", posterRow.status)

            val publishedRow = repository.artworkForAnime(1L).single { it.artworkType == "backdrop" }
            val publishedFile = File(requireNotNull(publishedRow.localPath))
            assertTrue("The downloaded backdrop must exist as a local file.", publishedFile.isFile)
            assertEquals(downloadedBackdropBytes.toList(), publishedFile.readBytes().toList())
            assertEquals("ready", publishedRow.status)
            assertEquals("https://cdn.myanimelist.net/backdrop-90210.png", publishedRow.externalUrl)
            assertEquals(downloadedBackdropBytes.size.toLong(), publishedRow.byteSize)
            assertEquals(8, publishedRow.width)
            assertEquals(8, publishedRow.height)
            assertEquals(
                MessageDigest.getInstance("SHA-256").digest(downloadedBackdropBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) },
                publishedRow.checksum,
            )
            assertEquals(200, publishedRow.httpStatus)
            val publication = artwork.publishedArtworks.value["anime|1|backdrop"]
            assertNotNull("A committed local image must publish an artwork revision.", publication)
            assertTrue(requireNotNull(publication).revision.contains(publishedFile.canonicalPath))

            val projectedRows = repository.loadLibraryCatalogRows()
            val projected = ReiAnixNativeLibraryCatalogProjector.project(projectedRows).animes.single()
            assertEquals(publishedFile.absolutePath, projected.artwork?.backdropLocalPath)
            val incrementalRow = repository.loadLibraryCatalogRowForArtwork("anime", "1")
            assertNotNull("The publication listener must be able to reload the owning library row.", incrementalRow)
            val incrementallyProjected = ReiAnixNativeLibraryCatalogProjector
                .project(listOf(requireNotNull(incrementalRow))).animes.single()
            assertEquals(publishedFile.absolutePath, incrementallyProjected.artwork?.backdropLocalPath)
            assertEquals(poster.absolutePath, updated.coverCache)
            assertEquals(poster.readBytes().toList(), File(updated.coverCache!!).readBytes().toList())
            assertFalse("The poster URL must not be fetched again during backdrop recovery.", httpCalls.get() > 1)

            assertTrue("A second artwork pass should be accepted for reconciliation.", artwork.requestAnimeArtwork(1L))
            withTimeout(5_000L) { artwork.awaitAnimeArtwork(1L) }
            assertEquals("Already validated local artwork must not be downloaded again.", 1, httpCalls.get())
        } finally {
            poster.delete()
            downloadedBackdrop.delete()
        }
    }

    @Test
    fun bannerArtworkRemainsIndependentFromPosterAndBackdropInV29Projection() = runBlocking {
        createCanonicalFixture()
        val poster = createValidPng("banner-separation-poster.png")
        val backdrop = createValidPng("banner-separation-backdrop.png")
        val banner = createValidPng("banner-separation-banner.png")
        try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL(
                    "UPDATE anime SET media_kind='series',cover_cache=?,metadata_manual_fields='[]' WHERE id=1",
                    arrayOf(poster.absolutePath),
                )
            }
            insertArtworkTestRow(
                id = 20L,
                entityType = "anime",
                entityId = 1L,
                artworkType = "backdrop",
                source = "manual",
                localPath = backdrop.absolutePath,
                manual = true,
            )
            insertArtworkTestRow(
                id = 21L,
                entityType = "anime",
                entityId = 1L,
                artworkType = "banner",
                source = "manual",
                localPath = banner.absolutePath,
                manual = true,
            )

            val database = ReiAnixSqliteDatabase(context)
            val repository = ReiAnixSqliteLibraryRepository(database)
            val artwork = ReiAnixArtworkService(context, database)

            assertTrue(artwork.hasValidAnimeArtwork(1L, "poster"))
            assertTrue(artwork.hasValidAnimeArtwork(1L, "backdrop"))
            assertTrue(artwork.hasValidAnimeArtwork(1L, "banner"))

            val schema = repository.inspectSchema()
            assertEquals(29, schema.schemaVersion)

            val projected = ReiAnixNativeLibraryCatalogProjector
                .project(repository.loadLibraryCatalogRows()).animes.single()
            assertEquals(poster.absolutePath, projected.artwork?.localPath)
            assertEquals(backdrop.absolutePath, projected.artwork?.backdropLocalPath)
            assertEquals(banner.absolutePath, projected.artwork?.bannerLocalPath)
            assertFalse(projected.artwork?.bannerLocalPath == projected.artwork?.backdropLocalPath)
        } finally {
            poster.delete()
            backdrop.delete()
            banner.delete()
        }
    }

    @Test
    fun persistedBannerCandidateUsesArtworkPipelineWithoutBecomingBackdrop() = runBlocking {
        createCanonicalFixture()
        val responseImage = createValidPng("persisted-banner-response.png")
        try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL(
                    "UPDATE anime SET media_kind='series',cover_url=NULL,cover_cache=NULL," +
                        "banner_url=NULL,metadata_manual_fields='[]' WHERE id=1",
                )
            }
            val bannerUrl = "https://cdn.kitsu.io/persisted-banner.png"
            insertArtworkTestRow(
                id = 22L,
                entityType = "anime",
                entityId = 1L,
                artworkType = "banner",
                source = "kitsu",
                localPath = null,
                externalUrl = bannerUrl,
            )

            val database = ReiAnixSqliteDatabase(context)
            val repository = ReiAnixSqliteLibraryRepository(database)
            val artwork = ReiAnixArtworkService(context, database)
            val responseBytes = responseImage.readBytes()
            artwork.httpConnectionFactory = { url ->
                FakeArtworkHttpURLConnection(url, 200, responseBytes)
            }

            assertTrue(artwork.requestAnimeArtwork(1L))
            withTimeout(5_000L) { artwork.awaitAnimeArtwork(1L) }

            val publishedRow = repository.artworkForAnime(1L).single { it.artworkType == "banner" }
            assertEquals("ready", publishedRow.status)
            assertEquals(bannerUrl, publishedRow.externalUrl)
            assertTrue(artwork.hasValidAnimeArtwork(1L, "banner"))
            assertNotNull(artwork.publishedArtworks.value["anime|1|banner"])

            val projected = ReiAnixNativeLibraryCatalogProjector
                .project(repository.loadLibraryCatalogRows()).animes.single()
            assertEquals(publishedRow.localPath, projected.artwork?.bannerLocalPath)
            assertNull(projected.artwork?.backdropLocalPath)
        } finally {
            responseImage.delete()
        }
    }

    @Test
    fun backdropCandidateRecoveryDoesNotReplaceAnAlreadyValidPoster() = runBlocking {
        createCanonicalFixture()
        val poster = createValidPng("poster-preserved-during-backdrop-recovery.png")
        try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL(
                    "UPDATE anime SET cover_cache=?,metadata_manual_fields='[]' WHERE id=1",
                    arrayOf(poster.absolutePath),
                )
            }
            val database = ReiAnixSqliteDatabase(context)
            val repository = ReiAnixSqliteLibraryRepository(database)
            val artwork = ReiAnixArtworkService(context, database)
            val before = requireNotNull(repository.getAnime(1L))
            assertTrue(artwork.hasValidAnimeArtwork(1L, "poster"))
            assertFalse(artwork.hasValidAnimeArtwork(1L, "backdrop"))

            assertTrue(
                artwork.replaceExternalArtworkCandidate(
                    animeId = 1L,
                    artworkType = "backdrop",
                    rawUrl = "https://cdn.kitsu.io/backdrop-candidate.jpg",
                ),
            )

            val after = requireNotNull(repository.getAnime(1L))
            assertEquals(before.coverUrl, after.coverUrl)
            assertEquals(before.coverCache, after.coverCache)
            assertEquals("https://cdn.kitsu.io/backdrop-candidate.jpg", after.bannerUrl)
            assertTrue(artwork.hasValidAnimeArtwork(1L, "poster"))
            // A remote URL is only a candidate; it is not treated as a validated local backdrop.
            assertFalse(artwork.hasValidAnimeArtwork(1L, "backdrop"))
        } finally {
            poster.delete()
        }
    }

    @Test
    fun manualPosterAndBackdropArtworkAreProtectedFromSupplementalCandidates() = runBlocking {
        createCanonicalFixture()
        val manualImage = createValidPng("manual-poster-and-backdrop.png")
        try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("UPDATE anime SET metadata_manual_fields='[]' WHERE id=1")
            }
            insertArtworkTestRow(
                id = 20L,
                entityType = "anime",
                entityId = 1L,
                artworkType = "poster",
                source = "manual",
                localPath = manualImage.absolutePath,
                manual = true,
            )
            insertArtworkTestRow(
                id = 21L,
                entityType = "anime",
                entityId = 1L,
                artworkType = "backdrop",
                source = "manual",
                localPath = manualImage.absolutePath,
                manual = true,
            )

            val database = ReiAnixSqliteDatabase(context)
            val artwork = ReiAnixArtworkService(context, database)
            assertTrue(artwork.hasValidAnimeArtwork(1L, "poster"))
            assertTrue(artwork.hasValidAnimeArtwork(1L, "backdrop"))
            assertFalse(
                artwork.replaceExternalArtworkCandidate(
                    1L, "poster", "https://media.kitsu.io/remote-poster.jpg",
                ),
            )
            assertFalse(
                artwork.replaceExternalArtworkCandidate(
                    1L, "backdrop", "https://cdn.myanimelist.net/remote-backdrop.jpg",
                ),
            )
            val rows = ReiAnixSqliteLibraryRepository(database).artworkForAnime(1L)
            assertEquals(manualImage.absolutePath, rows.single { it.artworkType == "poster" }.localPath)
            assertEquals(manualImage.absolutePath, rows.single { it.artworkType == "backdrop" }.localPath)
        } finally {
            manualImage.delete()
        }
    }

    @Test
    fun corruptBackdropAndArtworkFromAnotherEntityTypeAreNotValidForThisAnime() = runBlocking {
        createCanonicalFixture()
        val foreignImage = createValidPng("foreign-movie-backdrop.png")
        val corruptImage = File(context.cacheDir, "corrupt-anime-backdrop.jpg")
        corruptImage.writeText("this is not a decodable image")
        val ownImage = createValidPng("second-anime-backdrop.png")
        try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL(
                    "INSERT INTO anime(id,lookup_title,title,added_at,media_kind) VALUES(2,'other-key','Other Anime',1001,'series')",
                )
            }
            insertArtworkTestRow(
                id = 30L,
                entityType = "movie",
                entityId = 1L,
                artworkType = "backdrop",
                source = "local",
                localPath = foreignImage.absolutePath,
            )
            insertArtworkTestRow(
                id = 31L,
                entityType = "anime",
                entityId = 1L,
                artworkType = "backdrop",
                source = "local",
                localPath = corruptImage.absolutePath,
            )
            insertArtworkTestRow(
                id = 32L,
                entityType = "anime",
                entityId = 2L,
                artworkType = "backdrop",
                source = "local",
                localPath = ownImage.absolutePath,
            )

            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("UPDATE anime SET media_kind='series' WHERE id=1")
            }
            val artwork = ReiAnixArtworkService(context, ReiAnixSqliteDatabase(context))
            assertFalse(
                "A movie artwork row sharing the numeric ID must not be associated with an anime.",
                artwork.hasValidAnimeArtwork(1L, "backdrop"),
            )
            assertTrue(
                "The same valid bytes remain associated with their own anime ID.",
                artwork.hasValidAnimeArtwork(2L, "backdrop"),
            )
            val projected = ReiAnixNativeLibraryCatalogProjector.project(
                ReiAnixSqliteLibraryRepository(context).loadLibraryCatalogRows(),
            ).animes.single { it.id == 1L }
            assertNull(
                "The library projection must not attach a movie backdrop to a series with the same numeric ID.",
                projected.artwork?.backdropLocalPath,
            )
        } finally {
            foreignImage.delete()
            corruptImage.delete()
            ownImage.delete()
        }
    }

    @Test
    @Config(application = Application::class, sdk = [30])
    fun unsupportedAvifWithoutPlatformDecoderIsNotValidOrProjectedLocally() = runBlocking {
        assertEquals(30, Build.VERSION.SDK_INT)
        createCanonicalFixture()
        val unsupportedAvif = File(context.cacheDir, "unsupported-avif-backdrop.avif")
        // ISO-BMFF ftyp/avif signature only; this deliberately contains no decodable image.
        unsupportedAvif.writeBytes(
            byteArrayOf(
                0, 0, 0, 16,
                'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
                'a'.code.toByte(), 'v'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(),
                0, 0, 0, 0,
            ),
        )
        try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("UPDATE anime SET media_kind='series',metadata_manual_fields='[]' WHERE id=1")
            }
            insertArtworkTestRow(
                id = 55L,
                entityType = "anime",
                entityId = 1L,
                artworkType = "backdrop",
                source = "local",
                localPath = unsupportedAvif.absolutePath,
            )

            val database = ReiAnixSqliteDatabase(context)
            val artwork = ReiAnixArtworkService(context, database)
            assertFalse(
                "An AVIF signature without a platform decoder must not count as a valid backdrop on API 30.",
                artwork.hasValidAnimeArtwork(1L, "backdrop"),
            )

            val projected = ReiAnixNativeLibraryCatalogProjector
                .project(ReiAnixSqliteLibraryRepository(database).loadLibraryCatalogRows())
                .animes.single()
            assertNull(
                "The library must not project a local AVIF path that the API 30 decoder cannot validate.",
                projected.artwork?.backdropLocalPath,
            )
        } finally {
            unsupportedAvif.delete()
        }
    }

    @Test
    fun sqliteLibraryProjectionCarriesValidatedBackdropForItsOwningAnime() = runBlocking {
        createCanonicalFixture()
        val backdrop = createValidPng("projected-backdrop.png")
        try {
            insertArtworkTestRow(
                id = 40L,
                entityType = "anime",
                entityId = 1L,
                artworkType = "backdrop",
                source = "local",
                localPath = backdrop.absolutePath,
                externalUrl = "https://cdn.kitsu.io/projected-backdrop.jpg",
            )
            val repository = ReiAnixSqliteLibraryRepository(context)
            val rows = repository.loadLibraryCatalogRows()
            val projected = ReiAnixNativeLibraryCatalogProjector.project(rows).animes.single()

            assertEquals(1L, projected.id)
            assertEquals(backdrop.absolutePath, projected.artwork?.backdropLocalPath)
            assertEquals("https://cdn.kitsu.io/projected-backdrop.jpg", projected.artwork?.backdropExternalUrl)
        } finally {
            backdrop.delete()
        }
    }

    @Test
    fun enrichmentSkipsExternalCatalogWhenPosterAndBackdropAreAlreadyValid() = runBlocking {
        createCanonicalFixture()
        val poster = createValidPng("poster-already-valid.png")
        val backdrop = createValidPng("backdrop-already-valid.png")
        try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL(
                    """
                    UPDATE anime SET year=2016,format='TV',media_kind='series',
                        description='Complete local synopsis for the no-download case.',
                        episodes_count=12,duration=24,status='Finished Airing',score=86,
                        cover_cache=? WHERE id=1
                    """.trimIndent(),
                    arrayOf(poster.absolutePath),
                )
            }
            insertArtworkTestRow(
                id = 50L,
                entityType = "anime",
                entityId = 1L,
                artworkType = "backdrop",
                source = "local",
                localPath = backdrop.absolutePath,
            )
            val kitsuCalls = AtomicInteger()
            val jikanCalls = AtomicInteger()
            fun provider(catalogSource: ReiAnixCatalogSource, calls: AtomicInteger) =
                object : ReiAnixAnimeCatalogProvider {
                    override val source: ReiAnixCatalogSource = catalogSource
                    override suspend fun searchAnime(
                        query: String,
                        limit: Int,
                    ): ReiAnixCatalogResult<List<ReiAnixCatalogAnime>> {
                        calls.incrementAndGet()
                        return ReiAnixCatalogResult.Success(emptyList())
                    }
                    override suspend fun getAnime(externalId: String) =
                        ReiAnixCatalogResult.Success<ReiAnixCatalogAnime?>(null)
                }
            val database = ReiAnixSqliteDatabase(context)
            val artwork = ReiAnixArtworkService(context, database)
            val service = ReiAnixExternalCatalogIntegrationService(
                library = ReiAnixSqliteLibraryRepository(database),
                providers = ReiAnixCatalogProviderRegistry(
                    listOf(
                        provider(ReiAnixCatalogSource.KITSU, kitsuCalls),
                        provider(ReiAnixCatalogSource.JIKAN, jikanCalls),
                    ),
                    ReiAnixExternalRequestCoordinator(maxConcurrentRequests = 1),
                ),
                artwork = artwork,
            )

            assertEquals(0, service.enrichLibraryGaps())
            assertEquals(0, kitsuCalls.get())
            assertEquals(0, jikanCalls.get())
            assertTrue(artwork.hasValidAnimeArtwork(1L, "poster"))
            assertTrue(artwork.hasValidAnimeArtwork(1L, "backdrop"))
        } finally {
            poster.delete()
            backdrop.delete()
        }
    }


    @Test
    fun realLoopbackHttpConnectionMaterializesAndProjectsValidatedBackdrop() = runBlocking {
        createCanonicalFixture()
        val responseImage = createValidPng("real-loopback-backdrop.png")
        val responseBytes = responseImage.readBytes()
        val preservedPoster = createValidPng("real-loopback-preserved-poster.png")
        val posterBytes = preservedPoster.readBytes()
        val server = LocalArtworkHttpServer(
            statusCode = 200,
            responseBytes = responseBytes,
            cacheControl = "public, max-age=300",
        )
        try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                // Seed the loopback URL directly in this isolated fixture. The production
                // external-candidate API intentionally accepts HTTPS only; it is not relaxed
                // just to let a JVM test connect to its own HTTP server.
                db.execSQL(
                    "UPDATE anime SET media_kind='series',cover_url=NULL,cover_cache=?,banner_url=?,metadata_manual_fields='[]' WHERE id=1",
                    arrayOf(preservedPoster.absolutePath, server.url),
                )
            }
            val database = ReiAnixSqliteDatabase(context)
            val repository = ReiAnixSqliteLibraryRepository(database)
            val artwork = ReiAnixArtworkService(context, database)

            // Do not replace httpConnectionFactory: this test crosses a real loopback TCP
            // socket through URL.openConnection(), just as the production transport does.
            assertTrue(artwork.hasValidAnimeArtwork(1L, "poster"))
            assertTrue(artwork.requestAnimeArtwork(1L, force = true))
            withTimeout(5_000L) { artwork.awaitAnimeArtwork(1L) }

            assertTrue("The local HTTP fixture did not receive a TCP request.", server.awaitRequest())
            assertTrue(
                "The artwork materializer must issue a real HTTP GET.",
                server.requestLine.orEmpty().startsWith("GET /backdrop.png HTTP/1.1"),
            )
            assertEquals(1, server.requestCount.get())

            val row = repository.artworkForAnime(1L).single { it.artworkType == "backdrop" }
            val savedFile = File(requireNotNull(row.localPath))
            assertEquals("anime", row.entityType)
            assertEquals("1", row.entityId)
            assertEquals(server.url, row.externalUrl)
            assertEquals("ready", row.status)
            assertEquals(200, row.httpStatus)
            assertEquals(responseBytes.toList(), savedFile.readBytes().toList())
            val artworkCacheExpiry = SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                db.rawQuery(
                    "SELECT next_retry_at FROM artwork WHERE entity_type='anime' AND entity_id='1' " +
                        "AND artwork_type='backdrop' LIMIT 1",
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getDouble(0)
                }
            }
            val nowSeconds = System.currentTimeMillis() / 1_000.0
            assertTrue("Cache-Control max-age must be persisted as an expiry.", artworkCacheExpiry > nowSeconds + 250.0)
            assertTrue("Artwork expiry must remain bounded by max-age.", artworkCacheExpiry <= nowSeconds + 300.0)
            assertTrue(artwork.hasValidAnimeArtwork(1L, "backdrop"))
            assertNotNull(artwork.publishedArtworks.value["anime|1|backdrop"])

            val projected = ReiAnixNativeLibraryCatalogProjector
                .project(repository.loadLibraryCatalogRows()).animes.single()
            assertEquals(preservedPoster.absolutePath, projected.artwork?.localPath)
            assertEquals(posterBytes.toList(), preservedPoster.readBytes().toList())
            assertEquals(savedFile.absolutePath, projected.artwork?.backdropLocalPath)
        } finally {
            server.close()
            responseImage.delete()
            preservedPoster.delete()
        }
    }

    @Test
    fun invalidHttpImageBytesNeverBecomeAPublishedBackdrop() = runBlocking {
        createCanonicalFixture()
        val poster = createValidPng("invalid-backdrop-poster.png")
        try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL(
                    "UPDATE anime SET media_kind='series',cover_cache=?,metadata_manual_fields='[]' WHERE id=1",
                    arrayOf(poster.absolutePath),
                )
            }
            val database = ReiAnixSqliteDatabase(context)
            val artwork = ReiAnixArtworkService(context, database)
            val invalidBytes = "not-a-real-png".toByteArray()
            artwork.httpConnectionFactory = { url ->
                FakeArtworkHttpURLConnection(url, 200, invalidBytes)
            }
            val url = "https://cdn.kitsu.io/invalid-backdrop.png"
            assertTrue(artwork.replaceExternalArtworkCandidate(1L, "backdrop", url))
            assertTrue(artwork.requestAnimeArtwork(1L, force = true))
            withTimeout(5_000L) { artwork.awaitAnimeArtwork(1L) }

            assertFalse(artwork.hasValidAnimeArtwork(1L, "backdrop"))
            val rows = ReiAnixSqliteLibraryRepository(database).artworkForAnime(1L)
            assertTrue(
                "Invalid response must still persist a failure row. Diagnostics: ${artworkDiagnosticSummary()}",
                rows.any { it.artworkType == "backdrop" && it.externalUrl == url },
            )
            val row = rows.single { it.artworkType == "backdrop" && it.externalUrl == url }
            assertEquals("failed", row.status)
            assertNull(row.localPath)
            assertEquals(200, row.httpStatus)
            assertFalse(artwork.publishedArtworks.value.containsKey("anime|1|backdrop"))
            val cache = File(File(context.filesDir, "data/covers"), "artwork")
            assertTrue(cache.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            poster.delete()
        }
    }

    @Test
    fun httpNotFoundRateLimitAndServerErrorsPersistFiniteRetryPolicy() = runBlocking {
        createCanonicalFixture()
        val poster = createValidPng("http-errors-poster.png")
        val responseFile = createValidPng("http-error-response.png")
        val validImageBytes = responseFile.readBytes()
        try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL(
                    "UPDATE anime SET media_kind='series',cover_cache=?,metadata_manual_fields='[]' WHERE id=1",
                    arrayOf(poster.absolutePath),
                )
            }
            val database = ReiAnixSqliteDatabase(context)
            val repository = ReiAnixSqliteLibraryRepository(database)
            val artwork = ReiAnixArtworkService(context, database)
            val cases = listOf(
                Triple(404, null, "https://cdn.kitsu.io/backdrop-404.png"),
                Triple(429, "3600", "https://cdn.kitsu.io/backdrop-429.png"),
                Triple(503, "1800", "https://cdn.kitsu.io/backdrop-503.png"),
            )
            for ((status, retryAfter, url) in cases) {
                artwork.httpConnectionFactory = { requested ->
                    FakeArtworkHttpURLConnection(requested, status, validImageBytes, retryAfter)
                }
                assertTrue(artwork.replaceExternalArtworkCandidate(1L, "backdrop", url))
                val startedAt = System.currentTimeMillis() / 1_000.0
                assertTrue(artwork.requestAnimeArtwork(1L, force = true))
                withTimeout(5_000L) { artwork.awaitAnimeArtwork(1L) }
                val rows = repository.artworkForAnime(1L)
                assertTrue(
                    "HTTP $status must retain its attempt row. Diagnostics: ${artworkDiagnosticSummary()}",
                    rows.any { it.artworkType == "backdrop" && it.externalUrl == url },
                )
                val row = rows.single { it.artworkType == "backdrop" && it.externalUrl == url }
                assertEquals(status, row.httpStatus)
                assertNull(row.localPath)
                assertEquals(if (status == 404) "failed" else "retry_wait", row.status)
                val retryAt = requireNotNull(row.nextRetryAt)
                val expectedDelay = when (status) {
                    404 -> 86_400.0
                    429 -> 3_600.0
                    else -> 1_800.0
                }
                assertTrue(
                    "HTTP $status must persist its bounded retry deadline.",
                    retryAt >= startedAt + expectedDelay - 1.0,
                )
                assertFalse(artwork.hasValidAnimeArtwork(1L, "backdrop"))
            }
            assertTrue(artwork.hasValidAnimeArtwork(1L, "poster"))
        } finally {
            responseFile.delete()
            poster.delete()
        }
    }

    @Test
    fun cancelledBackdropDownloadDoesNotPublishTemporaryFile() = runBlocking {
        createCanonicalFixture()
        val poster = createValidPng("cancel-backdrop-poster.png")
        val responseImage = createValidPng("cancel-backdrop-response.png").readBytes()
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL(
                    "UPDATE anime SET media_kind='series',cover_cache=?,metadata_manual_fields='[]' WHERE id=1",
                    arrayOf(poster.absolutePath),
                )
            }
            val database = ReiAnixSqliteDatabase(context)
            val artwork = ReiAnixArtworkService(context, database)
            artwork.httpConnectionFactory = { url ->
                FakeArtworkHttpURLConnection(url, 200, responseImage, readStarted = readStarted, releaseRead = releaseRead)
            }
            val url = "https://cdn.kitsu.io/cancel-backdrop.png"
            assertTrue(artwork.replaceExternalArtworkCandidate(1L, "backdrop", url))
            assertTrue(artwork.requestAnimeArtwork(1L, force = true))
            assertTrue("HTTP response did not reach the controlled read. Diagnostics: ${artworkDiagnosticSummary()}", readStarted.await(5, TimeUnit.SECONDS))
            assertTrue(artwork.cancelAnimeArtworkRequest(1L))
            withTimeout(5_000L) { artwork.awaitAnimeArtwork(1L) }

            val row = ReiAnixSqliteLibraryRepository(database).artworkForAnime(1L)
                .single { it.artworkType == "backdrop" && it.externalUrl == url }
            assertEquals("not_requested", row.status)
            assertNull(row.localPath)
            assertFalse(artwork.hasValidAnimeArtwork(1L, "backdrop"))
            assertFalse(artwork.publishedArtworks.value.containsKey("anime|1|backdrop"))
            val cache = File(File(context.filesDir, "data/covers"), "artwork")
            assertTrue(cache.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            releaseRead.countDown()
            poster.delete()
        }
    }

    @Test
    fun concurrentBackdropRequestsForSameAnimeShareSingleDownload() = runBlocking {
        createCanonicalFixture()
        val poster = createValidPng("concurrent-backdrop-poster.png")
        val responseImageFile = createValidPng("concurrent-backdrop-response.png")
        val responseBytes = responseImageFile.readBytes()
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val httpCalls = AtomicInteger()
        try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL(
                    "UPDATE anime SET media_kind='series',cover_cache=?,metadata_manual_fields='[]' WHERE id=1",
                    arrayOf(poster.absolutePath),
                )
            }
            val database = ReiAnixSqliteDatabase(context)
            val artwork = ReiAnixArtworkService(context, database)
            artwork.httpConnectionFactory = { url ->
                httpCalls.incrementAndGet()
                FakeArtworkHttpURLConnection(url, 200, responseBytes, readStarted = readStarted, releaseRead = releaseRead)
            }
            assertTrue(artwork.replaceExternalArtworkCandidate(1L, "backdrop", "https://cdn.kitsu.io/concurrent-backdrop.png"))
            assertTrue(artwork.requestAnimeArtwork(1L, force = true))
            assertTrue("First download did not reach the controlled response. Diagnostics: ${artworkDiagnosticSummary()}", readStarted.await(5, TimeUnit.SECONDS))
            assertFalse("A second request for the active anime must be deduplicated.", artwork.requestAnimeArtwork(1L))
            assertEquals(1, httpCalls.get())
            releaseRead.countDown()
            withTimeout(5_000L) { artwork.awaitAnimeArtwork(1L) }
            assertTrue(artwork.hasValidAnimeArtwork(1L, "backdrop"))
            assertEquals(1, httpCalls.get())
        } finally {
            releaseRead.countDown()
            poster.delete()
            responseImageFile.delete()
        }
    }

    /** One-shot local HTTP/1.1 server for exercising the production URLConnection transport. */
    private class LocalArtworkHttpServer(
        statusCode: Int,
        private val responseBytes: ByteArray,
        private val cacheControl: String? = null,
    ) : AutoCloseable {
        private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val requestServed = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        val url: String = "http://127.0.0.1:${serverSocket.localPort}/backdrop.png"
        val requestCount = AtomicInteger()
        @Volatile
        var requestLine: String? = null
            private set

        private val worker = Thread({
            try {
                serverSocket.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                    requestLine = input.readLine()
                    check(!requestLine.isNullOrBlank()) { "The HTTP request line was empty." }
                    while (input.readLine()?.isNotEmpty() == true) Unit
                    requestCount.incrementAndGet()

                    val reason = when (statusCode) {
                        200 -> "OK"
                        404 -> "Not Found"
                        429 -> "Too Many Requests"
                        503 -> "Service Unavailable"
                        else -> "Fixture Response"
                    }
                    val cacheHeader = cacheControl
                        ?.takeIf(String::isNotBlank)
                        ?.let { "Cache-Control: $it\r\n" }
                        .orEmpty()
                    val responseHead = (
                        "HTTP/1.1 $statusCode $reason\r\n" +
                            "Content-Type: image/png\r\n" +
                            cacheHeader +
                            "Content-Length: ${responseBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII)
                    val output = socket.getOutputStream()
                    output.write(responseHead)
                    output.write(responseBytes)
                    output.flush()
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                requestServed.countDown()
            }
        }, "reianix-loopback-artwork-fixture").apply {
            isDaemon = true
            start()
        }

        fun awaitRequest(timeoutMillis: Long = 5_000L): Boolean {
            val served = requestServed.await(timeoutMillis, TimeUnit.MILLISECONDS)
            if (served) {
                failure.get()?.let { throw AssertionError("Local HTTP fixture failed.", it) }
            }
            return served
        }

        override fun close() {
            runCatching { serverSocket.close() }
            worker.join(1_000L)
        }
    }

    private class FakeArtworkHttpURLConnection(
        url: URL,
        private val statusCode: Int,
        private val responseBytes: ByteArray,
        private val retryAfter: String? = null,
        private val readStarted: CountDownLatch? = null,
        private val releaseRead: CountDownLatch? = null,
    ) : HttpURLConnection(url) {
        override fun connect() {
            connected = true
        }

        override fun disconnect() {
            releaseRead?.countDown()
        }

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = statusCode

        override fun getContentType(): String? = "image/png"

        override fun getHeaderField(name: String?): String? = when (name?.lowercase()) {
            "content-length" -> responseBytes.size.toString()
            "retry-after" -> retryAfter
            else -> null
        }

        override fun getInputStream(): InputStream {
            if (statusCode !in 200..299) throw IOException("HTTP $statusCode")
            val bytes = ByteArrayInputStream(responseBytes)
            if (releaseRead == null) return bytes
            return object : InputStream() {
                private fun awaitReadRelease() {
                    readStarted?.countDown()
                    try {
                        releaseRead.await()
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Controlled HTTP read interrupted.", interrupted)
                    }
                }

                override fun read(): Int {
                    awaitReadRelease()
                    return bytes.read()
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    awaitReadRelease()
                    return bytes.read(buffer, offset, length)
                }

                override fun close() {
                    bytes.close()
                }
            }
        }
    }

    private fun createValidPng(fileName: String): File {
        val file = File(context.cacheDir, fileName)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun artworkDiagnosticSummary(): String =
        ReiAnixDiagnosticTimeline.events.value
            .filter { it.source == "ReiAnixArtworkService" }
            .take(20)
            .joinToString("; ") { event ->
                "${event.name}=${event.result.orEmpty()} error=${event.error.orEmpty()} context=${event.context}"
            }

    private fun insertArtworkTestRow(
        id: Long,
        entityType: String,
        entityId: Long,
        artworkType: String,
        source: String,
        localPath: String?,
        manual: Boolean = false,
        externalUrl: String? = null,
    ) {
        val values = ContentValues().apply {
            put("id", id)
            put("entity_type", entityType)
            put("entity_id", entityId.toString())
            put("artwork_type", artworkType)
            put("source", source)
            if (localPath == null) putNull("local_path") else put("local_path", localPath)
            if (externalUrl == null) putNull("external_url") else put("external_url", externalUrl)
            put("manual", if (manual) 1 else 0)
            put("priority", 75)
            put("status", "ready")
            put("discovered_at", 1_000.0)
            put("updated_at", 1_000.0)
        }
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.insertOrThrow("artwork", null, values)
        }
    }


    @Test
    fun externalProviderCandidatesPersistByTypeAndKeepLegacyBannerUrlAsBackdrop() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                "UPDATE anime SET media_kind='series',cover_url=NULL,cover_cache=NULL,banner_url=NULL," +
                    "metadata_manual_fields='[]' WHERE id=1",
            )
        }
        val database = ReiAnixSqliteDatabase(context)
        val artwork = ReiAnixArtworkService(context, database)

        assertTrue(
            artwork.replaceExternalArtworkCandidate(
                animeId = 1L, artworkType = "poster", rawUrl = "https://cdn.kitsu.io/poster.jpg",
                source = "kitsu", sourceRef = "kitsu:42:posterImage", variant = "original",
            ),
        )
        assertTrue(
            artwork.replaceExternalArtworkCandidate(
                animeId = 1L, artworkType = "backdrop", rawUrl = "https://cdn.anipub.org/backdrop.jpg",
                source = "anipub", sourceRef = "anipub:91:Backdrop", variant = "wide",
            ),
        )
        assertTrue(
            artwork.replaceExternalArtworkCandidate(
                animeId = 1L, artworkType = "banner", rawUrl = "https://cdn.anilist.co/banner.jpg",
                source = "anilist", sourceRef = "anilist:777:bannerImage", variant = "original",
            ),
        )

        fun snapshot(): Map<String, Pair<String, String>> =
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(
                    "SELECT artwork_type,external_url,source_ref FROM artwork " +
                        "WHERE entity_type='anime' AND entity_id='1' AND artwork_type IN ('poster','backdrop','banner')",
                    null,
                ).use { cursor ->
                    buildMap {
                        while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1) to cursor.getString(2))
                    }
                }
            }

        val initial = snapshot()
        assertEquals("https://cdn.kitsu.io/poster.jpg", initial["poster"]?.first)
        assertEquals("kitsu:42:posterImage", initial["poster"]?.second)
        assertEquals("https://cdn.anipub.org/backdrop.jpg", initial["backdrop"]?.first)
        assertEquals("anipub:91:Backdrop", initial["backdrop"]?.second)
        assertEquals("https://cdn.anilist.co/banner.jpg", initial["banner"]?.first)
        assertEquals("anilist:777:bannerImage", initial["banner"]?.second)

        // Replacing only Poster must not overwrite the other two artwork records.
        assertTrue(
            artwork.replaceExternalArtworkCandidate(
                animeId = 1L, artworkType = "poster", rawUrl = "https://cdn.kitsu.io/poster-v2.jpg",
                source = "kitsu", sourceRef = "kitsu:42:posterImage", variant = "original",
            ),
        )
        val updated = snapshot()
        assertEquals("https://cdn.kitsu.io/poster-v2.jpg", updated["poster"]?.first)
        assertEquals(initial["backdrop"], updated["backdrop"])
        assertEquals(initial["banner"], updated["banner"])

        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT banner_url FROM anime WHERE id=1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("https://cdn.anipub.org/backdrop.jpg", cursor.getString(0))
            }
            db.rawQuery("PRAGMA user_version", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(29, cursor.getInt(0))
            }
        }
    }

    @Test
    fun aniListBannerImagePersistsAsBannerWithoutChangingLegacyBackdrop() = runBlocking {
        createCanonicalFixture()
        val legacyBackdrop = "https://cdn.example/legacy-backdrop.jpg"
        val aniListBanner = "https://cdn.anilist.co/banner-image.jpg"
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                "UPDATE anime SET media_kind='series',anilist_id=777,banner_url=?," +
                    "metadata_manual_fields='[]' WHERE id=1",
                arrayOf(legacyBackdrop),
            )
        }

        val repository = ReiAnixSqliteLibraryRepository(ReiAnixSqliteDatabase(context))
        val updated = repository.updateAniListMetadataAndAssociation(
            patch = ReiAnixAnimeMetadataPatch(
                animeId = 1L,
                bannerArtworkUrl = aniListBanner,
                bannerArtworkSourceRef = "anilist:777:bannerImage",
                bannerArtworkVariant = "original",
            ),
            anilistId = 777L,
            manual = true,
            score = null,
            margin = null,
        )

        assertEquals("The legacy field continues to represent Backdrop.", legacyBackdrop, updated.bannerUrl)
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT artwork_type,source,source_ref,external_url FROM artwork " +
                    "WHERE entity_type='anime' AND entity_id='1' AND artwork_type='banner'",
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("banner", cursor.getString(0))
                assertEquals("anilist", cursor.getString(1))
                assertEquals("anilist:777:bannerImage", cursor.getString(2))
                assertEquals(aniListBanner, cursor.getString(3))
                assertFalse(cursor.moveToNext())
            }
            db.rawQuery("SELECT banner_url FROM anime WHERE id=1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(legacyBackdrop, cursor.getString(0))
            }
            db.rawQuery("PRAGMA user_version", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(29, cursor.getInt(0))
            }
        }
    }

    private fun createCanonicalFixture(transformFixture: (JSONObject) -> Unit = {}) {
        databaseFile.parentFile?.mkdirs()
        val schema = loadCanonicalSchemaFixture()
        transformFixture(schema)
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

    @Test
    fun aniMapAndAniPubEnrichmentShareVerifiedIdsAndPreserveV29LocalArtworkOwnership() = runBlocking {
        createCanonicalFixture()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                """
                UPDATE anime
                SET year=2016, format='TV', media_kind='series', episodes_count=12,
                    anilist_id=777, cover_url=NULL, banner_url=NULL
                WHERE id=1
                """.trimIndent(),
            )
        }
        val database = ReiAnixSqliteDatabase(context)
        val library = ReiAnixSqliteLibraryRepository(database)
        val mapping = ReiAnixCatalogIdentifierMapping(
            ids = ReiAnixExternalCatalogIds(aniListId = 777L, myAnimeListId = 9002L, aniDbId = 23L),
            title = "Original Title",
            alternativeTitles = setOf("Old English Title"),
            releaseYear = 2016,
            format = "TV",
            mediaKind = ReiAnixCatalogMediaKind.SERIES,
            episodes = 12,
        )
        val resolver = object : ReiAnixCatalogIdResolver {
            override suspend fun resolveIds(ids: ReiAnixExternalCatalogIds): ReiAnixCatalogResult<ReiAnixCatalogIdentifierMapping?> {
                assertEquals(777L, ids.aniListId)
                return ReiAnixCatalogResult.Success(mapping)
            }
        }
        val remote = ReiAnixCatalogAnime(
            source = ReiAnixCatalogSource.ANIPUB,
            ids = ReiAnixExternalCatalogIds(
                myAnimeListId = 9002L,
                additionalIds = mapOf("anipub" to "91"),
            ),
            title = "Original Title",
            alternativeTitles = setOf("Old English Title"),
            synopsis = "AniPub contributes a verified synopsis without replacing editorial fields.",
            releaseYear = 2016,
            format = "TV",
            mediaKind = ReiAnixCatalogMediaKind.SERIES,
            episodes = 12,
            durationMinutes = 24,
            status = "Finished Airing",
        )
        val anipub = object : ReiAnixAnimeCatalogProvider {
            var searchCalls = 0
            override val source = ReiAnixCatalogSource.ANIPUB
            override suspend fun searchAnime(
                query: String,
                limit: Int,
            ): ReiAnixCatalogResult<List<ReiAnixCatalogAnime>> {
                searchCalls++
                return ReiAnixCatalogResult.Success(listOf(remote))
            }
            override suspend fun getAnime(externalId: String) =
                ReiAnixCatalogResult.Success<ReiAnixCatalogAnime?>(null)
        }
        val registry = ReiAnixCatalogProviderRegistry(
            providers = listOf(anipub),
            requestCoordinator = ReiAnixExternalRequestCoordinator(maxConcurrentRequests = 1),
        )
        val artwork = ReiAnixArtworkService(context, database)
        val service = ReiAnixExternalCatalogIntegrationService(
            library = library,
            providers = registry,
            artwork = artwork,
            identifierResolver = resolver,
        )

        assertEquals(1, service.enrichLibraryGaps())
        val savedIds = requireNotNull(library.getExternalCatalogIds(1L))
        assertEquals(777L, savedIds.aniListId)
        assertEquals(9002L, savedIds.myAnimeListId)
        assertEquals(23L, savedIds.aniDbId)
        assertEquals("91", savedIds.additionalIds["anipub"])
        val updated = requireNotNull(library.getAnime(1L))
        assertEquals(
            "AniPub contributes a verified synopsis without replacing editorial fields.",
            updated.description,
        )
        assertEquals("Original Title", updated.title)
        assertNull(updated.coverUrl)
        assertNull(updated.bannerUrl)
        assertEquals(3, anipub.searchCalls)
        assertEquals(29, library.inspectSchema().schemaVersion)
        registry.cancelPendingRequests()
    }

}
