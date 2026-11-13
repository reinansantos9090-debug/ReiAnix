package com.reianix.reianix_local.downloads

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** VOD HLS: one muxed rendition, TS or fMP4, with optional identity AES-128 encryption. */
class DownloadHls(private val context: Context,private val store: DownloadStore) {
    data class ByteSpan(val start:Long,val length:Long) {
        init {require(start>=0 && length>0 && start<=Long.MAX_VALUE-length)}
        val end:Long get() = start+length-1
    }
    data class Segment(val url: String,val key: String?,val iv: String?,val sequence: Long,
        val byteSpan:ByteSpan?=null)
    data class Playlist(val segments: List<Segment>,val init: String?,val duration: Double,val initByteSpan:ByteSpan?=null)
    companion object {
        fun attributes(line: String): Map<String,String> = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)").findAll(line.substringAfter(':')).associate {it.groupValues[1] to it.groupValues[2].trim('"')}
        /** RFC 8216: choose the default audio rendition of the selected group. */
        fun audioRenditionUrl(master: String, masterUrl: String, group: String): String? {
            val matches=master.lineSequence().map(String::trim)
                .filter {it.startsWith("#EXT-X-MEDIA:",true)}
                .map(::attributes).filter {
                    it["TYPE"]?.equals("AUDIO",true)==true && it["GROUP-ID"]==group &&
                        !it["URI"].isNullOrBlank()
                }.toList()
            val chosen=matches.firstOrNull {it["DEFAULT"]?.equals("YES",true)==true}
                ?: matches.firstOrNull() ?: return null
            val resolved=runCatching {URL(URL(masterUrl),chosen.getValue("URI")).toString()}.getOrNull()
                ?: return null
            return resolved.takeIf {runCatching {DownloadHttp.validateUrl(it)}.isSuccess}
        }

        fun parseByteSpan(value:String,previous:ByteSpan?,sameResource:Boolean):ByteSpan {
            val parts=value.trim().split('@')
            require(parts.size in 1..2) {"Faixa HLS inválida"}
            val bytes=parts[0].toLongOrNull()?.takeIf{it>0}
                ?: throw IllegalArgumentException("Faixa HLS inválida")
            val start=if(parts.size==2) parts[1].toLongOrNull()
                else if(sameResource) previous?.end?.plus(1) else null
            require(start!=null && start>=0 && start<=Long.MAX_VALUE-bytes) {
                "Playlist HLS não informa deslocamento de faixa seguro"
            }
            return ByteSpan(start,bytes)
        }

        fun parse(text: String,url: String): Playlist {
            require(text.trimStart().startsWith("#EXTM3U")) {"Playlist HLS inválida"}
            require(text.contains("#EXT-X-ENDLIST")) {"Transmissões ao vivo ainda não são suportadas para download"}
            var key: String?=null; var iv: String?=null; var seq=0L
            var init:String?=null; var initSpan:ByteSpan?=null
            var pendingRange:String?=null
            var previousSpan:ByteSpan?=null;var previousUri:String?=null
            val segments=mutableListOf<Segment>(); var duration=0.0
            text.lineSequence().map {it.trim()}.filter {it.isNotBlank()}.forEach { line ->
                when {
                    line.startsWith("#EXTINF:") -> {val seconds=line.substringAfter(':').substringBefore(',').toDouble();require(seconds.isFinite() && seconds>=0);duration+=seconds}
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq=line.substringAfter(':').toLong().also {require(it>=0)}
                    line.startsWith("#EXT-X-KEY:") -> {
                        val a=attributes(line)
                        require(a["METHOD"] in setOf("NONE","AES-128") && a["KEYFORMAT"].let {it==null || it=="identity"}) {"Vídeo com proteção DRM não é suportado"}
                        key=if(a["METHOD"]=="NONE")null else URL(URL(url),a["URI"] ?: error("Chave HLS ausente")).toString(); iv=a["IV"]
                    }
                    line.startsWith("#EXT-X-BYTERANGE:") -> {
                        pendingRange=line.substringAfter(':').trim()
                    }
                    line.startsWith("#EXT-X-MAP:") -> {
                        val a=attributes(line)
                        require(key==null) {"Inicialização HLS protegida não suportada"}
                        val next=URL(URL(url),a["URI"] ?: error("Inicialização ausente")).toString()
                        val span=a["BYTERANGE"]?.let{parseByteSpan(it,null,false)}
                        require(init==null || (init==next && initSpan==span)) {
                            "Playlist com mudanças de inicialização não suportada"
                        }
                        init=next;initSpan=span
                    }
                    line.startsWith("#EXT-X-DISCONTINUITY") -> error("Playlist com mudanças de linha do tempo não suportada")
                    line.isNotBlank() && !line.startsWith('#') -> {
                        require(segments.size<20000)
                        val resolved=URL(URL(url),line).toString()
                        val span=pendingRange?.let {
                            parseByteSpan(it,previousSpan,previousUri==resolved)
                        }
                        segments+=Segment(resolved,key,iv,seq++,span)
                        pendingRange=null
                        previousSpan=span;previousUri=resolved
                    }
                }
            }
            require(segments.isNotEmpty() && pendingRange==null)
            return Playlist(segments,init,duration,initSpan)
        }
    }
    internal suspend fun receive(id: String,url: String,headers: Map<String,String>,target: DownloadTransferFile): Boolean {
        suspend fun fetch(link: String,max: Int,span:ByteSpan?=null): ByteArray =
            withContext(Dispatchers.IO) {
            val c=DownloadHttp.connect(link,headers,span?.start ?: 0L,null,
                end=span?.end,credentialOrigin=url,allowMediaHttpDowngrade=true,
                redirectCookieProvider=DownloadSessionHeaders::cookieForTarget)
            try {
                require(c.responseCode==if(span==null)200 else 206) {
                    "Resposta HTTP não corresponde à faixa HLS solicitada"
                }
                if(span!=null) {
                    val returned=DownloadHttp.range(c.getHeaderField("Content-Range"))
                    require(returned!=null && returned.start==span.start &&
                        returned.end==span.end && span.length<=max) {
                        "Servidor não retornou a faixa HLS correta"
                    }
                }
                c.inputStream.use { input ->
                    val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(64*1024)
                    while(true) { currentCoroutineContext().ensureActive(); if(store.get(id)?.state !in DownloadState.pending) throw Paused()
                        val n=input.read(buffer); if(n<0)break; require(out.size().toLong()+n<=max) {"Parte da playlist excede o limite seguro"}; out.write(buffer,0,n) }
                    val result=out.toByteArray()
                    require(span==null || result.size.toLong()==span.length) {
                        "Faixa HLS incompleta"
                    }
                    result
                }
            } finally {c.disconnect()}
        }
        try {
            var manifest=url; var text=String(fetch(manifest,2*1024*1024),Charsets.UTF_8)
            var depth=0
            while(text.contains("#EXT-X-STREAM-INF:")) {
                require(depth++<4) {"Playlists aninhadas demais"}
                val lines=text.lines(); val variants=lines.indices.filter {lines[it].startsWith("#EXT-X-STREAM-INF:")}.mapNotNull { i ->
                    val a=attributes(lines[i]); val relative=lines.drop(i+1).firstOrNull {it.isNotBlank() && !it.startsWith('#')} ?: return@mapNotNull null
                    Triple(a["BANDWIDTH"]?.toLongOrNull() ?: 0,URL(URL(manifest),relative.trim()).toString(),a["AUDIO"])
                }
                val chosen=variants.maxByOrNull {it.first} ?: error("Nenhuma qualidade disponível")
                if(chosen.third!=null) {
                    val audio=audioRenditionUrl(text,manifest,chosen.third!!)
                        ?: throw IllegalStateException("Playlist HLS com faixa de áudio inválida")
                    // Export only finite VOD, not live streams of unknown duration.
                    val videoText=String(fetch(chosen.second,2*1024*1024),Charsets.UTF_8)
                    val audioText=String(fetch(audio,2*1024*1024),Charsets.UTF_8)
                    require(videoText.contains("#EXT-X-ENDLIST") && audioText.contains("#EXT-X-ENDLIST")) {
                        "A conversão de playlists ao vivo não está disponível"
                    }
                    val converted=DownloadMediaTranscoder.tryAdaptiveHls(context,url)
                        ?: throw IllegalStateException("Este aparelho ou servidor não permitiu montar as faixas HLS em MP4")
                    try {
                        if(store.get(id)?.state!="DOWNLOADING")return false
                        var copied=0L
                        target.open().use {out ->
                            out.setLength(0);out.seek(0)
                            converted.inputStream().use {input ->
                                val buffer=ByteArray(256*1024)
                                while(true) {
                                    currentCoroutineContext().ensureActive()
                                    if(store.get(id)?.state!="DOWNLOADING")throw Paused()
                                    val n=input.read(buffer)
                                    if(n<0)break
                                    out.write(buffer,0,n);copied+=n
                                }
                            }
                            out.sync()
                        }
                        require(copied==converted.length() && copied>0L) {"MP4 HLS incompleto"}
                        val duration=runCatching {
                            val meta=android.media.MediaMetadataRetriever()
                            try {
                                meta.setDataSource(converted.absolutePath)
                                meta.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                                    ?.toDoubleOrNull()?.div(1000.0) ?: 0.0
                            } finally {meta.release()}
                        }.getOrDefault(0.0)
                        val current=store.get(id) ?: return false
                        val outputName=DownloadContainerDetector.outputName(current.name,"mp4")
                        store.mutate(id) {
                            it.put("hls",true).put("mime","video/mp4").put("name",outputName)
                                .put("serverNameResolved",true).put("bytes",copied).put("total",copied)
                                .put("hlsCount",1).put("hlsIndex",1).put("speed",0)
                            if(it.optLong("episodeId")==0L)
                                it.put("virtualUri","content://" + context.packageName +
                                    ".downloads/" + id + "/" + Uri.encode(outputName))
                        }
                        DownloadCaptureGuard.approveHls(context,store,id,duration)
                        runCatching {target.syncName()}
                        store.registerMedia(id)
                        store.get(id)?.takeIf {it.json.optBoolean("identityKnown")}?.let {
                            DownloadMetadata.offer(context,it.animeId)
                        }
                        return true
                    } finally {converted.delete()}
                }
                manifest=chosen.second; text=String(fetch(manifest,2*1024*1024),Charsets.UTF_8)
            }
            val playlist=parse(text,manifest)
            val fingerprint=DownloadStore.digest(text.toByteArray())
            var item=store.get(id) ?: return false
            if(item.bytes>target.size() || item.json.optString("hlsFingerprint")!=fingerprint) {
                target.open().use {it.setLength(0);it.sync()}
                item=store.mutate(id) { it.put("bytes",0).put("hlsIndex",0).put("generation",it.optLong("generation")+1).put("hlsFingerprint",fingerprint).put("hlsEstimatedTotal",0) }
            }
            val extension=if(playlist.init==null) "ts" else "mp4"
            val base=item.name.substringBeforeLast('.',item.name)
            store.mutate(id) {
                it.put("hls",true).put("mime",if(extension=="ts") "video/mp2t" else "video/mp4").put("total",-1).put("hlsCount",playlist.segments.size)
                it.put("name","$base.$extension")
                if(it.optLong("episodeId")==0L) it.put("virtualUri","content://${context.packageName}.downloads/$id/${Uri.encode("$base.$extension")}")
            }
            // Playlist detection may change .mp4 to .ts (or vice versa).
            // Keep the visible My Files entry in sync with the selected extension.
            runCatching { target.syncName() }
            DownloadCaptureGuard.approveHls(context,store,id,playlist.duration)
            store.registerMedia(id)
            store.get(id)?.takeIf {it.json.optBoolean("identityKnown")}?.let {DownloadMetadata.offer(context,it.animeId)}
            var count=item.bytes; var index=item.json.optInt("hlsIndex")
            val keys=mutableMapOf<String,ByteArray>()
            target.open().use { out ->
                out.setLength(count); out.seek(count)
                if(count==0L && playlist.init!=null) {val bytes=fetch(playlist.init,16*1024*1024,playlist.initByteSpan); out.write(bytes);out.sync();count=bytes.size.toLong();store.mutate(id){it.put("bytes",count)}}
                while(index<playlist.segments.size) {
                    currentCoroutineContext().ensureActive(); if(store.get(id)?.state!="DOWNLOADING")return false
                    val start=android.os.SystemClock.elapsedRealtime();val segment=playlist.segments[index]
                    // Stream each HLS segment directly to the public target. An
                    // interrupted or corrupt segment is truncated back to the
                    // last committed boundary; no full segment is cached in app data.
                    val segmentStart=count
                    var received=0L
                    try {
                        val cipher=if(segment.key!=null) {
                            val key=keys[segment.key] ?: fetch(segment.key,16).also {require(it.size==16);keys[segment.key]=it}
                            val iv=ByteArray(16)
                            if(segment.iv!=null) {
                                val hex=segment.iv.removePrefix("0x").padStart(32,'0')
                                require(hex.length==32)
                                for(i in 0..15)iv[i]=hex.substring(i*2,i*2+2).toInt(16).toByte()
                            } else for(i in 0..7)iv[15-i]=(segment.sequence ushr (8*i)).toByte()
                            Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                                init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),IvParameterSpec(iv))
                            }
                        } else null
                        val span=segment.byteSpan
                        val c=DownloadHttp.connect(segment.url,headers,span?.start ?: 0L,null,
                            end=span?.end,credentialOrigin=url,allowMediaHttpDowngrade=true,
                redirectCookieProvider=DownloadSessionHeaders::cookieForTarget)
                        try {
                            require(c.responseCode==if(span==null)200 else 206) {
                                "O servidor não forneceu o intervalo do segmento HLS"
                            }
                            if(span!=null) {
                                val actual=DownloadHttp.range(c.getHeaderField("Content-Range"))
                                require(actual!=null && actual.start==span.start && actual.end==span.end) {
                                    "O servidor retornou uma faixa HLS incorreta"
                                }
                            }
                            val counting=object:java.io.FilterInputStream(c.inputStream) {
                                override fun read():Int = super.read().also {if(it>=0) received++}
                                override fun read(buffer:ByteArray,off:Int,len:Int):Int =
                                    super.read(buffer,off,len).also {if(it>0)received+=it}
                            }
                            (if(cipher==null) counting else javax.crypto.CipherInputStream(counting,cipher)).use { input ->
                                val buffer=ByteArray(128*1024)
                                while(true) {
                                    currentCoroutineContext().ensureActive()
                                    if(store.get(id)?.state!="DOWNLOADING") throw Paused()
                                    val n=input.read(buffer)
                                    if(n<0)break
                                    require(received<=1024L*1024*1024) {"Segmento excede o limite seguro"}
                                    out.write(buffer,0,n)
                                }
                            }
                            require(received>0 && (c.contentLengthLong<0 || received==c.contentLengthLong) &&
                                (span==null || received==span.length)) {"Segmento incompleto"}
                            out.sync()
                            count=out.filePointer
                            index++
                        } finally {c.disconnect()}
                    } catch(error:Exception) {
                        out.setLength(segmentStart);out.seek(segmentStart);out.sync()
                        throw error
                    }
                    val speed=received*1000L/(android.os.SystemClock.elapsedRealtime()-start).coerceAtLeast(1)
                    store.mutate(id){it.put("bytes",count).put("hlsIndex",index).put("speed",speed)
                        if(index>=3) it.put("hlsEstimatedTotal",(count.toDouble()*playlist.segments.size/index).toLong())}
                }
            }
            store.mutate(id){it.put("total",count).put("speed",0)}
            return true
        } catch(_: Paused) {return false}
    }
    private class Paused: IOException()
}
