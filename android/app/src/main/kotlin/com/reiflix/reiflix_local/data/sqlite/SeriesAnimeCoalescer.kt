package com.reiflix.reiflix_local.data.sqlite

import android.content.ContentValues
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Coalesces actual local video rows, not thumbnails or catalogue search results.
 *
 * Evidence (strongest first):
 *  - same AniList ID, with no manually owned records;
 *  - exact local/romaji/English/alias title, with no conflicting AniList IDs;
 *  - an explicit season ordinal with the exact same series base title, even
 *    when AniList assigns a DIFFERENT entry to season II.
 *
 * Physical episode IDs, media URIs, progress, season card thumbnails and
 * observation rows are preserved. Movies, side stories and merely similar
 * names are never candidates. All operations happen in one SQLite transaction.
 */
internal object SeriesAnimeCoalescer {
    private const val TAG="ReiAnixSeriesMerge"
    private data class Album(
        val id: Long,
        val remoteId: Long?,
        val kind: String,
        val source: String,
        val status: String,
        val manualFields: String,
        val manualMatch: Boolean,
        val forms: Set<SeriesTitleIdentity.TitleForm>,
        val episodeCount: Int,
        val regularCount: Int,
        val seasons: Set<Int>,
    ) {
        val protected: Boolean get() =
            kind !in setOf("", "series", "tv") ||
            manualMatch || source=="manual" || status=="manual" ||
            manualFields.trim() !in setOf("", "[]")
        val exactKeys: Set<String> get() = forms.map { it.key }.toSet()
    }
    private data class Candidate(val keep: Album,val donor: Album,val donorSeason: Int?)

    fun mergeVerified(db: SQLiteDatabase, preferredId: Long? = null): Boolean {
        val busyAnimeIds=unfinishedDownloadAnimeIds(db)
        var changed=false
        // Refresh after each successful merge: a group of three seasons must
        // converge and must not reference rows deleted in the previous pass.
        repeat(64) {
            val rows=albums(db)
                .filter { it.episodeCount>0 && !it.protected && it.id !in busyAnimeIds }
            var merged=false
            outer@ for (i in rows.indices) {
                for (j in i+1 until rows.size) {
                    val candidate=resolve(rows[i],rows[j],preferredId) ?: continue
                    if (mergeOne(db,candidate)) {
                        changed=true
                        merged=true
                        break@outer
                    }
                }
            }
            if(!merged) return changed
        }
        return changed
    }

    private fun resolve(a: Album,b: Album,preferredId:Long?):Candidate? {
        if(a.regularCount==0 || b.regularCount==0) return null
        val sameRemote=a.remoteId!=null && a.remoteId==b.remoteId

        // The suffix must explicitly express a season ("Youjo Senki II",
        // "Youjo Senki Season 2"). Do not infer sequels from vague similarity
        // or from a broadcaster's calendar season ("Autumn", "Winter").
        val seasonPair=seasonEvidence(a,b)
        if(seasonPair!=null) return seasonPair
        val sameTitle=a.exactKeys.intersect(b.exactKeys).isNotEmpty()
        if(!sameRemote && !sameTitle) return null
        if(!sameRemote && a.remoteId!=null && b.remoteId!=null) return null
        val keep=when {
            a.id==preferredId -> a
            b.id==preferredId -> b
            a.remoteId!=null && b.remoteId==null -> a
            b.remoteId!=null && a.remoteId==null -> b
            a.episodeCount>b.episodeCount -> a
            b.episodeCount>a.episodeCount -> b
            else -> if(a.id<b.id)a else b
        }
        return Candidate(keep,if(keep.id==a.id)b else a,null)
    }

    private fun seasonEvidence(a:Album,b:Album):Candidate? {
        // Distinct AniList IDs are normal for a sequel, but they may NOT
        // justify joining anything without an explicit matching base title.
        val shared=a.forms.map {it.key}.intersect(b.forms.map {it.key}.toSet())
        if(shared.isEmpty()) return null
        val choices=buildList {
            for(key in shared) for(x in a.forms.filter{it.key==key})
                for(y in b.forms.filter{it.key==key}) {
                    if(!x.tagged && !y.tagged) continue
                    if(x.season==y.season) continue
                    add(x.season to y.season)
                }
        }
        if(choices.isEmpty()) return null
        val distinct=choices.distinct()
        // Conflicting English/Japanese aliases must not move an episode into an
        // arbitrary season.
        if(distinct.size!=1) return null
        val (left,right)=distinct.single()
        val keep=if(left<right)a else b
        val donor=if(keep.id==a.id)b else a
        val donorSeason=if(keep.id==a.id)right else left
        val keeperSeason=if(keep.id==a.id)left else right
        if(keeperSeason !in 1..20 || donorSeason !in 2..20) return null
        // Explicit already-indexed season values must not be overwritten.
        if(donor.seasons.any {it !in setOf(0,1,donorSeason)}) return null
        if(keep.seasons.any {it !in setOf(0,1,keeperSeason)}) return null
        return Candidate(keep,donor,donorSeason)
    }

    private fun albums(db:SQLiteDatabase):List<Album> {
        val counts=mutableMapOf<Long,MutableList<Pair<Int,String>>>()
        db.rawQuery(
            "SELECT anime_id,COALESCE(season,0),COALESCE(episode_type,'') FROM episodes",null
        ).use {c ->
            while(c.moveToNext()) counts.getOrPut(c.getLong(0)){mutableListOf()}
                .add(c.getInt(1) to c.getString(2).lowercase())
        }
        return db.rawQuery(
            """SELECT id,anilist_id,COALESCE(media_kind,'series'),
               COALESCE(metadata_source,''),COALESCE(metadata_status,''),
               COALESCE(metadata_manual_fields,'[]'),COALESCE(anilist_match_manual,0),
               title,lookup_title,romaji,english,native,aliases
               FROM anime ORDER BY id""",null,
        ).use { c ->
            buildList {
                while(c.moveToNext()) {
                    val episodeData=counts[c.getLong(0)].orEmpty()
                    val forms=linkedSetOf<SeriesTitleIdentity.TitleForm>()
                    for(n in 7..11) SeriesTitleIdentity.form(c.getString(n))?.let(forms::add)
                    val aliases=c.getString(12)
                    runCatching {
                        val array=JSONArray(aliases?.ifBlank{"[]"} ?: "[]")
                        for(n in 0 until array.length()) SeriesTitleIdentity.form(array.optString(n))
                            ?.let(forms::add)
                    }
                    add(Album(
                        id=c.getLong(0),
                        remoteId=if(c.isNull(1))null else c.getLong(1).takeIf{it>0},
                        kind=c.getString(2).lowercase(),
                        source=c.getString(3).lowercase(),status=c.getString(4).lowercase(),
                        manualFields=c.getString(5),manualMatch=c.getInt(6)!=0,
                        forms=forms,
                        episodeCount=episodeData.size,
                        regularCount=episodeData.count{(_,kind) ->
                            kind !in setOf("movie","film","special","ova","oad","ona","extra")
                        },
                        seasons=episodeData.map{it.first}.toSet(),
                    ))
                }
            }
        }
    }

    private fun unfinishedDownloadAnimeIds(db:SQLiteDatabase):Set<Long> = runCatching {
        db.rawQuery("SELECT data FROM native_downloads",null).use {c ->
            buildSet {
                while(c.moveToNext()) {
                    val json=runCatching {JSONObject(c.getString(0))}.getOrNull() ?: continue
                    if(json.optString("state") in setOf("COMPLETE","CANCELLED","CORRUPT"))continue
                    json.optLong("animeId").takeIf{it>0}?.let(::add)
                }
            }
        }
    }.getOrDefault(emptySet())

    private fun mergeOne(db:SQLiteDatabase,candidate:Candidate):Boolean {
        val (keep,donor,season)=candidate
        db.execSQL("SAVEPOINT intelligent_series_merge")
        try {
            val a=animeValues(db,keep.id) ?: return false
            val b=animeValues(db,donor.id) ?: return false

            // An original season-2 album often has episode rows labelled season 1
            // because the parser had only the file's local episode number.
            if(season!=null) db.execSQL(
                "UPDATE episodes SET season=? WHERE anime_id=? AND COALESCE(season,0) IN (0,1)",
                arrayOf(season,donor.id),
            )
            val keepSeason=(keep.forms.firstOrNull{it.tagged}?.season ?: 1)
            if(season!=null && keepSeason>1) db.execSQL(
                "UPDATE episodes SET season=? WHERE anime_id=? AND COALESCE(season,0) IN (0,1)",
                arrayOf(keepSeason,keep.id),
            )

            val selected=ContentValues()
            for(field in listOf(
                "cover_url","cover_cache","banner_url","description",
                "description_original","genres","studio","romaji","english","native"
            )) if(a.getAsString(field).isNullOrBlank() && !b.getAsString(field).isNullOrBlank())
                selected.put(field,b.getAsString(field))
            for(field in listOf("favorite","is_pinned"))
                selected.put(field,maxOf(a.getAsInteger(field) ?: 0,b.getAsInteger(field) ?: 0))
            fun strings(raw:String?):List<String> = runCatching {
                val array=JSONArray(raw?.ifBlank{"[]"} ?: "[]")
                (0 until array.length()).mapNotNull {array.optString(it).takeIf(String::isNotBlank)}
            }.getOrDefault(emptyList())
            val aliases=(strings(a.getAsString("aliases")) + strings(b.getAsString("aliases")) +
                listOfNotNull(b.getAsString("title"),b.getAsString("romaji"),b.getAsString("english")))
                .distinct()
            if(aliases.isNotEmpty())selected.put("aliases",JSONArray(aliases).toString())
            selected.put("user_tags",JSONArray(
                (strings(a.getAsString("user_tags"))+strings(b.getAsString("user_tags"))).distinct()
            ).toString())
            val notes=listOf(a.getAsString("personal_note"),b.getAsString("personal_note"))
                .filterNotNull().map(String::trim).filter(String::isNotEmpty)
                .distinct().joinToString("\n")
            if(notes.isNotBlank()) selected.put("personal_note",notes)

            if(selected.size()>0)db.update("anime",selected,"id=?",arrayOf(keep.id.toString()))
            // Episode identity, thumbnail entity ID, progress and file URI are
            // unchanged: only the album FK (and proven season) is updated.
            db.execSQL("UPDATE episodes SET anime_id=? WHERE anime_id=?",
                arrayOf(keep.id,donor.id))
            db.execSQL(
                "INSERT OR IGNORE INTO anime_genres(anime_id,genre_id,source,created_at,updated_at) " +
                "SELECT ?,genre_id,source,created_at,updated_at FROM anime_genres WHERE anime_id=?",
                arrayOf(keep.id,donor.id),
            )
            db.execSQL("UPDATE OR IGNORE artwork SET entity_id=? WHERE entity_type='anime' AND entity_id=?",
                arrayOf(keep.id.toString(),donor.id.toString()))
            db.delete("artwork","entity_type='anime' AND entity_id=?",arrayOf(donor.id.toString()))
            db.delete("anime_genres","anime_id=?",arrayOf(donor.id.toString()))

            // Completed download records still refer to the old album ID.
            // Updating their parent keeps future retry/reconciliation safe; jobs
            // in progress were excluded above.
            runCatching {
                db.rawQuery("SELECT id,data FROM native_downloads",null).use {c ->
                    while(c.moveToNext()) {
                        val json=runCatching{JSONObject(c.getString(1))}.getOrNull()?:continue
                        if(json.optLong("animeId")!=donor.id)continue
                        json.put("animeId",keep.id)
                        db.update("native_downloads",ContentValues().apply {
                            put("data",json.toString())
                        },"id=?",arrayOf(c.getString(0)))
                    }
                }
            }
            // Preserve different AniList season IDs and the removed album's
            // editorial metadata for future re-identification/recovery.
            runCatching {
                db.insertWithOnConflict("preferences",null,ContentValues().apply {
                    put("key","reianix.series.merged.${keep.id}.${donor.id}")
                    put("value",JSONObject().apply {
                        put("fromId",donor.id);put("toId",keep.id)
                        put("season",season ?: JSONObject.NULL)
                        put("anilistId",donor.remoteId ?: JSONObject.NULL)
                        put("title",b.getAsString("title"))
                        put("coverUrl",b.getAsString("cover_url"))
                    }.toString())
                    put("updated_at",System.currentTimeMillis()/1000.0)
                },SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.delete("anime","id=?",arrayOf(donor.id.toString()))
            db.execSQL("RELEASE intelligent_series_merge")
            Log.i(TAG,"Grouped local series album=${keep.id} from=${donor.id} season=${season ?: "same"}")
            return true
        } catch(error:Exception) {
            db.execSQL("ROLLBACK TO intelligent_series_merge")
            db.execSQL("RELEASE intelligent_series_merge")
            Log.w(TAG,"Protected series merge refused keep=${keep.id} donor=${donor.id}",error)
            return false
        }
    }

    private fun animeValues(db:SQLiteDatabase,id:Long):ContentValues? =
        db.rawQuery("SELECT * FROM anime WHERE id=?",arrayOf(id.toString())).use {
            if(!it.moveToFirst())null else ContentValues().also {values ->
                DatabaseUtils.cursorRowToContentValues(it,values)
            }
        }
}
