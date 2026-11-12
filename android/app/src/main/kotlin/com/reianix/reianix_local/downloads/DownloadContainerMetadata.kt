package com.reianix.reianix_local.downloads

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/**
 * Container headers are hints, not a substitute for an HTTP Content-Length.
 * 43 MP4 atoms; 45 EBML Segment; 47 ASF properties; 48 MPEG-TS PCR;
 * 49 FLV AMF; 50 Android MediaMetadataRetriever.
 */
internal object DownloadContainerMetadata {
    data class Hint(val bytes:Long,val exact:Boolean,val source:String)
    private const val LIMIT=65536
    private fun be32(b:ByteArray,pos:Int):Long =
        ByteBuffer.wrap(b,pos,4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
    private fun le32(b:ByteArray,pos:Int):Long =
        ByteBuffer.wrap(b,pos,4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    private fun be64(b:ByteArray,pos:Int):Long =
        ByteBuffer.wrap(b,pos,8).order(ByteOrder.BIG_ENDIAN).long
    private fun le64(b:ByteArray,pos:Int):Long =
        ByteBuffer.wrap(b,pos,8).order(ByteOrder.LITTLE_ENDIAN).long
    private fun tag(b:ByteArray,pos:Int,t:String):Boolean =
        pos>=0 && pos+t.length<=b.size &&
            String(b,pos,t.length,Charsets.ISO_8859_1)==t

    fun aviSize(b:ByteArray):Hint? {
        if(b.size<12 || !tag(b,0,"RIFF") || !tag(b,8,"AVI "))return null
        val raw=le32(b,4)
        return (raw+8).takeIf{it>12}?.let{Hint(it,true,"avi-riff-header")}
    }

    private val asfGuid=byteArrayOf(0xa1.toByte(),0xdc.toByte(),0xab.toByte(),0x8c.toByte(),
        0x47,0xa9.toByte(),0xcf.toByte(),0x11,0x8e.toByte(),0xe4.toByte(),0,
        0xc0.toByte(),0x0c,0x20,0x53,0x65)
    fun asfSize(b:ByteArray):Hint? {
        if(b.size<64)return null
        val last=(b.size-48).coerceAtMost(LIMIT-48)
        for(i in 0..last){
            if((0 until 16).all{k->b[i+k]==asfGuid[k]}) {
                val declared=le64(b,i+40)
                if(declared>64)return Hint(declared,true,"asf-file-properties")
            }
        }
        return null
    }

    /** Top-level atom sizes can describe a prefix, not necessarily full MP4. */
    fun mp4AtomExtent(b:ByteArray):Hint? {
        if(b.size<16 || !tag(b,4,"ftyp"))return null
        var pos=0
        var declared=0L
        var found=false
        var count=0
        while(pos+8<=b.size && count++<64) {
            val normal=be32(b,pos)
            val size=if(normal==1L && pos+16<=b.size)be64(b,pos+8) else normal
            val header=if(normal==1L)16L else 8L
            if(size<header || size>Long.MAX_VALUE-pos)break
            val end=pos+size
            if(end>declared)declared=end
            val container=tag(b,pos+4,"mdat") || tag(b,pos+4,"moov")
            found=found||container
            if(end>b.size)break
            pos=end.toInt()
        }
        return declared.takeIf{found && it>16}?.let {Hint(it,false,"mp4-atom-extent")}
    }

    private fun vint(b:ByteArray,pos:Int):Pair<Long,Int>? {
        if(pos>=b.size)return null
        val first=b[pos].toInt() and 0xff
        var mask=0x80
        var len=1
        while(len<=8 && first and mask==0){mask=mask shr 1;len++}
        if(len>8 || pos+len>b.size)return null
        var value=(first and (mask-1)).toLong()
        for(i in 1 until len)value=(value shl 8) or (b[pos+i].toLong() and 0xff)
        val unknown=(1L shl (len*7))-1
        return value.takeIf{it!=unknown}?.let{it to len}
    }

    fun ebmlSegmentExtent(b:ByteArray):Hint? {
        if(b.size<16 || b[0]!=0x1a.toByte() || b[1]!=0x45.toByte() ||
            b[2]!=0xdf.toByte() || b[3]!=0xa3.toByte())return null
        val segmentId=byteArrayOf(0x18,0x53,0x80.toByte(),0x67)
        for(i in 0 until (b.size-12).coerceAtMost(8192)) {
            if(segmentId.indices.all{j->b[i+j]==segmentId[j]}) {
                val size=vint(b,i+4)?:return null
                val end=i+4L+size.second+size.first
                return end.takeIf{it>0}?.let{Hint(it,false,"ebml-segment-extent")}
            }
        }
        return null
    }

    fun flvSize(b:ByteArray):Hint? {
        if(b.size<40 || !tag(b,0,"FLV"))return null
        val key="filesize".toByteArray(Charsets.ISO_8859_1)
        for(i in 0 until (b.size-18).coerceAtMost(16384)) {
            if(key.indices.all{j->b[i+j]==key[j]}) {
                val valueIndex=i+key.size
                if((b[valueIndex].toInt() and 0xff)!=0x00)continue // AMF0 Number
                if(valueIndex+9>b.size)continue
                val value=Double.fromBits(be64(b,valueIndex+1))
                if(value.isFinite() && value>0 && value<Long.MAX_VALUE.toDouble())
                    return Hint(value.roundToLong(),false,"flv-onmetadata")
            }
        }
        return null
    }

    /** MPEG-TS PCR rate derived from first and last PCR-bearing packets. */
    fun tsPcrBitsPerSecond(b:ByteArray):Long? {
        if(b.size<188*5)return null
        val offsets=listOf(0,4).filter{start->
            start+188*3<b.size && (0..2).all{(b[start+it*188].toInt() and 255)==0x47}
        }
        val start=offsets.firstOrNull() ?: return null
        val marks=mutableListOf<Pair<Int,Long>>()
        for(pos in start until b.size-188 step 188) {
            if((b[pos].toInt() and 0xff)!=0x47)continue
            val control=(b[pos+3].toInt() ushr 4) and 3
            if(control !in setOf(2,3))continue
            val adaptation=b[pos+4].toInt() and 255
            if(adaptation<7 || pos+11>=b.size)continue
            if((b[pos+5].toInt() and 0x10)==0)continue
            val a=pos+6
            val base=((b[a].toLong() and 255) shl 25) or
                ((b[a+1].toLong() and 255) shl 17) or
                ((b[a+2].toLong() and 255) shl 9) or
                ((b[a+3].toLong() and 255) shl 1) or
                ((b[a+4].toLong() and 0x80) shr 7)
            marks.add(pos to base)
        }
        if(marks.size<2)return null
        val first=marks.first()
        val last=marks.last()
        val seconds=(last.second-first.second).toDouble()/90000.0
        return if(seconds>0.02) ((last.first-first.first)*8.0/seconds)
            .roundToLong().takeIf{it>0} else null
    }

    fun scan(prefix:ByteArray):Hint? =
        aviSize(prefix) ?: asfSize(prefix) ?: ebmlSegmentExtent(prefix) ?:
        flvSize(prefix) ?: mp4AtomExtent(prefix)

    /** Native MediaMetadataRetriever is supplemental: bitrate times duration is approximate. */
    fun nativeEstimate(context:Context,uri:Uri):Hint? = runCatching {
        val meta=MediaMetadataRetriever()
        try {
            meta.setDataSource(context,uri)
            val duration=meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toDoubleOrNull()?.div(1000.0)
            val rate=meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull()
            val result=DownloadSizeProbe.bitrateEstimate(duration ?: 0.0,rate)
            result?.let{Hint(it,false,"native-media-metadata")}
        }finally{meta.release()}
    }.getOrNull()

    fun inspect(url:String,headers:Map<String,String>):Hint? {
        var c:HttpURLConnection?=null
        return try {
            c=DownloadHttp.connect(url,headers,0,null,probeRange="bytes=0-65535",
                connectTimeoutMs=2200,readTimeoutMs=2400)
            if(c.responseCode !in setOf(200,206))return null
            val mime=c.contentType.orEmpty().lowercase()
            if(mime.startsWith("text/") || mime.contains("mpegurl") ||
                mime.contains("dash+xml") || mime.contains("json"))return null
            val out=ByteArrayOutputStream()
            val buffer=ByteArray(8192)
            c.inputStream.use {input->
                while(out.size()<LIMIT){
                    val n=input.read(buffer,0,minOf(buffer.size,LIMIT-out.size()))
                    if(n<0)break
                    out.write(buffer,0,n)
                }
            }
            scan(out.toByteArray())
        }catch(_:Exception){null}
        finally{c?.disconnect()}
    }
}
