package com.reiflix.reiflix_local.downloads

import org.json.JSONObject
import java.net.URI

/** Automatic discovery needs player evidence, not merely a video extension/MIME. Manual links remain unrestricted. */
internal data class PlayerMediaCandidate(val url: String,val page: String,val frame: String,val duration: Double,
    val width: Int,val height: Int,val renderedWidth: Double,val renderedHeight: Double,
    val source: String,val receivedAt: Long = System.nanoTime()/1_000_000) {
    val area: Double get()=renderedWidth*renderedHeight
    fun evidence(): JSONObject = JSONObject().put("url",url).put("page",page).put("frame",frame).put("duration",duration)
        .put("width",width).put("height",height).put("renderedWidth",renderedWidth).put("renderedHeight",renderedHeight).put("source",source)
    companion object {
        fun parse(obj: JSONObject): PlayerMediaCandidate? = runCatching {
            val item=PlayerMediaCandidate(obj.getString("url"),obj.getString("page"),obj.getString("frame"),obj.getDouble("duration"),
                obj.getInt("width"),obj.getInt("height"),obj.getDouble("renderedWidth"),obj.getDouble("renderedHeight"),obj.getString("source"))
            if(eligible(item))item else null
        }.getOrNull()
        fun eligible(item: PlayerMediaCandidate): Boolean = runCatching {
            DownloadHttp.validateUrl(item.url);DownloadHttp.validateUrl(item.page)
            item.url.length<=16384 && item.page.length<=16384 && item.frame.length in 1..100 && item.source in setOf("video","hls") &&
                item.duration.isFinite() && item.duration>=120 && item.width>=160 && item.height>=90 &&
                item.width.toDouble()/item.height in 0.45..3.2 && item.renderedWidth.isFinite() && item.renderedHeight.isFinite() &&
                item.renderedWidth>=200 && item.renderedHeight>=110 && !advertisementUrl(item.url)
        }.getOrDefault(false)
        fun durationMatches(expected: Double,actual: Double): Boolean = expected.isFinite() && actual.isFinite() &&
            expected>=120 && kotlin.math.abs(expected-actual)<=maxOf(10.0,expected*0.02)
        fun advertisementUrl(url: String): Boolean = runCatching {
            val uri=URI(url);val host=uri.host.orEmpty().lowercase();val path=uri.path.orEmpty().lowercase()
            val adDomain=listOf("doubleclick.net","googlesyndication.com","adnxs.com","adsterra.com","popads.net").any {host==it || host.endsWith(".$it")}
            val dimensions=Regex("(?:^|[/_.-])(\\d{2,4})[xX](\\d{2,4})(?=[/_. -]|$)").find(path)
            val banner=dimensions?.let {val w=it.groupValues[1].toInt();val h=it.groupValues[2].toInt();h>0 && w.toDouble()/h>4.0} ?: false
            adDomain || banner || Regex("(?:^|/)(?:ads?|advert(?:isement)?s?|banners?|preroll)(?:/|[_.-]|$)").containsMatchIn(path)
        }.getOrDefault(true)
    }
}
