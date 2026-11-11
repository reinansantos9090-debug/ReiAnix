package com.reiflix.reiflix_local.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.reiflix.reiflix_local.ReiAnixAppDependencies
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.security.MessageDigest

/** Single transfer runner shared by UIDT and WorkManager; never two writers for a partial. */
class DownloadEngine(private val context: Context) {
    private val store=DownloadStore(context)
    suspend fun run(onProgress: (String)->Unit, budgetMs: Long = Long.MAX_VALUE): Boolean = withContext(Dispatchers.IO) {
        runner.lock()
        try {
            store.ready()
            store.all().filter { it.state in setOf("DOWNLOADING","VERIFYING","SAVING") }.forEach { item ->
                store.mutate(item.id) { it.put("state","QUEUED") }
            }
            val started=android.os.SystemClock.elapsedRealtime()
            coroutineScope {
                val active=mutableMapOf<String,Job>()
                while(isActive && android.os.SystemClock.elapsedRealtime()-started < budgetMs) {
                    active.entries.removeAll { it.value.isCompleted }
                    val jobs=store.all()
                    val wifiOnly=ReiAnixAppDependencies.from(context).sqliteSettingsRepository.getPreference("downloads.wifi_only","false")=="true"
                    val network=context.getSystemService(android.net.ConnectivityManager::class.java)
                    val allowed=!wifiOnly || network.getNetworkCapabilities(network.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)==true
                    if(!allowed) {
                        active.values.forEach {it.cancel()};active.values.joinAll();active.clear()
                        if(jobs.none {it.state in DownloadState.pending})break
                        onProgress("Aguardando Wi-Fi");delay(1000);continue
                    }
                    val limit=(ReiAnixAppDependencies.from(context).sqliteSettingsRepository.getPreference("downloads.parallel","2")?.toIntOrNull() ?: 2).coerceIn(1,4)
                    jobs.filter { it.id !in active && it.state in DownloadState.pending && it.json.optLong("nextAttempt")<=System.currentTimeMillis() }
                        .sortedBy { it.json.optLong("created") }.take((limit-active.size).coerceAtLeast(0)).forEach { item ->
                            active[item.id]=launch { transfer(item.id) }
                        }
                    if(active.isEmpty() && jobs.none { it.state in DownloadState.pending }) break
                    // Don't keep a user-initiated foreground notification alive
                    // indefinitely with "0 downloads" while all retryable jobs
                    // are sleeping. WorkManager/JobScheduler will resume the queue.
                    if(active.isEmpty() && jobs.none {
                        it.state in DownloadState.pending &&
                            it.json.optLong("nextAttempt")<=System.currentTimeMillis()
                    }) break
                    onProgress("${active.size} download(s) • ${android.text.format.Formatter.formatFileSize(context,jobs.filter { it.state in DownloadState.pending }.sumOf { it.bytes })} recebidos")
                    delay(1000)
                }
                active.values.forEach { it.cancel() }
                active.values.joinAll()
            }
            store.all().any { it.state in DownloadState.pending }
        } finally { try {DownloadSnapshots.refresh(context);runCatching {DownloadScheduling.publish(context)}} finally {runner.unlock()} }
    }
    private suspend fun transfer(id: String) {
        var connection: HttpURLConnection?=null
        try {
            var item=store.get(id) ?: return
            if(item.state !in DownloadState.pending) return
            val file=store.partial(id)
            if(item.json.optBoolean("restart")) {
                RandomAccessFile(file,"rw").use { it.setLength(0) }
                item=store.mutate(id) { it.put("generation",it.optLong("generation")+1).put("bytes",0).put("total",-1).put("restart",false).remove("validator"); it.remove("serverHash"); it.put("hlsIndex",0); it.remove("hlsFingerprint");if(it.has("playerCapture"))it.put("captureApproved",false).put("captureProbeStage",0) }
            }
            // A crash can leave uncommitted bytes. Only persisted, fsynced bytes are reusable.
            val offset=minOf(item.bytes,file.length())
            RandomAccessFile(file,"rw").use { it.setLength(offset) }
            val secret=store.secret(item); val headers=secret.optJSONObject("headers") ?: JSONObject()
            val requestHeaders=headers.keys().asSequence().associateWith { headers.getString(it) }
            var resume=offset
            var validator=item.json.optString("validator").takeIf { it.isNotBlank() }
            val hls=item.json.optBoolean("hls") || android.net.Uri.parse(secret.getString("url")).path.orEmpty().endsWith(".m3u8",true)
            if(resume>0 && validator==null && !hls) { restart(id,file); resume=0 }
            store.mutate(id) { if(it.optString("state") in DownloadState.pending) it.put("state","DOWNLOADING").put("message","") }
            if(!isDownloading(id)) return
            if(hls) {
                if(receiveHls(id,secret.getString("url"),requestHeaders,file)) verifyAndSave(id,file)
                return
            }
            // Fully received files interrupted during verification/export need no redownload.
            if(item.total>=0 && resume==item.total && resume>0) {
                verifyAndSave(id,file); return
            }
            connection=DownloadHttp.connect(secret.getString("url"),requestHeaders,resume,validator)
            val code=connection.responseCode
            if(code==401) throw PermanentFailure("O servidor solicitou autenticação (HTTP 401). Abra novamente o vídeo no navegador e capture o link.")
            if(code==403) throw PermanentFailure("O servidor recusou o download (HTTP 403). Capture novamente o vídeo no navegador; a sessão ou as regras do servidor podem impedir esta transferência.")
            if(code==404 || code==410) throw PermanentFailure("Arquivo não encontrado ou link expirado")
            if(code==416) { restart(id,file); throw IOException("Faixa anterior indisponível; reiniciando") }
            if(code==429 || code>=500) throw IOException("Servidor temporariamente indisponível")
            if(code !in setOf(200,206)) throw PermanentFailure("O servidor recusou o download (HTTP $code)")
            if(connection.getHeaderField("Content-Encoding")?.let { it!="identity" }==true) throw PermanentFailure("O servidor enviou uma representação comprimida incompatível com a retomada")
            val range=DownloadHttp.range(connection.getHeaderField("Content-Range"))
            if(code==206 && (range==null || range.start!=resume)) throw PermanentFailure("O servidor retornou uma faixa de bytes inválida")
            if(resume>0 && code==200) { restart(id,file); resume=0 }
            val newValidator=connection.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") }
                ?: connection.getHeaderField("Last-Modified")
            if(resume>0 && validator!=null && newValidator!=null && validator!=newValidator) {
                restart(id,file); throw IOException("O arquivo no servidor mudou; reiniciando com segurança")
            }
            val length=connection.getHeaderFieldLong("Content-Length",-1)
            if(range!=null && length>=0 && length!=range.end-range.start+1) throw PermanentFailure("O tamanho e a faixa retornados pelo servidor não conferem")
            val responseEnd=range?.end?.plus(1)
            val total=range?.total ?: length
            val contentType=connection.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if(item.json.has("playerCapture") && contentType.startsWith("image/"))throw DownloadCaptureGuard.Rejected("A captura retornou uma imagem, não o episódio")
            if(contentType in setOf("text/html","application/xhtml+xml")) throw PermanentFailure("Este link abre uma página. Informe o link direto do arquivo.")
            if(contentType.contains("mpegurl",true)) {
                connection.disconnect(); connection=null
                restart(id,file); store.mutate(id){it.put("hls",true)}
                if(receiveHls(id,secret.getString("url"),requestHeaders,file)) verifyAndSave(id,file)
                return
            }
            if(contentType.contains("dash+xml",true)) throw PermanentFailure("Esta playlist DASH exige montagem de faixas ainda não suportada. Abra o site e escolha uma versão de arquivo ou HLS compatível.")
            if(total>0 && StatFs(store.directory.path).availableBytes < (total-resume).coerceAtLeast(0)+16L*1024*1024) throw PermanentFailure("Espaço insuficiente para baixar o arquivo")
            val serverHash=if(resume==0L && (code==200 || range?.end?.plus(1)==range?.total)) serverHash(connection) else null
            val disposition=connection.getHeaderField("Content-Disposition")
            store.mutate(id) {
                if(it.optBoolean("automaticName") && it.optLong("episodeId")==0L &&
                    !it.has("playerCapture")) {
                    val serverName=android.webkit.URLUtil.guessFileName(secret.getString("url"),disposition,contentType)
                    val proposed=if(it.has("playerCapture")) it.getString("name").substringBeforeLast('.')+"."+serverName.substringAfterLast('.',"mp4") else serverName
                    if(proposed.isNotBlank() && proposed !in setOf(".","..") && proposed.length<=200 && proposed.none { c -> c=='/' || c=='\\' || c.code<32 }) {
                        it.put("name",proposed).put("virtualUri","content://${context.packageName}.downloads/$id/${Uri.encode(proposed)}")
                    }
                }
                it.put("total",total).put("validator",newValidator ?: "")
                if(contentType.startsWith("video/")) it.put("mime",contentType)
                if(resume==0L) { it.remove("serverHash"); it.remove("serverHashType"); it.put("hlsIndex",0); it.remove("hlsFingerprint") }
                if(serverHash!=null) it.put("serverHash",serverHash.first).put("serverHashType",serverHash.second)
            }
            try { store.registerMedia(id) } catch(_: IllegalArgumentException) { throw PermanentFailure("Já existe um episódio com este nome na biblioteca. Altere o nome antes de adicionar outro download.") }
            store.get(id)?.takeIf {it.json.optBoolean("identityKnown")}?.let { DownloadMetadata.offer(context,it.animeId) }
            val prefs=ReiAnixAppDependencies.from(context).sqliteSettingsRepository
            val connections=(prefs.getPreference("downloads.connections","4")?.toIntOrNull() ?: 4).coerceIn(1,16)
            if(connections>1 && total>=4*1024*1024 && !store.get(id)!!.json.optBoolean("parallelDisabled") &&
                newValidator?.startsWith("\"")==true && connection.getHeaderField("Accept-Ranges").equals("bytes",true)) {
                connection.disconnect(); connection=null
                try {
                    if(DownloadRanges.receive(context,store,id,secret.getString("url"),requestHeaders,file,resume,total,newValidator,connections)) verifyAndSave(id,file)
                } catch(cancelled: CancellationException) { throw cancelled }
                catch(rejected: DownloadCaptureGuard.Rejected) {throw rejected}
                catch(_: DownloadRanges.Paused) { return }
                catch(error: Exception) { if(store.get(id)?.state=="DOWNLOADING") { restart(id,file);store.mutate(id){it.put("parallelDisabled",true)};throw IOException("Retomando com uma conexão",error) } }
                return
            }
            val bufferSize=(prefs.getPreference("downloads.buffer_kb","256")?.toIntOrNull() ?: 256).coerceIn(64,512)*1024
            var count=resume; var committedAt=android.os.SystemClock.elapsedRealtime(); var previous=resume; var checkedAt=0L
            RandomAccessFile(file,"rw").use { output ->
                output.seek(resume)
                connection.inputStream.use { input ->
                    val buffer=ByteArray(bufferSize)
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val controlNow=android.os.SystemClock.elapsedRealtime()
                        if(controlNow-checkedAt>=250) {
                            checkedAt=controlNow
                            if(!isDownloading(id)) { output.fd.sync(); store.mutate(id) { it.put("bytes",count) }; return }
                        }
                        val read=input.read(buffer)
                        if(read<0) break
                        if(responseEnd!=null && count+read>responseEnd) throw PermanentFailure("O servidor enviou dados fora da faixa solicitada")
                        if(count==0L) {
                            val prefix=String(buffer,0,minOf(read,256),Charsets.UTF_8).trimStart().lowercase()
                            if(prefix.startsWith("#extm3u")) {
                                store.mutate(id){it.put("hls",true)}
                                throw IOException("Playlist detectada; preparando download HLS")
                            }
                            if(prefix.startsWith("<!doctype html") || prefix.startsWith("<html") ||
                                (store.get(id)?.mime?.startsWith("video/")==true && (prefix.startsWith("{") || prefix.startsWith("[")))) {
                                throw PermanentFailure("O link retornou uma página ou mensagem de erro, não o vídeo")
                            }
                        }
                        if(total>=0 && count+read>total) throw PermanentFailure("O servidor enviou mais dados que o tamanho informado")
                        output.write(buffer,0,read); count+=read
                        val now=android.os.SystemClock.elapsedRealtime()
                        if(now-committedAt>=1000) {
                            output.fd.sync()
                            val speed=(count-previous)*1000/(now-committedAt).coerceAtLeast(1)
                            store.mutate(id) { it.put("bytes",count).put("speed",speed) }
                            DownloadCaptureGuard.probe(context,store,id,file,count)
                            previous=count; committedAt=now
                        }
                    }
                }
                output.fd.sync()
            }
            store.mutate(id) { it.put("bytes",count).put("speed",0) }
            if(total>=0 && count!=total) throw IOException("Transferência interrompida antes do fim")
            if(count==0L) throw PermanentFailure("O arquivo recebido está vazio")
            if(!isDownloading(id)) return
            if(total<0) store.mutate(id) { it.put("total",count) }
            verifyAndSave(id,file)
        } catch(cancelled: CancellationException) {
            withContext(NonCancellable+Dispatchers.IO) {
                runCatching { store.mutate(id) { if(it.optString("state") in DownloadState.pending) it.put("state","QUEUED").put("speed",0) } }
            }
            throw cancelled
        } catch(error: Exception) {
            val item=store.get(id) ?: return
            if(item.state !in DownloadState.pending) return
            val attempts=item.json.optInt("attempts")+1
            val permanent=error is DownloadCaptureGuard.Rejected || error is PermanentFailure || error is SecurityException || error is IllegalArgumentException || attempts>=12
            store.mutate(id) {
                it.put("state",if(permanent) "ERROR" else "WAITING").put("attempts",attempts).put("speed",0)
                    .put("nextAttempt",System.currentTimeMillis()+minOf(60_000L,2000L*(1L shl attempts.coerceAtMost(5))))
                    .put("message",when(error) { is DownloadCaptureGuard.Rejected -> error.message; is PermanentFailure -> error.message; is SecurityException -> "Permissão de destino indisponível. Escolha novamente a pasta."; else -> if(permanent) "Não foi possível concluir. Confira a conexão e tente retomar." else "Aguardando conexão ou servidor; a retomada é automática." })
            }
        } finally { connection?.disconnect() }
    }
    private suspend fun receiveHls(id: String,url: String,headers: Map<String,String>,file: File): Boolean = try {
        DownloadHls(context,store).receive(id,url,headers,file)
    } catch(error: IllegalArgumentException) { throw PermanentFailure(error.message ?: "Playlist HLS incompatível") }
    catch(error: IllegalStateException) { throw PermanentFailure(error.message ?: "Playlist HLS incompatível") }
    private fun isDownloading(id: String)=store.get(id)?.state=="DOWNLOADING"
    private fun restart(id: String,file: File) {
        RandomAccessFile(file,"rw").use { it.setLength(0); it.fd.sync() }
        store.mutate(id) { it.put("generation",it.optLong("generation")+1).put("bytes",0).put("total",-1); it.remove("validator"); it.remove("serverHash"); it.remove("serverHashType");it.put("hlsIndex",0);it.remove("hlsFingerprint");if(it.has("playerCapture"))it.put("captureApproved",false).put("captureProbeStage",0) }
    }
    private suspend fun verifyAndSave(id: String,file: File) {
        val item=store.get(id) ?: return
        if(item.state !in DownloadState.pending) return
        DownloadCaptureGuard.probe(context,store,id,file,item.bytes,complete=true)
        store.mutate(id) { if(it.optString("state") in DownloadState.pending) it.put("state","VERIFYING") }
        // Reference-entry UI was removed. Honor a whole-file digest supplied by the server,
        // but do not hash every unreferenced file merely to display a checksum.
        val expected=item.json.optString("serverHash")
        if(expected.isNotBlank()) {
            val requested=MessageDigest.getInstance(item.json.optString("serverHashType","SHA-256"))
            file.inputStream().use {input ->
                val buffer=ByteArray(256*1024)
                while(true) {
                    currentCoroutineContext().ensureActive();if(store.get(id)?.state !in DownloadState.pending)return
                    val n=input.read(buffer);if(n<0)break;requested.update(buffer,0,n)
                }
            }
            val computed=requested.digest().joinToString("") {"%02x".format(it)}
            if(!computed.equals(expected,true)) {
                store.mutate(id){it.put("state","CORRUPT").put("message","O arquivo recebido não confere com o servidor. Tente baixar novamente.")}
                store.available(id,false);return
            }
        }
        if(store.get(id)?.state !in DownloadState.pending) return
        // Optional lossless remux only after downloading and verifying the TS.
        // Keep the original .part untouched so existing player readers can
        // finish; incompatible media simply retains the original TS file.
        val beforePublication=store.get(id) ?: return
        val isTs=beforePublication.mime.equals("video/mp2t",true) ||
            beforePublication.name.endsWith(".ts",true)
        val remuxed=if(isTs) DownloadTsRemux.tryRemux(context,file) else null
        try {
            if(remuxed!=null) {
                val original=beforePublication.name
                val mp4Name=original.substringBeforeLast('.',original)+".mp4"
                store.mutate(id) {
                    it.put("name",mp4Name).put("mime","video/mp4")
                }
                store.registerMedia(id)
            }
            if(store.get(id)?.state !in DownloadState.pending) return
            store.mutate(id) { if(it.optString("state") in DownloadState.pending) it.put("state","SAVING") }
            if(store.get(id)?.state!="SAVING") return
            val publishedFile=remuxed ?: file
            val destination=export(id,publishedFile)
            // Commit the final published file size, which may differ after
            // changing container headers without recompressing the video.
            store.complete(id,destination,publishedFile.length())
            file.delete() // Original TS is deleted only after durable publication.
        } finally { remuxed?.delete() }
    }
    private suspend fun export(id: String,file: File): Uri {
        var item=store.get(id)!!
        val tree=item.json.optString("destination")
        var output=item.output.takeIf { it.isNotBlank() }?.let(Uri::parse)
        if(output==null) {
            output=when {
                tree.isNotBlank() -> DocumentFile.fromTreeUri(context,Uri.parse(tree))?.createFile(item.mime, ".${item.name}.$id.part")?.uri
                    ?: throw PermanentFailure("Não foi possível criar o arquivo na pasta escolhida")
                Build.VERSION.SDK_INT>=29 -> context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME,item.name); put(MediaStore.Downloads.MIME_TYPE,item.mime)
                    put(MediaStore.Downloads.RELATIVE_PATH,"Download/ReiAnix"); put(MediaStore.Downloads.IS_PENDING,1)
                }) ?: throw PermanentFailure("Não foi possível criar o arquivo em Downloads")
                else -> {
                    val directory=File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: throw PermanentFailure("Armazenamento externo indisponível"),"ReiAnix").apply { mkdirs() }
                    var name=File(directory,item.name); var suffix=1
                    while(name.exists()) { name=File(directory,"${item.name.substringBeforeLast('.',item.name)} (${suffix++}).${item.name.substringAfterLast('.',"bin")}") }
                    check(name.createNewFile()); Uri.fromFile(name)
                }
            }
            store.mutate(id) { it.put("output",output.toString()) }
            item=store.get(id)!!
        }
        val target=output!!
        val stream=if(target.scheme=="file") FileOutputStream(File(target.path!!),false) else context.contentResolver.openOutputStream(target,"wt")
        (stream ?: throw PermanentFailure("Não foi possível abrir o arquivo de destino")).use { out ->
            file.inputStream().use { input ->
                val buffer=ByteArray(256*1024)
                while(true) {
                    currentCoroutineContext().ensureActive()
                    // Completion owns this short publish step; pause/cancel before it remains honored.
                    val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n)
                }
            }
            out.flush()
        }
        if(target.scheme=="file") RandomAccessFile(File(target.path!!),"rw").use { it.fd.sync() }
        val keys=DownloadCatalogBridge.fileKeys(context,target.toString())
        store.mutate(id){it.put("outputKeys",org.json.JSONArray(keys.toList())).put("outputKeysAt",System.currentTimeMillis())}
        if(tree.isNotBlank()) {
            val parent=DocumentFile.fromTreeUri(context,Uri.parse(tree)) ?: throw PermanentFailure("Pasta de destino indisponível")
            var finalName=item.name; var suffix=1
            val stem=item.name.substringBeforeLast('.',item.name); val extension=item.name.substringAfterLast('.',"")
            while(parent.findFile(finalName)!=null) {
                finalName="$stem (${suffix++})"+(if(extension.isBlank()) "" else ".$extension")
                if(suffix>1000) throw PermanentFailure("Há muitos arquivos com esse nome; escolha outro nome")
            }
            val renamed=DocumentsContract.renameDocument(context.contentResolver,target,finalName) ?: throw PermanentFailure("Não foi possível concluir o nome do arquivo")
            store.mutate(id) { it.put("output",renamed.toString()) }
            return renamed
        }
        if(Build.VERSION.SDK_INT>=29 && target.scheme=="content") {
            // Publish only after the full video has been flushed. Android can
            // then index the final display name, MIME and media thumbnail.
            val published=context.contentResolver.update(target,ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME,item.name)
                put(MediaStore.Downloads.MIME_TYPE,item.mime)
                put(MediaStore.Downloads.IS_PENDING,0)
            },null,null)
            if(published<=0) throw PermanentFailure("Não foi possível publicar o arquivo concluído no Android")
            runCatching {
                val displayName=context.contentResolver.query(
                    target,arrayOf(MediaStore.Downloads.DISPLAY_NAME),null,null,null
                )?.use { cursor -> if(cursor.moveToFirst()) cursor.getString(0) else null }
                if(!displayName.isNullOrBlank()) {
                    val publicFile=File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "ReiAnix/$displayName",
                    )
                    android.media.MediaScannerConnection.scanFile(
                        context,arrayOf(publicFile.absolutePath),arrayOf(item.mime),null
                    )
                }
            }.onFailure { android.util.Log.w("ReiAnixDownload","Android media index refresh failed",it) }
        }
        return target
    }
    private fun serverHash(connection: HttpURLConnection): Pair<String,String>? {
        connection.getHeaderField("Digest")?.split(',')?.forEach { raw ->
            val type=raw.substringBefore('=').trim().lowercase(); val value=raw.substringAfter('=',"").trim().trim(':','"')
            val algo=when(type) { "sha-256" -> "SHA-256"; "sha" -> "SHA-1"; "md5" -> "MD5"; else -> null }
            if(algo!=null) runCatching { Base64.decode(value,Base64.DEFAULT) }.getOrNull()?.let { bytes ->
                val size=when(algo) { "SHA-256"->32;"SHA-1"->20;else->16 }
                if(bytes.size==size) return bytes.joinToString("") { "%02x".format(it) } to algo
            }
        }
        return connection.getHeaderField("Content-MD5")?.let { raw -> runCatching { Base64.decode(raw,Base64.DEFAULT) }.getOrNull()?.takeIf { it.size==16 }?.let { bytes -> bytes.joinToString("") { "%02x".format(it) } to "MD5" } }
    }
    private class PermanentFailure(message: String): IOException(message)
    companion object { private val runner=Mutex() }
}
