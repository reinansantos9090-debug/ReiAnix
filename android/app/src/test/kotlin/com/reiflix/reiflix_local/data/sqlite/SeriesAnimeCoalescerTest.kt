package com.reiflix.reiflix_local.data.sqlite

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
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
    private fun db():SQLiteDatabase = SQLiteDatabase.create(null).apply {
        execSQL(
            """CREATE TABLE anime (
                id INTEGER PRIMARY KEY, anilist_id INTEGER, title TEXT, lookup_title TEXT,
                romaji TEXT, english TEXT, native TEXT, aliases TEXT,
                media_kind TEXT, metadata_source TEXT, metadata_status TEXT,
                metadata_manual_fields TEXT, anilist_match_manual INTEGER,
                favorite INTEGER, is_pinned INTEGER,user_tags TEXT, personal_note TEXT,
                cover_url TEXT, cover_cache TEXT,banner_url TEXT,description TEXT,
                description_original TEXT,genres TEXT,studio TEXT,
                episodes_count INTEGER
            )"""
        )
        execSQL(
            """CREATE TABLE episodes (
                id INTEGER PRIMARY KEY, anime_id INTEGER NOT NULL,
                media_identity TEXT, file_name TEXT, progress REAL,
                season INTEGER, number REAL,episode_type TEXT
            )"""
        )
        execSQL("CREATE TABLE artwork(id INTEGER PRIMARY KEY, entity_type TEXT,entity_id TEXT)")
        execSQL(
            """CREATE TABLE anime_genres(
                anime_id INTEGER,genre_id INTEGER,source TEXT,
                created_at REAL,updated_at REAL,UNIQUE(anime_id,genre_id)
            )"""
        )
        execSQL("CREATE TABLE native_downloads(id TEXT PRIMARY KEY,data TEXT)")
        execSQL("CREATE TABLE preferences(key TEXT PRIMARY KEY,value TEXT,updated_at REAL)")
    }

    private fun add(db:SQLiteDatabase,id:Long,remote:Long?,name:String,first:Int,last:Int,
        season:Int=1,manual:Boolean=false,kind:String="series",
        episodeKind:String="regular",expected:Int?=null,
        manualFields:String="[]") {
        db.execSQL(
            """INSERT INTO anime(
                id,anilist_id,title,lookup_title,media_kind,
                metadata_source,metadata_manual_fields,anilist_match_manual,user_tags,episodes_count
            ) VALUES(?,?,?,?,?,'anilist',?,?,'[]',?)""",
            arrayOf(id,remote,name,name.lowercase(),kind,manualFields,if(manual)1 else 0,expected),
        )
        for(n in first..last)db.execSQL(
            "INSERT INTO episodes(id,anime_id,media_identity,file_name,progress,season,number,episode_type) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(id*1000+n,id,"saf:$id-$n","Episode $n.mkv",if(n==1)12.5 else 0.0,
                season,n.toDouble(),episodeKind),
        )
    }

    private fun count(db:SQLiteDatabase,anime:Long):Int =
        db.rawQuery("SELECT COUNT(*) FROM episodes WHERE anime_id=?",arrayOf(anime.toString())).use{
            it.moveToFirst();it.getInt(0)
        }
    private fun countSeason(db:SQLiteDatabase,anime:Long,season:Int):Int =
        db.rawQuery("SELECT COUNT(*) FROM episodes WHERE anime_id=? AND season=?",
            arrayOf(anime.toString(),season.toString())).use {
            it.moveToFirst();it.getInt(0)
        }

    @Test fun twoSplitShowsMergeAndUnrelatedWandanceStaysUntouched() {
        db().use {database ->
            add(database,1,10001,"Pass the Monster Meat, Milady!",1,6)
            add(database,2,10001,"Pass the Monster Meat, Milady!",7,12)
            add(database,3,10002,"May I Ask for One Final Thing?",1,10)
            add(database,4,10002,"May I Ask for One Final Thing?",11,13)
            add(database,5,10003,"Wandance",1,12)
            add(database,6,10004,"Another Series",1,1)
            assertTrue(SeriesAnimeCoalescer.mergeVerified(database))
            assertEquals(12,count(database,1))
            assertEquals(13,count(database,3))
            assertEquals(12,count(database,5))
            assertEquals(1,count(database,6))
            assertEquals(0,count(database,2))
            assertEquals(0,count(database,4))
            database.rawQuery("SELECT progress FROM episodes WHERE id=1001",null).use {
                assertTrue(it.moveToFirst());assertEquals(12.5,it.getDouble(0),0.0)
            }
            assertFalse(SeriesAnimeCoalescer.mergeVerified(database))
        }
    }

    @Test fun conflictingAniListIdsMergeOnlyWithCompleteNumericContinuationAndSameExpectedTotal() {
        db().use {database ->
            // The user's 6+6 case has two visually identical series names, but
            // provider associations may have inconsistent remote IDs.
            add(database,1,521001,"Pass the Monster Meat, Milady!",1,6,expected=12)
            add(database,2,521099,"Pass the Monster Meat, Milady!",7,12,expected=12)
            assertTrue(SeriesAnimeCoalescer.mergeVerified(database))
            assertEquals(12,count(database,1))
            assertEquals(0,count(database,2))
            database.rawQuery("SELECT value FROM preferences WHERE key=?",
                arrayOf("reianix.series.merged.1.2")).use {
                assertTrue(it.moveToFirst())
                assertEquals(521099,JSONObject(it.getString(0)).getInt("anilistId"))
            }
            assertFalse(SeriesAnimeCoalescer.mergeVerified(database))
        }
    }

    @Test fun customCoverFieldsDoNotPreventMergingTwoHalvesWithSameVerifiedIdentity() {
        db().use {database ->
            add(database,1,77,"Pass the Monster Meat, Milady!",1,6)
            add(database,2,77,"Pass the Monster Meat, Milady!",7,12,
                manualFields="""["cover_url","description"]""")
            database.execSQL(
                "UPDATE anime SET cover_url='https://example.org/manual.png' WHERE id=2"
            )
            assertTrue(SeriesAnimeCoalescer.mergeVerified(database))
            assertEquals(12,count(database,2))
            database.rawQuery("SELECT cover_url FROM anime WHERE id=2",null).use {
                assertTrue(it.moveToFirst())
                assertEquals("https://example.org/manual.png",it.getString(0))
            }
        }
    }

    @Test fun differentSeriesAndConflictingEpisodeRangesNeverMergeOnTitleAlone() {
        db().use {database ->
            add(database,1,101,"Shared Anime Title",1,6,expected=12)
            add(database,2,102,"Shared Anime Title",1,6,expected=12)
            add(database,3,103,"Shared Anime Title",7,13,expected=13)
            add(database,4,104,"Other Anime Title",7,12,expected=12)
            assertFalse(SeriesAnimeCoalescer.mergeVerified(database))
            assertEquals(6,count(database,1))
            assertEquals(6,count(database,2))
            assertEquals(7,count(database,3))
            assertEquals(6,count(database,4))
        }
    }

    @Test fun sameTitleWithDifferentRemoteIdsAndMissingExpectedTotalRemainsUnmerged() {
        db().use {database ->
            add(database,1,201,"Shared Anime Title",1,6)
            add(database,2,202,"Shared Anime Title",7,12)
            assertFalse(SeriesAnimeCoalescer.mergeVerified(database))
        }
    }

    @Test fun seasonSequelsHaveOneCoverButSeparateSeasonEpisodeLists() {
        db().use {database ->
            add(database,11,301,"Youjo Senki",1,12)
            add(database,12,302,"Youjo Senki II",1,7)
            assertTrue(SeriesAnimeCoalescer.mergeVerified(database))
            assertEquals(19,count(database,11))
            assertEquals(12,countSeason(database,11,1))
            assertEquals(7,countSeason(database,11,2))
            assertEquals(0,count(database,12))
        }
    }

    @Test fun thirdSeasonJoinsEvenAfterSecondSeasonMetadataWasPreserved() {
        db().use {database ->
            add(database,11,301,"Youjo Senki",1,12)
            add(database,12,302,"Youjo Senki II",1,7)
            add(database,13,303,"Youjo Senki Season 3",1,3)
            assertTrue(SeriesAnimeCoalescer.mergeVerified(database))
            assertEquals(12,countSeason(database,11,1))
            assertEquals(7,countSeason(database,11,2))
            assertEquals(3,countSeason(database,11,3))
            assertFalse(SeriesAnimeCoalescer.mergeVerified(database))
        }
    }

    @Test fun completedNativeDownloadsDoNotPreventVerifiedSeriesGrouping() {
        db().use {database ->
            add(database,1,10001,"Pass the Monster Meat, Milady!",1,6)
            add(database,2,10001,"Pass the Monster Meat, Milady!",7,12)
            database.execSQL("UPDATE episodes SET media_identity='download:finished' WHERE id=2007")
            database.execSQL("INSERT INTO native_downloads(id,data) VALUES('finished',?)",
                arrayOf(JSONObject().put("id","finished").put("animeId",2)
                    .put("episodeId",2007).put("state","COMPLETE").toString()))
            assertTrue(SeriesAnimeCoalescer.mergeVerified(database))
            assertEquals(12,count(database,1))
            database.rawQuery("SELECT data FROM native_downloads WHERE id='finished'",null).use{
                assertTrue(it.moveToFirst());assertEquals(1,JSONObject(it.getString(0)).getInt("animeId"))
            }
        }
    }

    @Test fun unfinishedTransfersAreProtectedUntilCompletion() {
        db().use {database ->
            add(database,1,10001,"Pass the Monster Meat, Milady!",1,6)
            add(database,2,10001,"Pass the Monster Meat, Milady!",7,12)
            database.execSQL("INSERT INTO native_downloads(id,data) VALUES('active',?)",
                arrayOf(JSONObject().put("id","active").put("animeId",2)
                    .put("episodeId",2007).put("state","DOWNLOADING").toString()))
            assertFalse(SeriesAnimeCoalescer.mergeVerified(database))
            assertEquals(6,count(database,2))
            database.execSQL("UPDATE native_downloads SET data=? WHERE id='active'",
                arrayOf(JSONObject().put("id","active").put("animeId",2)
                    .put("episodeId",2007).put("state","COMPLETE").toString()))
            assertTrue(SeriesAnimeCoalescer.mergeVerified(database))
        }
    }

    @Test fun similarlyNamedShowsAndFilmsDoNotMerge() {
        db().use {database ->
            add(database,1,100,"Youjo Senki",1,12)
            add(database,2,101,"Youjo Shenki 2",1,7)
            add(database,3,102,"Youjo Senki Movie",1,1,kind="movie",episodeKind="movie")
            add(database,4,103,"Youjo Senki OVA",1,1,kind="movie",episodeKind="ova")
            assertFalse(SeriesAnimeCoalescer.mergeVerified(database))
            assertEquals(12,count(database,1))
            assertEquals(7,count(database,2))
        }
    }

    @Test fun manualAssociationsRemainSeparateAndPreferredVerifiedAlbumSurvives() {
        db().use {database ->
            add(database,1,90001,"A Named Show",1,1,manual=true)
            add(database,2,90001,"A Named Show",2,2)
            assertFalse(SeriesAnimeCoalescer.mergeVerified(database))
        }
        db().use {database ->
            add(database,1,90001,"A Named Show",1,1)
            add(database,2,90001,"A Named Show",2,2)
            assertTrue(SeriesAnimeCoalescer.mergeVerified(database,preferredId=2))
            assertEquals(2,count(database,2))
            assertEquals(0,count(database,1))
        }
    }
}
