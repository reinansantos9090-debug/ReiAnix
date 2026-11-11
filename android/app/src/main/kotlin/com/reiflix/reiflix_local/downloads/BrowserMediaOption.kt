package com.reiflix.reiflix_local.downloads

import org.json.JSONObject
import java.net.URI

/**
 * A media URL exposed by the visible player DOM or an HLS master playlist
 * already available to the browser. This is a candidate, not proof that the
 * server grants download access; the real transfer still verifies HTTP/format.
 */
internal data class BrowserMediaOption(
    val url: String,
    val page: String,
    val frame: String,
    val quality: String,
    val format: String,
    val origin: String,
    val mimeHint: String = "",
    val separateAudio: Boolean = false,
    val receivedAt: Long = System.nanoTime() / 1_000_000,
) {
    companion object {
        fun parse(obj: JSONObject): BrowserMediaOption? = runCatching {
            BrowserMediaOption(
                url = obj.getString("url"),
                page = obj.getString("page"),
                frame = obj.getString("frame"),
                quality = obj.optString("quality").trim().take(48),
                format = obj.optString("format").lowercase(),
                origin = obj.optString("origin").lowercase(),
                mimeHint = obj.optString("mimeHint").lowercase().take(80),
                separateAudio = obj.optBoolean("separateAudio"),
            ).takeIf(::eligible)
        }.getOrNull()

        fun eligible(value: BrowserMediaOption): Boolean = runCatching {
            DownloadHttp.validateUrl(value.url)
            DownloadHttp.validateUrl(value.page)
            val media = URI(value.url)
            val path = media.path.orEmpty().lowercase()
            val knownMedia = listOf(
                ".m3u8", ".mpd", ".mp4", ".mkv", ".webm", ".m4v", ".mov", ".ts", ".m2ts",
            ).any { path.endsWith(it) }
            value.url.length in 8..16384 && value.page.length in 8..16384 &&
                value.frame.length in 1..100 &&
                value.format in setOf("hls", "video", "dash") &&
                value.origin in setOf("visible-video", "source", "quality-link", "master") &&
                (knownMedia || value.origin == "visible-video" ||
                    value.origin == "master" ||
                    (value.origin == "source" &&
                        (value.mimeHint.startsWith("video/") ||
                            value.mimeHint.contains("mpegurl") ||
                            value.mimeHint.contains("dash+xml")))) &&
                value.quality.none { it == '\r' || it == '\n' || it.isISOControl() } &&
                !PlayerMediaCandidate.advertisementUrl(value.url)
        }.getOrDefault(false)
    }
}

/** Deduplicate the selected player rendition and independent page/manifest options. */
internal data class BrowserDownloadChoice(
    val url: String,
    val page: String,
    val quality: String,
    val format: String,
    val separateAudio: Boolean,
    val capture: PlayerMediaCandidate?,
)

internal object BrowserMediaResults {
    fun combine(
        confirmed: List<PlayerMediaCandidate>,
        discovered: List<BrowserMediaOption>,
    ): List<BrowserDownloadChoice> {
        val byUrl = LinkedHashMap<String, BrowserDownloadChoice>()
        discovered.filter(BrowserMediaOption::eligible).forEach { option ->
            byUrl.putIfAbsent(
                option.url,
                BrowserDownloadChoice(option.url, option.page, option.quality, option.format, option.separateAudio, null),
            )
        }
        confirmed.filter(PlayerMediaCandidate::eligible).forEach { item ->
            val previous = byUrl[item.url]
            byUrl[item.url] = BrowserDownloadChoice(
                url = item.url,
                page = item.page,
                quality = previous?.quality?.takeIf { it != "Qualidade não informada" } ?: "${item.height}p",
                format = previous?.format ?: if (item.source == "hls") "hls" else "video",
                separateAudio = previous?.separateAudio == true,
                capture = item,
            )
        }
        return byUrl.values.sortedWith(
            compareByDescending<BrowserDownloadChoice> {
                Regex("(2160|1440|1080|720|480|360|240)p").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }.thenBy { it.format }.thenBy { it.url },
        )
    }
}
