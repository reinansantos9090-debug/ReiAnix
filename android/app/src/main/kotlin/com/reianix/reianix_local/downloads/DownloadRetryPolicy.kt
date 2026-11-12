package com.reianix.reianix_local.downloads

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Respect standards-defined server retry limits without blocking a transfer thread. */
internal class DownloadRetryableHttpFailure(
    val status:Int,
    val retryAfter:String?
):IOException("Servidor respondeu HTTP $status")

internal object DownloadRetryPolicy {
    private const val MAX_RETRY_MS=24L*60*60*1000

    fun retryAfterDelay(header:String?,nowMillis:Long):Long? {
        if(header.isNullOrBlank() || header.length>128)return null
        val value=header.trim()
        val seconds=value.toLongOrNull()
        if(seconds!=null)
            return (seconds.coerceIn(0L,86400L)*1000L)
        return runCatching {
            val fmt=SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",Locale.US).apply {
                timeZone=TimeZone.getTimeZone("GMT")
                isLenient=false
            }
            val date=fmt.parse(value) ?: return@runCatching null
            (date.time-nowMillis).coerceIn(0,MAX_RETRY_MS)
        }.getOrNull()
    }

    fun nextAttempt(attempts:Int,id:String,header:String?,nowMillis:Long):Long {
        val step=(2000L * (1L shl attempts.coerceIn(0,5))).coerceAtMost(60_000L)
        // Small deterministic jitter spreads simultaneous retries after a network outage.
        val jitter=((id.hashCode().toLong() and 0xffffL)%((step/5)+1))
        val allowed=retryAfterDelay(header,nowMillis) ?: 0L
        return nowMillis+maxOf(step+jitter,allowed)
    }
}
