package com.reianix.reianix_local.downloads

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadLinkMetadataTest {
    @Test fun webDavDepthZeroParsesSingle200Propstat() {
        val xml="<d:multistatus xmlns:d=\"DAV:\"><d:response>"+
            "<d:href>/movie.mp4</d:href><d:propstat><d:prop>"+
            "<d:getcontentlength>140000000</d:getcontentlength></d:prop>"+
            "<d:status>HTTP/1.1 200 OK</d:status>"+
            "</d:propstat></d:response></d:multistatus>"
        assertEquals(140_000_000L,DownloadLinkMetadata.webDav(xml.toByteArray())?.bytes)
    }
    @Test fun metalinkReportsReferencedPayloadRatherThanManifestSize() {
        val xml="<metalink xmlns=\"urn:ietf:params:xml:ns:metalink\">"+
            "<file name=\"ep01.mp4\"><size>140000000</size></file></metalink>"
        val r=DownloadLinkMetadata.metalink(xml.toByteArray())!!
        assertEquals(140_000_000L,r.bytes)
        assertEquals("ep01.mp4",r.name)
        assertTrue(r.describesReferencedFile)
    }
    @Test fun bencodedTorrentSingleFileUsesInfoLength() {
        val data="d4:infod6:lengthi140000000e4:name7:ep1.mp4ee"
        assertEquals(140_000_000L,DownloadLinkMetadata.torrent(data.toByteArray())?.bytes)
    }
    @Test fun videoObjectSchemaAndProviderApiFieldsAreConditional() {
        val html="<script type=\"application/ld+json\">"+
            "{\"@type\":\"VideoObject\",\"contentSize\":\"140 MB\"}</script>"
        assertEquals(140_000_000L,DownloadLinkMetadata.videoPage(html)?.bytes)
        assertNull(DownloadLinkMetadata.videoPage("<h1>VideoObject VideoObject</h1>"))
        assertEquals(140_000_000L,DownloadLinkMetadata.providerJson(
            "{\"size\":\"140000000\",\"name\":\"ep01.mp4\"}")?.bytes)
    }
    @Test fun xmlDtdMustNotReadLocalFiles() {
        val xml="<!DOCTYPE metalink [ <!ENTITY x SYSTEM \"file:///etc/passwd\"> ]>"+
            "<metalink><file name=\"one\"><size>&x;</size></file></metalink>"
        assertNull(DownloadLinkMetadata.metalink(xml.toByteArray()))
    }
}
