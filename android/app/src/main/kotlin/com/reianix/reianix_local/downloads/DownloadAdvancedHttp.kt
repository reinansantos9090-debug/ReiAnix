package com.reianix.reianix_local.downloads

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Extra standards-aware metadata resolution. Never contacts arbitrary URLs
 * extracted from page content, never downloads a large file during inspection,
 * and never upgrades an estimate to a verified HTTP length.
 *
 * Strategies: 04 (416), 05 (suffix), 07 (multipart), 10 (Content-Location),
 * 12 (chunked completion), 14 (conditional ETag), 16 (documented vendor size),
 * 20 (bounded transient retry).
 */
internal object DownloadAdvancedHttp {
    private data class Entry(val size:Long,val etag:String,val timestamp:Long)
    private val cache=ConcurrentHashMap<String,Entry>()
    private const val CACHE_MS=120_000L
    private const val PREFIX_MAX=24_576

    fun unsatisfiedLength(code:Int, header:String?):Long? {
        if(code!=416)return null
        return Regex("""(?i)^bytes\s+\*/(\d+)$""").matchEntire(header.orEmpty().trim())
            ?.groupValues?.get(1)?.toLongOrNull()?.takeIf {it>0}
    }

    fun vendorLength(headers:Map<String,String>):Long? {
        val lower=headers.mapKeys {it.key.lowercase(Locale.ROOT)}
        return listOf("x-goog-stored-content-length","x-ms-blob-content-length",
            "x-amz-meta-content-length","x-file-size","x-content-length",
            "x-original-content-length","x-object-size").firstNotNullOfOrNull {key ->
            lower[key]?.trim()?.toLongOrNull()?.takeIf {it>0L}
        }
    }

    /** Extract only MIME part headers, never read part bodies as filenames. */
    fun multipartTotal(prefix:ByteArray):Long? {
        if(prefix.size> PREFIX_MAX || prefix.isEmpty())return null
        val text=String(prefix,Charsets.ISO_8859_1)
        val ranges=Regex("""(?im)^Content-Range:\s*bytes\s+\d+-\d+/(\d+)\s*$""")
            .findAll(text).mapNotNull{it.groupValues[1].toLongOrNull()}.distinct().toList()
        return ranges.singleOrNull()?.takeIf {it>0}
    }

    fun resolveContentLocation(current:String,location:String?):String? {
        if(location.isNullOrBlank() || location.length>2048)return null
        return runCatching {
            val base=URL(current)
            val resolved=URL(base,location)
            if(!DownloadHttp.sameOrigin(base,resolved) || base.toString()==resolved.toString())
                return@runCatching null
            DownloadHttp.validateUrl(resolved.toString())
            resolved.toString()
        }.getOrNull()
    }

    fun chunkedCompleteLength(bytesRead:Long, reachedEof:Boolean):Long? =
        bytesRead.takeIf {reachedEof && it>0}

    private fun snapshot(c:HttpURLConnection):Map<String,String> =
        c.headerFields.mapNotNull { (key,values) ->
            key?.let { safe -> values?.firstOrNull()?.let { safe to it } }
        }.toMap()

    private fun looksLikeVideo(c:HttpURLConnection):Boolean {
        val mime=c.contentType.orEmpty().substringBefore(';').lowercase(Locale.ROOT)
        return !mime.startsWith("text/") && !mime.contains("mpegurl") &&
            !mime.contains("dash+xml") && !mime.contains("json") && !mime.contains("xml")
    }

    fun inspect(url:String,headers:Map<String,String>):DownloadSizeProbe.Result? {
        val original=runCatching {DownloadHttp.validateUrl(url)}.getOrNull()?:return null
        if(original.scheme !in setOf("http","https"))return null
        val saved=cache[url]?.takeIf {System.currentTimeMillis()-it.timestamp in 0..CACHE_MS}
        var metadataName=DownloadResourceIdentity.bestName(null,url,null)
        // Retry only once for temporary responses. Never retry 401/403.
        val phases=listOf("suffix" to "bytes=-1","multipart" to "bytes=0-0,-1",
            "unsatisfied" to "bytes=999999999999999999")
        for((phase,range) in phases) {
            var retries=0
            while(retries<2) {
                retries++
                var c:HttpURLConnection?=null
                try {
                    c=DownloadHttp.connect(url,headers,0,null,probeRange=range,
                        conditionalEtag=if(phase=="suffix")saved?.etag else null,
                        connectTimeoutMs=2100,readTimeoutMs=2500)
                    val code=c.responseCode
                    if(code==304 && saved!=null)
                        return DownloadSizeProbe.Result(saved.size,true,"http-etag",metadataName)
                    val newName=DownloadResourceIdentity.bestName(c.getHeaderField("Content-Disposition"),
                        c.url.toString(),c.contentType)
                    if(newName!=null)metadataName=newName
                    if(!looksLikeVideo(c))break
                    val vendor=vendorLength(snapshot(c))
                    if(vendor!=null)
                        return DownloadSizeProbe.Result(vendor,false,"http-vendor",metadataName)
                    var length=unsatisfiedLength(code,c.getHeaderField("Content-Range"))
                    if(code==206 && length==null)
                        length=DownloadHttp.range(c.getHeaderField("Content-Range"))?.total
                    if(code==200 && !c.getHeaderField("Content-Encoding").orEmpty()
                            .let {it.isNotBlank() && !it.equals("identity",true)}) {
                        length=c.getHeaderFieldLong("Content-Length",-1).takeIf{it>0}
                    }
                    if(code==206 && c.contentType.orEmpty()
                            .startsWith("multipart/byteranges",ignoreCase=true) &&
                        c.getHeaderFieldLong("Content-Length",0) in 1..PREFIX_MAX) {
                        val buffer=ByteArray(PREFIX_MAX)
                        val read=c.inputStream.use {it.read(buffer)}
                        if(read>0)length=multipartTotal(buffer.copyOf(read))
                    }
                    if(length!=null && length>0) {
                        val tag=c.getHeaderField("ETag").orEmpty()
                        if(tag.startsWith("\"") && tag.length<=256) {
                            if(cache.size>120)cache.clear()
                            cache[url]=Entry(length,tag,System.currentTimeMillis())
                        }
                        return DownloadSizeProbe.Result(length,true,"http-"+phase,metadataName)
                    }
                    if(phase=="suffix") {
                        resolveContentLocation(c.url.toString(),c.getHeaderField("Content-Location"))
                            ?.let { other ->
                                // Only a same-origin HEAD, and only if it is a different
                                // representation URI. Never forward cross-origin credentials.
                                var alternate:HttpURLConnection?=null
                                try {
                                    val response=DownloadHttp.connect(other,headers,0,null,
                                        credentialOrigin=url,method="HEAD",
                                        connectTimeoutMs=1500,readTimeoutMs=1500)
                                    alternate=response
                                    val alternateLength=DownloadSizeProbe.directLength(
                                        response.responseCode,
                                        response.getHeaderFieldLong("Content-Length",-1),
                                        response.getHeaderField("Content-Range"),
                                        response.getHeaderField("Content-Encoding"),response.contentType)
                                    if(alternateLength!=null)
                                        return DownloadSizeProbe.Result(alternateLength,true,
                                            "http-content-location",metadataName)
                                } catch(_:Exception){} finally {alternate?.disconnect()}
                            }
                    }
                    if(code !in setOf(408,429,500,502,503,504))break
                } catch(_:java.io.IOException) {
                    if(retries>=2)break
                } finally {c?.disconnect()}
                if(retries<2)Thread.sleep(120L*retries)
            }
        }
        return null
    }
}
