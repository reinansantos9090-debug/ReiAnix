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
    fun mutate(id: String, change: (JSONObject) -> Unit): DownloadItem = db.transaction { connection ->
        val data = connection.rawQuery("SELECT data FROM native_downloads WHERE id=?", arrayOf(id)).use {
            check(it.moveToFirst()) { "Download não encontrado" }; JSONObject(it.getString(0))
        }
        change(data)
        connection.update("native_downloads", ContentValues().apply { put("data", data.toString()) }, "id=?", arrayOf(id))
        DownloadItem(data)
    }
    suspend fun enqueue(url: String, name: String, destination: String, expectedHash: String, hashType: String,
        headers: Map<String, String>, animeTitle: String = ""): DownloadItem = withContext(Dispatchers.IO) {
        ready()
        DownloadHttp.validateUrl(url)
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
            .put("created", System.currentTimeMillis()).put("animeTitle", animeTitle.trim())
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
        get(id)!!.also { DownloadMetadata.offer(context,it.animeId) }
    }
    fun secret(item: DownloadItem): JSONObject = JSONObject(DownloadSecrets.decrypt(item.json.getString("secret")))
    fun registerMedia(id: String) {
        val current = get(id) ?: return
        if(current.episodeId > 0 || !current.mime.startsWith("video/")) return
        val parsed = MediaFilenameParser.parseVideoPath(current.name)
        val title = current.json.optString("animeTitle").ifBlank { parsed.animeTitle.ifBlank { current.name.substringBeforeLast('.') } }
        val lookup = MediaTitleNormalizer.normalizeTitle(title).ifBlank { "download $id" }
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
            data.put("animeId", anime).put("episodeId", episode)
            connection.update("native_downloads", ContentValues().apply { put("data", data.toString()) }, "id=?", arrayOf(id))
        }
        DownloadEvents.catalog.tryEmit(Unit)
    }
    fun available(id: String, available: Boolean) {
        get(id)?.takeIf { it.episodeId > 0 }?.let { item ->
            db.transaction { c -> c.update("episodes", ContentValues().apply {
                put("missing", if(available) 0 else 1); put("availability_state", if(available) "available" else "unavailable")
            }, "id=?", arrayOf(item.episodeId.toString())) }
            DownloadEvents.catalog.tryEmit(Unit)
        }
    }
    fun complete(id: String, output: Uri) {
        db.transaction { connection ->
            val data = connection.rawQuery("SELECT data FROM native_downloads WHERE id=?", arrayOf(id)).use { it.moveToFirst(); JSONObject(it.getString(0)) }
            data.put("state", "COMPLETE").put("output", output.toString()).put("message", "Concluído")
            connection.update("native_downloads", ContentValues().apply { put("data", data.toString()) }, "id=?", arrayOf(id))
            if(data.optLong("episodeId")>0) connection.update("episodes", ContentValues().apply {
                put("path", output.toString()); put("source_folder", data.optString("destination").ifBlank { "Downloads/ReiAnix" })
                put("file_size", data.optLong("bytes")); put("missing", 0); put("availability_state", "available")
            }, "id=?", arrayOf(data.getLong("episodeId").toString()))
        }
        DownloadEvents.catalog.tryEmit(Unit)
    }
    fun control(id: String, action: String) {
        val before=get(id) ?: return
        if(before.state=="SAVING") throw IllegalStateException("Aguarde o salvamento final")
        when(action) {
            "pause" -> mutate(id) { if(it.optString("state") in DownloadState.pending) it.put("state", "PAUSED") }
            "resume" -> { mutate(id) { if(it.optString("state") in setOf("PAUSED", "ERROR", "WAITING")) it.put("state", "QUEUED").put("attempts", 0).put("nextAttempt", 0) }; available(id,true) }
            "cancel" -> { if(before.state=="COMPLETE") return; mutate(id) { it.put("state", "CANCELLED") }; available(id,false) }
            "restart" -> { require(before.state !in setOf("DOWNLOADING","SAVING","VERIFYING","COMPLETE")); mutate(id) { it.put("restart",true).put("state","QUEUED").put("attempts",0).put("nextAttempt",0) }; available(id,true) }
        }
    }
    fun updateSource(id: String,url: String,headers: Map<String,String>,hash: String,hashType: String) {
        DownloadHttp.validateUrl(url)
        val item=get(id) ?: error("Download não encontrado")
        require(item.state in setOf("ERROR","PAUSED"))
        require(hashType in setOf("MD5","SHA-1","SHA-256"))
        val normalized=hash.trim().lowercase()
        val length=when(hashType) { "MD5"->32; "SHA-1"->40; else->64 }
        require(normalized.isBlank() || normalized.matches(Regex("[0-9a-f]{$length}"))) { "Hash inválido" }
        val encrypted=DownloadSecrets.encrypt(JSONObject().put("url",url).put("headers",JSONObject(headers)).toString())
        mutate(id) {
            require(it.optString("state") in setOf("ERROR","PAUSED"))
            it.put("secret",encrypted).put("signature",digest(url.toByteArray())).put("expectedHash",normalized)
                .put("hashType",hashType).put("state","QUEUED").put("attempts",0).put("nextAttempt",0)
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
private object DownloadSecrets {
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
        catch (_: Exception) { offered.remove(animeId) }
    }
}
