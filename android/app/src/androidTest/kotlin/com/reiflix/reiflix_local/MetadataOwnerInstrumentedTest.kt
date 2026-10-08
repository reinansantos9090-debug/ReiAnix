package com.reiflix.reiflix_local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MetadataOwnerInstrumentedTest {
    private lateinit var target: Context
    private lateinit var device: UiDevice
    private lateinit var databaseFile: File

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        target = instrumentation.targetContext
        device = UiDevice.getInstance(instrumentation)
        databaseFile = File(File(target.filesDir, "data"), "library.sqlite3")
        installFixture()
        val intent = android.content.Intent(target, MainActivity::class.java)
            .addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        instrumentation.startActivitySync(intent)
        waitForForegroundPackage(target.packageName)
    }

    @After
    fun tearDown() {
        runCatching { device.pressHome() }
    }

    @Test
    fun metadata_refresh_preserves_five_local_episodes_and_progress() {
        val before = readDatabaseSnapshot()
        assertEquals("Fixture must contain exactly five local episodes", 5, before.episodeIds.size)
        assertEquals("Fixture EP01 must start at 37%", 37.0, before.progress.first(), 0.01)

        val title = By.text(before.title)
        assertNotNull("Fixture anime must be rendered on Home", waitFor(title, 20_000L))
        device.findObject(title).click()

        assertNotNull("Details screen must open", waitFor(By.text("Detalhes"), 10_000L))

        val refresh = By.text("Atualizar metadata")
        assertNotNull("Details must expose the existing metadata refresh action", waitFor(refresh, 10_000L))
        device.findObject(refresh).click()

        // The request may succeed or fail depending on network availability; either
        // result must leave the canonical local collection untouched.
        waitFor(By.text("Atualizar metadata"), 10_000L)
        SystemClock.sleep(750L)

        val after = readDatabaseSnapshot()
        assertLocalSnapshotUnchanged(before, after)
        assertAllEpisodeLabelsVisible()

        device.pressBack()
        waitForForegroundPackage(target.packageName)
        val afterTitle = By.text(after.title)
        assertNotNull("Home must remain mounted after metadata refresh", waitFor(afterTitle, 10_000L))
        device.findObject(afterTitle).click()
        assertNotNull("Details must reopen after metadata refresh", waitFor(By.text("Detalhes"), 10_000L))
        assertAllEpisodeLabelsVisible()
        assertLocalSnapshotUnchanged(before, readDatabaseSnapshot())

        // Process restart regression: metadata must not depend on in-memory
        // catalog state or the previous Details instance.
        device.executeShellCommand("am force-stop ${target.packageName}")
        val restartIntent = android.content.Intent(target, MainActivity::class.java)
            .addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        InstrumentationRegistry.getInstrumentation().startActivitySync(restartIntent)
        waitForForegroundPackage(target.packageName)
        val afterRestart = readDatabaseSnapshot()
        assertLocalSnapshotUnchanged(before, afterRestart)
        assertNotNull(
            "Home must remount the local anime after process restart",
            waitFor(By.text(afterRestart.title), 20_000L)
        )
        device.findObject(By.text(afterRestart.title)).click()
        assertNotNull("Details must reopen after process restart", waitFor(By.text("Detalhes"), 10_000L))
        assertAllEpisodeLabelsVisible()
        assertLocalSnapshotUnchanged(before, readDatabaseSnapshot())
    }

    private fun installFixture() {
        val dataDir = databaseFile.parentFile ?: error("Missing Flet data directory")
        if (!dataDir.exists()) {
            assertTrue("Unable to create Flet data directory", dataDir.mkdirs() || dataDir.isDirectory)
        }
        databaseFile.delete()
        InstrumentationRegistry.getInstrumentation().context.assets.open("library_fixture.sqlite3").use { input ->
            databaseFile.outputStream().use { output -> input.copyTo(output) }
        }
        assertTrue("earlier validation stage 43 SQLite fixture must exist", databaseFile.isFile)
    }

    private data class DbSnapshot(
        val animeId: Long,
        val title: String,
        val anilistId: Long?,
        val episodeIds: List<Long>,
        val episodeAnimeIds: List<Long>,
        val episodePaths: List<String>,
        val mediaIdentities: List<String>,
        val progress: List<Double>,
    )

    private fun readDatabaseSnapshot(): DbSnapshot {
        val db = SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY)
        db.use { database ->
            database.rawQuery(
                "SELECT id, title, anilist_id FROM anime ORDER BY id LIMIT 1",
                null,
            ).use { animeCursor ->
                assertTrue("Fixture anime row must exist", animeCursor.moveToFirst())
                val animeId = animeCursor.getLong(animeCursor.getColumnIndexOrThrow("id"))
                val title = animeCursor.getString(animeCursor.getColumnIndexOrThrow("title"))
                val anilistIndex = animeCursor.getColumnIndexOrThrow("anilist_id")
                val anilistId = if (animeCursor.isNull(anilistIndex)) {
                    null
                } else {
                    animeCursor.getLong(anilistIndex)
                }
                val ids = mutableListOf<Long>()
                val owners = mutableListOf<Long>()
                val paths = mutableListOf<String>()
                val identities = mutableListOf<String>()
                val progress = mutableListOf<Double>()

                database.rawQuery(
                    "SELECT id, anime_id, path, media_identity, progress FROM episodes WHERE anime_id=? ORDER BY number",
                    arrayOf(animeId.toString()),
                ).use { episodeCursor ->
                    while (episodeCursor.moveToNext()) {
                        ids += episodeCursor.getLong(episodeCursor.getColumnIndexOrThrow("id"))
                        owners += episodeCursor.getLong(episodeCursor.getColumnIndexOrThrow("anime_id"))
                        paths += episodeCursor.getString(episodeCursor.getColumnIndexOrThrow("path"))
                        identities += episodeCursor.getString(episodeCursor.getColumnIndexOrThrow("media_identity"))
                        progress += episodeCursor.getDouble(episodeCursor.getColumnIndexOrThrow("progress"))
                    }
                }
                return DbSnapshot(animeId, title, anilistId, ids, owners, paths, identities, progress)
            }
        }
    }

    private fun assertLocalSnapshotUnchanged(before: DbSnapshot, after: DbSnapshot) {
        assertEquals("anime.id must remain canonical", before.animeId, after.animeId)
        assertEquals("episode IDs must remain canonical", before.episodeIds, after.episodeIds)
        assertEquals("episode anime_id ownership must remain canonical", before.episodeAnimeIds, after.episodeAnimeIds)
        assertEquals("episode paths must remain stable", before.episodePaths, after.episodePaths)
        assertEquals("media_identity values must remain stable", before.mediaIdentities, after.mediaIdentities)
        assertEquals("progress must remain stable", before.progress, after.progress)
        assertEquals("AniList ID must be persisted only as external metadata identity", 16498L, after.anilistId)
        assertTrue("Metadata title may change, but it must remain non-empty", after.title.isNotBlank())
        assertEquals("All five episodes must remain in SQLite", 5, after.episodeIds.size)
        assertTrue("Every episode must retain its local owner", after.episodeAnimeIds.all { it == after.animeId })
    }

    private fun assertAllEpisodeLabelsVisible() {
        for (episode in 1..5) {
            scrollToText("EP ${episode.toString().padStart(2, '0')}")
        }
    }

    private fun scrollToText(text: String) {
        val selector = UiSelector().text(text)
        val scrollable = UiScrollable(UiSelector().scrollable(true))
        runCatching { scrollable.scrollIntoView(selector) }
        assertNotNull("Details must still expose $text", waitFor(By.text(text), 3_000L))
    }

    private fun waitFor(selector: androidx.test.uiautomator.BySelector, timeoutMs: Long): UiObject2? {
        val immediate = device.findObject(selector)
        return immediate ?: device.wait(Until.findObject(selector), timeoutMs)
    }

    private fun waitForForegroundPackage(vararg packages: String, timeoutMs: Long = 15_000L) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        val expected = packages.toSet()
        while (SystemClock.uptimeMillis() < deadline) {
            if (expected.contains(device.currentPackageName)) return
            SystemClock.sleep(100L)
        }
        error("Expected foreground Android package ${expected.joinToString()}, got ${device.currentPackageName}")
    }
}
