package com.reianix.reianix_local.downloads

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

/** Optional DASH VOD to MP4 path. Never treats the downloaded MPD XML as a video. */
internal class DownloadAdaptiveTransfer(
    private val context:Context,
    private val store:DownloadStore
) {
    class Unsupported(message:String):IllegalArgumentException(message)
    suspend fun receiveDash(id:String,url:String,target:DownloadTransferFile):Boolean {
        val converted=DownloadMediaTranscoder.tryAdaptiveDash(context,url)
            ?: throw Unsupported("Esta fonte DASH não pôde ser convertida em MP4 neste aparelho. Escolha outra qualidade compatível.")
        try {
            if(store.get(id)?.state!="DOWNLOADING")return false
            var received=0L
            target.open().use {file ->
                file.setLength(0)
                file.seek(0)
                converted.inputStream().use {input ->
                    val buffer=ByteArray(256*1024)
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        if(store.get(id)?.state!="DOWNLOADING") {
                            file.setLength(0)
                            return false
                        }
                        val n=input.read(buffer)
                        if(n<0)break
                        file.write(buffer,0,n);received+=n
                    }
                }
                file.sync()
            }
            if(received<=0 || received!=converted.length())
                throw IOException("DASH: MP4 gerado está incompleto")
            val duration=runCatching {
                val retriever=MediaMetadataRetriever()
                try {
                    retriever.setDataSource(converted.absolutePath)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toDoubleOrNull()?.div(1000.0) ?: 0.0
                } finally {retriever.release()}
            }.getOrDefault(0.0)
            val item=store.get(id) ?: return false
            val name=DownloadContainerDetector.outputName(item.name,"mp4")
            store.mutate(id) { json ->
                json.put("name",name).put("mime","video/mp4").put("bytes",received)
                    .put("total",received).put("speed",0).put("serverNameResolved",true)
                    .put("adaptiveDash",true)
                if(json.optLong("episodeId")==0L)
                    json.put("virtualUri","content://"+context.packageName+
                        ".downloads/"+id+"/"+Uri.encode(name))
            }
            // The capture guard validates the finished local MP4 by its media
            // tracks. The original DASH manifest is never registered as video.
            DownloadCaptureGuard.approveHls(context,store,id,duration)
            runCatching {target.syncName()}
            store.registerMedia(id)
            store.get(id)?.takeIf {it.json.optBoolean("identityKnown")}?.let {
                DownloadMetadata.offer(context,it.animeId)
            }
            return true
        } catch(cancelled:CancellationException) {throw cancelled}
        finally {converted.delete()}
    }
}
