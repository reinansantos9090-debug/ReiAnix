package com.reiflix.reiflix_local.data.sqlite

import android.content.ContentValues
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray

/**
 * A season with file-based episode titles may be scanned into several local
 * anime rows before AniList identification. Once the SAME authoritative AniList
 * media ID is confirmed for the rows, they are one series, not multiple posters.
 *
 * Never infer identity from episode titles, filenames, the artwork URL, or a
 * fuzzy similarity. Manual AniList ownership/manual metadata stays untouched.
 */
internal object SeriesAnimeCoalescer {
    private data class Row(
        val id: Long,
        val remoteId: Long,
        val kind: String,
        val source: String,
        val status: String,
        val manualFields: String,
        val manualMatch: Boolean,
    )

    fun mergeVerified(db: SQLiteDatabase, preferredId: Long? = null): Boolean {
        val rows=db.rawQuery(
            """SELECT id,anilist_id,COALESCE(media_kind,'series'),
                COALESCE(metadata_source,''),COALESCE(metadata_status,''),
                COALESCE(metadata_manual_fields,'[]'),COALESCE(anilist_match_manual,0)
               FROM anime WHERE anilist_id IS NOT NULL AND anilist_id>0 ORDER BY id""",
            null,
        ).use { cursor ->
            buildList {
                while(cursor.moveToNext()) add(Row(
                    cursor.getLong(0),cursor.getLong(1),cursor.getString(2).lowercase(),
                    cursor.getString(3).lowercase(),cursor.getString(4).lowercase(),
                    cursor.getString(5),cursor.getInt(6)!=0,
                ))
            }
        }
        var changed=false
        for(group in rows.groupBy {it.remoteId}.values.filter {it.size>1}) {
            if(group.any {
                it.kind !in setOf("", "series", "tv") ||
                    it.manualMatch || it.source=="manual" || it.status=="manual" ||
                    it.manualFields.trim() !in setOf("", "[]")
            }) continue
            // Downloads still in the active internal queue reference their own
            // anime ID. Avoid invalidating that identity during playback.
            val hasLiveDownload = group.any { row ->
                db.rawQuery(
                    "SELECT 1 FROM episodes WHERE anime_id=? AND media_identity LIKE 'download:%' LIMIT 1",
                    arrayOf(row.id.toString()),
                ).use {it.moveToFirst()}
            }
            if(hasLiveDownload) continue
            val keep=group.firstOrNull {it.id==preferredId}
                ?: group.maxWithOrNull(compareBy<Row> { countEpisodes(db,it.id) }.thenBy {-it.id})
                ?: continue
            for(other in group.filter {it.id!=keep.id}) {
                db.execSQL("SAVEPOINT merge_verified_series")
                try {
                    val a=animeValues(db,keep.id) ?: continue
                    val b=animeValues(db,other.id) ?: continue
                    val selected=ContentValues()
                    for(field in listOf(
                        "cover_url","cover_cache","banner_url","description",
                        "description_original","genres","studio","romaji","english","native"
                    )) {
                        if(a.getAsString(field).isNullOrBlank() && !b.getAsString(field).isNullOrBlank())
                            selected.put(field,b.getAsString(field))
                    }
                    for(field in listOf("favorite","is_pinned"))
                        selected.put(field,maxOf(a.getAsInteger(field) ?: 0,b.getAsInteger(field) ?: 0))
                    fun tags(raw:String?):List<String> = runCatching {
                        val array=JSONArray(raw?.ifBlank {"[]"} ?: "[]")
                        (0 until array.length()).map {array.getString(it)}
                    }.getOrDefault(emptyList())
                    selected.put("user_tags",JSONArray(
                        (tags(a.getAsString("user_tags"))+tags(b.getAsString("user_tags"))).distinct()
                    ).toString())
                    val note= listOf(a.getAsString("personal_note"),b.getAsString("personal_note"))
                        .filterNotNull().map(String::trim).filter(String::isNotEmpty)
                        .distinct().joinToString("\n")
                    if(note.isNotBlank()) selected.put("personal_note",note)
                    if(selected.size()>0) db.update("anime",selected,"id=?",arrayOf(keep.id.toString()))
                    db.execSQL("UPDATE episodes SET anime_id=? WHERE anime_id=?",arrayOf(keep.id,other.id))
                    db.execSQL(
                        "INSERT OR IGNORE INTO anime_genres(anime_id,genre_id,source,created_at,updated_at) " +
                        "SELECT ?,genre_id,source,created_at,updated_at FROM anime_genres WHERE anime_id=?",
                        arrayOf(keep.id,other.id),
                    )
                    db.execSQL("UPDATE OR IGNORE artwork SET entity_id=? WHERE entity_type='anime' AND entity_id=?",
                        arrayOf(keep.id.toString(),other.id.toString()))
                    db.delete("artwork","entity_type='anime' AND entity_id=?",arrayOf(other.id.toString()))
                    db.delete("anime_genres","anime_id=?",arrayOf(other.id.toString()))
                    db.delete("anime","id=?",arrayOf(other.id.toString()))
                    changed=true
                } catch(_:Exception) {
                    // A foreign-key constraint or protected row must never cause
                    // an unrelated library scan or AniList update to fail.
                    db.execSQL("ROLLBACK TO merge_verified_series")
                } finally {db.execSQL("RELEASE merge_verified_series")}
            }
        }
        return changed
    }

    private fun countEpisodes(db:SQLiteDatabase,id:Long):Long =
        db.rawQuery("SELECT COUNT(*) FROM episodes WHERE anime_id=?",arrayOf(id.toString())).use {
            if(it.moveToFirst())it.getLong(0) else 0L
        }

    private fun animeValues(db:SQLiteDatabase,id:Long):ContentValues? =
        db.rawQuery("SELECT * FROM anime WHERE id=?",arrayOf(id.toString())).use {
            if(!it.moveToFirst()) null else ContentValues().also {values ->
                DatabaseUtils.cursorRowToContentValues(it,values)
            }
        }
}
