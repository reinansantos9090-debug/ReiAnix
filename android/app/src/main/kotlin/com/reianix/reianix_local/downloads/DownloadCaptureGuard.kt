package com.reianix.reianix_local.downloads

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.reianix.reianix_local.ReiAnixAppDependencies
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Only automatic browser captures are gated. Probe local bytes, never make another network request. */
internal object DownloadCaptureGuard {
    data class Facts(val duration: Double,val width: Int,val height: Int)
    class Rejected(message: String): IOException(message)
    private fun facts(context: Context,file: File?,uri: Uri?): Facts? {
        val retriever=runCatching {MediaMetadataRetriever()}.getOrNull() ?: return null
        return try {
            if(file!=null)retriever.setDataSource(file.path)
            else {require(uri?.scheme in setOf("file","content"));retriever.setDataSource(context,uri!!)}
            val duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000) ?: return null
            val width=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val height=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            if(duration>0 && width>0 && height>0)Facts(duration,width,height) else null
        } catch(_: Exception) {null} finally {runCatching {retriever.release()}}
    }
    private fun valid(facts: Facts)=facts.duration>=120 && facts.width.toDouble()/facts.height in 0.45..3.2
    fun probe(context: Context,store: DownloadStore,id: String,source: Uri,bytes: Long,complete: Boolean = false) {
        val item=store.get(id) ?: return
        if(!item.json.has("playerCapture") || item.json.optBoolean("captureApproved"))return
        val stage=item.json.optInt("captureProbeStage")
        val thresholds=longArrayOf(256L*1024,2L*1024*1024,16L*1024*1024,128L*1024*1024)
        if(!complete && (stage>=thresholds.size || bytes<thresholds[stage]))return
        store.mutate(id){it.put("captureProbeStage",stage+1)}
        val actual=if(source.scheme=="file") facts(context,File(source.path.orEmpty()),null) else facts(context,null,source)
        val expected=item.json.getJSONObject("playerCapture").optDouble("duration")
        val matches=actual!=null && valid(actual) && PlayerMediaCandidate.durationMatches(expected,actual.duration)
        if(actual!=null && (actual.width.toDouble()/actual.height !in 0.45..3.2 || (complete && !matches)))throw Rejected("O recurso capturado não corresponde a um episódio: vídeo curto ou formato de banner. Use o download do próprio site.")
        if(actual==null && complete)throw Rejected("Não foi possível confirmar o vídeo capturado. Use o link do download do próprio site.")
        if(matches)approve(context,store,id)
    }
    fun approveHls(context: Context,store: DownloadStore,id: String,duration: Double) {
        val item=store.get(id) ?: return
        val capture=item.json.optJSONObject("playerCapture") ?: return
        val expected=capture.optDouble("duration")
        if(duration<120 || !PlayerMediaCandidate.durationMatches(expected,duration))throw Rejected("A playlist não corresponde à duração do episódio no player.")
        approve(context,store,id)
    }
    private fun approve(context: Context,store: DownloadStore,id: String) {
        val item=store.get(id) ?: return
        if(item.state !in DownloadState.pending && item.state!="PAUSED")return
        store.mutate(id){it.put("captureApproved",true)}
        store.registerMedia(id)
        store.get(id)?.takeIf {it.json.optBoolean("identityKnown")}?.let {DownloadMetadata.offer(context,it.animeId)}
    }
    /** Narrow repair of the reported legacy banner: keep its physical file, never discard watched/manual entries. */
    fun removeLegacyBanners(context: Context,store: DownloadStore): Boolean {
        val database=ReiAnixAppDependencies.from(context).sqliteDatabase
        var changed=false
        store.all().filter {it.state=="COMPLETE" && !it.json.optBoolean("excludedBanner")}.forEach { item ->
            val dimensions=Regex("(?i)(\\d{2,4})x(\\d{2,4})").find(item.name) ?: return@forEach
            val w=dimensions.groupValues[1].toInt();val h=dimensions.groupValues[2].toInt()
            if(h<=0 || w.toDouble()/h<=4 || item.output.isBlank())return@forEach
            val actual=facts(context,null,Uri.parse(item.output)) ?: return@forEach
            if(actual.duration>15 || actual.height>200 || actual.width.toDouble()/actual.height<=4)return@forEach
            val paths=database.read {db ->db.rawQuery("SELECT id,path FROM episodes",null).use {c ->buildList {while(c.moveToNext())add(c.getLong(0) to c.getString(1))}}}
            val aliases=DownloadCatalogBridge.aliases(database,paths.map {it.second})
            database.transaction {db ->
                val targets=paths.filter {it.first==item.episodeId || aliases[it.second]==item.id}
                val archived=org.json.JSONArray()
                val removals=mutableListOf<Pair<Long,Long>>()
                var protected=false
                targets.forEach target@{ (episode,_) ->
                    db.rawQuery("SELECT e.*,a.favorite,a.is_pinned,a.user_tags,a.personal_note,a.anilist_match_manual,a.metadata_source AS anime_source,a.metadata_manual_fields AS anime_manual_fields FROM episodes e JOIN anime a ON a.id=e.anime_id WHERE e.id=?",arrayOf(episode.toString())).use {c ->
                        if(!c.moveToFirst())return@target
                        fun number(name: String)=c.getDouble(c.getColumnIndexOrThrow(name))
                        fun string(name: String)=c.getString(c.getColumnIndexOrThrow(name)).orEmpty()
                        if(number("progress")>0 || number("watched")>0 || number("manual_override")>0 || number("favorite")>0 || number("is_pinned")>0 || number("anilist_match_manual")>0 || string("user_tags") !in setOf("","[]") || string("personal_note").isNotBlank() || string("anime_source")=="manual" || string("anime_manual_fields") !in setOf("","[]")) {protected=true;return@target}
                        val anime=c.getLong(c.getColumnIndexOrThrow("anime_id"))
                        if(db.rawQuery("SELECT 1 FROM artwork WHERE manual=1 AND ((entity_type='episode' AND entity_id=?) OR (entity_type='anime' AND entity_id=?)) LIMIT 1",arrayOf(episode.toString(),anime.toString())).use {it.moveToFirst()}) {protected=true;return@target}
                        val archive=JSONObject();for(i in 0 until c.columnCount)archive.put(c.getColumnName(i),c.getString(i));archived.put(archive)
                        removals+=episode to anime
                    }
                }
                if(protected)return@transaction
                removals.forEach { (episode,anime) ->
                    db.delete("artwork","entity_type='episode' AND entity_id=?",arrayOf(episode.toString()))
                    db.delete("episodes","id=?",arrayOf(episode.toString()));changed=true
                    val empty=db.rawQuery("SELECT 1 FROM episodes WHERE anime_id=? LIMIT 1",arrayOf(anime.toString())).use {!it.moveToFirst()}
                    if(empty) {db.delete("artwork","entity_type='anime' AND entity_id=?",arrayOf(anime.toString()));db.delete("anime","id=?",arrayOf(anime.toString()))}
                }
                val data=JSONObject(item.json.toString()).put("excludedBanner",true).put("state","CANCELLED").put("episodeId",0).put("animeId",0).put("rejectedLibraryRows",archived)
                db.execSQL("UPDATE native_downloads SET data=? WHERE id=?",arrayOf(data.toString(),item.id))
            }
        }
        return changed
    }
}
