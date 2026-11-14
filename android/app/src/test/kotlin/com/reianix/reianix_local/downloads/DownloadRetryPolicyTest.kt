package com.reianix.reianix_local.downloads

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class DownloadRetryPolicyTest {
    @Test fun respectsDeltaSecondsWithoutBusyLoop() {
        val now=1_700_000_000_000L
        val next=DownloadRetryPolicy.nextAttempt(2,"episode-id","120",now)
        assertTrue(next>=now+120_000)
    }
    @Test fun supportsRfcHttpDate() {
        val now=1_700_000_000_000L
        val fmt=SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",Locale.US)
        fmt.timeZone=TimeZone.getTimeZone("GMT")
        val header=fmt.format(java.util.Date(now+75_000))
        assertEquals(75_000L,DownloadRetryPolicy.retryAfterDelay(header,now))
    }
    @Test fun limitsServerDelayAndSpreadsDefaultRetries() {
        val now=1_700_000_000_000L
        assertEquals(86_400_000L,DownloadRetryPolicy.retryAfterDelay("9999999999",now))
        val next=DownloadRetryPolicy.nextAttempt(1,"id-1",null,now)
        assertTrue(next>=now+4000L && next<=now+4_800L)
    }
    @Test fun invalidHeaderIsIgnored() {
        assertNull(DownloadRetryPolicy.retryAfterDelay("not a date",0))
        assertEquals(0L,DownloadRetryPolicy.retryAfterDelay("-1000",0))
    }
}
