package com.reianix.reianix_local.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.MimeTypeMap
import com.reianix.reianix_local.ReiAnixAppDependencies
import com.reianix.reianix_local.data.media.MediaFilenameParser
import com.reianix.reianix_local.data.media.MediaTitleNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object DownloadEvents {
    // Catalog invalidation only on structural changes, never on progress updates.
    val catalog = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 8)
}

object DownloadState {
    val pending = setOf("QUEUED", "DOWNLOADING", "WAITING", "VERIFYING", "SAVING")
    val labels = mapOf("QUEUED" to "Na fila", "DOWNLOADING" to "Baixando", "WAITING" to "Aguardando conexão / nova tentativa",
        "VERIFYING" to "Preparando arquivo", "SAVING" to "Salvando na pasta", "PAUSED" to "Pausado",
        "COMPLETE" to "Concluído", "ERROR" to "Falhou", "CORRUPT" to "Integridade não confere", "CANCELLED" to "Cancelado")
}

data class DownloadItem(val json: JSONObject) {
    val id: String get() = json.getString("id")
    // The app-controlled virtual URI protects the original episode filename from
    // a generic CDN/provider rename. When remux changes TS <-> MP4, the new
    // container extension recorded by the engine must take precedence instead.
    val name: String get() {
        val stored = json.optString("name")
        val virtual = Uri.parse(json.optString("virtualUri")).lastPathSegment
            ?.takeIf { it.isNotBlank() } ?: return stored
        val mimeExtension = when (json.optString("mime").substringBefore(';').lowercase()) {
            "video/mp4" -> "mp4"
            "video/mp2t" -> "ts"
            "video/x-matroska" -> "mkv"
            "video/webm" -> "webm"
            "video/x-msvideo" -> "avi"
            "video/quicktime" -> "mov"
            "video/x-ms-wmv" -> "wmv"
            "video/x-flv" -> "flv"
            "video/3gpp" -> "3gp"
            else -> null
        }
        val storedExtension = stored.substringAfterLast('.', "").lowercase()
        val virtualExtension = virtual.substringAfterLast('.', "").lowercase()
        if (mimeExtension != null && storedExtension == mimeExtension &&
            virtualExtension != mimeExtension) return stored
        return virtual
    }
    val state: String get() = json.optString("state", "QUEUED")
    val bytes: Long get() = json.optLong("bytes")
    val total: Long get() = json.optLong("total", -1)
    val generation: Long get() = json.optLong("generation")
    val mime: String get() = json.optString("mime", "application/octet-stream")
    val output: String get() = json.optString("output")
    val episodeId: Long get() = json.optLong("episodeId")
    val animeId: Long get() = json.optLong("animeId")
    val virtualUri: Uri get() = Uri.parse(json.getString("virtualUri"))
}

/** Queue records live beside the library in the canonical SQLite database. */
class DownloadStore(context: Context) {
    private val context = context.applicationContext
    private val deps get() = ReiAnixAppDependencies.from(context)
    private val db get() = deps.sqliteDatabase
    val directory: File get() = File(context.filesDir, "native-downloads").apply { mkdirs() }
    fun partial(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return File(directory, "$id.part")
    }
    suspend fun ready() = withContext(Dispatchers.IO) {
        deps.bootstrap.awaitStarted()
        db.transaction { connection ->
            connection.execSQL("CREATE TABLE IF NOT EXISTS native_downloads (id TEXT PRIMARY KEY NOT NULL, data TEXT NOT NULL)")
            connection.execSQL("CREATE TABLE IF NOT EXISTS native_download_profiles (id TEXT PRIMARY KEY NOT NULL, secret TEXT NOT NULL)")
            // Repair old COMPLETE rows whose name/path were overwritten by an output
            // provider. Match by the download-owned identity, never by filename alone.
            val legacy = connection.rawQuery("SELECT id,data FROM native_downloads", null).use { c ->
                buildList {
                    while (c.moveToNext()) add(c.getString(0) to JSONObject(c.getString(1)))
                }
            }
            legacy.forEach { (id, data) ->
                if (data.optString("state") != "COMPLETE" || data.optLong("episodeId") <= 0) return@forEach
                val originalUri = data.optString("virtualUri")
                val outputUri = data.optString("output")
                if (originalUri.isBlank() || outputUri.isBlank()) return@forEach
                val originalName = DownloadItem(data).name
                if (originalName.isBlank()) return@forEach
                val providerName = data.optString("name")
                if (providerName != originalName) {
                    data.put("name", originalName)
                    connection.update("native_downloads", ContentValues().apply {
                        put("data", data.toString())
                    }, "id=?", arrayOf(id))
                }
                val owned = arrayOf(data.optLong("episodeId").toString(), "download:$id")
                connection.update("episodes", ContentValues().apply {
                    put("path", originalUri)
                }, "id=? AND media_identity=? AND path=?", owned + outputUri)
                connection.update("episodes", ContentValues().apply {
                    put("file_name", originalName)
                }, "id=? AND media_identity=? AND manual_override=0 AND path=? AND (file_name IS NULL OR file_name!=?)",
                    owned + originalUri + originalName)
                // The scanner previously also derived an episode title from
                // the output filename (e.g. "Animes Online"). For native downloads
                // the chosen filename is authoritative unless edited manually.
                connection.execSQL("UPDATE episodes SET episode_title=NULL WHERE id=? AND media_identity=? AND manual_override=0 AND path=? AND episode_title IS NOT NULL",
                    owned + originalUri)
            }
        }
        // Migrate only episodes proven to belong to previously completed
        // downloads. Older builds stored a MediaStore Downloads URI directly,
        // which the player could mistake for a revoked video permission.
        val migrated = db.transaction { connection ->
            val completed = connection.rawQuery(
                "SELECT data FROM native_downloads", null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val item = runCatching { DownloadItem(JSONObject(cursor.getString(0))) }.getOrNull()
                        if (item != null && item.state == "COMPLETE" && item.episodeId > 0 &&
                            item.output.isNotBlank()) add(item)
                    }
                }
            }
            completed.sumOf { item ->
                connection.update(
                    "episodes",
                    ContentValues().apply { put("path", item.virtualUri.toString()) },
                    "id=? AND media_identity=? AND path=?",
                    arrayOf(item.episodeId.toString(), "download:${item.id}", item.output),
                )
            }
        }
        if (migrated > 0) DownloadEvents.catalog.tryEmit(Unit)
    }
    fun all(): List<DownloadItem> = db.read { connection ->
        connection.rawQuery("SELECT data FROM native_downloads ORDER BY rowid DESC", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(DownloadItem(JSONObject(cursor.getString(0)))) }
        }
    }
    fun get(id: String): DownloadItem? = db.read { connection ->
        connection.rawQuery("SELECT data FROM native_downloads WHERE id=?", arrayOf(id)).use {
            if (it.moveToFirst()) DownloadItem(JSONObject(it.getString(0))) else null
        }
    }
    /** SQLite row IDs may be reused after a user deletes a library item. Never let a
     * retained download record target another episode's playback or progress. */
    fun projectLinks(rows: List<DownloadItem>): List<DownloadItem> = db.read { connection ->
        val matches=mutableMapOf<Long,Triple<String,String,Long>>()
        rows.map { it.episodeId }.filter { it>0 }.distinct().chunked(500).forEach { ids ->
            connection.rawQuery("SELECT id,path,media_identity,anime_id FROM episodes WHERE id IN (${ids.joinToString(",") { "?" }})",ids.map { it.toString() }.toTypedArray()).use { c ->
                while(c.moveToNext()) matches[c.getLong(0)]=Triple(c.getString(1),c.getString(2).orEmpty(),c.getLong(3))
            }
        }
        rows.map { item ->
            val linked=matches[item.episodeId]
            val stableReference=item.virtualUri.toString()
            // A completed download may still have a legacy output URI in SQLite.
            // Only the exact owner identity proves that it is the same episode.
            if(item.episodeId>0 && ((!item.json.has("identityKnown") && item.json.optString("animeTitle").isBlank() && DownloadIdentity.title(item.name).isBlank()) || linked==null || (linked.first!=stableReference && linked.first!=item.output && linked.second!="download:${item.id}"))) {
                DownloadItem(JSONObject(item.json.toString()).put("episodeId",0).put("animeId",0))
            } else if(linked!=null && linked.third!=item.animeId) DownloadItem(JSONObject(item.json.toString()).put("animeId",linked.third)) else item
        }
    }
    fun mutate(id: String, change: (JSONObject) -> Unit): DownloadItem = db.transaction { connection ->
        val data = connection.rawQuery("SELECT data FROM native_downloads WHERE id=?", arrayOf(id)).use {
            check(it.moveToFirst()) { "Download não encontrado" }; JSONObject(it.getString(0))
        }
        change(data)
        connection.update("native_downloads", ContentValues().apply { put("data", data.toString()) }, "id=?", arrayOf(id))
        DownloadItem(data)
    }
    suspend fun enqueue(url: String, name: String, destination: String, expectedHash: String, hashType: String,
        headers: Map<String, String>, animeTitle: String = "", automaticName: Boolean = false,
        playerCapture: JSONObject? = null, hlsHint: Boolean = false,
        detectedSize: Long? = null, detectedExact: Boolean = false): DownloadItem = withContext(Dispatchers.IO) {
        ready()
        DownloadHttp.validateUrl(url)
        require(url.length<=16384) { "O link é longo demais" }
        require(headers.values.all { it.length<=16384 && it.none { c -> c=='\r' || c=='\n' } }) { "Cabeçalho de autenticação inválido" }
        require(name.isNotBlank() && name.length <= 200 && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it.code < 32 }) { "Nome de arquivo inválido" }
        require(hashType in setOf("SHA-256", "SHA-1", "MD5"))
        val hash = expectedHash.trim().lowercase()
        require(hash.isEmpty() || hash.matches(Regex(when(hashType) { "MD5" -> "[0-9a-f]{32}"; "SHA-1" -> "[0-9a-f]{40}"; else -> "[0-9a-f]{64}" }))) { "Hash de referência inválido" }
        if(playerCapture!=null)require(PlayerMediaCandidate.parse(playerCapture)?.url==url.trim()) {"Não foi possível confirmar o player principal"}
        val id = UUID.randomUUID().toString()
        val payload = JSONObject().put("url", url).put("headers", JSONObject(headers))
        val signature = digest(url.toByteArray())
        val data = JSONObject().put("id", id).put("name", name).put("state", "QUEUED").put("bytes", 0)
            .put("total", -1).put("generation", 0).put("destination", destination).put("expectedHash", hash)
            .put("hashType", hashType).put("secret", DownloadSecrets.encrypt(payload.toString())).put("signature", signature)
            .put("created", System.currentTimeMillis()).put("animeTitle", animeTitle.trim()).put("automaticName",automaticName)
            .put("hls",hlsHint)
            .put("virtualUri", "content://${context.packageName}.downloads/$id/${Uri.encode(name)}")
            .put("mime", MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream")
        // Discovered size is display metadata only. Never use an HLS estimate
        // as the transfer's authoritative total or expected byte count.
        detectedSize?.takeIf { it>0L }?.let { size ->
            data.put("detectedSize",size).put("detectedSizeExact",detectedExact)
        }
        if(playerCapture!=null) {val evidence=JSONObject(playerCapture.toString());evidence.remove("url");evidence.remove("page");data.put("playerCapture",evidence)}
        db.transaction { connection ->
            val duplicate = connection.rawQuery("SELECT data FROM native_downloads", null).use { c ->
                var found = false
                while(c.moveToNext()) { val j=JSONObject(c.getString(0)); if(j.optString("signature")==signature && j.optString("state") !in setOf("CANCELLED", "CORRUPT", "ERROR")) found=true }
                found
            }
            require(!duplicate) { "Este link já está na lista de downloads" }
            connection.insertOrThrow("native_downloads", null, ContentValues().apply { put("id", id); put("data", data.toString()) })
        }
        // No private .part is allocated for new Android 10+ downloads; the
        // transfer target is created in the selected/public destination on demand.
        try { registerMedia(id) } catch (e: Exception) {
            mutate(id) { it.put("state", "ERROR").put("message", "Não foi possível adicionar o vídeo à biblioteca") }
            throw e
        }
        get(id)!!.also { if(it.json.optBoolean("identityKnown")) DownloadMetadata.offer(context,it.animeId) }
    }
    fun relink(id: String,title: String) {
        require(title.isNotBlank() && title.length<=300)
        val item=get(id) ?: return
        if(item.episodeId<=0) {mutate(id){it.put("animeTitle",title)};return}
        db.transaction { c ->
            val owns=c.rawQuery("SELECT 1 FROM episodes WHERE id=? AND media_identity=?",arrayOf(item.episodeId.toString(),"download:$id")).use {it.moveToFirst()}
            if(!owns)return@transaction
            val lookup=MediaTitleNormalizer.normalizeTitle(title)
            val target=c.rawQuery("SELECT id FROM anime WHERE lookup_title=? LIMIT 1",arrayOf(lookup)).use {if(it.moveToFirst())it.getLong(0) else null}
                ?: c.insertOrThrow("anime",null,ContentValues().apply {put("lookup_title",lookup);put("title",title);put("added_at",System.currentTimeMillis()/1000.0);put("metadata_source","local_download")})
            c.update("episodes",ContentValues().apply {put("anime_id",target)},"id=?",arrayOf(item.episodeId.toString()))
            val data=c.rawQuery("SELECT data FROM native_downloads WHERE id=?",arrayOf(id)).use {it.moveToFirst();JSONObject(it.getString(0))}
            data.put("animeId",target).put("animeTitle",title).put("identityKnown",true)
            c.update("native_downloads",ContentValues().apply {put("data",data.toString())},"id=?",arrayOf(id))
        }
        DownloadEvents.catalog.tryEmit(Unit)
        get(id)?.let {DownloadMetadata.offer(context,it.animeId)}
    }
    fun profile(url: String): JSONObject = db.read { c ->
        c.rawQuery("SELECT secret FROM native_download_profiles WHERE id=?",arrayOf(digest(url.trim().toByteArray()))).use {
            if(it.moveToFirst()) JSONObject(DownloadSecrets.decrypt(it.getString(0))) else JSONObject()
        }
    }
    fun saveProfile(url: String,data: JSONObject) {
        DownloadHttp.validateUrl(url)
        require(data.toString().length<65536)
        val hash=data.optString("hash").trim().lowercase()
        val size=when(data.optString("hashType","SHA-256")) { "MD5"->32; "SHA-1"->40; else->64 }
        require(hash.isBlank() || hash.matches(Regex("[0-9a-f]{$size}"))) { "Hash de referência inválido" }
        db.transaction { c -> c.insertWithOnConflict("native_download_profiles",null,ContentValues().apply {
            put("id",digest(url.trim().toByteArray())); put("secret",DownloadSecrets.encrypt(data.toString()))
        },android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE) }
    }
    fun artwork(item: DownloadItem): Pair<String,String>? = db.read { c ->
        c.rawQuery("SELECT title,cover_cache FROM anime WHERE id=?",arrayOf(item.animeId.toString())).use {
            if(it.moveToFirst()) it.getString(0) to it.getString(1).orEmpty() else null
        }
    }
    fun secret(item: DownloadItem): JSONObject = JSONObject(DownloadSecrets.decrypt(item.json.getString("secret")))
    fun registerMedia(id: String) {
        val current = get(id) ?: return
        if(current.state in setOf("CANCELLED","CORRUPT","ERROR"))return
        if(current.json.has("playerCapture") && !current.json.optBoolean("captureApproved"))return
        if(!current.mime.startsWith("video/")) return
        if(current.episodeId>0) {
            val changed=db.transaction { c -> c.update("episodes",ContentValues().apply {put("mime_type",current.mime)},
                "id=? AND media_identity=? AND (mime_type IS NULL OR mime_type!=?)",arrayOf(current.episodeId.toString(),"download:$id",current.mime)) }
            if(changed>0)DownloadEvents.catalog.tryEmit(Unit)
            return
        }
        val parsed = MediaFilenameParser.parseVideoPath(current.name)
        val explicit=current.json.optString("animeTitle").trim()
        val inferred=DownloadIdentity.title(current.name)
        val identified=explicit.ifBlank { inferred }
        val title=identified.ifBlank { "Download não identificado — ${current.name}" }
        val lookup = if(identified.isBlank()) "download $id" else MediaTitleNormalizer.normalizeTitle(title)
        db.transaction { connection ->
            val data = connection.rawQuery("SELECT data FROM native_downloads WHERE id=?", arrayOf(id)).use { it.moveToFirst(); JSONObject(it.getString(0)) }
            if(data.optLong("episodeId") > 0) return@transaction
            val anime = connection.rawQuery("SELECT id FROM anime WHERE lookup_title=? LIMIT 1", arrayOf(lookup)).use { if(it.moveToFirst()) it.getLong(0) else null }
                ?: connection.insertOrThrow("anime", null, ContentValues().apply {
                    put("lookup_title", lookup); put("title", title); put("added_at", System.currentTimeMillis()/1000.0); put("metadata_source", if(identified.isBlank()) "local_download_unidentified" else "local_download")
                })
            val reuse = connection.rawQuery("SELECT id,path,missing FROM episodes WHERE anime_id=? AND file_name=? LIMIT 1", arrayOf(anime.toString(), current.name)).use { c ->
                if(!c.moveToFirst()) null else {
                    val priorUri=Uri.parse(c.getString(1)); val priorId=priorUri.pathSegments.firstOrNull().orEmpty()
                    val forgotten=connection.rawQuery("SELECT id FROM native_downloads WHERE id=?",arrayOf(priorId)).use { !it.moveToFirst() }
                    require(c.getInt(2)==1 && priorUri.authority==context.packageName+".downloads" && forgotten) { "Um episódio com este nome já existe na biblioteca" }
                    c.getLong(0)
                }
            }
            if(reuse!=null) connection.update("episodes",ContentValues().apply {
                put("path",current.virtualUri.toString()); put("media_identity","download:$id"); put("missing",0); put("availability_state","available")
            },"id=?",arrayOf(reuse.toString()))
            val episode = reuse ?: connection.insertOrThrow("episodes", null, ContentValues().apply {
                put("anime_id", anime); put("path", current.virtualUri.toString()); put("file_name", current.name)
                put("season", parsed.season ?: 0); parsed.episodeNumber?.let { put("number", it) }
                put("mime_type", current.mime); put("file_size", 0); put("source_folder", "content://${context.packageName}.downloads")
                put("media_identity", "download:$id"); put("identification_source", "local_download")
                put("identification_confidence", parsed.confidence.name.lowercase()); put("manual_override", 0)
                put("missing", 0); put("availability_state", "available")
            })
            data.put("animeId", anime).put("episodeId", episode).put("identityKnown",identified.isNotBlank())
            connection.update("native_downloads", ContentValues().apply { put("data", data.toString()) }, "id=?", arrayOf(id))
        }
        DownloadEvents.catalog.tryEmit(Unit)
    }
    fun available(id: String, available: Boolean) {
        get(id)?.takeIf { it.episodeId > 0 }?.let { item ->
            db.transaction { c -> c.update("episodes", ContentValues().apply {
                put("missing", if(available) 0 else 1); put("availability_state", if(available) "available" else "unavailable")
            }, "id=? AND (media_identity=? OR path=?)", arrayOf(item.episodeId.toString(),"download:$id",item.virtualUri.toString())) }
            DownloadEvents.catalog.tryEmit(Unit)
        }
    }
    fun complete(id: String, output: Uri, finalBytes: Long = -1L) {
        DownloadCatalogBridge.reconcile(db)
        db.transaction { connection ->
            val data = connection.rawQuery("SELECT data FROM native_downloads WHERE id=?", arrayOf(id)).use { it.moveToFirst(); JSONObject(it.getString(0)) }
            check(data.optString("state")=="SAVING") { "O download não está na etapa de publicação" }
            data.put("state", "COMPLETE").put("output", output.toString()).put("message", "Concluído")
                .put("name", DownloadItem(data).name)
            // PR #296: the exported MP4 can have a different byte length from its TS source.
            if (finalBytes > 0L) data.put("bytes", finalBytes).put("total", finalBytes)
            connection.update("native_downloads", ContentValues().apply { put("data", data.toString()) }, "id=?", arrayOf(id))
            if(data.optLong("episodeId")>0) connection.update("episodes", ContentValues().apply {
                // The episode keeps one provider URI before and after publication.
                put("path", data.getString("virtualUri")); put("file_name", data.getString("name"))
                put("source_folder", data.optString("destination").ifBlank { "Downloads/ReiAnix" })
                put("file_size", data.optLong("bytes")); put("mime_type",data.optString("mime"))
                put("missing", 0); put("availability_state", "available")
            }, "id=? AND (media_identity=? OR path=? OR path=?)",
                arrayOf(data.getLong("episodeId").toString(),"download:$id",data.getString("virtualUri"),output.toString()))
            if(data.optLong("episodeId")>0) connection.execSQL(
                "UPDATE episodes SET episode_title=NULL WHERE id=? AND media_identity=? AND manual_override=0 AND episode_title IS NOT NULL",
                arrayOf(data.getLong("episodeId").toString(),"download:$id")
            )
        }
        DownloadEvents.catalog.tryEmit(Unit)
    }
    fun control(id: String, action: String) {
        val before=get(id) ?: return
        if(before.state=="SAVING") throw IllegalStateException("Aguarde o salvamento final")
        when(action) {
            "pause" -> mutate(id) { if(it.optString("state") in DownloadState.pending && it.optString("state")!="SAVING") it.put("state", "PAUSED") }
            "resume" -> { mutate(id) { if(it.optString("state") in setOf("PAUSED", "ERROR", "WAITING")) it.put("state", "QUEUED").put("attempts", 0).put("nextAttempt", 0) }; available(id,true) }
            "cancel" -> {
                var output=""
                val cancelled=mutate(id) {
                    if(it.optString("state") !in setOf("SAVING","COMPLETE")) { output=it.optString("output"); it.put("state","CANCELLED").put("speed",0) }
                }
                if(cancelled.state!="CANCELLED") return
                if(output.isNotBlank()) {
                    val removed=runCatching { val uri=Uri.parse(output)
                        if(uri.scheme=="file") File(uri.path!!).delete() else context.contentResolver.delete(uri,null,null)>0
                    }.getOrDefault(false)
                    mutate(id) { if(removed) it.put("output","") else it.put("message","Autorize novamente a pasta para remover o arquivo temporário.") }
                }
                DownloadTransferFile(context,this,id).let { target ->
                    target.uri?.let { target.discard(it) }
                }
                mutate(id) { it.remove("transferUri") }
                available(id,false)
            }
            "restart" -> { require(before.state !in setOf("DOWNLOADING","SAVING","VERIFYING","COMPLETE")); mutate(id) { require(it.optString("state") in setOf("PAUSED","ERROR","CORRUPT","CANCELLED")); it.put("restart",true).put("state","QUEUED").put("bytes",0).put("total",-1).put("attempts",0).put("nextAttempt",0) }; available(id,true) }
        }
    }
    fun updateSource(id: String,url: String,headers: Map<String,String>,hash: String,hashType: String,destination: String) {
        DownloadHttp.validateUrl(url)
        val item=get(id) ?: error("Download não encontrado")
        require(item.state in setOf("ERROR","PAUSED"))
        require(hashType in setOf("MD5","SHA-1","SHA-256"))
        val normalized=hash.trim().lowercase()
        val length=when(hashType) { "MD5"->32; "SHA-1"->40; else->64 }
        require(normalized.isBlank() || normalized.matches(Regex("[0-9a-f]{$length}"))) { "Hash inválido" }
        require(url.length<=16384 && headers.values.all { it.length<=16384 && it.none { c -> c=='\r' || c=='\n' } }) { "Link ou cabeçalho inválido" }
        val previousUrl=runCatching { secret(item).getString("url") }.getOrNull()
        val encrypted=DownloadSecrets.encrypt(JSONObject().put("url",url).put("headers",JSONObject(headers)).toString())
        var previousOutput=""
        var previousTransfer=""
        var destinationChanged=false
        mutate(id) {
            require(it.optString("state") in setOf("ERROR","PAUSED"))
            if(it.optString("destination")!=destination) {
                destinationChanged=true
                previousOutput=it.optString("output")
                previousTransfer=it.optString("transferUri")
                it.put("destination",destination).put("output","").remove("transferUri")
                it.put("restart",true).put("bytes",0).put("total",-1)
            }
            if(previousUrl!=url) {
                it.put("restart",true).put("bytes",0).put("total",-1).put("parallelDisabled",false)
                it.remove("hls");it.remove("playerCapture");it.remove("captureApproved");it.remove("captureProbeStage")
                // The editor probe described the old URL; never show it as
                // the byte count of an entirely different source.
                it.remove("detectedSize");it.remove("detectedSizeExact")
            }
            it.put("secret",encrypted).put("signature",digest(url.toByteArray())).put("expectedHash",normalized)
                .put("hashType",hashType).put("state","QUEUED").put("attempts",0).put("nextAttempt",0)
        }
        if(previousOutput.isNotBlank()) runCatching {
            val uri=Uri.parse(previousOutput)
            if(uri.scheme=="file") java.io.File(uri.path!!).delete() else context.contentResolver.delete(uri,null,null)
        }
        if(previousTransfer.isNotBlank() && previousTransfer!=previousOutput) runCatching {
            context.contentResolver.delete(Uri.parse(previousTransfer),null,null)
        }
        if(destinationChanged) partial(id).delete()
        available(id,true)
    }
    fun forget(id: String) {
        val item=get(id) ?: return
        require(item.state in setOf("COMPLETE", "CANCELLED", "ERROR", "CORRUPT"))
        require(item.state=="COMPLETE" || item.output.isBlank()) { "Conclua ou retome o salvamento antes de remover o registro" }
        db.transaction { connection ->
            if(item.state=="COMPLETE" && item.output.isNotBlank() && item.episodeId>0) {
                // A forgotten download becomes an ordinary local file reference.
                connection.update("episodes",ContentValues().apply {
                    put("path",item.output)
                    putNull("media_identity")
                },"id=? AND media_identity=?",arrayOf(item.episodeId.toString(),"download:$id"))
            }
            connection.delete("native_downloads","id=?",arrayOf(id))
        }
        if(item.state!="COMPLETE") DownloadTransferFile(context,this,id).let { target ->
            target.uri?.let { target.discard(it) }
        }
        partial(id).delete()
        DownloadEvents.catalog.tryEmit(Unit)
    }
    companion object {
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Credential-bearing URLs and headers never appear in logs, WorkData or plaintext preferences. */
internal object DownloadSecrets {
    @Synchronized private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val currentAlias = "reianix.downloads"
        val previousAlias = com.reianix.reianix_local.ReiAnixLegacyIdentity.previousPrefix + ".downloads"
        (store.getKey(currentAlias,null) as? SecretKey)?.let { return it }
        // AndroidKeyStore AES keys are non-exportable: reuse an existing alias
        // so downloads queued before the branding change remain decryptable.
        (store.getKey(previousAlias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("reianix.downloads",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun encrypt(text: String): String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key())
        return Base64.encodeToString(cipher.iv+cipher.doFinal(text.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
    }
    fun decrypt(text: String): String {
        val bytes=Base64.decode(text,Base64.NO_WRAP)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
    }
}

/** Use the existing matcher and artwork queue, without changing their speed or policies. */
object DownloadMetadata {
    private val offered=java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+Dispatchers.IO)
    fun offer(context: Context,animeId: Long) {
        if(animeId<=0 || !offered.add(animeId)) return
        scope.launchMetadata(context.applicationContext,animeId)
    }
    private fun kotlinx.coroutines.CoroutineScope.launchMetadata(context: Context,animeId: Long) = launch {
        try {
            val deps=ReiAnixAppDependencies.from(context)
            deps.aniListIntegrationService.autoMatch(animeId)
            deps.artworkService.requestPendingArtwork()
            DownloadEvents.catalog.tryEmit(Unit)
        } catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { /* Existing artwork recovery remains available. */ }
        finally { offered.remove(animeId) }
    }
}
