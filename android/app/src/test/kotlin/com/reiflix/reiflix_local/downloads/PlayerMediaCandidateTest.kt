package com.reiflix.reiflix_local.downloads

import org.junit.Assert.*
import org.junit.Test

class PlayerMediaCandidateTest {
    private fun episode(url: String="https://cdn.example/asset?id=1")=PlayerMediaCandidate(url,"https://player.example/watch","frame",1440.0,1920,1080,640.0,360.0,"video",0)
    @Test fun acceptsPlayerBackedExtensionlessEpisode() {assertTrue(PlayerMediaCandidate.eligible(episode()))}
    @Test fun rejectsReportedAnimatedBannerAndPreRoll() {
        assertFalse(PlayerMediaCandidate.eligible(episode("https://cdn.example/728x90.webm").copy(duration=2.517,width=728,height=90)))
        assertFalse(PlayerMediaCandidate.eligible(episode().copy(duration=30.0)))
        assertFalse(PlayerMediaCandidate.eligible(episode("https://cdn.example/ads/video.mp4")))
    }
    @Test fun requiresVisiblePlayerAndSupportedEvidence() {
        assertFalse(PlayerMediaCandidate.eligible(episode().copy(renderedWidth=120.0,renderedHeight=60.0)))
        assertFalse(PlayerMediaCandidate.eligible(episode().copy(source="network")))
        assertFalse(PlayerMediaCandidate.eligible(episode().copy(duration=Double.NaN)))
    }
}
