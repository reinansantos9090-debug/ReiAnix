package com.reianix.reianix_local.downloads

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.URL

/** Expand a playlist already declared by the visible player, without playing media. */
internal object BrowserHlsDiscovery {
    private const val MAX_BYTES=1024*1024

    fun variants(text:String,baseUrl:String,source:BrowserMediaOption):List<BrowserMediaOption> {
        if(!text.trimStart().startsWith("#EXTM3U") || text.length>MAX_BYTES)return emptyList()
        val lines=text.lineSequence().map(String::trim).toList()
        return lines.indices.asSequence().filter {lines[it].startsWith("#EXT-X-STREAM-INF:")}
            .take(32).mapNotNull { index ->
                val attrs=DownloadHls.attributes(lines[index])
                val next=lines.drop(index+1).asSequence()
                    .takeWhile {!it.startsWith("#EXT-X-STREAM-INF:")}
                    .firstOrNull {it.isNotBlank() && !it.startsWith('#')} ?: return@mapNotNull null
                val link=runCatching {URL(URL(baseUrl),next).toString()}.getOrNull()
                    ?: return@mapNotNull null
                val height=attrs["RESOLUTION"]?.substringAfter('x',"")?.toIntOrNull()
                    ?: Regex("""(?:^|[-_/])(2160|1440|1080|720|480|360|240)\.(?:m3u8)(?:[?#]|$)""")
                        .find(link)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                if(height !in 90..4320)return@mapNotNull null
                source.copy(url=link,quality="${height}p",format="hls",origin="master",
                    mimeHint="application/vnd.apple.mpegurl",masterUrl=source.url,
                    separateAudio=attrs.containsKey("AUDIO"),estimatedBytes=null,
                    receivedAt=System.nanoTime()/1_000_000)
                    .takeIf(BrowserMediaOption::eligible)
            }.distinctBy {it.url}.toList()
    }

    suspend fun inspect(source:BrowserMediaOption,headers:Map<String,String>):List<BrowserMediaOption> =
        withContext(Dispatchers.IO) {
            if(source.format!="hls" || !BrowserMediaOption.eligible(source))return@withContext emptyList()
            val connection=DownloadHttp.connect(source.url,headers,0,null,
                connectTimeoutMs=1800,readTimeoutMs=2500,allowMediaHttpDowngrade=true,
                redirectCookieProvider=DownloadSessionHeaders::cookieForTarget)
            try {
                if(connection.responseCode!=200 ||
                    connection.getHeaderFieldLong("Content-Length",-1)>MAX_BYTES)return@withContext emptyList()
                val text=connection.inputStream.use { input ->
                    val out=java.io.ByteArrayOutputStream()
                    val buffer=ByteArray(8192)
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val n=input.read(buffer)
                        if(n<0)break
                        if(out.size()+n>MAX_BYTES)return@withContext emptyList()
                        out.write(buffer,0,n)
                    }
                    out.toString(Charsets.UTF_8.name())
                }
                variants(text,connection.url.toString(),source)
            } finally {connection.disconnect()}
        }
}
