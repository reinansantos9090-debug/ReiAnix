package com.reianix.reianix_local.downloads

import org.junit.Assert.*
import org.junit.Test

class DownloadSizeIntelligenceTest {
    @Test fun provided141MBIsApproximateAndRoundsTo140MBForDisplay() {
        val hint=DownloadSizeIntelligence.fromSharedMetadata(
            "Stream • 720p • Original Audio [MP4] • TAMANHO 141 MB"
        )!!
        assertEquals(141_000_000L,hint.bytes)
        assertFalse(hint.exact)
        assertEquals(140_000_000L,DownloadSizeIntelligence.roundedForDisplay(hint.bytes))
    }
    @Test fun acceptsDecimalAndBinarySizesWithoutMistakingResolution() {
        assertEquals(141_500_000L,
            DownloadSizeIntelligence.fromSharedMetadata("tamanho: 141,5MB")?.bytes)
        assertEquals(64L*1024*1024,
            DownloadSizeIntelligence.fromSharedMetadata("size 64 MiB")?.bytes)
        assertNull(DownloadSizeIntelligence.fromSharedMetadata("1080p 720p 480p"))
        assertNull(DownloadSizeIntelligence.fromSharedMetadata("100 MB"))
    }
    @Test fun aVerifiedLengthAlwaysOutranksProviderEstimates() {
        val official=DownloadSizeIntelligence.Evidence(139_988_111L,true,"content-length")
        val hint=DownloadSizeIntelligence.Evidence(141_000_000L,false,"shared-metadata")
        assertEquals(official,DownloadSizeIntelligence.best(official,hint))
        assertEquals(official,DownloadSizeIntelligence.best(hint,official))
        assertEquals(hint,DownloadSizeIntelligence.best(null,hint))
    }
    @Test fun neverClaimExactWhenSourceOnlyEstimated() {
        assertEquals("Tamanho a confirmar",DownloadSizeIntelligence.dialogLabel(null,false))
        assertEquals("Tamanho aproximado",DownloadSizeIntelligence.dialogLabel(141_000_000L,false))
        assertEquals("Tamanho confirmado",DownloadSizeIntelligence.dialogLabel(141_000_000L,true))
    }
}
