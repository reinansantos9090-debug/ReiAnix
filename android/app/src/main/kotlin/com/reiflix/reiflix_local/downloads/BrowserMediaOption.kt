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
                separateAudio = obj.optBoolean("separateAudio"),
            ).takeIf(::eligible)
        }.getOrNull()

        fun eligible(value: BrowserMediaOption): Boolean = runCatching {
            DownloadHttp.validateUrl(value.url)
            DownloadHttp.validateUrl(value.page)
            val media = URI(value.url)
            val path = media.path.orEmpty().lowercase()
            val knownMedia = listOf(
                ".m3u8", ".mp4", ".mkv", ".webm", ".m4v", ".mov", ".ts", ".m2ts",
            ).any { path.endsWith(it) }
            value.url.length in 8..16384 && value.page.length in 8..16384 &&
                value.frame.length in 1..100 &&
                value.format in setOf("hls", "video") &&
                value.origin in setOf("visible-video", "source", "quality-link", "master") &&
                (knownMedia || value.origin == "visible-video") &&
                value.quality.none { it == '\r' || it == '\n' || it.isISOControl() } &&
                !PlayerMediaCandidate.advertisementUrl(value.url)
        }.getOrDefault(false)
    }
}
