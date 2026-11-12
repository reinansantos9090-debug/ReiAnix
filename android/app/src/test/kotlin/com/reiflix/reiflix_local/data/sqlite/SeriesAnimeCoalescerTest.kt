package com.reiflix.reiflix_local.data.sqlite

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SeriesAnimeCoalescerTest {
    private fun db(): SQLiteDatabase = SQLiteDatabase.create(null).apply {
        execSQL(
            """CREATE TABLE anime (
                id INTEGER PRIMARY KEY, anilist_id INTEGER, media_kind TEXT,
                metadata_source TEXT, metadata_status TEXT, metadata_manual_fields TEXT,
                anilist_match_manual INTEGER, favorite INTEGER, is_pinned INTEGER,
                user_tags TEXT, personal_note TEXT, cover_url TEXT, cover_cache TEXT,
                banner_url TEXT, description TEXT, description_original TEXT,
                genres TEXT, studio TEXT, romaji TEXT, english TEXT, native TEXT
            )"""
        )
        execSQL(
            """CREATE TABLE episodes (
                id INTEGER PRIMARY KEY, anime_id INTEGER NOT NULL,
                media_identity TEXT, file_name TEXT, progress REAL
            )"""
        )
        execSQL(
            """CREATE TABLE artwork (
                id INTEGER PRIMARY KEY, entity_type TEXT, entity_id TEXT
            )"""
        )
        execSQL(
            """CREATE TABLE anime_genres (
                anime_id INTEGER,genre_id INTEGER,source TEXT,
                created_at REAL,updated_at REAL,
                UNIQUE(anime_id,genre_id)
            )"""
        )
    }
    private fun insert(db: SQLiteDatabase,id: Long,remoteId: Long,episodes: Int,first:Int=1,
                       manual:Boolean=false) {
        db.execSQL(
            "INSERT INTO anime(id,anilist_id,media_kind,metadata_source,metadata_manual_fields,anilist_match_manual,user_tags) VALUES(?,?,'series','anilist','[]',?,'[]')",
            arrayOf(id,remoteId,if(manual)1 else 0)
        )
        repeat(episodes) { i ->
            val n=first+i
            db.execSQL(
                "INSERT INTO episodes(id,anime_id,media_identity,file_name,progress) VALUES(?,?,'saf:'||?, ?, ?)",
                arrayOf(id*1000+n,id,"$id-$n","Episode $n.mkv",if(n==1)12.5 else 0.0),
            )
        }
    }
    private fun count(db:SQLiteDatabase,anime:Long):Int =
        db.rawQuery("SELECT COUNT(*) FROM episodes WHERE anime_id=?",arrayOf(anime.toString())).use {
            it.moveToFirst();it.getInt(0)
        }

    @Test
    fun twoSplitShowsJoinWithoutRemovingAnyOfTheirPhysicalEpisodes() {
        db().use { database ->
            // Pass the Monster Meat: 6 numbered + 6 titled episodes.
            insert(database,1,10001,6)
            insert(database,2,10001,6,7)
            // May I Ask for One Final Thing: 10 numbered + 3 titled.
            insert(database,3,10002,10)
            insert(database,4,10002,3,11)
            // Wandance was already correct, so leave it intact.
            insert(database,5,10003,12)
            insert(database,6,10004,1)

            assertTrue(SeriesAnimeCoalescer.mergeVerified(database))
            assertEquals(12,count(database,1))
            assertEquals(13,count(database,3))
            assertEquals(12,count(database,5))
            assertEquals(1,count(database,6))
            assertEquals(0,count(database,2))
            assertEquals(0,count(database,4))
            database.rawQuery("SELECT COUNT(*) FROM anime",null).use {
                it.moveToFirst();assertEquals(4,it.getInt(0))
            }
            database.rawQuery("SELECT progress FROM episodes WHERE id=1001",null).use {
                assertTrue(it.moveToFirst());assertEquals(12.5,it.getDouble(0),0.0)
            }
        }
    }

    @Test
    fun manualAssociationsRemainSeparateAndPreferredVerifiedAlbumSurvives() {
        db().use { database ->
            insert(database,1,90001,1,manual=true)
            insert(database,2,90001,1,2)
            assertFalse(SeriesAnimeCoalescer.mergeVerified(database))
            assertEquals(1,count(database,1))
            assertEquals(1,count(database,2))
        }
        db().use { database ->
            insert(database,1,90001,1)
            insert(database,2,90001,1,2)
            assertTrue(SeriesAnimeCoalescer.mergeVerified(database,preferredId=2))
            assertEquals(2,count(database,2))
            assertEquals(0,count(database,1))
        }
    }
}
