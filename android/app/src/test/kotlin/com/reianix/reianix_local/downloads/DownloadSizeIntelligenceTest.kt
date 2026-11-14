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
    @Test fun combinesCloseEstimatesWithoutEverClaimingExactSize() {
        val provider=DownloadSizeIntelligence.Evidence(141_000_000L,false,"shared-metadata")
        val probe=DownloadSizeIntelligence.Evidence(139_000_000L,false,"hls-estimate")
        val joined=DownloadSizeIntelligence.reconcile(provider,probe)!!
        assertFalse(joined.exact)
        assertEquals(140_500_000L,joined.bytes)
        assertEquals(140_000_000L,DownloadSizeIntelligence.roundedForDisplay(joined.bytes))
    }
    @Test fun rejectsInconsistentBitrateExtrapolation() {
        val provider=DownloadSizeIntelligence.Evidence(141_000_000L,false,"provider")
        val extrapolated=DownloadSizeIntelligence.Evidence(25_000_000L,false,"bitrate")
        assertEquals(provider,DownloadSizeIntelligence.reconcile(provider,extrapolated))
    }

    @Test fun cleansSharedTitleWithoutLosingEpisodeName() {
        val title=DownloadSizeIntelligence.stripSizeAnnotations(
            "Solo Leveling - Episódio 01 • TAMANHO 141 MB")
        assertTrue(title.startsWith("Solo Leveling - Episódio 01"))
        assertFalse(title.contains("TAMANHO"))
    }

    @Test fun urlSizeMustBeExplicitAndPlausible() {
        val hint=DownloadSizeIntelligence.fromUrlMetadata(
            "https://cdn.example/media.bin?sign=abc&file_size=141000000")!!
        assertFalse(hint.exact)
        assertEquals(141_000_000L,hint.bytes)
        assertEquals(140_000_000L,DownloadSizeIntelligence.roundedForDisplay(hint.bytes))
        assertNull(DownloadSizeIntelligence.fromUrlMetadata(
            "https://cdn.example/video.mp4?size=720&quality=1080p"))
        assertNull(DownloadSizeIntelligence.fromUrlMetadata(
            "https://cdn.example/video.mp4?sign=abc"))
    }

}
