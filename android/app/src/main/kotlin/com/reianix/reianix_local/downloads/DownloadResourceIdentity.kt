package com.reianix.reianix_local.downloads

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** HTTP and signed-URL download metadata, independent of network and UI. */
internal object DownloadResourceIdentity {
    private val technical=Regex("""(?i)^(?:index|master|playlist|manifest|stream|video|videoplayback|download|file|media|resource|output|blob|part)(?:[-_. ]*\d+)?$""")
    private val hexId=Regex("""(?i)^[a-f0-9]{16,}$""")
    private val uuid=Regex("""(?i)^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$""")
    private val extensions=setOf("mp4","m4v","ts","m2ts","mkv","mov","avi","wmv","flv","webm","3gp","m3u8","mpd","bin")

    /** RFC 5987 uses path escaping; preserve literal '+' rather than turning it into a space. */
    private fun decode(value:String,charset:String="UTF-8"):String =
        runCatching {URLDecoder.decode(value.replace("+","%2B"),charset)}.getOrDefault(value)

    private fun clean(value:String):String = value.trim().trim('"','\'')
        .replace(Regex("""[\\/:*?"<>|\u0000-\u001f]""")," ")
        .replace(Regex("""\s+""")," ").trim().trim('.').take(195).trim().trim('.')

    fun disposition(header:String?):String? {
        if(header.isNullOrBlank())return null
        val extended=Regex("""(?i)(?:^|;)\s*filename\*\s*=\s*(?:"([^"]+)"|([^;]+))""")
            .find(header)?.let {it.groupValues[1].ifBlank {it.groupValues[2]}.trim()}
        if(extended!=null) {
            val charset=extended.substringBefore("'","").uppercase(Locale.ROOT)
            val encoded=extended.substringAfter("''",extended.substringAfterLast("'",""))
            if(charset in setOf("UTF-8","US-ASCII","ISO-8859-1") && encoded.isNotBlank()) {
                val candidate=clean(decode(encoded,charset))
                if(candidate.isNotBlank())return candidate
            }
        }
        val match=Regex("""(?i)(?:^|;)\s*filename\s*=\s*(?:"([^"]*)"|'([^']*)'|([^;]*))""")
            .find(header)?:return null
        return clean(decode(match.groupValues.drop(1).firstOrNull(String::isNotBlank).orEmpty()))
            .takeIf(String::isNotBlank)
    }

    fun opaque(value:String):Boolean {
        val name=value.substringAfterLast('/').substringBefore('?').trim()
        val stem=name.substringBeforeLast('.',name).lowercase(Locale.ROOT)
        return stem.isBlank() || technical.matches(stem) || hexId.matches(stem) ||
            uuid.matches(stem) || stem.length>190
    }

    private fun params(url:String):Map<String,String> {
        val raw=runCatching {URI(url).rawQuery}.getOrNull()?:return emptyMap()
        return raw.split('&').take(80).mapNotNull {entry ->
            if(entry.isBlank())null else decode(entry.substringBefore('=')).lowercase(Locale.ROOT) to
                decode(entry.substringAfter('=',""))
        }.toMap()
    }

    fun fromUrl(url:String):String? {
        val query=params(url)
        val disposition=sequenceOf("response-content-disposition","response_content_disposition","content-disposition")
            .mapNotNull(query::get).firstOrNull()
        disposition(disposition)?.takeUnless(::opaque)?.let{return it}
        for(key in listOf("filename","file_name","download_name","downloadname")) {
            val candidate=clean(query[key].orEmpty())
            if(candidate.isNotBlank() && !opaque(candidate))return candidate
        }
        return runCatching{URI(url).rawPath}.getOrNull()?.substringAfterLast('/')
            ?.let(::decode)?.let(::clean)?.takeIf{it.isNotBlank()&&!opaque(it)}
    }

    fun bestName(disposition:String?,url:String,contentType:String?):String? {
        val name=this.disposition(disposition)?.takeUnless(::opaque) ?: fromUrl(url) ?: return null
        val ext=name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val inferred=when(contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
            "video/mp4" -> "mp4"
            "video/mp2t" -> "ts"
            "video/x-matroska" -> "mkv"
            "video/webm" -> "webm"
            "video/quicktime" -> "mov"
            "video/x-msvideo" -> "avi"
            "video/x-ms-wmv" -> "wmv"
            "video/x-flv" -> "flv"
            "video/3gpp" -> "3gp"
            else -> null
        }
        return clean(if(ext !in extensions && inferred!=null)name+"."+inferred else name)
            .takeIf(String::isNotBlank)
    }

    fun contextualEpisodeName(url:String):String? {
        val path=runCatching{URI(url).rawPath}.getOrNull()?:return null
        val parts=path.split('/').filter(String::isNotBlank).map(::decode)
        if(parts.size<3)return null
        val position=parts.indices.reversed().firstOrNull {index ->
            val token=parts[index]
            token.matches(Regex("""(?i)(?:ep|episode|episodio|episódio)[-_ ]*0*\d{1,3}""")) ||
                (token.matches(Regex("""\d{1,3}""")) &&
                    parts.drop(index+1).any {it.endsWith(".m3u8",true)||it.endsWith(".mp4",true)})
        }?:return null
        val number=Regex("""\d{1,3}""").find(parts[position])?.value?:return null
        val slug=parts.take(position).asReversed().firstOrNull{p ->
            p.length in 4..90 && p.count(Char::isLetter)>=4 &&
                !opaque(p) && p.any {it in "-_ "} &&
                p.lowercase() !in setOf("streams","stream","video","animes","episodes","series","watch","media")
        }?:return null
        val title=slug.replace(Regex("""[-_]+""")," ").trim()
            .split(Regex("""\s+""")).joinToString(" ") {it.replaceFirstChar(Char::uppercaseChar)}.take(110)
        return clean(title+" - Episódio "+number.padStart(2,'0'))
    }
}
