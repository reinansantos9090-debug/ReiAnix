package com.reianix.reianix_local.downloads

import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Small parallel waves, committed in order: the player's available prefix is always contiguous. */
internal object DownloadRanges {
    suspend fun receive(context: android.content.Context,store: DownloadStore,id: String,url: String,headers: Map<String,String>,target: DownloadTransferFile,start: Long,total: Long,validator: String,connections: Int): Boolean = coroutineScope {
        var position=start
        target.open().use { out ->
            out.setLength(start);out.seek(start)
            while(position<total) {
                ensureActive(); if(store.get(id)?.state!="DOWNLOADING")return@coroutineScope false
                val time=android.os.SystemClock.elapsedRealtime(); val beginning=position
                val ranges=(0 until connections).map { beginning+it*1024L*1024 }.filter {it<total}.map { it to minOf(total-1,it+1024*1024-1) }
                val parts=ranges.map { (offset,end) -> async(Dispatchers.IO) {
                    val c=DownloadHttp.connect(url,headers,offset,validator,end)
                    try {
                        val r=DownloadHttp.range(c.getHeaderField("Content-Range"))
                        if(c.responseCode!=206 || r?.start!=offset || r.end!=end || r.total!=total || c.getHeaderField("ETag")!=validator)throw IOException("Servidor não confirmou as faixas paralelas")
                        if(c.getHeaderField("Content-Encoding")?.let {it!="identity"}==true)throw IOException("Representação comprimida")
                        val bytes=ByteArray((end-offset+1).toInt());var read=0
                        c.inputStream.use { input ->
                            while(read<bytes.size) {ensureActive();if(store.get(id)?.state!="DOWNLOADING")throw Paused();val n=input.read(bytes,read,bytes.size-read);if(n<0)throw IOException("Faixa incompleta");read+=n}
                            if(input.read()!=-1)throw IOException("Faixa maior que o esperado")
                        };bytes
                    } finally {c.disconnect()}
                } }
                val values=parts.awaitAll()
                if(store.get(id)?.state!="DOWNLOADING")return@coroutineScope false
                values.forEach {out.write(it);position+=it.size};out.sync()
                store.mutate(id){it.put("bytes",position).put("speed",(position-beginning)*1000/(android.os.SystemClock.elapsedRealtime()-time).coerceAtLeast(1))}
                DownloadCaptureGuard.probe(context,store,id,target.readUri(),position)
            }
        }
        true
    }
    class Paused: IOException()
}
