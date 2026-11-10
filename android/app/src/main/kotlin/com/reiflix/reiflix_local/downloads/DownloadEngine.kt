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
                    val limit=(ReiAnixAppDependencies.from(context).sqliteSettingsRepository.getPreference("downloads.parallel","2")?.toIntOrNull() ?: 2).coerceIn(1,4)
                    jobs.filter { it.id !in active && it.state in DownloadState.pending && it.json.optLong("nextAttempt")<=System.currentTimeMillis() }
                        .sortedBy { it.json.optLong("created") }.take((limit-active.size).coerceAtLeast(0)).forEach { item ->
                            active[item.id]=launch { transfer(item.id) }
                        }
                    if(active.isEmpty() && jobs.none { it.state in DownloadState.pending }) break
                    onProgress("${active.size} download(s) • ${android.text.format.Formatter.formatFileSize(context,jobs.filter { it.state in DownloadState.pending }.sumOf { it.bytes })} recebidos")
                    delay(1000)
                }
                active.values.forEach { it.cancel() }
                active.values.joinAll()
            }
            store.all().any { it.state in DownloadState.pending }
        } finally { DownloadSnapshots.refresh(context); runner.unlock() }
    }
    private suspend fun transfer(id: String) {
        var connection: HttpURLConnection?=null
        try {
            var item=store.get(id) ?: return
            if(item.state !in DownloadState.pending) return
            val file=store.partial(id)
            if(item.json.optBoolean("restart")) {
                RandomAccessFile(file,"rw").use { it.setLength(0) }
                item=store.mutate(id) { it.put("generation",it.optLong("generation")+1).put("bytes",0).put("total",-1).put("restart",false).remove("validator"); it.remove("serverHash") }
            }
            // A crash can leave uncommitted bytes. Only persisted, fsynced bytes are reusable.
            val offset=minOf(item.bytes,file.length())
            RandomAccessFile(file,"rw").use { it.setLength(offset) }
            val secret=store.secret(item); val headers=secret.optJSONObject("headers") ?: JSONObject()
            val requestHeaders=headers.keys().asSequence().associateWith { headers.getString(it) }
            var resume=offset
            var validator=item.json.optString("validator").takeIf { it.isNotBlank() }
            if(resume>0 && validator==null) { restart(id,file); resume=0 }
            store.mutate(id) { if(it.optString("state") in DownloadState.pending) it.put("state","DOWNLOADING").put("message","") }
            if(!isDownloading(id)) return
            // Fully received files interrupted during verification/export need no redownload.
            if(item.total>=0 && resume==item.total && resume>0) {
                verifyAndSave(id,file); return
            }
            connection=DownloadHttp.connect(secret.getString("url"),requestHeaders,resume,validator)
            val code=connection.responseCode
            if(code==401 || code==403) throw PermanentFailure("Autenticação necessária ou link expirado. Atualize o link ou a sessão.")
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
            if(contentType in setOf("text/html","application/xhtml+xml")) throw PermanentFailure("Este link abre uma página. Informe o link direto do arquivo.")
            if(contentType.contains("mpegurl",true) || contentType.contains("dash+xml",true)) throw PermanentFailure("Esta fonte é uma playlist HLS/DASH; use o link direto de um arquivo de vídeo.")
            if(total>0 && StatFs(store.directory.path).availableBytes < (total-resume).coerceAtLeast(0)+16L*1024*1024) throw PermanentFailure("Espaço insuficiente para baixar o arquivo")
            val serverHash=serverHash(connection)
            store.mutate(id) {
                if(it.optBoolean("automaticName") && it.optLong("episodeId")==0L) {
                    val proposed=android.webkit.URLUtil.guessFileName(secret.getString("url"),connection.getHeaderField("Content-Disposition"),contentType)
                    if(proposed.isNotBlank() && proposed.length<=200 && proposed.none { c -> c=='/' || c=='\\' || c.code<32 }) {
                        it.put("name",proposed).put("virtualUri","content://${context.packageName}.downloads/$id/${Uri.encode(proposed)}")
                    }
                }
                it.put("total",total).put("validator",newValidator ?: "")
                if(contentType.startsWith("video/")) it.put("mime",contentType)
                if(serverHash!=null) it.put("serverHash",serverHash.first).put("serverHashType",serverHash.second)
            }
            try { store.registerMedia(id) } catch(_: IllegalArgumentException) { throw PermanentFailure("Já existe um episódio com este nome na biblioteca. Altere o nome antes de adicionar outro download.") }
            DownloadMetadata.offer(context,store.get(id)?.animeId ?: 0)
            var count=resume; var committedAt=android.os.SystemClock.elapsedRealtime(); var previous=resume; var checkedAt=0L
            RandomAccessFile(file,"rw").use { output ->
                output.seek(resume)
                connection.inputStream.use { input ->
                    val buffer=ByteArray(128*1024)
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
            val permanent=error is PermanentFailure || error is SecurityException || error is IllegalArgumentException || attempts>=12
            store.mutate(id) {
                it.put("state",if(permanent) "ERROR" else "WAITING").put("attempts",attempts).put("speed",0)
                    .put("nextAttempt",System.currentTimeMillis()+minOf(60_000L,2000L*(1L shl attempts.coerceAtMost(5))))
                    .put("message",when(error) { is PermanentFailure -> error.message; is SecurityException -> "Permissão de destino indisponível. Escolha novamente a pasta."; else -> if(permanent) "Não foi possível concluir. Confira a conexão e tente retomar." else "Aguardando conexão ou servidor; a retomada é automática." })
            }
        } finally { connection?.disconnect() }
    }
    private fun isDownloading(id: String)=store.get(id)?.state=="DOWNLOADING"
    private fun restart(id: String,file: File) {
        RandomAccessFile(file,"rw").use { it.setLength(0); it.fd.sync() }
        store.mutate(id) { it.put("generation",it.optLong("generation")+1).put("bytes",0).put("total",-1) }
    }
    private suspend fun verifyAndSave(id: String,file: File) {
        val item=store.get(id) ?: return
        if(item.state !in DownloadState.pending) return
        store.mutate(id) { if(it.optString("state") in DownloadState.pending) it.put("state","VERIFYING") }
        val expected=item.json.optString("expectedHash").ifBlank { item.json.optString("serverHash") }
        val algorithm=if(item.json.optString("expectedHash").isNotBlank()) item.json.optString("hashType","SHA-256") else item.json.optString("serverHashType","SHA-256")
        val requested=MessageDigest.getInstance(algorithm); val sha=if(algorithm=="SHA-256") requested else MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer=ByteArray(256*1024)
            while(true) {
                currentCoroutineContext().ensureActive()
                if(store.get(id)?.state !in DownloadState.pending) return
                val n=input.read(buffer); if(n<0) break
                requested.update(buffer,0,n); if(sha!==requested) sha.update(buffer,0,n)
            }
        }
        val computed=requested.digest().joinToString("") { "%02x".format(it) }
        val sha256=if(sha===requested) computed else sha.digest().joinToString("") { "%02x".format(it) }
        store.mutate(id) { it.put("sha256",sha256).put("integrity",if(expected.isBlank()) "SHA-256 calculado; sem referência para comparar" else "$algorithm verificado") }
        if(expected.isNotBlank() && !computed.equals(expected,true)) {
            store.mutate(id) { it.put("state","CORRUPT").put("message","O hash não confere. O arquivo não foi publicado na pasta de destino.") }
            store.available(id,false); return
        }
        if(store.get(id)?.state !in DownloadState.pending) return
        store.mutate(id) { if(it.optString("state") in DownloadState.pending) it.put("state","SAVING") }
        if(store.get(id)?.state!="SAVING") return
        val destination=export(id,file)
        // Once export is durable, publish exactly that URI and preserve the episode/progress ID.
        store.complete(id,destination)
        file.delete() // Open player file descriptors remain valid; new opens use the final URI.
    }
    private suspend fun export(id: String,file: File): Uri {
        var item=store.get(id)!!
        val tree=item.json.optString("destination")
        var output=item.output.takeIf { it.isNotBlank() }?.let(Uri::parse)
        if(output==null) {
            output=when {
                tree.isNotBlank() -> DocumentFile.fromTreeUri(context,Uri.parse(tree))?.createFile("application/octet-stream", ".${item.name}.$id.part")?.uri
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
        if(Build.VERSION.SDK_INT>=29 && target.scheme=="content") context.contentResolver.update(target,ContentValues().apply { put(MediaStore.Downloads.IS_PENDING,0) },null,null)
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
