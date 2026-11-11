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

/** Display-only size: estimates never become an authoritative transfer limit. */
internal data class DownloadDisplaySize(val bytes: Long, val exact: Boolean)

internal fun downloadDisplaySize(item: DownloadItem): DownloadDisplaySize? {
    if(item.total>0L) return DownloadDisplaySize(item.total,true)
    val discovered=item.json.optLong("detectedSize")
    val estimatedHls=item.json.optLong("hlsEstimatedTotal")
    // Once enough segments have arrived, the downloader's running estimate
    // is more representative than an initial bitrate/sample-based estimate.
    if(estimatedHls>item.bytes && estimatedHls>0L &&
        !item.json.optBoolean("detectedSizeExact")) {
        return DownloadDisplaySize(estimatedHls,false)
    }
    if(discovered>0L && discovered>=item.bytes)
        return DownloadDisplaySize(discovered,item.json.optBoolean("detectedSizeExact"))
    return estimatedHls.takeIf { it>item.bytes }?.let { DownloadDisplaySize(it,false) }
}

internal fun downloadPercent(item: DownloadItem): Int? = when {
    item.state=="COMPLETE" -> 100
    item.total>0 -> (item.bytes.toDouble()*100/item.total).toInt().coerceIn(0,100)
    item.json.optInt("hlsCount")>0 -> (item.json.optInt("hlsIndex")*100/item.json.optInt("hlsCount")).coerceIn(0,100)
    !item.json.optBoolean("hls") && item.json.optBoolean("detectedSizeExact") -> {
        val total=downloadDisplaySize(item)?.bytes ?: 0L
        if(total>0L) (item.bytes.toDouble()*100/total).toInt().coerceIn(0,99) else null
    }
    else -> null
}
