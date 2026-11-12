package com.reianix.reianix_local.downloads

import android.content.Context
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Non-downloading size inspection, analogous to open download-manager HTTP
 * probing: HEAD -> GET bytes=0-0 -> HLS playlist/fragment metadata. The last
 * fallback may be an estimate, never a fictional exact byte count.
 *
 * No media bodies are downloaded. HTTP connections share the downloader's
 * existing redirect policy (no cross-origin cookie/authorization forwarding).
 */
internal object DownloadSizeProbe {
    data class Result(val bytes: Long?, val exact: Boolean, val source: String, val name: String? = null, val mime: String? = null) {
        companion object { val Unknown = Result(null, false, "unknown") }
    }
    internal data class HlsPlan(val segments: List<String>, val init: String?, val durationSeconds: Double,
        val encrypted: Boolean)
    internal data class MasterChoice(val url: String, val bandwidth: Long?)

    private const val NETWORK_TIMEOUT_MS = 4_000
    private const val MAX_MANIFEST_BYTES = 1024 * 1024
    private const val MAX_EXACT_SEGMENTS = 40
    private const val MAX_SAMPLE_SEGMENTS = 12
    private const val MAX_SAMPLE_BYTES = 4 * 1024 * 1024

    fun internalAvailableBytes(context: Context): Long? = runCatching {
        val folder = runCatching { Environment.getExternalStorageDirectory() }
            .getOrElse { context.filesDir }
        StatFs(folder.absolutePath).availableBytes.takeIf { it >= 0 }
    }.getOrNull()

    /** A valid exact length is either a full identity response or a legal 206 total. */
    fun directLength(
        status: Int, contentLength: Long, contentRange: String?,
        contentEncoding: String?, contentType: String?,
    ): Long? {
        if (contentEncoding != null && !contentEncoding.equals("identity",true)) return null
        val mime=contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        if (mime.contains("mpegurl") || mime.contains("dash+xml") || mime.startsWith("text/") ||
            mime.contains("json") || mime.contains("xml")) return null
        return when (status) {
            HttpURLConnection.HTTP_PARTIAL ->
                DownloadHttp.range(contentRange)?.takeIf { it.start==0L }?.total
            HttpURLConnection.HTTP_OK -> contentLength.takeIf { it > 0 }
            else -> null
        }
    }

    fun parseMaster(text: String, sourceUrl: String): MasterChoice? {
        val lines=text.lineSequence().map(String::trim).toList()
        return lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF:",true) }
            .mapNotNull { index ->
                val attrs=DownloadHls.attributes(lines[index])
                val candidate=lines.drop(index+1).firstOrNull { it.isNotBlank() && !it.startsWith('#') }
                    ?: return@mapNotNull null
                val url=runCatching { URL(URL(sourceUrl),candidate).toString() }.getOrNull()
                    ?: return@mapNotNull null
                if (runCatching { DownloadHttp.validateUrl(url) }.isFailure) return@mapNotNull null
                // The existing downloader chooses the largest BANDWIDTH variant.
                MasterChoice(url,attrs["BANDWIDTH"]?.toLongOrNull())
            }.maxByOrNull { it.bandwidth ?: 0L }
    }

    fun parseVod(text: String, sourceUrl: String): HlsPlan? {
        if (!text.trimStart().startsWith("#EXTM3U") || !text.contains("#EXT-X-ENDLIST")) return null
        val segments=mutableListOf<String>()
        var init: String?=null
        var duration=0.0
        var encrypted=false
        for(line in text.lineSequence().map { it.trim() }) {
            when {
                line.startsWith("#EXT-X-BYTERANGE") -> return null // not supported by the downloader
                line.startsWith("#EXT-X-DISCONTINUITY") -> return null
                line.startsWith("#EXT-X-MAP:") -> {
                    val uri=DownloadHls.attributes(line)["URI"] ?: return null
                    if(DownloadHls.attributes(line)["BYTERANGE"]!=null)return null
                    val resolved=runCatching { URL(URL(sourceUrl),uri).toString() }.getOrNull() ?: return null
                    if(runCatching { DownloadHttp.validateUrl(resolved) }.isFailure)return null
                    if(init!=null && init!=resolved)return null
                    init=resolved
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    if(DownloadHls.attributes(line)["METHOD"] !in setOf(null,"NONE")) encrypted=true
                }
                line.startsWith("#EXTINF:") -> {
                    val n=line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                    if(n != null && n.isFinite() && n>=0)duration+=n
                }
                line.isNotBlank() && !line.startsWith('#') -> {
                    if(segments.size>=20_000)return null
                    val url=runCatching { URL(URL(sourceUrl),line).toString() }.getOrNull() ?: return null
                    if(runCatching { DownloadHttp.validateUrl(url) }.isFailure)return null
                    segments+=url
                }
            }
        }
        return if(segments.isNotEmpty())HlsPlan(segments,init,duration,encrypted) else null
    }

    /**
     * Some Googlevideo VOD URLs carry the signed `clen` (content length)
     * parameter for this exact representation. Do not trust arbitrary
     * user-controlled `size` URL parameters on other domains.
     */
    fun trustedUrlLength(value: String): Long? = runCatching {
        val uri=DownloadHttp.validateUrl(value)
        val hostname=uri.host.orEmpty().lowercase(Locale.ROOT)
        if(hostname!="googlevideo.com" && !hostname.endsWith(".googlevideo.com"))return@runCatching null
        if(uri.path!="/videoplayback")return@runCatching null
        uri.rawQuery?.split('&')?.firstOrNull { it.substringBefore('=')=="clen" }
            ?.substringAfter('=',"")?.toLongOrNull()?.takeIf { it>0L }
    }.getOrNull()

    /** Samples of complete media fragments produce an estimate, never an exact byte count. */
    fun sampledSegmentEstimate(sizes: List<Long?>, segmentCount: Int): Long? {
        if (segmentCount <= 0 || sizes.isEmpty()) return null
        val known = sizes.filterNotNull().filter { it > 0 }
        if (known.size < minOf(2, sizes.size)) return null
        val estimate = known.average() * segmentCount
        return estimate.takeIf { it.isFinite() && it in 1.0..Long.MAX_VALUE.toDouble() }?.toLong()
    }

    fun bitrateEstimate(durationSeconds: Double, bitsPerSecond: Long?): Long? {
        if(!durationSeconds.isFinite() || durationSeconds<=0 || bitsPerSecond==null || bitsPerSecond<=0)return null
        val total=durationSeconds*bitsPerSecond/8.0
        return total.takeIf { it.isFinite() && it in 1.0..Long.MAX_VALUE.toDouble() }?.toLong()
    }

    suspend fun inspect(url: String, headers: Map<String,String>, hlsHint: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            val hint=DownloadResourceIdentity.bestName(null,url,null)
            try {
                DownloadHttp.validateUrl(url)
                val path=URL(url).path.lowercase(Locale.ROOT)
                val manifest=hlsHint || path.endsWith(".m3u8")
                if(manifest) return@withContext inspectHls(url,headers).copy(name=hint)
                trustedUrlLength(url)?.let { return@withContext Result(it,true,"googlevideo-clen",hint) }
                val response=probe(url,headers)
                if(response.manifest) return@withContext inspectHls(url,headers)
                    .copy(name=response.name ?: hint, mime=response.mime)
                if(response.length!=null)
                    return@withContext Result(response.length,true,response.source,response.name ?: hint,response.mime)
                Result(null,false,"unknown",response.name ?: hint,response.mime)
            } catch(c: kotlinx.coroutines.CancellationException) { throw c }
            catch(_: Exception) { Result(null,false,"unknown",hint) }
        }

    private data class Headers(
        val length: Long?, val manifest: Boolean, val name: String?, val mime: String?, val source: String
    )

    /**
     * Gopeed/Download Navi style: first request headers without a body, then
     * one-byte Range, then a plain GET when servers reject HEAD or Range.
     * Plain GET is disconnected after headers; the video is NOT downloaded here.
     * Name and MIME remain useful even when size is unavailable.
     */
    private fun probe(url: String, headers: Map<String,String>, credentialOrigin: String = url): Headers {
        var bestName=DownloadResourceIdentity.bestName(null,url,null)
        var bestMime:String?=null
        var manifest=false
        for(phase in listOf("HEAD","GET_RANGE","GET")) {
            currentThreadCheck()
            var c:HttpURLConnection?=null
            try {
                c=DownloadHttp.connect(url,headers,0,null,
                    end=if(phase=="GET_RANGE")0 else null,
                    credentialOrigin=credentialOrigin,
                    method=if(phase=="HEAD")"HEAD" else "GET",
                    connectTimeoutMs=NETWORK_TIMEOUT_MS,readTimeoutMs=NETWORK_TIMEOUT_MS)
                val code=c.responseCode
                val mime=c.contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
                if(mime.startsWith("video/") || mime.contains("mpegurl")) bestMime=mime
                val resolved=DownloadResourceIdentity.bestName(c.getHeaderField("Content-Disposition"),
                    c.url.toString(),c.contentType)
                if(resolved!=null && (bestName==null ||
                    DownloadResourceIdentity.opaque(bestName) || resolved.contains("Episódio",true)))
                    bestName=resolved
                if(mime.contains("mpegurl")) {manifest=true;break}
                val size=directLength(code,c.getHeaderFieldLong("Content-Length",-1),
                    c.getHeaderField("Content-Range"),c.getHeaderField("Content-Encoding"),c.contentType)
                if(size!=null)return Headers(size,false,bestName,bestMime,phase.lowercase(Locale.ROOT))
                // Some CDNs reject ranges but permit GET. A missing Content-Length
                // in a 200 chunked response cannot be treated as an exact size.
                if(phase=="GET" && code in setOf(401,403))break
            } catch(_:java.io.IOException) { /* try the next header-only strategy */ }
            finally {c?.disconnect()}
        }
        return Headers(null,manifest,bestName,bestMime,"unknown")
    }

    private suspend fun inspectHls(url: String, headers: Map<String,String>): Result {
        val first=readManifest(url,headers,url) ?: return Result.Unknown
        val choice=parseMaster(first,url)
        val mediaUrl=choice?.url ?: url
        val playlist=if(choice!=null) readManifest(mediaUrl,headers,url) else first
        val plan=playlist?.let {parseVod(it,mediaUrl)}
        if(plan==null)return Result.Unknown
        val bitrate=bitrateEstimate(plan.durationSeconds,choice?.bandwidth)
        val segments=plan.segments
        // Inspect all small playlists or sample long ones. Keep network work
        // bounded so opening the dialog never starts hundreds of downloads.
        val indices=if(segments.size<=MAX_EXACT_SEGMENTS)segments.indices.toList()
        else (segments.indices.take(MAX_SAMPLE_SEGMENTS/2) +
            segments.indices.toList().takeLast(MAX_SAMPLE_SEGMENTS/2)).distinct()
        val sizes=coroutineScope {
            indices.chunked(6).flatMap { chunk ->
                chunk.map { index -> async(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    probe(segments[index],headers,credentialOrigin=url).length
                }}.awaitAll()
            }
        }
        val successes=sizes.filterNotNull()
        if(successes.size==indices.size && successes.isNotEmpty()) {
            val rawTotal=if(indices.size==segments.size) successes.sumOf { it }
            else (successes.average()*segments.size).toLong()
            val initSize=plan.init?.let {probe(it,headers,credentialOrigin=url).length}
            if(plan.init!=null && initSize==null)return Result(
                bitrate ?: rawTotal,false,"hls-estimate")
            val bytes=rawTotal+(initSize ?: 0)
            if(bytes>0) {
                // TS may be remuxed to MP4 (different exact output bytes);
                // encrypted segments also change length after decryption.
                val exact=indices.size==segments.size && !plan.encrypted && plan.init!=null
                return Result(bytes,exact,if(exact)"hls-fmp4" else "hls-estimate")
            }
        }
        if(bitrate!=null) return Result(bitrate,false,"hls-bitrate")
        // Some video hosts reject HEAD and Range requests, but permit normal
        // segment GETs. Sample only three *complete*, bounded segments. This
        // is expressly an estimate and is never used as a download limit.
        val sampleIndices=listOf(0,segments.size/2,segments.lastIndex).distinct()
        val sampleSizes=coroutineScope {
            sampleIndices.map { index -> async(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                sampleCompleteSegment(segments[index],headers,url)
            }}.awaitAll()
        }
        val sampled=sampledSegmentEstimate(sampleSizes,segments.size)
        return sampled?.let { Result(it,false,"hls-sampled") } ?: Result.Unknown
    }

    private fun sampleCompleteSegment(url: String, headers: Map<String,String>, origin: String): Long? {
        var connection: HttpURLConnection?=null
        return try {
            connection=DownloadHttp.connect(url,headers,0,null,credentialOrigin=origin,
                connectTimeoutMs=NETWORK_TIMEOUT_MS,readTimeoutMs=NETWORK_TIMEOUT_MS)
            if(connection.responseCode!=200 || connection.contentType.orEmpty().startsWith("text/") ||
                connection.getHeaderFieldLong("Content-Length",-1)>MAX_SAMPLE_BYTES) return null
            var total=0L
            connection.inputStream.use { input ->
                val buffer=ByteArray(32*1024)
                while(true) {
                    currentThreadCheck()
                    val n=input.read(buffer)
                    if(n<0) break
                    total+=n
                    if(total>MAX_SAMPLE_BYTES) return null
                }
            }
            total.takeIf { it>0L }
        } catch(_:java.io.IOException) { null }
        finally {connection?.disconnect()}
    }

    private suspend fun readManifest(url: String,headers: Map<String,String>,credentialOrigin: String): String? =
        withContext(Dispatchers.IO) {
            val conn=DownloadHttp.connect(url,headers,0,null,credentialOrigin=credentialOrigin,
                connectTimeoutMs=NETWORK_TIMEOUT_MS,readTimeoutMs=NETWORK_TIMEOUT_MS)
            try {
                if(conn.responseCode!=200 ||
                    conn.getHeaderFieldLong("Content-Length",-1)>MAX_MANIFEST_BYTES) return@withContext null
                conn.inputStream.use { input ->
                    val out=java.io.ByteArrayOutputStream()
                    val buffer=ByteArray(16*1024)
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val n=input.read(buffer)
                        if(n<0)break
                        if(out.size()+n>MAX_MANIFEST_BYTES) return@withContext null
                        out.write(buffer,0,n)
                    }
                    out.toString(Charsets.UTF_8.name()).takeIf {it.trimStart().startsWith("#EXTM3U")}
                }
            } finally {conn.disconnect()}
        }

    private fun currentThreadCheck() {
        if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException()
    }
}
