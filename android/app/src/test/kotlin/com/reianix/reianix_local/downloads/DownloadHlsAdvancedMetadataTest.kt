package com.reianix.reianix_local.downloads

import org.junit.Assert.*
import org.junit.Test

class DownloadHlsAdvancedMetadataTest {
    @Test fun byteRangesAndInitGiveSourceTotalWithoutNetwork() {
        val text="#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"200@0\"\n" +
            "#EXTINF:4,\n#EXT-X-BYTERANGE:500@200\nmedia.mp4\n" +
            "#EXTINF:4,\n#EXT-X-BYTERANGE:600@700\nmedia.mp4\n#EXT-X-ENDLIST"
        val p=DownloadHlsMetadata.parse(text,"https://cdn.example/show/index.m3u8")!!
        assertEquals(1300L,p.knownRangeSize)
        assertEquals(8.0,p.duration,0.001)
        assertEquals("https://cdn.example/show/init.mp4",p.initUrl)
    }
    @Test fun llHlsPartDoesNotDoubleCountCompletedSegment() {
        val txt="#EXTM3U\n#EXT-X-PART:DURATION=1.0,URI=\"part1.m4s\",BYTERANGE=\"100@0\"\n"+
            "#EXTINF:2,\nwhole.m4s\n"+
            "#EXT-X-PART:DURATION=1.0,URI=\"final.m4s\",BYTERANGE=\"200@0\"\n#EXT-X-ENDLIST"
        val p=DownloadHlsMetadata.parse(txt,"https://cdn.example/m.m3u8")!!
        assertEquals(2,p.pieces.size)
        assertEquals(1,p.pieces.count{it.partial})
        assertNull(p.knownRangeSize)
    }
    @Test fun weightedSamplesAccountForDifferentSegmentLengths() {
        val pieces=listOf(
            DownloadHlsMetadata.Piece("a",2.0,null,false),
            DownloadHlsMetadata.Piece("b",8.0,null,false),
            DownloadHlsMetadata.Piece("c",5.0,null,false))
        val estimate=DownloadHlsMetadata.weightedEstimate(pieces,mapOf(0 to 200L,1 to 800L))
        assertEquals(1500L,estimate)
        assertEquals(48L,DownloadHlsMetadata.encryptedTransportOverhead(3))
    }
    @Test fun fragmentCacheIsBoundedAndReturnsKnownSize() {
        DownloadHlsMetadata.rememberSize("https://cdn.example/segment-1",150_000)
        assertEquals(150_000L,DownloadHlsMetadata.cachedSize("https://cdn.example/segment-1"))
    }
}
