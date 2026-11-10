package com.reiflix.reiflix_local.downloads

import com.reiflix.reiflix_local.data.media.MediaFilenameParser

/** A season/episode marker alone is not an anime title. Never match it against AniList. */
object DownloadIdentity {
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
