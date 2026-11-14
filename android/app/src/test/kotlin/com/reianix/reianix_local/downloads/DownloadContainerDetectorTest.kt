package com.reianix.reianix_local.downloads

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class DownloadContainerDetectorTest {
    @Test fun mislabeledBinWithMp4HeaderIsMp4() {
        val bytes = byteArrayOf(0,0,0,24) + "ftypisom".toByteArray() + ByteArray(48)
        assertEquals("mp4", DownloadContainerDetector.sniff(ByteArrayInputStream(bytes))?.extension)
        assertEquals("Hanaori-san.mp4",DownloadContainerDetector.outputName("Hanaori-san.bin","mp4"))
    }

    @Test fun detectsTransportStreamAndWebmWithoutTrustingFileName() {
        val ts = ByteArray(600)
        ts[0]=0x47;ts[188]=0x47;ts[376]=0x47
        assertEquals("ts",DownloadContainerDetector.sniff(ts)?.extension)
        val webm = byteArrayOf(0x1a,0x45,0xdf.toByte(),0xa3.toByte()) +
            ByteArray(20) + "webm".toByteArray() + ByteArray(40)
        assertEquals("webm",DownloadContainerDetector.sniff(webm)?.extension)
    }

    @Test fun doesNotRenameArbitraryBinaryOrHtmlToMp4() {
        assertNull(DownloadContainerDetector.sniff(ByteArray(1024)))
        assertNull(DownloadContainerDetector.sniff("<html>not a video</html>".toByteArray()))
        assertNull(DownloadContainerDetector.sniff(byteArrayOf(1,2,3,4)))
    }

    @Test fun allRequestedContainersAreConsideredForConversion() {
        assertTrue(DownloadContainerDetector.convertibleExtensions.containsAll(
            listOf("ts","mkv","avi","mov","wmv","flv","webm","3gp")))
    }
}
