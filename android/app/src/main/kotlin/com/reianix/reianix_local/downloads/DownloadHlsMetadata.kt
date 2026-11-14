package com.reianix.reianix_local.downloads

import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

/**
 * RFC 8216 metadata-only VOD analysis, not a second HLS downloader.
 * 23 average bandwidth; 25 segment totals when available; 27 duration-weighted
 * samples; 29 MAP; 31 crypto overhead; 32 LL-HLS parts; 34 bounded cache.
 */
internal object DownloadHlsMetadata {
    data class Piece(val url:String,val duration:Double,val length:Long?,
        val encrypted:Boolean,val partial:Boolean=false)
    data class Plan(val pieces:List<Piece>,val initUrl:String?,
        val initLength:Long?,val duration:Double,val encrypted:Boolean,
        val allByteRanges:Boolean,val partCount:Int) {
        val knownRangeSize:Long? get() {
            if(pieces.isEmpty() || !allByteRanges ||
                pieces.any{it.length==null} || (initUrl!=null && initLength==null))return null
            val total=pieces.sumOf {it.length ?: 0L}
            return (total+(initLength?:0L)).takeIf{it>0}
        }
    }

    private data class Entry(val result:Long,val at:Long)
    private val cache=ConcurrentHashMap<String,Entry>()
    private const val TTL=60_000L
    fun rememberSize(url:String,bytes:Long) {
        if(bytes<=0 || url.length>5000)return
        if(cache.size>320)cache.clear()
        cache[url]=Entry(bytes,System.currentTimeMillis())
    }
    fun cachedSize(url:String):Long?=cache[url]?.takeIf {
        System.currentTimeMillis()-it.at in 0..TTL
    }?.result

    private fun validUrl(parent:String,relative:String):String? =
        runCatching {URL(URL(parent),relative).toString()}
            .getOrNull()?.takeIf{runCatching{DownloadHttp.validateUrl(it)}.isSuccess}

    private fun rangeBytes(value:String?):Long? =
        value?.substringBefore('@')?.trim()?.toLongOrNull()?.takeIf {it>0}

    fun parse(text:String,url:String):Plan? {
        if(!text.trimStart().startsWith("#EXTM3U") ||
            !text.contains("#EXT-X-ENDLIST"))return null
        val pieces=mutableListOf<Piece>()
        var duration=0.0
        var segmentDuration=0.0
        var initUrl:String?=null
        var initLength:Long?=null
        var pendingRange:Long?=null
        var crypto=false
        var anyCrypto=false
        var onlyRanges=true
        var parts=0
        var partBuffer=mutableListOf<Piece>()
        for(raw in text.lineSequence()) {
            val line=raw.trim()
            when {
                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs=DownloadHls.attributes(line)
                    crypto=attrs["METHOD"] !in setOf(null,"NONE")
                    anyCrypto=anyCrypto||crypto
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs=DownloadHls.attributes(line)
                    initUrl=validUrl(url,attrs["URI"] ?: return null) ?: return null
                    initLength=rangeBytes(attrs["BYTERANGE"])
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    pendingRange=rangeBytes(line.substringAfter(':')) ?: return null
                }
                line.startsWith("#EXTINF:") -> {
                    segmentDuration=line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                        ?.takeIf{it.isFinite() && it>=0} ?: return null
                }
                line.startsWith("#EXT-X-PART:") -> {
                    val attrs=DownloadHls.attributes(line)
                    val partUrl=validUrl(url,attrs["URI"] ?: return null) ?: return null
                    val partDuration=attrs["DURATION"]?.toDoubleOrNull()
                        ?.takeIf{it.isFinite() && it>=0} ?: return null
                    partBuffer.add(Piece(partUrl,partDuration,rangeBytes(attrs["BYTERANGE"]),crypto,true))
                    parts++
                    if(parts>4096)return null
                }
                line.isNotBlank() && !line.startsWith('#') -> {
                    val link=validUrl(url,line) ?: return null
                    // A complete segment supersedes the partials for that time
                    // period. Never double-count a part and the enclosing segment.
                    partBuffer.clear()
                    val known=pendingRange
                    pieces.add(Piece(link,segmentDuration,known,crypto))
                    onlyRanges=onlyRanges && known!=null
                    duration+=segmentDuration
                    segmentDuration=0.0
                    pendingRange=null
                    if(pieces.size>20_000)return null
                }
            }
        }
        // LL-HLS trailing parts: include only when the final segment is absent.
        for(part in partBuffer) {
            pieces.add(part);duration+=part.duration
            onlyRanges=onlyRanges && part.length!=null
        }
        return Plan(pieces,initUrl,initLength,duration,anyCrypto,
            onlyRanges && pieces.isNotEmpty(),parts)
    }

    /** Each sampled size is a full segment payload, weighted by its duration. */
    fun weightedEstimate(pieces:List<Piece>,samples:Map<Int,Long>):Long? {
        if(pieces.isEmpty() || samples.isEmpty())return null
        val known=pieces.mapIndexedNotNull{index,piece ->
            samples[index]?.takeIf {it>0 && piece.duration>0.01}?.let {
                (it.toDouble()/piece.duration) to piece.duration
            }
        }
        if(known.size<2)return null
        // Guard against one bitrate outlier dominating an anime opening/ending.
        val sorted=known.map{it.first}.sorted()
        val median=sorted[sorted.size/2]
        val clamped=known.map { (bytesPerSecond,seconds) ->
            bytesPerSecond.coerceIn(median*0.25,median*4.0) to seconds
        }
        val weightedRate=clamped.sumOf{it.first*it.second}/
            clamped.sumOf{it.second}
        val overall=weightedRate*pieces.sumOf{it.duration}
        return overall.takeIf{it.isFinite() && it>=1 && it<=Long.MAX_VALUE.toDouble()}?.roundToLong()
    }

    fun encryptedTransportOverhead(parts:Int):Long? =
        parts.takeIf{it in 1..20000}?.toLong()?.times(16L)
}
