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
    private val nonRegularTypes=setOf("movie","film","special","ova","oad","ona","extra")
    private data class EpisodeFact(val season:Int,val kind:String,val number:Double?)
    private data class Album(
        val id: Long,
        val remoteId: Long?,
        val kind: String,
        val source: String,
        val status: String,
        val manualFields: String,
        val manualMatch: Boolean,
        val forms: Set<SeriesTitleIdentity.TitleForm>,
        val primaryTitleKey: String?,
        val expectedEpisodes: Int?,
        val episodeNumbers: Map<Int,Set<Int>>,
        val primarySeason: Int,
        val primaryTagged: Boolean,
        val episodeCount: Int,
        val regularCount: Int,
        val seasons: Set<Int>,
    ) {
        val protected: Boolean get() = kind !in setOf("", "series", "tv")
        // A custom cover, description or note must not veto merging episodes.
        // Preserve the user's edits by favouring the curated row as survivor.
        val curated: Boolean get() = manualMatch || source=="manual" ||
            status=="manual" || manualFields.trim() !in setOf("", "[]")
        val identityManual: Boolean get() = manualMatch ||
            source=="manual" || status=="manual"
        val exactKeys: Set<String> get() = forms.map { it.key }.toSet()
    }
    private data class Candidate(val keep: Album,val donor: Album,val donorSeason: Int?)

    fun mergeVerified(db: SQLiteDatabase, preferredId: Long? = null): Boolean {
        val busyAnimeIds=unfinishedDownloadAnimeIds(db)
        var changed=false
        // Refresh after each successful merge: a group of three seasons must
        // converge and must not reference rows deleted in the previous pass.
        val maxMerges=albums(db).size
        repeat(maxMerges) {
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

    /**
     * AniList metadata updates may identify the sequel currently being moved
     * into an existing first-season record. Return the surviving album ID so
     * that the enclosing metadata transaction does not roll back the merge.
     */
    fun survivingAlbumId(db:SQLiteDatabase,originalId:Long):Long {
        var id=originalId
        repeat(16) {
            val exists=db.rawQuery("SELECT 1 FROM anime WHERE id=?",
                arrayOf(id.toString())).use {it.moveToFirst()}
            if(exists) return id
            val prior=db.rawQuery(
                "SELECT value FROM preferences WHERE key LIKE ? ORDER BY updated_at DESC LIMIT 1",
                arrayOf("reianix.series.merged.%.$id")
            ).use { if(it.moveToFirst())it.getString(0) else null }
            val next=prior?.let {JSONObject(it).optLong("toId")} ?: 0L
            if(next<=0 || next==id)return originalId
            id=next
        }
        return id
    }

    /**
     * A split 1..6 / 7..12 series may have TWO different AniList IDs when the
     * matching algorithm associated one half incorrectly. A common display
     * title alone must never win over conflicting IDs. Require a numbered,
     * non-overlapping continuation AND the same known episode total.
     *
     * This is a general rule for any title, not an anime-specific exception.
     */
    private fun verifiedContinuation(a: Album,b: Album):Boolean {
        if(a.primaryTagged || b.primaryTagged) return false
        if(a.primaryTitleKey.isNullOrBlank() || a.primaryTitleKey!=b.primaryTitleKey) return false
        if(a.identityManual || b.identityManual) return false
        val expected=a.expectedEpisodes ?: return false
        if(expected<4 || expected>2000 || b.expectedEpisodes!=expected) return false
        if(a.episodeNumbers.size!=1 || b.episodeNumbers.size!=1) return false
        val (aSeason,aNumbers)=a.episodeNumbers.entries.single()
        val (bSeason,bNumbers)=b.episodeNumbers.entries.single()
        if(aSeason!=bSeason || aSeason !in 1..20) return false
        if(aNumbers.size<2 || bNumbers.size<2 ||
            aNumbers.any {it !in 1..expected} || bNumbers.any {it !in 1..expected}) return false
        if(aNumbers.intersect(bNumbers).isNotEmpty()) return false
        val before=if(aNumbers.min()<bNumbers.min())aNumbers else bNumbers
        val after=if(before===aNumbers)bNumbers else aNumbers
        return before.min()==1 && before.max()<after.min() &&
            before.size+after.size<=expected
    }

    private fun resolve(a: Album,b: Album,preferredId:Long?):Candidate? {
        if(a.regularCount==0 || b.regularCount==0) return null
        val sameRemote=a.remoteId!=null && a.remoteId==b.remoteId
        val seasonPair=seasonEvidence(a,b)
        if(seasonPair!=null && !a.identityManual && !b.identityManual) return seasonPair
        val sameTitle=a.exactKeys.intersect(b.exactKeys).isNotEmpty()
        if(!sameRemote && !sameTitle) return null
        val continuation=verifiedContinuation(a,b)
        if(!sameRemote && a.remoteId!=null && b.remoteId!=null && !continuation) return null
        // Manually selected AniList links with contradictory identity are never
        // rewritten by a background organizer, even if titles look alike.
        if(!sameRemote && (a.identityManual || b.identityManual)) return null
        val keep=when {
            // For disjoint numbered halves, retain the original first episode's
            // album (and its cover), not whichever half was imported last.
            continuation -> {
                val aFirst=a.episodeNumbers.values.single().min()
                val bFirst=b.episodeNumbers.values.single().min()
                if(aFirst<=bFirst)a else b
            }
            a.curated && !b.curated -> a
            b.curated && !a.curated -> b
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
        val left=a.primarySeason
        val right=b.primarySeason
        if(!a.primaryTagged && !b.primaryTagged) return null
        if(left==right) return null
        val keep=if(left<right)a else b
        val donor=if(keep.id==a.id)b else a
        val donorSeason=if(keep.id==a.id)right else left
        val keeperSeason=if(keep.id==a.id)left else right
        if(keeperSeason !in 1..20 || donorSeason !in 2..20) return null
        // Explicit already-indexed season values must not be overwritten.
        if(donor.seasons.any {it !in setOf(0,1,donorSeason)}) return null
        // The survivor may ALREADY contain seasons 1+2. Their presence is
        // positive evidence of a multi-season library, not a conflict when
        // another confirmed season 3, 4, ... is being appended.
        if(keep.seasons.any {it !in 0..20}) return null
        return Candidate(keep,donor,donorSeason)
    }

    private fun albums(db:SQLiteDatabase):List<Album> {
        val counts=mutableMapOf<Long,MutableList<EpisodeFact>>()
        db.rawQuery(
            "SELECT anime_id,COALESCE(season,0),COALESCE(episode_type,''),number FROM episodes",null
        ).use {c ->
            while(c.moveToNext()) counts.getOrPut(c.getLong(0)){mutableListOf()}
                .add(EpisodeFact(c.getInt(1),c.getString(2).lowercase(),
                    if(c.isNull(3))null else c.getDouble(3)))
        }
        return db.rawQuery(
            """SELECT id,anilist_id,COALESCE(media_kind,'series'),
               COALESCE(metadata_source,''),COALESCE(metadata_status,''),
               COALESCE(metadata_manual_fields,'[]'),COALESCE(anilist_match_manual,0),
               title,lookup_title,romaji,english,native,aliases,episodes_count
               FROM anime ORDER BY id""",null,
        ).use { c ->
            buildList {
                while(c.moveToNext()) {
                    val episodeData=counts[c.getLong(0)].orEmpty()
                    val forms=linkedSetOf<SeriesTitleIdentity.TitleForm>()
                    for(n in 7..11) SeriesTitleIdentity.form(c.getString(n))?.let(forms::add)
                    val primary=SeriesTitleIdentity.form(c.getString(7))
                        ?: SeriesTitleIdentity.form(c.getString(8))
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
                        primaryTitleKey=primary?.key,
                        expectedEpisodes=if(c.isNull(13))null else c.getInt(13).takeIf {it>0},
                        episodeNumbers=episodeData.asSequence()
                            .filter {it.kind !in nonRegularTypes}
                            .filter {it.number!=null && it.number.isFinite() &&
                                it.number>0 && it.number<=2000 &&
                                it.number==it.number.toInt().toDouble()}
                            .groupBy({it.season},{it.number!!.toInt()})
                            .mapValues {(_,values)->values.toSet()},
                        primarySeason=primary?.season ?: 1,
                        primaryTagged=primary?.tagged == true,
                        episodeCount=episodeData.size,
                        regularCount=episodeData.count{it.kind !in nonRegularTypes},
                        seasons=episodeData.map{it.season}.toSet(),
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
            val a=animeValues(db,keep.id) ?: error("Series album missing during merge")
            val b=animeValues(db,donor.id) ?: error("Source album missing during merge")

            // An original season-2 album often has episode rows labelled season 1
            // because the parser had only the file's local episode number.
            if(season!=null) db.execSQL(
                "UPDATE episodes SET season=? WHERE anime_id=? AND COALESCE(season,0) IN (0,1)",
                arrayOf(season,donor.id),
            )
            val keepSeason=keep.primarySeason
            if(season!=null && keepSeason>1) db.execSQL(
                "UPDATE episodes SET season=? WHERE anime_id=? AND COALESCE(season,0) IN (0,1)",
                arrayOf(keepSeason,keep.id),
            )

            val selected=ContentValues()
            val inheritedFields=if(season==null) listOf(
                "cover_url","cover_cache","banner_url","description",
                "description_original","genres","studio","romaji","english","native"
            ) else listOf("cover_url","cover_cache","banner_url")
            // A sequel's description and airing metadata belong to that specific
            // season, not the first-season overview. Retain them in provenance.
            for(field in inheritedFields)
                if(a.getAsString(field).isNullOrBlank() && !b.getAsString(field).isNullOrBlank())
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
            // Preserve ALL original editorial/season metadata, including
            // different AniList IDs, so regrouping is reversible and never
            // silently discards a sequel's synopsis or manual information.
            val originalMetadata=JSONObject()
            for(entry in b.valueSet()) originalMetadata.put(entry.key,entry.value)
            val provenance=JSONObject()
                .put("fromId",donor.id).put("toId",keep.id)
                .put("season",season ?: JSONObject.NULL)
                .put("anilistId",donor.remoteId ?: JSONObject.NULL)
                .put("originalMetadata",originalMetadata)
            val saved=db.insertWithOnConflict("preferences",null,ContentValues().apply {
                put("key","reianix.series.merged.${keep.id}.${donor.id}")
                put("value",provenance.toString())
                put("updated_at",System.currentTimeMillis()/1000.0)
            },SQLiteDatabase.CONFLICT_REPLACE)
            check(saved!=-1L) { "Failed to preserve original season metadata" }
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
