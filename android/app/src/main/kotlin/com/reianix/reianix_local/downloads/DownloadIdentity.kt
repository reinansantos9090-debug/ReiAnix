package com.reianix.reianix_local.downloads

import com.reianix.reianix_local.data.media.MediaFilenameParser

/** A season/episode marker alone is not an anime title. Never match it against AniList. */
object DownloadIdentity {
    private val episodeMarker = Regex("""(?i)(?:^|[\s._-])(?:epis[oó]dio|episode|ep)[\s._-]*\d{1,4}(?:$|[\s._-])""")
    private val genericFile = Regex("""(?i)^(?:videoplayback|downloadfile|download|stream|playlist|master|index|video|file|media)(?:[-_ ]*\d+)?$""")

    /** Provider filenames such as videoplayback.bin.mp4 contain no anime identity. */
    fun isGenericMediaName(value: String): Boolean {
        var stem = value.substringBefore('?').substringBefore('#')
            .substringAfterLast('/').trim()
        repeat(3) {
            val ext = stem.substringAfterLast('.', "").lowercase()
            if (ext in DownloadContainerDetector.knownExtensions + setOf("bin", "m3u8", "mpd"))
                stem = stem.substringBeforeLast('.')
        }
        return stem.isBlank() || genericFile.matches(stem.trim()) || DownloadResourceIdentity.opaque(value)
    }

    private fun cleanedPageTitle(title: String): String = title
        .substringBefore(" | ").substringBefore(" – ")
        .replace(Regex("""(?i)\s*-\s*Animes Online(?:\s*TV)?\s*$"""), "")
        .trim()

    private fun usefulPageTitle(value: String): Boolean {
        val title = cleanedPageTitle(value)
        if (title.length < 3 || title.equals("Animes Online", true) ||
            title.equals("Assistir Animes Online", true) || title.equals("Home", true) ||
            isGenericMediaName(title)) return false
        // A title consisting only of "Episode 4" cannot identify its series.
        val withoutEpisode = title.replace(Regex("""(?i)\b(?:epis[oó]dio|episode|ep)\s*\d{1,4}\b"""), "")
        return withoutEpisode.count(Char::isLetter) >= 3
    }

    /** Use an explicit episode slug only, never a guessed CDN ID or sequence. */
    private fun episodeTitleFromPageUrl(pageUrl: String): String {
        val uri = runCatching { java.net.URI(pageUrl) }.getOrNull() ?: return ""
        if (uri.scheme !in setOf("http", "https")) return ""
        val host = uri.host.orEmpty().lowercase()
        if (host == "googlevideo.com" || host.endsWith(".googlevideo.com")) return ""
        val parts = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        val episodeIndex = parts.indexOfLast { episodeMarker.containsMatchIn(it.replace('-', ' ').replace('_', ' ')) }
        if (episodeIndex < 0) return ""
        val episode = parts[episodeIndex].replace(Regex("[-_]+"), " ").trim()
        val meaningfulEpisode = episode.replace(Regex("""(?i)\b(?:epis[oó]dio|episode|ep)\s*\d{1,4}\b"""), "")
            .count(Char::isLetter) >= 3
        val series = if (meaningfulEpisode) "" else parts.getOrNull(episodeIndex-1).orEmpty()
            .takeUnless { it.lowercase() in setOf("anime", "animes", "watch", "assistir", "episodios", "episodio", "episode") }
            ?.replace(Regex("[-_]+"), " ")?.takeIf { it.count(Char::isLetter)>=3 }.orEmpty()
        if (!meaningfulEpisode && series.isBlank()) return ""
        val text = listOf(series,episode).filter(String::isNotBlank).joinToString(" ")
        return text.split(Regex("""\s+""")).joinToString(" ") { word ->
            if (word.equals("episodio", true)) "Episódio"
            else word.replaceFirstChar { it.titlecase() }
        }.take(180)
    }

    /** Prefer actual episode metadata; never label the CDN videoplayback as an anime. */
    fun episodeTitleFromPage(pageTitle: String, pageUrl: String): String {
        val title = cleanedPageTitle(pageTitle)
        if (usefulPageTitle(title) && episodeMarker.containsMatchIn(title)) return title
        val fromUrl = episodeTitleFromPageUrl(pageUrl)
        if (fromUrl.isNotBlank()) return fromUrl
        return title.takeIf(::usefulPageTitle).orEmpty()
    }

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
    /** A sharing app may supply a series/episode title while its CDN URL is only index.m3u8.
     * Do not fabricate an episode number when the sender provides no episode metadata. */
    fun suggestedName(sharedTitle: String, url: String, providedName: String): String {
        if (providedName.isNotBlank() && !isGenericMediaName(providedName)) return providedName
        val fromContext=DownloadResourceIdentity.contextualEpisodeName(url)
        val episode=episodeTitleFromPage(sharedTitle,url).takeIf {
            episodeMarker.containsMatchIn(it) && usefulPageTitle(it)
        } ?: fromContext
        if (!episode.isNullOrBlank()) {
            val normalized=episode.replace(
                Regex("""(?i)\b(?:epis[oó]dio|episode|ep)[\s._-]*(\d{1,4})\b"""),
            ) { match -> "Episódio " + match.groupValues[1].padStart(2,'0') }
                .replace(Regex("""\s+""")," ").trim()
            val safe=normalized.map {
                if(it.isISOControl() || it in "\\/:*?\"<>|") ' ' else it
            }.joinToString("").take(180).trim()
            if(safe.isNotBlank()) {
                val ext=providedName.substringAfterLast('.', "").lowercase()
                    .takeIf { it in DownloadContainerDetector.knownExtensions } ?: "mp4"
                return safe+"."+ext
            }
        }
        DownloadResourceIdentity.bestName(null,url,null)?.let { return it }
        val plainTitle=sharedTitle.trim().takeIf { usefulPageTitle(it) }
        if(plainTitle!=null) {
            val clean=plainTitle.map {
                if(it.isISOControl() || it in "\\/:*?\"<>|") ' ' else it
            }.joinToString("").trim().take(180)
            if(clean.isNotBlank())return clean+".mp4"
        }
        return providedName.ifBlank {
            runCatching { android.webkit.URLUtil.guessFileName(url,null,null) }
                .getOrDefault("episodio.mp4")
        }
    }

    fun title(value: String): String {
        val clean=value.substringBefore(" | ").substringBefore(" – ").trim()
        if (!usefulPageTitle(clean)) return ""
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
