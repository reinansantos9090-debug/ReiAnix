package com.reianix.reianix_local.downloads

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
class DownloadContainerMetadataTest {
    @Test fun aviRiffReturnsDeclaredFileSize() {
        val b=ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(999_992).put("AVI ".toByteArray())
        assertEquals(1_000_000L,DownloadContainerMetadata.aviSize(b.array())?.bytes)
        assertTrue(DownloadContainerMetadata.aviSize(b.array())!!.exact)
    }
    @Test fun asfFilePropertiesReturnsDeclaredSize() {
        val bytes=ByteArray(128)
        val guid=byteArrayOf(0xa1.toByte(),0xdc.toByte(),0xab.toByte(),0x8c.toByte(),
            0x47,0xa9.toByte(),0xcf.toByte(),0x11,0x8e.toByte(),
            0xe4.toByte(),0,0xc0.toByte(),0x0c,0x20,0x53,0x65)
        guid.copyInto(bytes,16)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(56,700_000L)
        assertEquals(700_000L,DownloadContainerMetadata.asfSize(bytes)?.bytes)
    }
    @Test fun mp4AtomExtentIsOnlyEstimate() {
        val b=ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN)
        b.putInt(20).put("ftyp".toByteArray()).put("isom".toByteArray()).position(20)
        b.putInt(1000).put("mdat".toByteArray())
        val hint=DownloadContainerMetadata.mp4AtomExtent(b.array())!!
        assertEquals(1020L,hint.bytes)
        assertFalse(hint.exact)
    }
    @Test fun ebmlSegmentSizeIsNotClaimedAsExactWhenUnknownTrailingBytes() {
        val b=ByteArray(32)
        byteArrayOf(0x1a,0x45,0xdf.toByte(),0xa3.toByte(),0x80.toByte(),
            0x18,0x53,0x80.toByte(),0x67,0x88.toByte()).copyInto(b)
        val hint=DownloadContainerMetadata.ebmlSegmentExtent(b)!!
        assertFalse(hint.exact)
        assertEquals(18L,hint.bytes)
    }
    @Test fun flvMetadataFileSizeIsApproximate() {
        val b=ByteArray(100)
        "FLV".toByteArray().copyInto(b)
        "filesize".toByteArray().copyInto(b,25)
        b[33]=0
        ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).putDouble(34,500_000.0)
        assertEquals(500_000L,DownloadContainerMetadata.flvSize(b)?.bytes)
        assertFalse(DownloadContainerMetadata.flvSize(b)!!.exact)
    }
}
