package com.reianix.reianix_local.downloads

import org.junit.Assert.*
import org.junit.Test

class DownloadSignedLinkPolicyTest {
    @Test fun avoidsExtraRequestsBeforeTransferForSignedLinks() {
        assertTrue(DownloadSignedLinkPolicy.avoidPrefetch(
            "https://media.example/resource/video.mp4?sign=abc&t=99999"))
        assertTrue(DownloadSignedLinkPolicy.avoidPrefetch(
            "https://storage.example/movie.mp4?X-Amz-Signature=a&X-Amz-Expires=300"))
        assertTrue(DownloadSignedLinkPolicy.avoidPrefetch(
            "https://cdn.example/media?token=opaque"))
    }
    @Test fun allowsSafeMetadataPreviewForOrdinaryUrls() {
        assertFalse(DownloadSignedLinkPolicy.avoidPrefetch(
            "https://example.org/downloads/movie.mp4?quality=720"))
        assertFalse(DownloadSignedLinkPolicy.avoidPrefetch(
            "https://example.org/downloads/movie.mp4"))
    }
}
