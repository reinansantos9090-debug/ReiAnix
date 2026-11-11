package com.reiflix.reiflix_local.downloads

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Proven URI aliases of an app-owned output. A title or filename alone never proves a duplicate. */
internal object DownloadCatalogBridge {
    fun fileKeys(context: Context,value: String): Set<String> = buildSet {
        val uri=Uri.parse(value)
        add("uri:$value")
        if(uri.authority==context.packageName+".downloads")return@buildSet
        fun mediaKey(u: Uri) {
            if(u.authority=="media" && u.pathSegments.lastOrNull()?.toLongOrNull()!=null) {
                val volume=u.pathSegments.firstOrNull().orEmpty().let {if(it=="external_primary")"external" else it}
                add("media:$volume:${u.lastPathSegment}")
            }
        }
        mediaKey(uri)
        if(Build.VERSION.SDK_INT>=29 && uri.scheme=="content")runCatching {MediaStore.getMediaUri(context,uri)?.let(::mediaKey)}
        if(uri.authority=="com.android.externalstorage.documents")runCatching {
            add("document:"+uri.authority+":"+DocumentsContract.getDocumentId(uri))
        }
        runCatching {
            if(uri.scheme=="content")context.contentResolver.openFileDescriptor(uri,"r")?.use {fd ->
                val stat=Os.fstat(fd.fileDescriptor)
                if(OsConstants.S_ISREG(stat.st_mode) && stat.st_ino>0)add("inode:${stat.st_dev}:${stat.st_ino}:${stat.st_size}:${stat.st_mtime}")
            } else if(uri.scheme=="file" || uri.scheme==null) {
                val f=File(if(uri.scheme=="file")uri.path!! else value)
                val stat=Os.stat(f.path)
                if(OsConstants.S_ISREG(stat.st_mode) && stat.st_ino>0)add("inode:${stat.st_dev}:${stat.st_ino}:${stat.st_size}:${stat.st_mtime}")
            }
        }
    }
    fun outputs(database: ReiAnixSqliteDatabase): List<DownloadItem> = database.read {db ->
        val exists=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='native_downloads'",null).use {it.moveToFirst()}
        if(!exists)emptyList() else db.rawQuery("SELECT data FROM native_downloads",null).use {c ->buildList {
            while(c.moveToNext())runCatching {DownloadItem(JSONObject(c.getString(0)))}.getOrNull()?.takeIf {it.output.isNotBlank() && (it.json.optBoolean("excludedBanner") || (it.episodeId>0 && it.state in setOf("SAVING","COMPLETE")))}?.let {add(it)}
        }}
    }
    fun aliases(database: ReiAnixSqliteDatabase,locations: Collection<String>): Map<String,String> {
        val outputs=outputs(database)
        if(outputs.isEmpty())return emptyMap()
        val known=outputs.associate {item ->
            val live=fileKeys(database.appContext,item.output)
            val arr=item.json.optJSONArray("outputKeys")
            val saved=if(arr==null)emptySet() else (0 until arr.length()).map {arr.optString(it)}.toSet()
            val savedInodes=saved.filter {it.startsWith("inode:")}.toSet()
            val freshSaving=item.state=="SAVING" && System.currentTimeMillis()-item.json.optLong("outputKeysAt") in 0..300000
            val keys=when {
                freshSaving -> live+saved
                savedInodes.isNotEmpty() && live.none {it in savedInodes} -> emptySet()
                else -> live
            }
            item.id to keys
        }
        return locations.distinct().mapNotNull {location ->
            val keys=fileKeys(database.appContext,location)
            val matches=known.filterValues {it.intersect(keys).isNotEmpty()}.keys
            matches.singleOrNull()?.let {location to it}
        }.toMap()
    }
    /** Called inside the scanner's canonical transaction, after scope validation. */
    fun episodeFor(db: SQLiteDatabase,downloadId: String): Pair<Long,String>? = db.rawQuery("SELECT data FROM native_downloads WHERE id=?",arrayOf(downloadId)).use { c ->
        if(!c.moveToFirst())return@use null
        val item=DownloadItem(JSONObject(c.getString(0)))
        if(item.json.optBoolean("excludedBanner"))return@use 0L to "REJECTED"
        val owns=db.rawQuery("SELECT 1 FROM episodes WHERE id=? AND (media_identity=? OR path=? OR path=?)",arrayOf(item.episodeId.toString(),"download:$downloadId",item.virtualUri.toString(),item.output)).use {it.moveToFirst()}
        if(owns) item.episodeId to item.state else null
    }
    /** Repair prior app-created duplicates, only where a physical-file alias proves equivalence. */
    fun reconcile(database: ReiAnixSqliteDatabase): Boolean {
        val jobs=outputs(database).filter {!it.json.optBoolean("excludedBanner")}
        if(jobs.isEmpty())return false
        val episodes=database.read {db ->db.rawQuery("SELECT id,path FROM episodes",null).use {c ->buildList {while(c.moveToNext())add(c.getLong(0) to c.getString(1))}}}
        val aliases=aliases(database,episodes.map {it.second})
        var changed=false
        database.transaction { db ->
            jobs.forEach { job ->
                val owned=episodeFor(db,job.id) ?: return@forEach
                val duplicates=episodes.filter {it.first!=owned.first && aliases[it.second]==job.id}
                duplicates.forEach { (duplicateId,_) -> if(mergeEpisode(db,owned.first,duplicateId,job))changed=true }
            }
        }
        return changed
    }
    private fun mergeEpisode(db: SQLiteDatabase,keep: Long,duplicate: Long,job: DownloadItem): Boolean {
        fun row(id: Long)=db.rawQuery("SELECT * FROM episodes WHERE id=?",arrayOf(id.toString())).use {c ->if(!c.moveToFirst())null else ContentValues().also {values ->android.database.DatabaseUtils.cursorRowToContentValues(c,values)}}
        val a=row(keep) ?: return false;val b=row(duplicate) ?: return false
        val owner=a.getAsLong("anime_id");val redundant=b.getAsLong("anime_id")
        val media=b.getAsString("media_identity").orEmpty()
        if(media.startsWith("download:") && media!="download:${job.id}")return false
        val lastA=a.getAsDouble("last_played_at") ?: 0.0
        val lastB=b.getAsDouble("last_played_at") ?: 0.0
        val progressA=a.getAsDouble("progress") ?: 0.0
        val progressB=b.getAsDouble("progress") ?: 0.0
        val resume=when {lastA==lastB->maxOf(progressA,progressB);lastB>lastA->progressB;else->progressA}
        val patch=ContentValues().apply {
            put("progress",resume)
            put("last_played_at",maxOf(a.getAsDouble("last_played_at") ?: 0.0,b.getAsDouble("last_played_at") ?: 0.0))
            put("duration",maxOf(a.getAsDouble("duration") ?: 0.0,b.getAsDouble("duration") ?: 0.0))
            put("watched",maxOf(a.getAsInteger("watched") ?: 0,b.getAsInteger("watched") ?: 0))
            put("missing",0);put("availability_state","available");put("media_identity","download:${job.id}")
        }
        if((b.getAsInteger("manual_override") ?: 0)>0) listOf("number","season","absolute_number","episode_type","episode_title","identification_source","identification_confidence","manual_override").forEach {key ->copyValue(b,patch,key)}
        // Child rows survive; equivalent unique observations/artwork already present on keep win.
        db.execSQL("UPDATE OR IGNORE episode_observations SET episode_id=? WHERE episode_id=?",arrayOf(keep,duplicate))
        db.execSQL("UPDATE OR IGNORE artwork SET entity_id=? WHERE entity_type='episode' AND entity_id=?",arrayOf(keep.toString(),duplicate.toString()))
        db.delete("artwork","entity_type='episode' AND entity_id=?",arrayOf(duplicate.toString()))
        db.delete("episodes","id=?",arrayOf(duplicate.toString()))
        val occupied=db.rawQuery("SELECT 1 FROM episodes WHERE path=? AND id!=?",arrayOf(job.output,keep.toString())).use {it.moveToFirst()}
        if(!occupied)patch.put("path",job.output)
        db.update("episodes",patch,"id=?",arrayOf(keep.toString()))
        if(owner!=redundant)mergeEmptyAnime(db,owner,redundant)
        return true
    }
    private fun copyValue(from: ContentValues,to: ContentValues,key: String) { when(val v=from.get(key)) {
        null->to.putNull(key);is String->to.put(key,v);is Long->to.put(key,v);is Int->to.put(key,v);is Double->to.put(key,v);is Float->to.put(key,v)
    } }
    private fun mergeEmptyAnime(db: SQLiteDatabase,keep: Long,other: Long) {
        if(db.rawQuery("SELECT 1 FROM episodes WHERE anime_id=? LIMIT 1",arrayOf(other.toString())).use {it.moveToFirst()})return
        val records=db.rawQuery("SELECT * FROM anime WHERE id IN (?,?)",arrayOf(keep.toString(),other.toString())).use {c ->buildMap<Long,ContentValues> {while(c.moveToNext()){val v=ContentValues();android.database.DatabaseUtils.cursorRowToContentValues(c,v);put(v.getAsLong("id"),v)}}}
        val a=records[keep] ?: return;val b=records[other] ?: return
        val remoteA=a.getAsLong("anilist_id");val remoteB=b.getAsLong("anilist_id")
        if(remoteA!=null && remoteB!=null && remoteA!=remoteB)return
        val patch=ContentValues()
        listOf("favorite","is_pinned").forEach {patch.put(it,maxOf(a.getAsInteger(it) ?: 0,b.getAsInteger(it) ?: 0))}
        fun strings(raw: String?): List<String> = runCatching {val arr=JSONArray(raw ?: "[]");(0 until arr.length()).map {arr.getString(it)}}.getOrDefault(emptyList())
        patch.put("user_tags",JSONArray((strings(a.getAsString("user_tags"))+strings(b.getAsString("user_tags"))).distinct()).toString())
        val note=listOfNotNull(a.getAsString("personal_note"),b.getAsString("personal_note")).filter {it.isNotBlank()}.distinct().joinToString("\n")
        if(note.isNotBlank())patch.put("personal_note",note)
        if((b.getAsInteger("anilist_match_manual") ?: 0)>0 || b.getAsString("metadata_source")=="manual") {
            b.keySet().filter {it !in setOf("id","lookup_title","favorite","is_pinned","user_tags","personal_note","added_at")}.forEach {copyValue(b,patch,it)}
        }
        db.update("anime",patch,"id=?",arrayOf(keep.toString()))
        db.execSQL("INSERT OR IGNORE INTO anime_genres(anime_id,genre_id,source,created_at,updated_at) SELECT ?,genre_id,source,created_at,updated_at FROM anime_genres WHERE anime_id=?",arrayOf(keep,other))
        db.execSQL("UPDATE OR IGNORE artwork SET entity_id=? WHERE entity_type='anime' AND entity_id=?",arrayOf(keep.toString(),other.toString()))
        db.delete("artwork","entity_type='anime' AND entity_id=?",arrayOf(other.toString()))
        db.delete("anime","id=?",arrayOf(other.toString()))
    }
}
