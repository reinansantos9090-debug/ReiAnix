package com.reianix.reianix_local.downloads

import kotlin.math.roundToLong

/**
 * Display-only size intelligence. A source can disclose a useful approximate
 * value even when its CDN endpoint sends neither Content-Length nor a usable
 * Content-Range. Never use an estimate for HTTP Range, content verification,
 * file truncation, or deciding when a transfer is complete.
 *
 * Only explicitly tagged sizes from shared metadata are trusted as hints;
 * arbitrary "1080p / 720p / 141" text is not a byte count.
 */
internal object DownloadSizeIntelligence {
    data class Evidence(val bytes:Long,val exact:Boolean,val source:String)

    private val annotatedSize=Regex(
        """(?i)(?:^|[\s|;,(\[])(?:tamanho(?:\s+do\s+arquivo)?|file[\s_-]*size|content[\s_-]*size|size|tam\.?)\s*[:=–—-]?\s*(\d{1,8}(?:[.,]\d{1,2})?)\s*(TiB|GiB|MiB|KiB|TB|GB|MB|KB|B)\b"""
    )

    fun fromSharedMetadata(vararg texts:String):Evidence? {
        for(text in texts) {
            if(text.isBlank() || text.length>16_384)continue
            val match=annotatedSize.find(text) ?: continue
            val number=match.groupValues[1].replace(',','.').toDoubleOrNull()
                ?.takeIf {it.isFinite() && it>0.0} ?: continue
            val unit=match.groupValues[2].uppercase()
            val multiplier=when(unit) {
                "TIB" -> 1024.0*1024*1024*1024
                "GIB" -> 1024.0*1024*1024
                "MIB" -> 1024.0*1024
                "KIB" -> 1024.0
                "TB" -> 1_000_000_000_000.0
                "GB" -> 1_000_000_000.0
                "MB" -> 1_000_000.0
                "KB" -> 1_000.0
                else -> 1.0
            }
            val bytes=number*multiplier
            if(bytes in 1024.0..Long.MAX_VALUE.toDouble()) {
                return Evidence(bytes.roundToLong(),false,"shared-metadata")
            }
        }
        return null
    }

    /** Prefer a verified server value to any provider hint or approximation. */
    fun best(primary:Evidence?,secondary:Evidence?):Evidence? = when {
        primary==null -> secondary
        secondary==null -> primary
        primary.exact && !secondary.exact -> primary
        secondary.exact && !primary.exact -> secondary
        else -> primary
    }

    /**
     * Combine independent approximations only when they are reasonably
     * consistent. A label supplied by the originating app carries more weight
     * than a bitrate extrapolation; neither can override an exact HTTP length.
     * Inconsistent observations are not averaged into a fabricated certainty.
     */
    fun reconcile(provider:Evidence?,probe:Evidence?):Evidence? {
        if(provider==null || probe==null)return provider ?: probe
        if(provider.exact || probe.exact)return best(provider,probe)
        val low=minOf(provider.bytes,probe.bytes).toDouble()
        val high=maxOf(provider.bytes,probe.bytes).toDouble()
        if(low<=0 || high/low>1.6)return provider
        val combined=provider.bytes.toDouble()*0.75+probe.bytes.toDouble()*0.25
        return Evidence(combined.roundToLong(),false,"combined-provider-probe")
    }

    /**
     * Round *display-only* approximations to intuitive MB buckets.
     * Ex: a provided 141 MB displays as ~140 MB; the download still receives
     * every byte until the server reaches EOF.
     */
    fun roundedForDisplay(bytes:Long):Long {
        if(bytes<=0L)return 0L
        val mb=1_000_000L
        val increment=when {
            bytes>=100*mb -> 10*mb
            bytes>=10*mb -> 5*mb
            bytes>=mb -> mb
            else -> return bytes
        }
        return ((bytes.toDouble()/increment).roundToLong().coerceAtLeast(1))*increment
    }

    fun dialogLabel(bytes:Long?,exact:Boolean):String = when {
        bytes==null || bytes<=0L -> "Tamanho a confirmar"
        exact -> "Tamanho confirmado"
        else -> "Tamanho aproximado"
    }
}
