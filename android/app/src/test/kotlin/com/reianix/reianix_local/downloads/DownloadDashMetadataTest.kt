package com.reianix.reianix_local.downloads

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DownloadDashMetadataTest {
    @Test fun segmentTemplateGeneratesTimelineUrlsAndBitrateEstimate() {
        val template="$"+"Number"+"$"
        val xml="<MPD type=\"static\" mediaPresentationDuration=\"PT20S\">"+
            "<Period duration=\"PT20S\"><AdaptationSet mimeType=\"video/mp4\">"+
            "<Representation id=\"main\" bandwidth=\"800000\">"+
            "<SegmentTemplate startNumber=\"1\" timescale=\"1\" duration=\"5\" "+
            "media=\"v/"+template+".m4s\" initialization=\"v/init.mp4\"/>"+
            "</Representation></AdaptationSet></Period></MPD>"
        val p=DownloadDashMetadata.parse(xml,"https://cdn.example/series/index.mpd")!!
        assertEquals(5,p.segments.size)
        assertTrue(p.segments.any{it.url=="https://cdn.example/series/v/4.m4s"})
        assertEquals(2_000_000L,p.estimateBytes)
    }
    @Test fun segmentListCanProvideExactSourceRangeBytes() {
        val xml="<MPD type=\"static\" mediaPresentationDuration=\"PT3S\">"+
            "<Period duration=\"PT3S\"><AdaptationSet mimeType=\"video/mp4\">"+
            "<Representation bandwidth=\"900000\"><SegmentList>"+
            "<Initialization sourceURL=\"init.mp4\" range=\"0-49\"/>"+
            "<SegmentURL media=\"a.m4s\" mediaRange=\"0-99\"/>"+
            "<SegmentURL media=\"b.m4s\" mediaRange=\"0-149\"/>"+
            "</SegmentList></Representation></AdaptationSet></Period></MPD>"
        val p=DownloadDashMetadata.parse(xml,"https://cdn.example/v/manifest.mpd")!!
        assertEquals(300L,p.manifestBytes)
    }
    @Test fun segmentBaseIndexRangeIsPreserved() {
        val xml="<MPD type=\"static\" mediaPresentationDuration=\"PT2S\">"+
            "<Period duration=\"PT2S\"><AdaptationSet mimeType=\"video/mp4\">"+
            "<Representation bandwidth=\"100000\"><BaseURL>movie.mp4</BaseURL>"+
            "<SegmentBase indexRange=\"100-143\"/></Representation>"+
            "</AdaptationSet></Period></MPD>"
        val p=DownloadDashMetadata.parse(xml,"https://cdn.example/index.mpd")!!
        assertEquals("100-143",p.segments.first().indexRange)
    }
    @Test fun sidxReaderSumsReferenceSizesNotFullFileSize() {
        val b=ByteBuffer.allocate(44).order(ByteOrder.BIG_ENDIAN)
        b.putInt(44).put("sidx".toByteArray())
        b.putInt(0).putInt(1).putInt(1000).putInt(0).putInt(0)
        b.putShort(0).putShort(1)
        b.putInt(400).putInt(1000).putInt(0)
        assertEquals(400L,DownloadDashMetadata.sidxBytes(b.array()))
    }
    @Test fun untrustedXmlCannotDeclareExternalEntities() {
        val xml="<!DOCTYPE MPD [ <!ENTITY ext SYSTEM \"file:///etc/passwd\"> ]>"+
            "<MPD type=\"static\"><Period>&ext;</Period></MPD>"
        assertNull(DownloadDashMetadata.parse(xml,"https://cdn.example/a.mpd"))
    }
}
