package com.reiflix.reiflix_local.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.MimeTypeMap
import com.reiflix.reiflix_local.ReiAnixAppDependencies
import com.reiflix.reiflix_local.data.media.MediaFilenameParser
import com.reiflix.reiflix_local.data.media.MediaTitleNormalizer
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
    val catalog = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
}

object DownloadState {
    val pending = setOf("QUEUED", "DOWNLOADING", "WAITING", "VERIFYING", "SAVING")
    val labels = mapOf("QUEUED" to "Na fila", "DOWNLOADING" to "Baixando", "WAITING" to "Aguardando conexão / nova tentativa",
        "VERIFYING" to "Verificando integridade", "SAVING" to "Salvando na pasta", "PAUSED" to "Pausado",
        "COMPLETE" to "Concluído", "ERROR" to "Falhou", "CORRUPT" to "Integridade não confere", "CANCELLED" to "Cancelado")
}

data class DownloadItem(val json: JSONObject) {
    val id: String get() = json.getString("id")
    val name: String get() = json.getString("name")
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
        }
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
            val reference=if(item.state=="COMPLETE") item.output else item.virtualUri.toString()
            if(item.episodeId>0 && (linked==null || (linked.first!=reference && linked.second!="download:${item.id}"))) {
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
        headers: Map<String, String>, animeTitle: String = "", automaticName: Boolean = false): DownloadItem = withContext(Dispatchers.IO) {
        ready()
        DownloadHttp.validateUrl(url)
        require(url.length<=16384) { "O link é longo demais" }
        require(headers.values.all { it.length<=16384 && it.none { c -> c=='\r' || c=='\n' } }) { "Cabeçalho de autenticação inválido" }
        require(name.isNotBlank() && name.length <= 200 && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it.code < 32 }) { "Nome de arquivo inválido" }
        require(hashType in setOf("SHA-256", "SHA-1", "MD5"))
        val hash = expectedHash.trim().lowercase()
        require(hash.isEmpty() || hash.matches(Regex(when(hashType) { "MD5" -> "[0-9a-f]{32}"; "SHA-1" -> "[0-9a-f]{40}"; else -> "[0-9a-f]{64}" }))) { "Hash de referência inválido" }
        val id = UUID.randomUUID().toString()
        val payload = JSONObject().put("url", url).put("headers", JSONObject(headers))
        val signature = digest(url.toByteArray())
        val data = JSONObject().put("id", id).put("name", name).put("state", "QUEUED").put("bytes", 0)
            .put("total", -1).put("generation", 0).put("destination", destination).put("expectedHash", hash)
            .put("hashType", hashType).put("secret", DownloadSecrets.encrypt(payload.toString())).put("signature", signature)
            .put("created", System.currentTimeMillis()).put("animeTitle", animeTitle.trim()).put("automaticName",automaticName)
            .put("virtualUri", "content://${context.packageName}.downloads/$id/${Uri.encode(name)}")
            .put("mime", MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream")
        db.transaction { connection ->
            val duplicate = connection.rawQuery("SELECT data FROM native_downloads", null).use { c ->
                var found = false
                while(c.moveToNext()) { val j=JSONObject(c.getString(0)); if(j.optString("signature")==signature && j.optString("state") !in setOf("CANCELLED", "CORRUPT", "ERROR")) found=true }
                found
            }
            require(!duplicate) { "Este link já está na lista de downloads" }
            connection.insertOrThrow("native_downloads", null, ContentValues().apply { put("id", id); put("data", data.toString()) })
        }
        partial(id).createNewFile()
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
        if(current.episodeId > 0 || !current.mime.startsWith("video/")) return
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
                    put("lookup_title", lookup); put("title", title); put("added_at", System.currentTimeMillis()/1000.0); put("metadata_source", "local_download")
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
    fun complete(id: String, output: Uri) {
        val actualName=runCatching {
            if(output.scheme=="file") File(output.path!!).name else context.contentResolver.query(output,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        db.transaction { connection ->
            val data = connection.rawQuery("SELECT data FROM native_downloads WHERE id=?", arrayOf(id)).use { it.moveToFirst(); JSONObject(it.getString(0)) }
            check(data.optString("state")=="SAVING") { "O download não está na etapa de publicação" }
            data.put("state", "COMPLETE").put("output", output.toString()).put("message", "Concluído")
            actualName?.takeIf { it.isNotBlank() }?.let { data.put("name",it) }
            connection.update("native_downloads", ContentValues().apply { put("data", data.toString()) }, "id=?", arrayOf(id))
            if(data.optLong("episodeId")>0) connection.update("episodes", ContentValues().apply {
                put("path", output.toString()); actualName?.takeIf { it.isNotBlank() }?.let { put("file_name",it) }; put("source_folder", data.optString("destination").ifBlank { "Downloads/ReiAnix" })
                put("file_size", data.optLong("bytes")); put("missing", 0); put("availability_state", "available")
            }, "id=? AND (media_identity=? OR path=?)", arrayOf(data.getLong("episodeId").toString(),"download:$id",data.getString("virtualUri")))
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
        mutate(id) {
            require(it.optString("state") in setOf("ERROR","PAUSED"))
            if(it.optString("destination")!=destination) { previousOutput=it.optString("output"); it.put("destination",destination).put("output","") }
            if(previousUrl!=url) { it.put("restart",true).put("bytes",0).put("total",-1) }
            it.put("secret",encrypted).put("signature",digest(url.toByteArray())).put("expectedHash",normalized)
                .put("hashType",hashType).put("state","QUEUED").put("attempts",0).put("nextAttempt",0)
        }
        if(previousOutput.isNotBlank()) runCatching {
            val uri=Uri.parse(previousOutput)
            if(uri.scheme=="file") java.io.File(uri.path!!).delete() else context.contentResolver.delete(uri,null,null)
        }
        available(id,true)
    }
    fun forget(id: String) {
        val item=get(id) ?: return
        require(item.state in setOf("COMPLETE", "CANCELLED", "ERROR", "CORRUPT"))
        require(item.state=="COMPLETE" || item.output.isBlank()) { "Conclua ou retome o salvamento antes de remover o registro" }
        db.transaction { it.delete("native_downloads", "id=?", arrayOf(id)) }
        partial(id).delete()
    }
    companion object {
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Credential-bearing URLs and headers never appear in logs, WorkData or plaintext preferences. */
internal object DownloadSecrets {
    @Synchronized private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("reianix.downloads",null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
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
