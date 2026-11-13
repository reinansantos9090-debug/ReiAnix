package com.reianix.reianix_local.downloads

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/**
 * Chooses among URL candidates already supplied by the user/application.
 * Does not scrape third-party pages, remove signatures, or guess new CDN URLs.
 * A working media URL is not interchangeable with a page/gateway URL.
 */
object DownloadSourceSelection {
    enum class Kind { DIRECT_FILE, STREAM_MANIFEST, POSSIBLE_MEDIA, PAGE_OR_GATEWAY }
    data class Candidate(val url:String,val kind:Kind,val score:Int,val origin:String) {
        val label:String get()=when(kind) {
            Kind.DIRECT_FILE -> "Arquivo de vídeo"
            Kind.STREAM_MANIFEST -> "Playlist de vídeo"
            Kind.POSSIBLE_MEDIA -> "Possível arquivo"
            Kind.PAGE_OR_GATEWAY -> "Página ou link intermediário"
        }
        val host:String get()=runCatching{URI(url).host.orEmpty()}.getOrDefault("")
    }

    private val extensions=setOf("mp4","m4v","mkv","webm","mov","avi",
        "wmv","3gp","ts","m2ts","flv")
    private val manifests=setOf("m3u8","mpd")
    private val urlPattern=Regex("""(?i)https?://[^\s<>"']+""")
    private val namedUrlKeys=setOf("url","downloadurl","download_url","mediaurl",
        "media_url","videourl","video_url","streamurl","stream_url","fileurl",
        "file_url","sourceurl","source_url","href")
    private val encodedTargetKeys=setOf("url","download","download_url","media",
        "media_url","video","video_url","src","source","file")
    private val pagePaths=Regex("""(?i)(?:^|/)(?:watch|embed|player|episode|episodio|episódio|browse|search|page|login|register|index)(?:/|$)""")
    private val mediaQueryKeys=setOf("filename","file_name","response-content-disposition",
        "response_content_disposition","mime","content_type","content-type","format")
    private val mediaHint=Regex("""(?i)\.(?:mp4|mkv|webm|m4v|mov|avi|ts|m3u8|mpd)(?:$|[^a-z])|video/(?:mp4|webm|quicktime)|mpegurl|dash\+xml""")

    fun kind(url:String):Kind {
        val uri=runCatching {DownloadHttp.validateUrl(url)}.getOrNull()
            ?: return Kind.PAGE_OR_GATEWAY
        val path=uri.path.orEmpty().lowercase(Locale.ROOT)
        val ext=path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
        if(ext in extensions)return Kind.DIRECT_FILE
        if(ext in manifests)return Kind.STREAM_MANIFEST
        if(pagePaths.containsMatchIn(path) ||
            path.endsWith(".html") || path.endsWith(".htm") ||
            path.endsWith(".php") || path.endsWith(".aspx"))
            return Kind.PAGE_OR_GATEWAY
        if(ext=="bin")return Kind.POSSIBLE_MEDIA
        if(DownloadSignedLinkPolicy.avoidPrefetch(url))return Kind.POSSIBLE_MEDIA
        val query=uri.rawQuery.orEmpty()
        if(query.split('&').take(80).any {
            val name=it.substringBefore('=').lowercase(Locale.ROOT)
            name in mediaQueryKeys && mediaHint.containsMatchIn(
                runCatching{URLDecoder.decode(it.substringAfter('='),"UTF-8")}.getOrDefault("")
            )
        })return Kind.POSSIBLE_MEDIA
        return Kind.PAGE_OR_GATEWAY
    }

    /** A definite page path; extensionless CDN endpoints are not assumed pages. */
    fun clearlyPage(url:String):Boolean {
        val path=runCatching{URI(url).path.orEmpty().lowercase(Locale.ROOT)}
            .getOrDefault("")
        return path.endsWith(".html") || path.endsWith(".htm") ||
            pagePaths.containsMatchIn(path)
    }

    private fun normalize(raw:String,sharedText:Boolean):String? {
        val trimmed=raw.trim()
        // URL fields and decoded targets are already exact. Trim sentence
        // punctuation only when extracting a link from surrounding prose.
        val value=if(sharedText)trimmed.trimEnd('.',',',';',')',']','}','!')
            else trimmed
        if(value.length !in 10..16384)return null
        return runCatching {DownloadHttp.validateUrl(value);value}.getOrNull()
    }

    /**
     * Input is bounded and includes only the intent's explicit URL fields and
     * URLs literally present in shared text. Never dereference nested page
     * links automatically. Query-embedded target URLs are extracted solely
     * when they are explicit, valid HTTP(S) URLs.
     */
    fun choose(texts:List<String>,explicitUrls:List<String> = emptyList()):List<Candidate> {
        val seen=LinkedHashMap<String,Candidate>()
        fun insert(value:String,origin:String,weight:Int) {
            val url=normalize(value,origin=="shared")?:return
            val classification=kind(url)
            val base=when(classification){
                Kind.DIRECT_FILE -> 400
                Kind.STREAM_MANIFEST -> 300
                Kind.POSSIBLE_MEDIA -> 180
                Kind.PAGE_OR_GATEWAY -> 30
            }
            val item=Candidate(url,classification,base+weight,origin)
            val earlier=seen[url]
            if(earlier==null || item.score>earlier.score)seen[url]=item
        }
        explicitUrls.take(16).forEach{insert(it,"explicit",20)}
        texts.take(16).forEach {text ->
            if(text.length>32_768)return@forEach
            urlPattern.findAll(text).take(40).forEach{match ->
                insert(match.value,"shared",0)
                // Some sharing apps wrap a direct URL in a page's url= query.
                // Offer it as a separate choice; preserve the complete signed
                // query and let the user select it.
                val uri=runCatching{URI(match.value)}.getOrNull()
                uri?.rawQuery?.split('&')?.take(40)?.forEach{kv ->
                    if(kv.substringBefore('=').lowercase(Locale.ROOT) in encodedTargetKeys) {
                        val embedded=runCatching{
                            URLDecoder.decode(kv.substringAfter('=',""),"UTF-8")
                        }.getOrNull()
                        if(embedded!=null)insert(embedded,"nested",0)
                    }
                }
            }
        }
        return seen.values.sortedWith(compareByDescending<Candidate>{it.score}
            .thenBy {it.host}.thenBy {it.url}).take(24)
    }
}
