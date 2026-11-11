package com.reiflix.reiflix_local.downloads

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
    data class Result(val bytes: Long?, val exact: Boolean, val source: String) {
        companion object { val Unknown = Result(null, false, "unknown") }
    }
    internal data class HlsPlan(val segments: List<String>, val init: String?, val durationSeconds: Double,
        val encrypted: Boolean)
    internal data class MasterChoice(val url: String, val bandwidth: Long?)

    private const val NETWORK_TIMEOUT_MS = 4_000
    private const val MAX_MANIFEST_BYTES = 1024 * 1024
    private const val MAX_EXACT_SEGMENTS = 40
    private const val MAX_SAMPLE_SEGMENTS = 12

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
                line.startsWith("#EXT-X-MAP:") -> {
                    val uri=DownloadHls.attributes(line)["URI"] ?: return null
                    if(DownloadHls.attributes(line)["BYTERANGE"]!=null)return null
                    init=runCatching { URL(URL(sourceUrl),uri).toString() }.getOrNull() ?: return null
                    if(runCatching { DownloadHttp.validateUrl(init) }.isFailure)return null
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

    fun bitrateEstimate(durationSeconds: Double, bitsPerSecond: Long?): Long? {
        if(!durationSeconds.isFinite() || durationSeconds<=0 || bitsPerSecond==null || bitsPerSecond<=0)return null
        val total=durationSeconds*bitsPerSecond/8.0
        return total.takeIf { it.isFinite() && it in 1.0..Long.MAX_VALUE.toDouble() }?.toLong()
    }

    suspend fun inspect(url: String, headers: Map<String,String>, hlsHint: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            try {
                DownloadHttp.validateUrl(url)
                val path=URL(url).path.lowercase(Locale.ROOT)
                val isManifest=hlsHint || path.endsWith(".m3u8")
                if(isManifest) return@withContext inspectHls(url,headers)
                val first=probe(url,headers)
                if(first.manifest) return@withContext inspectHls(url,headers)
                first.length?.let { return@withContext Result(it,true,"http") }
                Result.Unknown
            } catch(c: kotlinx.coroutines.CancellationException) { throw c }
            catch(_: Exception) { Result.Unknown }
        }

    private data class Headers(val length: Long?, val manifest: Boolean)
    private fun probe(url: String, headers: Map<String,String>, credentialOrigin: String = url): Headers {
        var manifest=false
        for(method in listOf("HEAD","GET")) {
            currentThreadCheck()
            var c: HttpURLConnection?=null
            try {
                c=DownloadHttp.connect(url,headers,0,null,
                    end=if(method=="GET")0 else null,credentialOrigin=credentialOrigin,
                    method=method,connectTimeoutMs=NETWORK_TIMEOUT_MS,readTimeoutMs=NETWORK_TIMEOUT_MS)
                val mime=c.contentType.orEmpty().substringBefore(';').lowercase(Locale.ROOT)
                if(mime.contains("mpegurl")) {manifest=true;break}
                val size=directLength(
                    c.responseCode,c.getHeaderFieldLong("Content-Length",-1),
                    c.getHeaderField("Content-Range"),c.getHeaderField("Content-Encoding"),
                    c.contentType)
                if(size!=null)return Headers(size,false)
                if(c.responseCode==HttpURLConnection.HTTP_UNAUTHORIZED ||
                    c.responseCode==HttpURLConnection.HTTP_FORBIDDEN)break
            } catch(_: java.io.IOException) { /* HEAD may be unsupported; try a one-byte Range. */ }
            finally { c?.disconnect() }
        }
        return Headers(null,manifest)
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
        return Result(bitrate,false,"hls-bitrate").takeIf { bitrate!=null } ?: Result.Unknown
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
