package com.reianix.reianix_local.downloads

import java.net.URI
import java.util.Locale

/**
 * Never perform speculative metadata GETs for an ephemeral signed URL.
 * Some endpoints issue URLs valid for a single transfer or a short time;
 * probing before the actual download can invalidate otherwise valid links.
 * The download's first GET is still allowed to report authoritative size.
 */
internal object DownloadSignedLinkPolicy {
    private val signatureKeys=setOf(
        "sign","signature","sig","token","auth","access_token","jwt",
        "expires","expiry","expiration","expire","x-amz-signature",
        "x-amz-expires","x-goog-signature","x-goog-expires",
        "hdntl","hdnts","policy","__token__","awsaccesskeyid"
    )
    fun avoidPrefetch(url:String):Boolean {
        val query=runCatching{URI(url).rawQuery}.getOrNull() ?: return false
        if(query.length>16_384)return true
        return query.split('&').take(100).any {part ->
            val key=part.substringBefore('=').lowercase(Locale.ROOT)
            key in signatureKeys
        }
    }
}
