package com.reianix.reianix_local.downloads

import org.junit.Assert.*
import org.junit.Test

class DownloadAdvancedHttpStrategyTest {
    @Test fun unsatisfied416RevealsRealTotal() {
        assertEquals(140_000_000L,
            DownloadAdvancedHttp.unsatisfiedLength(416,"bytes */140000000"))
        assertNull(DownloadAdvancedHttp.unsatisfiedLength(200,"bytes */140000000"))
        assertNull(DownloadAdvancedHttp.unsatisfiedLength(416,"bytes */*"))
    }
    @Test fun multipartByterangesMustAgreeOnFullLength() {
        val multi="--abc\r\nContent-Type: video/mp4\r\n"+
            "Content-Range: bytes 0-0/140000000\r\n\r\nX\r\n"+
            "--abc\r\nContent-Range: bytes 139999999-139999999/140000000\r\n\r\nX"
        assertEquals(140_000_000L,DownloadAdvancedHttp.multipartTotal(multi.toByteArray()))
        assertNull(DownloadAdvancedHttp.multipartTotal(
            multi.replace("139999999/140000000","139999999/150000000").toByteArray()))
    }
    @Test fun onlySameOriginContentLocationIsAccepted() {
        assertEquals("https://cdn.example/object.mp4",
            DownloadAdvancedHttp.resolveContentLocation(
                "https://cdn.example/a/index","../object.mp4"))
        assertNull(DownloadAdvancedHttp.resolveContentLocation(
            "https://cdn.example/index","https://other.example/secret"))
    }
    @Test fun vendorLengthIsAHintNotAutomaticallyAnExactHttpLength() {
        assertEquals(140_000_000L,DownloadAdvancedHttp.vendorLength(
            mapOf("X-Goog-Stored-Content-Length" to "140000000")))
        assertNull(DownloadAdvancedHttp.vendorLength(mapOf("X-File-Size" to "-100")))
        assertNull(DownloadAdvancedHttp.chunkedCompleteLength(1000,false))
        assertEquals(1000L,DownloadAdvancedHttp.chunkedCompleteLength(1000,true))
    }
}
