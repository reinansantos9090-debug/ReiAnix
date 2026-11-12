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
    data class Segment(val url: String,val key: String?,val iv: String?,val sequence: Long)
    data class Playlist(val segments: List<Segment>,val init: String?,val duration: Double)
    companion object {
        fun attributes(line: String): Map<String,String> = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)").findAll(line.substringAfter(':')).associate {it.groupValues[1] to it.groupValues[2].trim('"')}
        fun parse(text: String,url: String): Playlist {
            require(text.trimStart().startsWith("#EXTM3U")) {"Playlist HLS inválida"}
            require(text.contains("#EXT-X-ENDLIST")) {"Transmissões ao vivo ainda não são suportadas para download"}
            require(!text.contains("#EXT-X-BYTERANGE")) {"Esta playlist usa faixas internas ainda não suportadas"}
            var key: String?=null; var iv: String?=null; var seq=0L; var init: String?=null
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
                    line.startsWith("#EXT-X-MAP:") -> {
                        val a=attributes(line); require(a["BYTERANGE"]==null && key==null) {"Inicialização HLS protegida ou por faixa não suportada"}
                        val next=URL(URL(url),a["URI"] ?: error("Inicialização ausente")).toString()
                        require(init==null || init==next) {"Playlist com mudanças de inicialização não suportada"}; init=next
                    }
                    line.startsWith("#EXT-X-DISCONTINUITY") -> error("Playlist com mudanças de linha do tempo não suportada")
                    !line.startsWith('#') -> {require(segments.size<20000); segments+=Segment(URL(URL(url),line).toString(),key,iv,seq++)}
                }
            }
            require(segments.isNotEmpty()); return Playlist(segments,init,duration)
        }
    }
    internal suspend fun receive(id: String,url: String,headers: Map<String,String>,target: DownloadTransferFile): Boolean {
        suspend fun fetch(link: String,max: Int): ByteArray = withContext(Dispatchers.IO) {
            val c=DownloadHttp.connect(link,headers,0,null,credentialOrigin=url)
            try {
                require(c.responseCode==200) {"Não foi possível obter parte da playlist"}
                c.inputStream.use { input ->
                    val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(64*1024)
                    while(true) { currentCoroutineContext().ensureActive(); if(store.get(id)?.state !in DownloadState.pending) throw Paused()
                        val n=input.read(buffer); if(n<0)break; require(out.size().toLong()+n<=max) {"Parte da playlist excede o limite seguro"}; out.write(buffer,0,n) }
                    out.toByteArray()
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
                require(chosen.third==null) {"Playlist com áudio separado ainda não suportada"}
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
            DownloadCaptureGuard.approveHls(context,store,id,playlist.duration)
            store.registerMedia(id)
            store.get(id)?.takeIf {it.json.optBoolean("identityKnown")}?.let {DownloadMetadata.offer(context,it.animeId)}
            var count=item.bytes; var index=item.json.optInt("hlsIndex")
            val keys=mutableMapOf<String,ByteArray>()
            target.open().use { out ->
                out.setLength(count); out.seek(count)
                if(count==0L && playlist.init!=null) {val bytes=fetch(playlist.init,16*1024*1024); out.write(bytes);out.sync();count=bytes.size.toLong();store.mutate(id){it.put("bytes",count)}}
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
                        val c=DownloadHttp.connect(segment.url,headers,0,null,credentialOrigin=url)
                        try {
                            require(c.responseCode==200) {"Não foi possível obter o segmento"}
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
                            require(received>0 && (c.contentLengthLong<0 || received==c.contentLengthLong)) {"Segmento incompleto"}
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
