package com.reiflix.reiflix_local.downloads

import com.reiflix.reiflix_local.data.media.MediaFilenameParser

/** A season/episode marker alone is not an anime title. Never match it against AniList. */
object DownloadIdentity {
    /** Page titles describe the episode; opaque CDN paths describe storage. */
    fun capturedFileName(pageTitle: String, url: String, mime: String?): String {
        val title=pageTitle.substringBefore(" | ").substringBefore(" – ")
            .map { if(it.isISOControl() || it in "\\/:*?\"<>|") ' ' else it }.joinToString("").trim().take(180).trim()
        val guessed=android.webkit.URLUtil.guessFileName(url,null,mime)
        if(title.length<3)return guessed
        val extension=guessed.substringAfterLast('.', "mp4").lowercase()
            .takeIf {it in setOf("mp4","mkv","webm","mov","m4v","ts")} ?: "mp4"
        return "$title.$extension"
    }
    fun title(value: String): String {
        val clean=value.substringBefore(" | ").substringBefore(" – ").trim()
        val parsed=MediaFilenameParser.parseVideoPath(clean).animeTitle.trim()
        if(parsed.isBlank() || parsed.equals("Arquivo não identificado",true)) return ""
        val letters=parsed.replace(Regex("(?i)\\b(?:s\\d+[ ._-]*e\\d+|epis[oó]dio[ ._-]*\\d+|episode[ ._-]*\\d+|downloadfile|download|video|arquivo|mkv|mp4|webm)\\b"),"").filter { it.isLetter() }
        if(letters.length<3 || parsed.matches(Regex("[a-fA-F0-9-]{16,}"))) return ""
        return parsed
    }
}

internal fun downloadPercent(item: DownloadItem): Int? = when {
    item.state=="COMPLETE" -> 100
    item.total>0 -> (item.bytes.toDouble()*100/item.total).toInt().coerceIn(0,100)
    item.json.optInt("hlsCount")>0 -> (item.json.optInt("hlsIndex")*100/item.json.optInt("hlsCount")).coerceIn(0,100)
    else -> null
}
